package local.headunit.diplay;

import android.media.AudioManager;
import android.content.Context;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only API17 audio-policy evidence. Never changes routes or uploads binaries. */
public final class AudioSystemEvidence {
    private static final String[] POLICY_FILES = {
        "/system/etc/audio_policy.conf",
        "/system/etc/audio_policy_configuration.xml",
        "/vendor/etc/audio_policy.conf",
        "/vendor/etc/audio_policy_configuration.xml",
        "/system/vendor/etc/audio_policy.conf",
        "/system/vendor/etc/audio_policy_configuration.xml"
    };
    private static final String[] HAL_DIRS = {
        "/system/lib/hw", "/system/vendor/lib/hw", "/vendor/lib/hw"
    };
    private static final String[] PROPERTY_KEYS = {
        "ro.hardware", "ro.board.platform", "ro.product.board", "ro.product.device",
        "ro.build.fingerprint", "ro.audio.flinger_standbytime_ms", "ro.audio.output_rate",
        "ro.audio.samplerate", "ro.audio.channels", "ro.audio.hal", "persist.audio.hal"
    };
    private static final int POLICY_TEXT_BUDGET = 12288;
    private static final int HAL_HASH_BUDGET = 16 * 1024 * 1024;
    private static final Map<String, Method> METHOD_CACHE = new HashMap<String, Method>();

    private AudioSystemEvidence() { }

