package local.headunit.diplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable breadcrumbs for process death during a hybrid wireless CarPlay connection. */
final class CrashEvidence {
    private static final String PREFS = "diplay_crash_evidence";
    private static final String PENDING = "pending_incidents";
    private static final int MAX_PENDING = 8;
    private static final int MAX_TRAIL_STAGES = 6;
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
    private static int currentIap2MessageId = -1;
    private static String phaseTrail = "";
    private static volatile BootContinuity processContinuity = BootContinuity.UNKNOWN;
    private static boolean initialized;

    private CrashEvidence() {}

    static void initialize(Context context) {
        synchronized (LOCK) {
            if (initialized) return;
            initialized = true;
            app = context.getApplicationContext();
        }
        installUncaughtHandler();
        try {
            // Sample once. Connection-stage commits only copy the small cached result.
            RECOVERY_WORKER.execute(new Runnable() {
                @Override public void run() {
                    try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                    catch (RuntimeException unavailable) { }
                    processContinuity = BootContinuity.sample(new File("/proc"));
                }
            });
        } catch (RuntimeException ignored) { }
        recoverPreviousProcess();
    }

    static void begin(Context context, String connectionId) {
        Context c = application(context);
        if (c == null) return;
        String id = safe(connectionId, 48, "unknown");
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            currentConnection = id;
            currentPhase = "service_start";
            connectionStartedElapsed = now;
            currentUsbLinked = false;
            currentVideoReady = false;
            currentIap2MessageId = -1;
            phaseTrail = "service_start:0";
            writeMarker(c, id, "service_start", now, false, false);
        }
    }

    static void updateStage(Context context, String connectionId, String stage,
                            boolean usbLinked, boolean videoReady) {
        updateStage(context, connectionId, stage, usbLinked, videoReady, -1);
    }

    static void updateStage(Context context, String connectionId, String stage,
                            boolean usbLinked, boolean videoReady, int lastIap2MessageId) {
        Context c = application(context);
        if (c == null) return;
        String id = safe(connectionId, 48, "unknown");
        synchronized (LOCK) {
            if (!id.equals(currentConnection)) return;
            String next = safe(stage, 48, "unknown");
            if (!next.equals(currentPhase)) {
                phaseTrail = appendPhase(phaseTrail, next, SystemClock.elapsedRealtime() - connectionStartedElapsed);
                currentPhase = next;
            }
            currentUsbLinked = usbLinked;
            currentVideoReady = videoReady;
            if (lastIap2MessageId >= 0 && lastIap2MessageId <= 0xffff) currentIap2MessageId = lastIap2MessageId;
            writeMarker(c, id, next, connectionStartedElapsed, usbLinked, videoReady);
        }
    }

    static void end(Context context, String connectionId, String stage, long elapsedMs) {
        Context c = application(context);
        if (c == null) return;
        String id = safe(connectionId, 48, "unknown");
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long started;
        final boolean usbLinked;
        final boolean videoReady;
        synchronized (LOCK) {
            if (!id.equals(currentConnection) || !id.equals(prefs.getString("connection", ""))) return;
            started = connectionStartedElapsed;
            usbLinked = currentUsbLinked;
            videoReady = currentVideoReady;
        }
        try {
            DiagnosticLog.recordImmediately(c, "CONNECTION_CLEAN_EXIT", new JSONObject()
                .put("phase", safe(stage, 48, "cleanup_complete"))
                .put("reason", "orderly_shutdown")
                .put("elapsedMs", Math.max(0L, elapsedMs))
                .put("usbLinked", usbLinked)
                .put("videoReady", videoReady));
            synchronized (LOCK) {
                if (!id.equals(currentConnection) || started != connectionStartedElapsed) return;
                clearMatchingMarker(prefs, id, started);
                currentConnection = "";
                currentPhase = "unknown";
                connectionStartedElapsed = 0L;
                currentUsbLinked = false;
                currentVideoReady = false;
                currentIap2MessageId = -1;
            }
        } catch (Throwable ignored) {
            // Keep the marker if durable logging failed; the next launch can still report it.
        }
    }

    private static void writeMarker(Context c, String id, String stage, long startedElapsed,
                                    boolean usbLinked, boolean videoReady) {
        try {
            BootContinuity continuity = processContinuity;
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("active", true)
                .putString("connection", id)
                .putString("phase", stage)
                .putInt("processId", Process.myPid())
                .putLong("startedAt", startedElapsed)
                .putLong("stageAt", SystemClock.elapsedRealtime())
                .putInt("lastIap2MessageId", currentIap2MessageId)
                .putString("phaseTrail", phaseTrail)
                .putString("bootId", continuity.bootId)
                .putString("systemServer", continuity.systemServer)
                .putString("zygote", continuity.zygote)
                .putBoolean("usbLinked", usbLinked)
                .putBoolean("videoReady", videoReady)
                .commit();
        } catch (Throwable ignored) {
            // Diagnostics must never interrupt the USB/CarPlay path.
        }
    }

    private static void clearMatchingMarker(SharedPreferences prefs, String id, long started) {
        synchronized (LOCK) {
            if (id.equals(prefs.getString("connection", "")) &&
                    started == prefs.getLong("startedAt", 0L)) prefs.edit().putBoolean("active", false).commit();
        }
    }

    /**
     * Preserve the old marker before the service can replace it. An interrupted
     * observer leaves its snapshot queued for the next app launch.
     */
    private static void recoverPreviousProcess() {
        final Context c = app;
        if (c == null) return;
        final SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray pending;
        String currentCapture = "";
        synchronized (LOCK) {
            pending = pendingIncidents(prefs);
            if (prefs.getBoolean("active", false)) {
                try {
                    JSONObject incident = new JSONObject()
                        .put("captureId", UUID.randomUUID().toString())
                        .put("phase", safe(prefs.getString("phase", "unknown"), 48, "unknown"))
                        .put("phaseTrail", prefs.getString("phaseTrail", ""))
                        .put("connection", safe(prefs.getString("connection", ""), 48, "unknown"))
                        .put("oldPid", prefs.getInt("processId", 0))
                        .put("startedAt", prefs.getLong("startedAt", 0L))
                        .put("stageAt", prefs.getLong("stageAt", -1L))
                        .put("reopenedAt", SystemClock.elapsedRealtime())
                        .put("observedWallTime", System.currentTimeMillis())
                        .put("usbLinked", prefs.getBoolean("usbLinked", false))
                        .put("videoReady", prefs.getBoolean("videoReady", false))
                        .put("lastIap2MessageId", prefs.getInt("lastIap2MessageId", -1))
                        .put("bootId", prefs.getString("bootId", ""))
                        .put("systemServer", prefs.getString("systemServer", ""))
                        .put("zygote", prefs.getString("zygote", ""))
                        .put("nativeFailure", readNativeFailure(c));
                    pending = appendIncident(pending, incident);
                    if (prefs.edit().putString(PENDING, pending.toString()).putBoolean("active", false).commit()) {
                        if (!incident.optString("nativeFailure").isEmpty()) {
                            new File(c.getFilesDir(), "native-crash.txt").delete();
                        }
                    }
                    currentCapture = incident.getString("captureId");
                } catch (JSONException ignored) {
                    // Keep the active marker when it could not be archived.
                }
            }
        }
        if (pending.length() == 0) return;
        final String observedHere = currentCapture;
        final JSONArray archived = pending;
        try {
            RECOVERY_WORKER.execute(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < archived.length(); i++) {
                        JSONObject incident = archived.optJSONObject(i);
                        if (incident == null) continue;
                        try {
                            String captureId = incident.getString("captureId");
                            JSONObject recovery = recoveryEntry(c, incident, captureId.equals(observedHere));
                            DiagnosticLog.recordRecovery(c, captureId, recovery);
                            synchronized (LOCK) {
                                JSONArray retained = pendingIncidents(prefs);
                                for (int index = 0; index < retained.length(); index++) {
                                    JSONObject stored = retained.optJSONObject(index);
                                    if (stored != null && captureId.equals(stored.optString("captureId"))) {
                                        // API 17 JSONArray has no remove method.
                                        retained = withoutIncident(retained, index);
                                        prefs.edit().putString(PENDING, retained.toString()).commit();
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            // Keep the archived marker for another startup.
                        }
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // Keep the archived markers if the worker cannot start.
        }
    }

    private static JSONArray pendingIncidents(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(PENDING, "[]")); }
        catch (JSONException invalid) { return new JSONArray(); }
    }

    static JSONArray withoutIncident(JSONArray pending, int index) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < pending.length(); i++) {
            if (i != index && pending.optJSONObject(i) != null) kept.put(pending.optJSONObject(i));
        }
        return kept;
    }

    static JSONArray appendIncident(JSONArray pending, JSONObject incident) {
        JSONArray retained = new JSONArray();
        for (int i = Math.max(0, pending.length() - MAX_PENDING + 1); i < pending.length(); i++) {
            JSONObject row = pending.optJSONObject(i);
            if (row != null) retained.put(row);
        }
        retained.put(incident);
        return retained;
    }

    static String appendPhase(String previous, String next, long elapsedMs) {
        String joined = previous.isEmpty() ? next + ":" + Math.max(0L, elapsedMs) :
            previous + "," + next + ":" + Math.max(0L, elapsedMs);
        int count = 1;
        for (int i = joined.length() - 1; i >= 0; i--) {
            if (joined.charAt(i) == ',' && ++count > MAX_TRAIL_STAGES) return joined.substring(i + 1);
        }
        return joined;
    }

    private static JSONObject recoveryEntry(Context c, JSONObject incident, boolean observedHere) throws JSONException {
        long previousStageAt = incident.optLong("stageAt", -1L);
        long reopenedAt = incident.optLong("reopenedAt", -1L);
        long started = incident.optLong("startedAt", 0L);
        BootContinuity previous = new BootContinuity(incident.optString("bootId"),
            incident.optString("systemServer"), incident.optString("zygote"));
        BootContinuity current = observedHere ? processContinuity : BootContinuity.UNKNOWN;
        String continuity = observedHere ? current.classify(previous, previousStageAt, reopenedAt) :
            "recovery_observer_interrupted";
        boolean sameBoot = BootContinuity.comparable(previous.bootId, current.bootId) &&
            previous.bootId.equals(current.bootId);
        JSONObject recovery = new JSONObject()
            .put("phase", incident.optString("phase", "unknown"))
            .put("reason", "active_connection_after_process_restart")
            .put("origin", "application_start")
            .put("errorType", "UnexpectedProcessExit")
            .put("attempt", incident.optInt("oldPid"))
            .put("elapsedMs", sameBoot && started > 0L && reopenedAt >= started ? reopenedAt - started : 0L)
            .put("recoveryContinuity", continuity)
            .put("previousBootElapsedMs", previousStageAt)
            .put("currentBootElapsedMs", reopenedAt)
            .put("bootIdCompared", BootContinuity.comparable(previous.bootId, current.bootId))
            .put("systemServerCompared", BootContinuity.comparable(previous.systemServer, current.systemServer))
            .put("zygoteCompared", BootContinuity.comparable(previous.zygote, current.zygote))
            .put("usbLinked", incident.optBoolean("usbLinked"))
            .put("videoReady", incident.optBoolean("videoReady"))
            .put("diagnosticCode", incident.optString("connection", "unknown"));
        if (observedHere) recovery.put("state", Process.myPid());
        Boolean serverChanged = BootContinuity.identityChanged(previous.systemServer, current.systemServer);
        if (serverChanged != null) recovery.put("systemServerChanged", serverChanged);
        Boolean zygoteChanged = BootContinuity.identityChanged(previous.zygote, current.zygote);
        if (zygoteChanged != null) recovery.put("zygoteChanged", zygoteChanged);
        if (sameBoot && previousStageAt >= 0L && reopenedAt >= previousStageAt) {
            recovery.put("previousStageAgeMs", reopenedAt - previousStageAt);
        }
        int messageId = incident.optInt("lastIap2MessageId", -1);
        if (messageId >= 0 && messageId <= 0xffff) recovery.put("lastIap2MessageId", messageId);
        String trail = incident.optString("phaseTrail", "");
        String nativeSummary = incident.optString("nativeFailure", "");
        JSONObject system = RecoverySystemEvidence.capture(c, incident.optLong("observedWallTime"), continuity);
        String summary = (trail.isEmpty() ? "" : "phaseTrail=" + safeTrail(trail) + " ")
            + system.optString("diagnosticSummary", "systemLogStatus=unavailable")
            + (nativeSummary.isEmpty() ? "" : " " + nativeSummary.substring(0, Math.min(160, nativeSummary.length())));
        recovery.put("diagnosticSummary", summary.trim());
        return recovery;
    }

    private static String safeTrail(String trail) {
        String value = trail.replaceAll("[^A-Za-z0-9_.,:/-]", "_");
        return value.length() <= 360 ? value : value.substring(value.length() - 360);
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
