package local.headunit.diplay;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.DropBoxManager;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** One best-effort read of system crash clues while the previous runtime's logs may still exist. */
final class RecoverySystemEvidence {
    private static final int LOG_BYTES = 16 * 1024;
    private static final long LOG_TIMEOUT_MS = 800;
    private static final long BEFORE_MS = 120_000;
    private static final long AFTER_MS = 10_000;
    private static final Pattern LOG_LINE = Pattern.compile(
        "^(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+(\\d+\\s+\\d+)\\s+[VDIWEFA]\\s+([^:]{1,32}):\\s*(.*)$");
    private static final Pattern WATCHDOG_MONITOR = Pattern.compile("^[A-Za-z0-9_.$]{1,72}$");
    private static final Pattern EXCEPTION_TYPE = Pattern.compile(
        "^([a-z][A-Za-z0-9_.$]{0,90}(?:Exception|Error|Throwable))(?::|$)");

    private RecoverySystemEvidence() {}

    static JSONObject capture(Context context, long recoveryWallTimeMs, String continuity)
            throws JSONException {
        boolean readLogs = context.checkCallingOrSelfPermission("android.permission.READ_LOGS")
            == PackageManager.PERMISSION_GRANTED;
        LogResult log = readLogcat(readLogs, recoveryWallTimeMs);
        StringBuilder summary = new StringBuilder("systemLogStatus=").append(log.status)
            .append(" systemLogGrant=").append(readLogs)
            .append(" systemLogLimited=").append(log.limited);
        summary.append(' ').append(readDropBox(context, readLogs, recoveryWallTimeMs, continuity));
        if (!log.matches.isEmpty()) summary.append(' ').append(log.matches);
        // Preserve room for the app's own recovery context in the compact report.
        return new JSONObject().put("status", log.status)
            .put("diagnosticSummary", summary.substring(0, Math.min(summary.length(), 480)));
    }

    private static LogResult readLogcat(boolean readLogs, long recoveryWallTimeMs) {
        Process process = null;
        InputStream input = null;
        try {
            // API 17 applies -t before tag filters. Dump the filtered ring once so
            // startup chatter cannot make a system crash disappear from the tail.
            process = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-b", "system",
                "-d", "-v", "threadtime", "-s", "Watchdog:W",
                "AndroidRuntime:E", "Zygote:I", "init:I", "DEBUG:I")
                .redirectErrorStream(true).start();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(2048);
            long deadline = SystemClock.elapsedRealtime() + LOG_TIMEOUT_MS;
            input = process.getInputStream();
            byte[] chunk = new byte[1024];
            while (bytes.size() < LOG_BYTES && SystemClock.elapsedRealtime() < deadline) {
                int available = input.available();
                if (available == 0) {
                    if (exitCode(process) != null) break;
                    Thread.sleep(20);
                    continue;
                }
                int count = input.read(chunk, 0, Math.min(Math.min(available, chunk.length),
                    LOG_BYTES - bytes.size()));
                if (count <= 0) break;
                bytes.write(chunk, 0, count);
            }
            Integer exit = exitCode(process);
            String text = new String(bytes.toByteArray(), Charset.forName("UTF-8"));
            TimeZone zone = TimeZone.getDefault();
            String matches = summarizeLog(text, recoveryWallTimeMs, zone);
            String lower = text.toLowerCase(Locale.US);
            String status;
            if (!matches.isEmpty()) {
                status = "observed";
            } else if (exit != null && exit != 0
                    && (lower.contains("permission denied") || lower.contains("operation not permitted"))) {
                status = "permission_denied";
            } else if (bytes.size() >= LOG_BYTES) {
                status = "size_limit";
            } else if (exit == null) {
                status = "timeout";
            } else if (exit != 0) {
                status = "failed";
            } else {
                status = readLogs ? "no_matching_evidence" : "restricted_no_matching_evidence";
            }
            return new LogResult(status, matches, bytes.size() >= LOG_BYTES || exit == null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new LogResult("interrupted", "", true);
        } catch (IOException | RuntimeException unavailable) {
            return new LogResult("unavailable", "", true);
        } finally {
            if (process != null) process.destroy();
            if (input != null) try { input.close(); } catch (IOException ignored) { }
        }
    }