    public static JSONObject environment() {
        JSONObject out = new JSONObject();
        try {
            out.put("schema", 1)
                .put("scope", "read_only_framework_audio_policy_and_hal_metadata_no_binary_upload")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("routing", routingSnapshot(null, null))
                .put("properties", properties())
                .put("policyFiles", policyFiles())
                .put("audioHalFiles", halFiles());
        } catch (Throwable error) {
            try { out.put("status", "partial").put("error", error.getClass().getSimpleName()); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    /** Snapshot resolved output device masks and current force-use values via read-only AOSP APIs. */
    public static JSONObject routingSnapshot(AudioManager manager, Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("scope", "AudioSystem_readback_policy_result_not_acoustic_loopback");
            JSONArray streams = new JSONArray();
            addStream(streams, "voice_call", AudioManager.STREAM_VOICE_CALL);
            addStream(streams, "music", AudioManager.STREAM_MUSIC);
            addStream(streams, "notification", AudioManager.STREAM_NOTIFICATION);
            if (context != null) {
                try {
                    int navigation = AudioRouting.secondMusic(context);
                    if (navigation >= 0) addStream(streams, "byd_navigation", navigation);
                } catch (Throwable ignored) { }
            }
            out.put("streamOutputDevices", streams);
            JSONArray forceUses = new JSONArray();
            addForceUse(forceUses, "communication", 0);
            addForceUse(forceUses, "media", 1);
            addForceUse(forceUses, "record", 2);
            out.put("forceUse", forceUses);
            addNativeInt(out, "primaryOutputSamplingRate", "getPrimaryOutputSamplingRate");
            addNativeInt(out, "primaryOutputFrameCount", "getPrimaryOutputFrameCount");
            JSONArray sources = new JSONArray();
            addSource(sources, "mic", 1);
            addSource(sources, "voice_communication", 7);
            addSource(sources, "voice_call", 4);
            out.put("activeRecordingSources", sources);
            out.put("status", "measured_or_individually_unavailable");
        } catch (Throwable error) {
            try { out.put("status", "partial").put("error", error.getClass().getSimpleName()); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    private static void addStream(JSONArray rows, String name, int stream) throws org.json.JSONException {
        JSONObject row = new JSONObject().put("name", name).put("stream", stream);
        try {
            int mask = invokeInt("getDevicesForStream", new Class<?>[]{Integer.TYPE}, new Object[]{Integer.valueOf(stream)});
            row.put("deviceMask", mask).put("deviceMaskHex", hex(mask)).put("devices", deviceNames(mask))
                .put("status", "measured");
        } catch (Throwable error) {
            row.put("status", "unavailable").put("error", error.getClass().getSimpleName());
        }
        rows.put(row);
    }

    private static void addForceUse(JSONArray rows, String usage, int id) throws org.json.JSONException {
        JSONObject row = new JSONObject().put("usage", usage).put("id", id);
        try {
            int value = invokeInt("getForceUse", new Class<?>[]{Integer.TYPE}, new Object[]{Integer.valueOf(id)});
            row.put("value", value).put("name", forceUseName(value)).put("status", "measured");
        } catch (Throwable error) {
            row.put("status", "unavailable").put("error", error.getClass().getSimpleName());
        }
        rows.put(row);
    }

    private static void addSource(JSONArray rows, String name, int id) throws org.json.JSONException {
        JSONObject row = new JSONObject().put("source", name).put("id", id);
        try {
            row.put("active", invokeBoolean("isSourceActive", id)).put("status", "measured");
        } catch (Throwable error) {
            row.put("status", "unavailable").put("error", error.getClass().getSimpleName());
        }
        rows.put(row);
    }

    private static void addNativeInt(JSONObject out, String key, String method) {
        try { out.put(key, invokeInt(method, new Class<?>[0], new Object[0])); }
        catch (Throwable error) {
            try { out.put(key + "Status", "unavailable").put(key + "Error", error.getClass().getSimpleName()); }
            catch (Throwable ignored) { }
        }
    }

    private static int invokeInt(String name, Class<?>[] types, Object[] args) throws Exception {
        return ((Number) cachedMethod("android.media.AudioSystem", name, types).invoke(null, args)).intValue();
    }

    private static boolean invokeBoolean(String name, int arg) throws Exception {
        return ((Boolean) cachedMethod("android.media.AudioSystem", name, new Class<?>[]{Integer.TYPE})
                .invoke(null, Integer.valueOf(arg))).booleanValue();
    }

    private static Method cachedMethod(String className, String name, Class<?>[] types) throws Exception {
        StringBuilder keyBuilder = new StringBuilder(className).append('#').append(name);
        for (Class<?> type : types) keyBuilder.append(':').append(type.getName());
        String key = keyBuilder.toString();
        synchronized (METHOD_CACHE) {
            Method cached = METHOD_CACHE.get(key);
            if (cached != null) return cached;
            Method method = Class.forName(className).getDeclaredMethod(name, types);
            method.setAccessible(true);
            METHOD_CACHE.put(key, method);
            return method;
        }
    }

    private static JSONObject properties() {
        JSONObject out = new JSONObject();
        try {
            Method get = cachedMethod("android.os.SystemProperties", "get", new Class<?>[]{String.class});
            for (String key : PROPERTY_KEYS) {
                try {
                    Object value = get.invoke(null, key);
                    if (value != null && String.valueOf(value).length() > 0) out.put(key, String.valueOf(value));
                } catch (Throwable error) {
                    out.put(key + "Status", error.getClass().getSimpleName());
                }
            }
        } catch (Throwable error) {
            try { out.put("status", "unavailable").put("error", error.getClass().getSimpleName()); }
            catch (Throwable ignored) { }
        }
        return out;
    }

    private static JSONArray policyFiles() throws org.json.JSONException {
        JSONArray rows = new JSONArray();
        int remaining = POLICY_TEXT_BUDGET;
        for (String path : POLICY_FILES) {
            File file = new File(path);
            if (!file.exists()) continue;
            JSONObject row = new JSONObject().put("path", path).put("size", file.length()).put("readable", file.canRead());
            try {
                byte[] bytes = readBounded(file, 512 * 1024);
                row.put("sha256", sha256(bytes));
                String text = new String(bytes, "UTF-8");
                String excerpt = relevantExcerpt(text, Math.min(remaining, 4096));
                if (excerpt.length() > 0) {
                    row.put("configurationExcerpt", excerpt);
                    remaining -= excerpt.getBytes("UTF-8").length;
                }
                row.put("excerptTruncated", file.length() > bytes.length || excerpt.length() < text.length());
                row.put("status", "readable_text_metadata_only");
            } catch (Throwable error) {
                row.put("status", "unavailable").put("error", error.getClass().getSimpleName());
            }
            rows.put(row);
        }
        return rows;
    }

    private static String relevantExcerpt(String text, int maxBytes) throws Exception {
        if (maxBytes <= 0) return "";
        byte[] all = text.getBytes("UTF-8");
        if (all.length <= maxBytes) return text;
        BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(all), "UTF-8"));
        StringBuilder out = new StringBuilder();
        String line;
        int scanned = 0;
        while ((line = reader.readLine()) != null && scanned++ < 4096 && out.length() < maxBytes) {
            String lower = line.toLowerCase(Locale.US);
            if (!(lower.contains("audio_hw_modules") || lower.contains("primary") || lower.contains("output")
                    || lower.contains("input") || lower.contains("speaker") || lower.contains("voice")
                    || lower.contains("phone") || lower.contains("routing") || lower.contains("device")
                    || lower.contains("sampling") || lower.contains("format") || lower.contains("channel")
                    || lower.contains("mixer") || lower.contains("microphone") || lower.contains("call")
                    || lower.contains("flags") || lower.contains("rate"))) continue;
            String candidate = line.length() > 240 ? line.substring(0, 240) : line;
            int bytes = candidate.getBytes("UTF-8").length + 1;
            if (out.toString().getBytes("UTF-8").length + bytes > maxBytes) break;
            out.append(candidate).append('\n');
        }
        return out.toString();
    }

    private static JSONArray halFiles() throws org.json.JSONException {
        JSONArray rows = new JSONArray();
        int remaining = HAL_HASH_BUDGET;
        int seen = 0;
        for (String path : HAL_DIRS) {
            File dir = new File(path);
            File[] files = dir.listFiles();
            if (files == null) {
                if (dir.exists()) rows.put(new JSONObject().put("directory", path).put("status", "unreadable_or_denied"));
                continue;
            }
            for (File file : files) {
                String name = file.getName();
                if (file.isDirectory() || !name.startsWith("audio.") || !name.endsWith(".so")) continue;
                if (seen++ >= 32) break;
                JSONObject row = new JSONObject().put("path", file.getAbsolutePath()).put("size", file.length()).put("readable", file.canRead());
                try {
                    if (file.length() > remaining || file.length() > 2 * 1024 * 1024) {
                        row.put("hashStatus", "skipped_size_budget");
                    } else {
                        byte[] bytes = readBounded(file, 2 * 1024 * 1024);
                        remaining -= bytes.length;
                        row.put("sha256", sha256(bytes)).put("hashStatus", "measured");
                        if (bytes.length >= 5 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F')
                            row.put("elfClass", bytes[4] == 1 ? 32 : bytes[4] == 2 ? 64 : -1);
                    }
                    row.put("status", "metadata_only_no_binary_uploaded");
                } catch (Throwable error) {
                    row.put("status", "unavailable").put("error", error.getClass().getSimpleName());
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private static byte[] readBounded(File file, int limit) throws Exception {
        if (file.length() > limit) throw new java.io.IOException("file_over_limit");
        InputStream input = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.max(0, file.length()));
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new java.io.IOException("file_grew_over_limit");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally { input.close(); }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(String.format(Locale.US, "%02x", Integer.valueOf(value & 255)));
        java.util.Arrays.fill(digest, (byte) 0);
        return out.toString();
    }

    private static String hex(int value) { return String.format(Locale.US, "0x%08x", Integer.valueOf(value)); }

    static String deviceNames(int mask) {
        String[] names = {"earpiece", "speaker", "wired_headset", "wired_headphone", "bluetooth_sco",
            "bluetooth_sco_headset", "bluetooth_sco_carkit", "bluetooth_a2dp", "bluetooth_a2dp_headphones",
            "bluetooth_a2dp_speaker", "aux_digital", "analog_dock", "digital_dock", "usb_accessory",
            "usb_device", "remote_submix", "telephony_tx", "line", "hdmi_arc", "spdif", "fm", "aux_line", "speaker_safe"};
        StringBuilder out = new StringBuilder();
        int recognized = 0;
        for (int bit = 0; bit < names.length; bit++) {
            int flag = 1 << bit;
            recognized |= flag;
            if ((mask & flag) == 0) continue;
            if (out.length() > 0) out.append(',');
            out.append(names[bit]);
        }
        if (mask == 0) return "none_or_unknown";
        recognized |= 0x40000000;
        if ((mask & 0x40000000) != 0) {
            if (out.length() > 0) out.append(',');
            out.append("default");
        }
        int unknown = mask & ~recognized;
        if (unknown != 0) {
            if (out.length() > 0) out.append(',');
            out.append("unknown_").append(hex(unknown));
        }
        return out.length() == 0 ? "unknown_mask" : out.toString();
    }

    private static String forceUseName(int value) {
        switch (value) {
            case 0: return "none";
            case 1: return "speaker";
            case 2: return "headphones";
            case 3: return "bluetooth_sco";
            case 4: return "bluetooth_a2dp";
            case 5: return "wired_accessory";
            case 6: return "bluetooth_car_dock";
            case 7: return "bluetooth_desk_dock";
            case 8: return "analog_dock";
            case 9: return "digital_dock";
            case 10: return "no_bluetooth_a2dp";
            default: return "unknown_" + value;
        }
    }
}
