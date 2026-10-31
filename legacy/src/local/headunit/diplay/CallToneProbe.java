package local.headunit.diplay;

import android.content.Context;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONObject;

/**
 * Injects three locally generated marker tones into the live call AudioTrack at
 * fixed offsets after the first call PCM. The tones are produced on the head
 * unit itself, so their audibility distinguishes "the whole Android output path
 * is cut during a call" from "only the CarPlay telephony PCM path is broken".
 * The user reports which beeps were heard; the log carries exact write times.
 */
public final class CallToneProbe {
    static final long[] OFFSETS_MS = { 3000, 8000, 13000 };
    static final double[] FREQUENCIES_HZ = { 660.0, 880.0, 1046.5 };
    static final int TONE_MS = 500;
    static final double AMPLITUDE = 10000.0;
    private static final Object LOCK = new Object();
    private static boolean active;
    private static long firstPcmNanos;
    private static int nextStage;

    private CallToneProbe() { }

    public static void callStarting() {
        synchronized (LOCK) { active = true; firstPcmNanos = 0; nextStage = 0; }
    }

    public static void callEnded() {
        synchronized (LOCK) { active = false; firstPcmNanos = 0; nextStage = 0; }
    }

    /** Index of the tone due at this elapsed time, or -1. Pure; unit-tested. */
    static int pendingStage(long elapsedMs, int stage) {
        return stage >= 0 && stage < OFFSETS_MS.length && elapsedMs >= OFFSETS_MS[stage] ? stage : -1;
    }

    static byte[] tonePcm(double hz, int sampleRate, int channels, int millis) {
        int frames = Math.max(1, sampleRate * millis / 1000);
        byte[] pcm = new byte[frames * channels * 2];
        for (int f = 0; f < frames; f++) {
            short v = (short) (Math.sin(2.0 * Math.PI * hz * f / sampleRate) * AMPLITUDE);
            for (int ch = 0; ch < channels; ch++) {
                int at = (f * channels + ch) * 2;
                pcm[at] = (byte) v;
                pcm[at + 1] = (byte) (v >>> 8);
            }
        }
        return pcm;
    }

    /** Called from the call audio worker before each packet write; injects at most one due tone. */
    public static void maybeInject(Context context, AudioTrack track, int sampleRate, int channels, int stream) {
        if (CallDuplexAB.isProbeActive()) return;
        int stage;
        long elapsedMs;
        synchronized (LOCK) {
            if (!active) return;
            long now = System.nanoTime();
            if (firstPcmNanos == 0) { firstPcmNanos = now; return; }
            elapsedMs = (now - firstPcmNanos) / 1000000L;
            stage = pendingStage(elapsedMs, nextStage);
            if (stage < 0) return;
            nextStage++;
        }
        byte[] tone = tonePcm(FREQUENCIES_HZ[stage], sampleRate, channels, TONE_MS);
        int written = 0;
        String status = "complete", error = null;
        long deadline = SystemClock.elapsedRealtime() + 2000;
        try {
            while (written < tone.length && SystemClock.elapsedRealtime() < deadline) {
                int n = track.write(tone, written, tone.length - written);
                if (n < 0) throw new IllegalStateException("write=" + n);
                if (n == 0) { Thread.sleep(10); continue; }
                written += n;
            }
            if (written != tone.length) status = "timeout";
        } catch (Throwable t) {
            status = "failed";
            error = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        } finally {
            java.util.Arrays.fill(tone, (byte) 0);
        }
        try {
            MainActivity.record(context, "CALL_TONE_INJECT", new JSONObject()
                    .put("stage", stage).put("frequencyHz", FREQUENCIES_HZ[stage])
                    .put("durationMs", TONE_MS).put("elapsedSinceFirstCallPcmMs", elapsedMs)
                    .put("stream", stream).put("sampleRate", sampleRate).put("channels", channels)
                    .put("writtenBytes", written).put("status", status).put("error", error)
                    .put("evidenceScope", "tone accepted by the call AudioTrack; audibility is reported by the user"));
        } catch (Exception ignored) { }
    }
}
