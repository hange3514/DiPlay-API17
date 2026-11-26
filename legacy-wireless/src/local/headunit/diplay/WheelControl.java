package local.headunit.diplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Tries the known BYD wheel-key broadcast protocol on every API 17+ head unit.
 * Profile matching is diagnostic metadata only; it must not suppress discovery.
 */
public final class WheelControl extends BroadcastReceiver implements Closeable {
    public interface Sink {
        Object session();
        boolean send(Object session, int action);
    }

    private static final String ACTION = "APP_REV_DATA";
    private static final String OEM_PACKAGE = "com.byd.multimediaservice";
    private static final int KNOWN_SERVICE_VERSION = 382;
    private static final long NON_KEY_FRAME_LOG_INTERVAL_MS = 15000L;
    static final long MAX_INPUT_AGE_MS = 600;

    private final Context context;
    private final Sink sink;
    private final WheelKeyPolicy policy = new WheelKeyPolicy();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(4));
    private final boolean knownProfileMatched;
    private final int oemServiceVersion;
    private volatile boolean closed;
    private volatile boolean registered;
    // Accessed only by Android's broadcast callback on the main thread.
    private long lastNonKeyLogAtMs = -1L;
    private int nonKeyFrameCount;

    private static int serviceVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(OEM_PACKAGE, 0);
            return info.versionCode;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean matchesKnownProfile(Context context, int serviceVersion) {
        return Build.VERSION.SDK_INT == 17
            && "MX9A0".equals(Build.MODEL)
            && serviceVersion == KNOWN_SERVICE_VERSION;
    }

    private static boolean shouldAttempt(Context context) {
        return context != null && Build.VERSION.SDK_INT >= 17;
    }

    public WheelControl(Context context, Sink sink) {
        this(context, sink, shouldAttempt(context));
    }

    /** Package-private switch remains for instrumentation tests. */
    WheelControl(Context context, Sink sink, boolean shouldAttempt) {
        this.context = context.getApplicationContext();
        this.sink = sink;
        this.oemServiceVersion = serviceVersion(this.context);
        this.knownProfileMatched = matchesKnownProfile(this.context, oemServiceVersion);

        if (!shouldAttempt) {
            log(0, "receiver_skipped_unsupported_sdk", -1, -1, -1, -1, null);
            return;
        }

        // Even an unknown model gets a real listener attempt. Only the existing
        // strictly decoded key frames are ever forwarded to the active session.
        try {
            this.context.registerReceiver(this, new IntentFilter(ACTION));
            registered = true;
            log(0, "receiver_registered", -1, -1, -1, -1, null);
        } catch (Exception error) {
            log(0, "receiver_register_failed", -1, -1, -1, -1,
                error.getClass().getSimpleName());
        }
    }

    public boolean duplicateAndroid(int key) {
        return !closed && registered && !MainActivity.wheelProbeActive()
            && sink.session() != null
            && policy.duplicateAndroid(key, SystemClock.elapsedRealtime());
    }

    /** Android key-event path; independent of the OEM broadcast receiver. */
    public boolean acceptAndroidKey(int key) {
        return !closed && policy.acceptAndroid(
            WheelKeyPolicy.androidAction(key), SystemClock.elapsedRealtime());
    }

    boolean enqueueAndroidReport(Runnable report) {
        if (closed) return false;
        try {
            worker.execute(report);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    @Override
    public void onReceive(Context ignored, Intent intent) {
        if (closed || MainActivity.wheelProbeActive() || intent == null
            || !ACTION.equals(intent.getAction())) return;

        try {
            final byte[] raw = intent.getByteArrayExtra("list");
            final int length = raw == null ? -1 : raw.length;
            final int header0 = raw != null && raw.length > 0 ? raw[0] & 255 : -1;
            final int header1 = raw != null && raw.length > 1 ? raw[1] & 255 : -1;
            final int keyCode = raw != null && raw.length == 3
                && header0 == 4 && header1 == 1 ? raw[2] & 255 : -1;
            final int action = WheelKeyPolicy.decode(raw);

            if (action == 0) {
                if (keyCode >= 0) {
                    log(0, "unmapped_oem_key", keyCode, length, header0, header1, null);
                } else {
                    logNonKeyFrame(raw == null ? "missing_key_payload" : "unrecognized_oem_frame",
                        length, null);
                }
                return;
            }

            final Object session = sink.session();
            if (session == null) {
                log(action, "no_active_session", keyCode, length, header0, header1, null);
                return;
            }

            final long received = SystemClock.elapsedRealtime();
            if (!policy.accept(action, received)) {
                log(action, "duplicate_ignored", keyCode, length, header0, header1, null);
                return;
            }

            try {
                worker.execute(new Runnable() {
                    @Override public void run() {
                        if (closed) return;
                        if (MainActivity.wheelProbeActive() || sink.session() != session
                            || SystemClock.elapsedRealtime() - received > MAX_INPUT_AGE_MS) {
                            log(action, "stale_ignored", keyCode, length,
                                header0, header1, null);
                            return;
                        }
                        try {
                            log(action, sink.send(session, action)
                                ? "reports_written_not_phone_acknowledged"
                                : "report_write_failed", keyCode, length,
                                header0, header1, null);
                        } catch (Exception error) {
                            log(action, "send_failed", keyCode, length,
                                header0, header1, error.getClass().getSimpleName());
                        }
                    }
                });
            } catch (RejectedExecutionException error) {
                log(action, "queue_full", keyCode, length, header0, header1, null);
            }
        } catch (Exception error) {
            logNonKeyFrame("invalid_event", -1, error.getClass().getSimpleName());
        }
    }

    private void logNonKeyFrame(String result, int frameLength, String errorType) {
        if (nonKeyFrameCount < Integer.MAX_VALUE) nonKeyFrameCount++;
        long now = SystemClock.elapsedRealtime();
        if (lastNonKeyLogAtMs >= 0 && now - lastNonKeyLogAtMs < NON_KEY_FRAME_LOG_INTERVAL_MS) return;
        lastNonKeyLogAtMs = now;
        // APP_REV_DATA also carries other OEM messages. Record only the shape
        // and volume of frames outside the known key envelope.
        log(0, result, -1, frameLength, -1, -1, errorType, nonKeyFrameCount);
        nonKeyFrameCount = 0;
    }

    private void log(int action, String result, int keyCode, int frameLength,
                     int header0, int header1, String errorType) {
        log(action, result, keyCode, frameLength, header0, header1, errorType, 0);
    }

    private void log(int action, String result, int keyCode, int frameLength,
                     int header0, int header1, String errorType, int nonKeyFrames) {
        try {
            JSONObject row = new JSONObject()
                .put("wheelAction", action)
                .put("keyPath", "oem_broadcast")
                .put("oemBroadcastAction", ACTION)
                .put("result", result)
                .put("knownProfileMatched", knownProfileMatched)
                .put("receiverRegistered", registered)
                .put("oemServiceVersion", oemServiceVersion)
                .put("connection", WiredService.currentConnectionId());
            if (keyCode >= 0) row.put("keyCode", keyCode);
            if (frameLength >= 0) row.put("oemFrameLength", frameLength);
            if (header0 >= 0) row.put("oemHeader0", header0);
            if (header1 >= 0) row.put("oemHeader1", header1);
            if (errorType != null) row.put("errorType", errorType);
            if (nonKeyFrames > 0) row.put("oemNonKeyFramesSinceLastSample", nonKeyFrames);
            MainActivity.record(context, "WHEEL_CONTROL", row);
        } catch (Exception ignored) { }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (registered) {
            registered = false;
            try {
                context.unregisterReceiver(this);
                log(0, "receiver_unregistered", -1, -1, -1, -1, null);
            } catch (Exception error) {
                log(0, "receiver_unregister_failed", -1, -1, -1, -1,
                    error.getClass().getSimpleName());
            }
        }
        worker.shutdownNow();
    }
}
