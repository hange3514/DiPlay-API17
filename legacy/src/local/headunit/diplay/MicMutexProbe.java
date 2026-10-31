package local.headunit.diplay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Panel self-test v5: per-segment immediate Q&A. Each segment plays, then a
 * dialog asks right away whether it was heard — no time memorizing. Segment
 * list: S0 tone only, S1 AudioRecord (mutes speaker), S2 raw ALSA capture at
 * the HAL's exact config (48kHz stereo) on tas3208, S3 raw capture on
 * pcm1754, S4 recovery. tas3208 exposes zero mixer controls (controls=0), so
 * mixer enabling was dropped; the capture config now matches
 * pcm_config_mm_in read from the OEM HAL binary.
 */
public final class MicMutexProbe {
    private static final int RATE = 48000;
    private static final int MIC_RATE = 16000;
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();

    private MicMutexProbe() { }

    public static void offer(final MainActivity owner) {
        new AlertDialog.Builder(owner).setTitle("麦克风互斥自检")
            .setMessage("停车后进行，不需要插 USB、不需要打电话。先把音量调到能听清的中等大小。"
                + "自检分 6 段，每段播放几秒提示音后声音会完全停下，然后弹出询问，回答后进入下一段。"
                + "其中一段没有提示音，需要你对着车机喊话。全部完成后自动提交日志。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始自检", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    owner.task("mic_mutex_probe", new MainActivity.Work() {
                        public String run() throws Exception { return runProbe(owner); }
                    });
                }
            }).show();
    }

    private static AudioTrack openToneTrack() {
        int min = AudioTrack.getMinBufferSize(RATE, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, RATE / 5),
                AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            return null;
        }
        track.play();
        return track;
    }

    private static String runProbe(final MainActivity owner) throws Exception {
        final Context context = owner.getApplicationContext();
        final AudioManager am = (AudioManager) owner.getSystemService(Context.AUDIO_SERVICE);
        final byte[] chunk = CallToneProbe.tonePcm(660.0, RATE, 1, 500);
        final boolean[] stop = { false };
        final boolean[] toneOn = { true };
        final AudioTrack[] holder = { openToneTrack() };
        Thread writer = new Thread(new Runnable() {
            public void run() {
                try {
                    while (!stop[0]) {
                        AudioTrack track = holder[0];
                        if (track == null || !toneOn[0]) { Thread.sleep(50); continue; }
                        int at = 0;
                        while (at < chunk.length && !stop[0] && toneOn[0] && holder[0] == track) {
                            int n = track.write(chunk, at, chunk.length - at);
                            if (n <= 0) break;
                            at += n;
                        }
                    }
                } catch (Throwable ignored) { }
                finally { java.util.Arrays.fill(chunk, (byte) 0); }
            }
        });
        writer.start();
        toneGateRef = toneOn;
        toneTrackRef = holder;
        JSONArray alsa = alsaProbe();
        boolean alsaAvailable = AlsaProbe.available();
        JSONArray answers = new JSONArray();
        JSONArray readbacks = new JSONArray();
        String[] mixerResults = new String[2];
        try {
            // S0: tone only, 4s. Baseline control snapshot at ~1.5s.
            record(context, "MIC_MUTEX_STAGE",
                    readback(am, 0, "stage_start").put("elapsedMs", 0));
            sleep(1500);
            if (alsaAvailable) {
                record(context, "MIC_MUTEX_CTLDUMP", new JSONObject()
                        .put("phase", "baseline_S0").put("card", 0).put("result", dumpControls(0)));
            }
            sleep(2500);
            answers.put(answer(owner, 0, 6, "纯提示音"));

            // S1: AudioRecord VOICE_RECOGNITION for 4s (expected to mute the speaker).
            record(context, "MIC_MUTEX_STAGE",
                    readback(am, 1, "stage_start").put("elapsedMs", 4000));
            runRecordStage(owner, context, am, 6, 1, 4000, readbacks, alsaAvailable);
            answers.put(answer(owner, 1, 6, "系统录音源（语音识别）"));

            // S2: raw ALSA capture on tas3208 at the HAL's exact config, 48kHz stereo, 4s.
            record(context, "MIC_MUTEX_STAGE",
                    readback(am, 2, "stage_start").put("elapsedMs", 8000));
            mixerResults[0] = runAlsaStage(context, 2, "/dev/snd/pcmC0D0c", RATE, 2, 4000, alsaAvailable, true);
            answers.put(answer(owner, 2, 6, "底层直采·48k立体声·声卡0(tas3208)"));

            // S3: raw ALSA capture on pcm1754 with tone ON, 4s.
            record(context, "MIC_MUTEX_STAGE",
                    readback(am, 3, "stage_start").put("elapsedMs", 12000));
            mixerResults[1] = runAlsaStage(context, 3, "/dev/snd/pcmC1D0c", RATE, 2, 4000, alsaAvailable, true);
            answers.put(answer(owner, 3, 6, "底层直采·声卡1·提示音开着"));

            // S4: raw ALSA capture on pcm1754 with tone OFF, user speaks, 6s.
            // Separates a real microphone signal from a playback loopback:
            // loopback -> near zero with the tone off; mic -> high while speaking.
            promptSpeak(owner);
            record(context, "MIC_MUTEX_STAGE",
                    readback(am, 4, "stage_start").put("elapsedMs", 16000).put("tone", "off"));
            toneGate(false);
            runAlsaStage(context, 4, "/dev/snd/pcmC1D0c", RATE, 2, 6000, alsaAvailable, false);
            toneGate(true);
            answers.put(new JSONObject().put("stage", 4)
                    .put("label", "第 5 段/共 6 段：无提示音·对车机喊话（判麦克风还是回环）")
                    .put("userPerception", "voice_prompt_done"));

            // S5: recovery tone, 3s.
            record(context, "MIC_MUTEX_STAGE",
                    readback(am, 5, "stage_start").put("elapsedMs", 22000));
            sleep(3000);
            answers.put(answer(owner, 5, 6, "恢复段（录音全关）"));
        } finally {
            stop[0] = true;
            toneGateRef = null;
            toneTrackRef = null;
            writer.join(1500);
            if (holder[0] != null) {
                try { holder[0].stop(); } catch (Throwable ignored) { }
                holder[0].release();
            }
            am.setMode(AudioManager.MODE_NORMAL);
            am.setMicrophoneMute(false);
        }
        JSONObject report = new JSONObject().put("answers", answers).put("readbacks", readbacks)
                .put("alsaDevices", alsa).put("alsaNativeAvailable", alsaAvailable)
                .put("toneRate", RATE).put("toneFrequencyHz", 660.0)
                .put("captureConfig", "48000Hz stereo (matches pcm_config_mm_in from OEM HAL)")
                .put("evidenceScope", "per-segment immediate perception; raw ALSA capture at HAL capture config");
        MainActivity.record(context, "MIC_MUTEX_DIAG", report);
        String submitted = owner.uploadNow();
        return "麦克风互斥自检完成，五段听感已记录。\n" + submitted;
    }

    /** Records with the given source for millis, sampling peak + readbacks every 500ms. */
    private static void runRecordStage(MainActivity owner, Context context, AudioManager am,
            int source, int stage, int millis, JSONArray readbacks, boolean alsaAvailable) {
        AudioRecord recorder = null;
        try {
            int rmin = AudioRecord.getMinBufferSize(MIC_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            try {
                recorder = new AudioRecord(source, MIC_RATE,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(rmin * 2, 4096));
            } catch (Throwable ignored) { }
            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                if (recorder != null) recorder.release();
                record(context, "MIC_MUTEX_RECORDER", new JSONObject()
                        .put("stage", stage).put("source", source).put("status", "init_failed"));
                sleep(millis);
                return;
            }
            String path = CarMicrophone.start(recorder, context);
            record(context, "MIC_MUTEX_RECORDER", new JSONObject()
                    .put("stage", stage).put("source", source).put("path", path)
                    .put("recordingState", recorder.getRecordingState()));
            long start = SystemClock.elapsedRealtime();
            long peak = 0, nextReadback = 500;
            boolean dumped = false;
            while (SystemClock.elapsedRealtime() - start < millis) {
                byte[] buf = new byte[2048];
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0) {
                    for (int i = 0; i + 1 < n; i += 2) {
                        int a = Math.abs((short) ((buf[i] & 255) | (buf[i + 1] << 8)));
                        if (a > peak) peak = a;
                    }
                } else {
                    Thread.sleep(5);
                }
                long t = SystemClock.elapsedRealtime() - start;
                if (!dumped && alsaAvailable && t >= 2000) {
                    dumped = true;
                    record(context, "MIC_MUTEX_CTLDUMP", new JSONObject()
                            .put("phase", "recording_S" + stage).put("card", 0)
                            .put("result", dumpControls(0)));
                }
                if (t >= nextReadback) {
                    nextReadback = t + 500;
                    JSONObject rb = readback(am, stage, "periodic");
                    rb.put("elapsedMs", 4000 + t).put("recorderActive", true).put("micPeak", peak);
                    peak = 0;
                    readbacks.put(rb);
                }
            }
            try { recorder.stop(); } catch (Throwable ignored) { }
            recorder.release();
            recorder = null;
        } catch (Exception error) {
            try { record(context, "MIC_MUTEX_RECORDER", new JSONObject()
                    .put("stage", stage).put("source", source)
                    .put("status", "error:" + error.getClass().getSimpleName())); }
            catch (Exception ignored) { }
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (Throwable ignored) { }
                recorder.release();
            }
        }
    }

    /** Raw ALSA capture for one stage; blocks in native while the tone keeps playing. */
    private static String runAlsaStage(Context context, int stage, String device,
            int rate, int channels, int millis, boolean alsaAvailable, boolean toneOn) {
        String result;
        if (!alsaAvailable) {
            result = "fail reason=native_unavailable";
        } else {
            try {
                result = AlsaProbe.captureTest(device, rate, channels, millis);
            } catch (Throwable error) {
                result = "fail reason=" + error.getClass().getSimpleName()
                        + " detail=" + error.getMessage();
            }
        }
        try {
            record(context, "MIC_MUTEX_ALSA", new JSONObject()
                    .put("stage", stage).put("device", device)
                    .put("rate", rate).put("channels", channels)
                    .put("millis", millis).put("toneOn", toneOn).put("result", result));
        } catch (Exception ignored) { }
        return result;
    }

    /** Blocking instruction dialog before the tone-off voice segment. */
    private static void promptSpeak(final MainActivity owner) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        owner.runOnUiThread(new Runnable() {
            public void run() {
                if (owner.closed) { done.countDown(); return; }
                new AlertDialog.Builder(owner).setTitle("下一段：请喊话")
                    .setMessage("接下来约 6 秒没有提示音。请点开始后，对着车机中控台方向"
                        + "连续大声说「喂喂喂，测试测试」，直到这段结束。")
                    .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { done.countDown(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { done.countDown(); }
                    }).show();
            }
        });
        done.await(60, TimeUnit.SECONDS);
    }

    /** Immediate per-segment question. The tone is fully stopped first so the
        question always refers to the segment that just ended, never to sound
        still playing while the dialog is up. */
    private static JSONObject answer(MainActivity owner, int index, int total, String what) throws Exception {
        String label = "第 " + (index + 1) + " 段/共 " + total + " 段：" + what;
        toneGate(false);
        String perception = askPerception(owner, label);
        toneGate(true);
        return new JSONObject().put("stage", index).put("label", label).put("userPerception", perception);
    }

    private static volatile boolean[] toneGateRef;
    private static volatile AudioTrack[] toneTrackRef;

    private static void toneGate(boolean on) {
        boolean[] gate = toneGateRef;
        AudioTrack[] track = toneTrackRef;
        if (gate == null) return;
        if (!on) {
            gate[0] = false;
            sleep(120); /* let the writer thread notice before we flush */
            if (track != null && track[0] != null) {
                try { track[0].pause(); } catch (Throwable ignored) { }
                try { track[0].flush(); } catch (Throwable ignored) { }
            }
        } else {
            if (track != null && track[0] != null) {
                try { track[0].play(); } catch (Throwable ignored) { }
            }
            gate[0] = true;
        }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { }
    }

    private static String dumpControls(int card) {
        try { return AlsaProbe.dumpControls(card); }
        catch (Throwable error) {
            return "fail reason=" + error.getClass().getSimpleName() + " detail=" + error.getMessage();
        }
    }

    private static JSONArray alsaProbe() {
        JSONArray out = new JSONArray();
        try {
            File dir = new File("/dev/snd");
            File[] files = dir.listFiles();
            if (files == null) {
                out.put(new JSONObject().put("status", dir.exists() ? "unreadable" : "missing"));
                return out;
            }
            for (File file : files) {
                out.put(new JSONObject().put("name", file.getName())
                        .put("read", file.canRead()).put("write", file.canWrite()));
            }
            out.put(new JSONObject().put("name", "/proc/asound/cards")
                    .put("text", readText("/proc/asound/cards")));
            out.put(new JSONObject().put("name", "/proc/asound/pcm")
                    .put("text", readText("/proc/asound/pcm")));
            out.put(new JSONObject().put("name", "/proc/asound/devices")
                    .put("text", readText("/proc/asound/devices")));
        } catch (Throwable error) {
            try { out.put(new JSONObject().put("status", error.getClass().getSimpleName())); }
            catch (Exception ignored) { }
        }
        return out;
    }

    private static String readText(String path) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            try {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int n, total = 0;
                while ((n = in.read(chunk)) > 0 && total < 4096) { buf.write(chunk, 0, n); total += n; }
                return buf.toString("UTF-8");
            } finally { in.close(); }
        } catch (Throwable error) {
            return "unreadable:" + error.getClass().getSimpleName();
        }
    }

    private static JSONObject readback(AudioManager am, int stage, String kind) {
        JSONObject row = new JSONObject();
        try {
            row.put("kind", kind).put("stage", stage).put("mode", am.getMode())
                    .put("musicVolume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("micMuted", am.isMicrophoneMute()).put("musicActive", am.isMusicActive());
            putInt(row, "devicesForMusic", "getDevicesForStream",
                    new Class<?>[]{Integer.TYPE}, new Object[]{Integer.valueOf(AudioManager.STREAM_MUSIC)});
            putBool(row, "musicStreamActive", "isStreamActive",
                    new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{Integer.valueOf(3), Integer.valueOf(0)});
            putBool(row, "masterMute", "getMasterMute", new Class<?>[0], new Object[0]);
            putBool(row, "micMutedNative", "isMicrophoneMuted", new Class<?>[0], new Object[0]);
        } catch (Exception ignored) { }
        return row;
    }

    private static void putBool(JSONObject row, String key, String name, Class<?>[] types, Object[] args) {
        try {
            row.put(key, ((Boolean) method(name, types).invoke(null, args)).booleanValue());
        } catch (Throwable t) {
            try { row.put(key + "Error", t.getClass().getSimpleName()); } catch (Exception ignored) { }
        }
    }

    private static void putInt(JSONObject row, String key, String name, Class<?>[] types, Object[] args) {
        try {
            row.put(key, ((Number) method(name, types).invoke(null, args)).intValue());
        } catch (Throwable t) {
            try { row.put(key + "Error", t.getClass().getSimpleName()); } catch (Exception ignored) { }
        }
    }

    private static Method method(String name, Class<?>[] types) throws Exception {
        StringBuilder keyBuilder = new StringBuilder(name);
        for (Class<?> type : types) keyBuilder.append(':').append(type.getName());
        String key = keyBuilder.toString();
        synchronized (METHODS) {
            Method cached = METHODS.get(key);
            if (cached != null) return cached;
            Method method = Class.forName("android.media.AudioSystem").getDeclaredMethod(name, types);
            method.setAccessible(true);
            METHODS.put(key, method);
            return method;
        }
    }

    private static void record(Context context, String event, JSONObject row) {
        try { MainActivity.record(context, event, row); } catch (Exception ignored) { }
    }

    private static String askPerception(final MainActivity owner, final String label) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final String[] result = { "timeout" };
        owner.runOnUiThread(new Runnable() {
            public void run() {
                if (owner.closed) { done.countDown(); return; }
                new AlertDialog.Builder(owner).setTitle("刚刚这一段").setMessage(label + "\n实际听到了什么？")
                    .setPositiveButton("清晰提示音", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result[0] = "clear"; done.countDown(); }
                    })
                    .setNegativeButton("有声音，但是杂音", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result[0] = "noise"; done.countDown(); }
                    })
                    .setNeutralButton("完全无声", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { result[0] = "silence"; done.countDown(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { result[0] = "skip"; done.countDown(); }
                    }).show();
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) result[0] = "timeout";
        return result[0];
    }
}
