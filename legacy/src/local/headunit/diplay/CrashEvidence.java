package local.headunit.diplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Durable breadcrumbs for process death during a wired CarPlay connection. */
final class CrashEvidence {
    private static final String PREFS = "diplay_crash_evidence";
    private static final int MAX_NATIVE_CRASH_BYTES = 8192;
    private static final int MAX_NATIVE_CRASH_SUMMARY = 768;
    private static final Object LOCK = new Object();
    private static final ExecutorService RECOVERY_WORKER = Executors.newSingleThreadExecutor();
    private static volatile Context app;
    private static volatile String currentConnection = "";
    private static volatile String currentPhase = "unknown";
    private static volatile long connectionStartedElapsed;
    private static volatile boolean currentUsbLinked;
    private static volatile boolean currentVideoReady;
    private static boolean initialized;

    private CrashEvidence() {}

    static void initialize(Context context) {
        synchronized (LOCK) {
            if (initialized) return;
            initialized = true;
            app = context.getApplicationContext();
        }
        installUncaughtHandler();
        recoverPreviousProcess();
    }

    static void begin(Context context, String connectionId) {
        Context c = application(context);
        if (c == null) return;
        String id = safe(connectionId, 48, "unknown");
        long now = SystemClock.elapsedRealtime();
        currentConnection = id;
        currentPhase = "service_start";
        connectionStartedElapsed = now;
        currentUsbLinked = false;
        currentVideoReady = false;
        writeMarker(c, id, "service_start", now, false, false);
    }

    static void updateStage(Context context, String connectionId, String stage,
                            boolean usbLinked, boolean videoReady) {
        Context c = application(context);
        if (c == null) return;
        String id = safe(connectionId, 48, "unknown");
        if (!id.equals(currentConnection)) return;
        String next = safe(stage, 48, "unknown");
        currentPhase = next;
        currentUsbLinked = usbLinked;
        currentVideoReady = videoReady;
        writeMarker(c, id, next, connectionStartedElapsed, usbLinked, videoReady);
    }

