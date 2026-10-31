package local.headunit.diplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.Closeable;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only audio/MCU evidence. Never captures microphone audio or identifies third-party apps. */
public final class OemAudioObserver extends BroadcastReceiver implements Closeable {
    private static final int MAX_RAW_CALL_FRAMES = 24;
    private static final int MAX_RAW_WHOLE_CALL_FRAMES = 64;
    private final Context context;
    private final Handler sampler = new Handler(Looper.getMainLooper());
    private boolean registered;
    private String lastState;
    private long lastSampleLog;
    private static volatile long callRouteCaptureUntil;
    private static volatile int rawCallFrameCount;
    private static volatile String callRouteCapturePhase = "none";
    private static volatile boolean wholeCallCapture;

    public interface McuSourceListener { void onMcuSource(int source, long elapsedMs); }
    private static volatile McuSourceListener mcuSourceListener;
    static void setMcuSourceListener(McuSourceListener listener) { mcuSourceListener = listener; }

    static void beginCallRouteCapture() {
        callRouteCapturePhase = "call_enter";
        rawCallFrameCount = 0;
        wholeCallCapture = false;
        callRouteCaptureUntil = SystemClock.elapsedRealtime() + 8000;
    }

    static void beginCallRouteExitCapture() {
        callRouteCapturePhase = "call_exit";
        rawCallFrameCount = 0;
        wholeCallCapture = false;
        callRouteCaptureUntil = SystemClock.elapsedRealtime() + 8000;
    }

    /** Whole-call capture: the conversation window had no source broadcast in the 0.53.52 report. */
    static void beginWholeCallCapture() {
        callRouteCapturePhase = "call_active";
        rawCallFrameCount = 0;
        wholeCallCapture = true;
        callRouteCaptureUntil = Long.MAX_VALUE;
    }

    static void endCallRouteCapture() { callRouteCaptureUntil = 0; callRouteCapturePhase = "none"; wholeCallCapture = false; }

    static boolean shouldCaptureCallRouteFrame(String action, int group, int command, long now, long until) {
        boolean rawSerial = "INTENT_SERIAL_DATA".equals(action);
        boolean appFeedback = "APP_REV_DATA".equals(action);
        boolean rawSourceState = rawSerial && group == 1 && command == 2;
        boolean sourceOrMuteEcho = appFeedback && group == 2;
        boolean muteStatus = group == 4 && command == 4;
        return (rawSourceState || sourceOrMuteEcho || muteStatus)
                && (rawSerial || appFeedback) && now <= until;
    }

    private final Runnable sample = new Runnable() {
        public void run() {
            if (!registered) return;
            try {
                JSONObject state = snapshot(context);
                String signature = state.toString();
                long now = SystemClock.elapsedRealtime();
                if (!signature.equals(lastState) || now - lastSampleLog >= 10000) {
                    state.put("elapsedMs", now).put("stateChanged", !signature.equals(lastState));
                    MainActivity.record(context, "THIRD_PARTY_AUDIO_STATE", state);
                    lastState = signature;
                    lastSampleLog = now;
                }
            } catch (Exception ignored) { }
            if (registered) sampler.postDelayed(this, 1000);
        }
    };

    public OemAudioObserver(Context c) {
        context = c.getApplicationContext();
        IntentFilter f = new IntentFilter("APP_REV_DATA");
        f.addAction("APP_REV_BT_DATA");
        // SerialService broadcasts non-ACK MCU responses here before the OEM
        // CSerialPortProxy transforms them into app-level broadcasts. Listening
        // is passive and lets diagnostics distinguish raw MCU feedback from the
        // OEM service's own request echo.
        f.addAction("INTENT_SERIAL_DATA");
        f.addAction("android.intent.action.MULTIMEDIA_AUDIO_FOCUS_INFO");
        f.addAction("android.intent.action.MULTIMEDIA_NAVI_AUDIO_START");
        f.addAction("android.intent.action.MULTIMEDIA_NAVI_AUDIO_STOP");
        context.registerReceiver(this, f);
        registered = true;
        sampler.post(sample);
    }