    private static Integer exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException running) {
            return null;
        }
    }

    private static String readDropBox(Context context, boolean readLogs, long wall, String continuity) {
        if (!readLogs) return "dropBoxStatus=permission_denied";
        // API 17 DropBox enforces READ_LOGS itself. Query only system restart tags.
        String lifecycle = "kernel_boot_changed".equals(continuity) || "boot_elapsed_reset".equals(continuity)
            ? "SYSTEM_BOOT" : "SYSTEM_RESTART";
        StringBuilder seen = new StringBuilder();
        try {
            DropBoxManager manager = (DropBoxManager) context.getSystemService(Context.DROPBOX_SERVICE);
            if (manager == null) return "dropBoxStatus=unavailable";
            for (String tag : new String[]{"system_server_watchdog", "system_server_crash", lifecycle}) {
                DropBoxManager.Entry entry = manager.getNextEntry(tag, wall - BEFORE_MS);
                if (entry == null) continue;
                try {
                    long offset = entry.getTimeMillis() - wall;
                    if (offset >= -BEFORE_MS && offset <= AFTER_MS) {
                        seen.append(" dropBox_").append(tag).append("_ms=").append(offset);
                    }
                } finally {
                    entry.close();
                }
            }
            return seen.length() > 0 ? "dropBoxStatus=observed" + seen : "dropBoxStatus=no_matching_evidence";
        } catch (SecurityException denied) {
            return "dropBoxStatus=permission_denied";
        } catch (RuntimeException unavailable) {
            return "dropBoxStatus=unavailable";
        }
    }

    static String summarizeLog(String log, long recoveryWallTimeMs, TimeZone zone) {
        Calendar timestamp = Calendar.getInstance(zone);
        timestamp.setTimeInMillis(recoveryWallTimeMs);
        int recoveryYear = timestamp.get(Calendar.YEAR);
        int recoveryMonth = timestamp.get(Calendar.MONTH);
        timestamp.setLenient(false);
        Map<String, Long> events = new LinkedHashMap<String, Long>();
        String monitor = "";
        String fatalThread = "";
        String javaFatalType = "";
        for (String line : log.split("\\r?\\n")) {
            if (line.length() > 768) continue;
            Matcher match = LOG_LINE.matcher(line);
            if (!match.matches()) continue;
            String thread = match.group(7);
            String tag = match.group(8).trim();
            String detail = match.group(9);
            String kind = classification(tag, detail);
            Matcher exception = EXCEPTION_TYPE.matcher(detail);
            boolean fatalDetail = kind.isEmpty() && !fatalThread.isEmpty() && javaFatalType.isEmpty()
                && "AndroidRuntime".equals(tag) && fatalThread.equals(thread) && exception.find();
            if (kind.isEmpty() && !fatalDetail) continue;
            long offset;
            try {
                int month = Integer.parseInt(match.group(1)) - 1;
                int year = recoveryYear;
                if (recoveryMonth == Calendar.JANUARY && month == Calendar.DECEMBER) year--;
                if (recoveryMonth == Calendar.DECEMBER && month == Calendar.JANUARY) year++;
                timestamp.clear();
                timestamp.set(year, month, Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)),
                    Integer.parseInt(match.group(5)));
                timestamp.set(Calendar.MILLISECOND, Integer.parseInt(match.group(6)));
                offset = timestamp.getTimeInMillis() - recoveryWallTimeMs;
            } catch (IllegalArgumentException invalidTime) {
                continue;
            }
            if (offset < -BEFORE_MS || offset > AFTER_MS) continue;
            if (fatalDetail) {
                long delta = offset - events.get("java_fatal");
                if (delta >= 0 && delta <= 500) javaFatalType = exception.group(1).replace('$', '.');
                continue;
            }
            Long previous = events.get(kind);
            if (previous != null && offset < previous) continue;
            events.put(kind, offset);
            if ("watchdog".equals(kind)) {
                String name = detail.substring(detail.lastIndexOf(':') + 1).trim();
                monitor = WATCHDOG_MONITOR.matcher(name).matches() ? name.replace('$', '.') : "";
            } else if ("java_fatal".equals(kind)) {
                fatalThread = thread;
                javaFatalType = "";
            }
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Long> event : events.entrySet()) {
            if (result.length() > 0) result.append(' ');
            result.append("systemLog_").append(event.getKey()).append("_ms=").append(event.getValue());
        }
        if (!monitor.isEmpty()) result.append(" watchdogMonitor=").append(monitor);
        if (!javaFatalType.isEmpty()) result.append(" systemLogJavaFatalType=").append(javaFatalType);
        return result.toString();
    }

    private static String classification(String tag, String detail) {
        if ("Watchdog".equals(tag) && detail.startsWith("*** WATCHDOG KILLING SYSTEM PROCESS:")) {
            return "watchdog";
        }
        if ("AndroidRuntime".equals(tag) && detail.startsWith("*** FATAL EXCEPTION IN SYSTEM PROCESS:")) {
            return "java_fatal";
        }
        if ("Zygote".equals(tag) && detail.startsWith("Exit zygote because system server (")
                && detail.endsWith("has terminated")) {
            return "zygote_exit";
        }
        if ("init".equals(tag) && (detail.startsWith("service 'zygote' ") || detail.startsWith("Service 'zygote' "))) {
            if (detail.contains("killed") || detail.contains("exited")) return "init_zygote_exit";
        }
        if ("DEBUG".equals(tag) && detail.endsWith(">>> system_server <<<")) return "debuggerd_system_server";
        return "";
    }

    private static final class LogResult {
        final String status;
        final String matches;
        final boolean limited;

        LogResult(String status, String matches, boolean limited) {
            this.status = status;
            this.matches = matches;
            this.limited = limited;
        }
    }
}