    static void end(Context context, String connectionId, String stage, long elapsedMs) {
        Context c = application(context);
        if (c == null) return;
        String id = safe(connectionId, 48, "unknown");
        if (!id.equals(currentConnection)) return;
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!id.equals(prefs.getString("connection", ""))) return;
        try {
            DiagnosticLog.recordImmediately(c, "CONNECTION_CLEAN_EXIT", new JSONObject()
                .put("phase", safe(stage, 48, "cleanup_complete"))
                .put("reason", "orderly_shutdown")
                .put("elapsedMs", Math.max(0L, elapsedMs))
                .put("usbLinked", currentUsbLinked)
                .put("videoReady", currentVideoReady));
            prefs.edit().clear().commit();
            currentConnection = "";
            currentPhase = "unknown";
            connectionStartedElapsed = 0L;
            currentUsbLinked = false;
            currentVideoReady = false;
        } catch (Throwable ignored) {
            // Keep the marker if durable logging failed; the next launch can still report it.
        }
    }

    private static void writeMarker(Context c, String id, String stage, long startedElapsed,
                                    boolean usbLinked, boolean videoReady) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("active", true)
                .putString("connection", id)
                .putString("phase", stage)
                .putInt("processId", Process.myPid())
                .putLong("startedAt", startedElapsed)
                .putLong("stageAt", SystemClock.elapsedRealtime())
                .putBoolean("usbLinked", usbLinked)
                .putBoolean("videoReady", videoReady)
                .commit();
        } catch (Throwable ignored) {
            // Diagnostics must never interrupt the USB/CarPlay path.
        }
    }

    private static void recoverPreviousProcess() {
        final Context c = app;
        if (c == null) return;
        final SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("active", false)) return;
        final long started = prefs.getLong("startedAt", 0L);
        long now = SystemClock.elapsedRealtime();
        final long elapsed = started > 0L && now >= started ? now - started : 0L;
        final String id = safe(prefs.getString("connection", ""), 48, "unknown");
        final String phase = safe(prefs.getString("phase", "unknown"), 48, "unknown");
        final int oldPid = prefs.getInt("processId", 0);
        final boolean usbLinked = prefs.getBoolean("usbLinked", false);
        final boolean videoReady = prefs.getBoolean("videoReady", false);
        final File nativeFile = new File(c.getFilesDir(), "native-crash.txt");
        final long nativeFileLength = nativeFile.isFile() ? nativeFile.length() : 0L;
        final long nativeFileModified = nativeFile.isFile() ? nativeFile.lastModified() : 0L;
        final String nativeFailure = readNativeFailure(c);
        AutoUsbService.blockAfterUnexpectedExit(c);
        try {
            RECOVERY_WORKER.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject recovery = new JSONObject()
                            .put("phase", phase)
                            .put("reason", "active_connection_after_process_restart")
                            .put("origin", "application_start")
                            .put("errorType", "UnexpectedProcessExit")
                            .put("attempt", oldPid)
                            .put("state", Process.myPid())
                            .put("elapsedMs", elapsed)
                            .put("usbLinked", usbLinked)
                            .put("videoReady", videoReady)
                            .put("diagnosticCode", id);
                        if (!nativeFailure.isEmpty()) recovery.put("diagnosticSummary", nativeFailure);
                        DiagnosticLog.recordImmediately(c, "PROCESS_RECOVERY", recovery);
                        if (!nativeFailure.isEmpty() && nativeFile.isFile() &&
                                nativeFile.length() == nativeFileLength && nativeFile.lastModified() == nativeFileModified) {
                            nativeFile.delete();
                        }
                        // A new connection may have started while this recovery was queued.
                        if (id.equals(prefs.getString("connection", "")) &&
                                started == prefs.getLong("startedAt", 0L)) prefs.edit().clear().commit();
                    } catch (Throwable ignored) {
                        // Retain the marker if recovery could not be persisted.
                    }
                }
            });
        } catch (Throwable ignored) {
            // Retain the marker for a later attempt if recovery could not be persisted.
        }
    }

    private static String readNativeFailure(Context c) {
        File file = new File(c.getFilesDir(), "native-crash.txt");
        if (!file.isFile() || file.length() <= 0) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            long skipRemaining = Math.max(0L, file.length() - MAX_NATIVE_CRASH_BYTES);
            while (skipRemaining > 0L) {
                long skipped = in.skip(skipRemaining);
                if (skipped <= 0L) {
                    if (in.read() < 0) break;
                    skipped = 1L;
                }
                skipRemaining -= skipped;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(MAX_NATIVE_CRASH_BYTES);
            byte[] buffer = new byte[512];
            int count;
            while ((count = in.read(buffer, 0, Math.min(buffer.length, MAX_NATIVE_CRASH_BYTES - out.size()))) > 0 && out.size() < MAX_NATIVE_CRASH_BYTES) out.write(buffer, 0, count);
            String raw = new String(out.toByteArray(), "UTF-8");
            if (raw.length() > MAX_NATIVE_CRASH_SUMMARY) {
                int start = raw.length() - MAX_NATIVE_CRASH_SUMMARY;
                int nextLine = raw.indexOf('\n', start);
                if (nextLine >= 0 && nextLine + 1 < raw.length()) start = nextLine + 1;
                raw = raw.substring(start);
            }
            raw = raw.replace('\n', ' ').replace('\r', ' ');
            StringBuilder safe = new StringBuilder(Math.min(MAX_NATIVE_CRASH_SUMMARY, raw.length()));
            for (int i = 0; i < raw.length() && safe.length() < MAX_NATIVE_CRASH_SUMMARY; i++) {
                char ch = raw.charAt(i);
                safe.append((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                    (ch >= '0' && ch <= '9') || "_.,:/;=x[]()+# -".indexOf(ch) >= 0 ? ch : '_');
            }
            return safe.toString().trim();
        } catch (Throwable ignored) {
            return "native_crash_record_unreadable";
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void installUncaughtHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try { recordUncaught(error, thread); } catch (Throwable ignored) {}
                if (previous != null && previous != this) {
                    previous.uncaughtException(thread, error);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static void recordUncaught(Throwable error, Thread thread) throws Exception {
        Context c = app;
        if (c == null) return;
        Throwable root = error;
        int causes = 0;
        while (root != null && root.getCause() != null && root.getCause() != root && causes++ < 8) root = root.getCause();
        String summary = stackSummary(error, root);
        long now = SystemClock.elapsedRealtime();
        long elapsed = connectionStartedElapsed > 0L && now >= connectionStartedElapsed
            ? now - connectionStartedElapsed : 0L;
        String threadName = thread == null ? "unknown" : safe(thread.getName(), 48, "unknown");
        DiagnosticLog.recordImmediately(c, "JAVA_UNCAUGHT_EXCEPTION", new JSONObject()
            .put("phase", safe(currentPhase, 48, "unknown"))
            .put("reason", "uncaught_java_exception")
            .put("origin", "uncaught_handler")
            .put("errorType", simpleType(error))
            .put("causeType", simpleType(root))
            .put("causeMessage", safe(root == null ? null : root.getMessage(), 180, ""))
            .put("attempt", error == null ? 0 : error.getStackTrace().length)
            .put("state", Process.myPid())
            .put("elapsedMs", elapsed)
            .put("usbLinked", currentUsbLinked)
            .put("videoReady", currentVideoReady)
            .put("diagnosticCode", threadName)
            .put("diagnosticSummary", summary));
    }

    private static String stackSummary(Throwable error, Throwable root) {
        StringBuilder out = new StringBuilder(640);
        appendFrames(out, error, "e");
        if (root != error) appendFrames(out, root, "c");
        String value = out.toString();
        return value.length() <= 700 ? value : value.substring(0, 700);
    }

    private static void appendFrames(StringBuilder out, Throwable error, String prefix) {
        if (error == null) return;
        StackTraceElement[] frames = error.getStackTrace();
        int limit = Math.min(frames == null ? 0 : frames.length, 8);
        for (int i = 0; i < limit && out.length() < 680; i++) {
            StackTraceElement frame = frames[i];
            String cls = frame.getClassName() == null ? "unknown" : frame.getClassName();
            int packageEnd = cls.lastIndexOf('.');
            if (packageEnd >= 0) cls = cls.substring(packageEnd + 1);
            String method = frame.getMethodName() == null ? "unknown" : frame.getMethodName();
            String token = (out.length() == 0 ? "" : ";") + prefix + i + "_" + cls + "." + method + ":" + frame.getLineNumber();
            token = token.replace('$', '_').replace('<', '_').replace('>', '_');
            StringBuilder safe = new StringBuilder(token.length());
            for (int j = 0; j < token.length(); j++) {
                char ch = token.charAt(j);
                safe.append((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                    (ch >= '0' && ch <= '9') || "_.,:/;=x[]()+# -".indexOf(ch) >= 0 ? ch : '_');
            }
            if (out.length() + safe.length() > 700) break;
            out.append(safe);
        }
    }

    private static String simpleType(Throwable value) {
        if (value == null || value.getClass() == null) return "Throwable";
        return safe(value.getClass().getSimpleName(), 48, "Throwable");
    }

    private static String safe(String value, int max, String fallback) {
        if (value == null) return fallback;
        StringBuilder out = new StringBuilder(Math.min(value.length(), max));
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char ch = value.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
                (ch >= '0' && ch <= '9') || "_.:/-".indexOf(ch) >= 0) out.append(ch);
            else out.append('_');
        }
        return out.length() == 0 ? fallback : out.toString();
    }

    private static Context application(Context context) {
        Context c = app;
        if (c != null) return c;
        if (context == null) return null;
        c = context.getApplicationContext();
        app = c;
        return c;
    }
}