    public static JSONObject snapshot(Context c) throws Exception {
        AudioManager a = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int second = AudioRouting.secondMusic(c);
        return new JSONObject().put("scope", "passive_global_audio_state")
                .put("appIdentity", "not_available_on_api17")
                .put("recordingOwner", "not_available_on_api17")
                .put("focusOwner", "not_available_by_public_api")
                .put("mode", a.getMode()).put("musicActive", a.isMusicActive())
                .put("musicVolume", a.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("musicMax", a.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("voiceCallVolume", a.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
                .put("voiceCallMax", a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL))
                .put("bluetoothSco", a.isBluetoothScoOn()).put("bluetoothA2dp", a.isBluetoothA2dpOn())
                .put("speaker", a.isSpeakerphoneOn()).put("wiredHeadset", a.isWiredHeadsetOn())
                .put("microphoneMuted", a.isMicrophoneMute()).put("navigationStream", second)
                .put("navigationVolume", second == 10 ? a.getStreamVolume(second) : -1)
                .put("status", "measured_without_audio_capture").put("legacyRouting", AudioRouteProbe.snapshot(a));
    }

    public void onReceive(Context c, Intent i) {
        try {
            String action = i.getAction();
            long now = SystemClock.elapsedRealtime();
            if ("INTENT_SERIAL_DATA".equals(action)) {
                byte[] frame = i.getByteArrayExtra("list");
                // Official CSerialPortProxy documents the raw payload at byte 4:
                // AA55, length, sequence, group, command, data... . The OEM
                // DecodeCommonInfo -> DecodeSourceInfo path uses group=1,
                // command=2, data at byte 6 for actual MCU source-state readback.
                // ACKs are consumed by SerialService and never broadcast here.
                if (frame == null || frame.length < 7) return;
                int group = frame[4] & 255, command = frame[5] & 255;
                if (!shouldCaptureCallRouteFrame(action, group, command, now, callRouteCaptureUntil)) return;
                if (group == 1 && command == 2) {
                    McuSourceListener listener = mcuSourceListener;
                    if (listener != null) listener.onMcuSource(frame[6] & 255, now);
                }
                int cap = wholeCallCapture ? MAX_RAW_WHOLE_CALL_FRAMES : MAX_RAW_CALL_FRAMES;
                if (rawCallFrameCount >= cap) return;
                rawCallFrameCount++;
                JSONArray bytes = new JSONArray();
                for (int n = 0; n < Math.min(frame.length, 16); n++) bytes.put(frame[n] & 255);
                MainActivity.record(c, "OEM_SERIAL_RAW_CALL_FEEDBACK", new JSONObject()
                        .put("elapsedMs", now).put("phase", callRouteCapturePhase).put("group", group).put("command", command)
                        .put("length", frame.length).put("bytes", bytes)
                        .put("feedbackKind", group == 1 && command == 2 ? "mcu_source_state" : "mcu_mute_or_audio_state")
                        .put("decodedSource", group == 1 && command == 2 ? frame[6] & 255 : -1)
                        .put("frameFormat", "AA55; group/command at offsets 4/5")
                        .put("scope", wholeCallCapture
                                ? "whole_call; raw non-ACK SerialService MCU broadcast; source state is decoded by OEM DecodeSourceInfo; not physical speaker confirmation"
                                : "bounded_8s; raw non-ACK SerialService MCU broadcast; source state is decoded by OEM DecodeSourceInfo; not physical speaker confirmation"));
                return;
            }

            JSONObject d = new JSONObject().put("action", action);
            if ("APP_REV_DATA".equals(action) || "APP_REV_BT_DATA".equals(action)) {
                byte[] b = i.getByteArrayExtra("list");
                if (b == null || b.length < 2) return;
                int group = b[0] & 255, command = b[1] & 255;
                if (shouldCaptureCallRouteFrame(action, group, command, now, callRouteCaptureUntil)) {
                    JSONArray bytes = new JSONArray();
                    for (int n = 0; n < Math.min(b.length, 12); n++) bytes.put(b[n] & 255);
                    MainActivity.record(c, "OEM_CALL_ROUTE_FEEDBACK", new JSONObject()
                            .put("elapsedMs", now).put("phase", callRouteCapturePhase).put("group", group).put("command", command)
                            .put("length", b.length).put("bytes", bytes)
                            .put("scope", "bounded_8s OEM app broadcast; may include service echo; not physical speaker confirmation"));
                }
                // 0.53.78: log every APP_REV_* frame. The key frames are group 4 ({4,1,x}) and were
                // invisible while the filter kept only group 2/3, which hid the track-key branches.
                d.put("group", group).put("command", command).put("elapsedMs", now);
                JSONArray rawBytes = new JSONArray();
                for (int n = 0; n < Math.min(b.length, 12); n++) rawBytes.put(b[n] & 255);
                d.put("bytes", rawBytes);
                if ("APP_REV_DATA".equals(action) && b.length >= 3) d.put("sourceOrMute", b[2] & 255);
            }
            if ("android.intent.action.MULTIMEDIA_AUDIO_FOCUS_INFO".equals(action)) {
                String pkg = i.getStringExtra("android.intent.extra.MULTIMEDIA_PACKAGE_NAME");
                if (pkg != null && pkg.length() <= 160 && pkg.matches("[A-Za-z0-9_.]+")) d.put("reportedPackage", pkg);
                d.put("reportedGain", i.getIntExtra("android.intent.extra.MULTIMEDIA_AUDIO_FOCUS_TYPE", -1))
                        .put("evidence", "oem_broadcast_not_authenticated_owner");
            }
            AudioManager a = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            d.put("mode", a.getMode()).put("sco", a.isBluetoothScoOn()).put("speaker", a.isSpeakerphoneOn());
            MainActivity.record(c, "OEM_AUDIO_STATE", d);
        } catch (Exception ignored) { }
    }

    public void close() {
        if (!registered) return;
        registered = false;
        sampler.removeCallbacks(sample);
        try { context.unregisterReceiver(this); } catch (Exception ignored) { }
    }
}
