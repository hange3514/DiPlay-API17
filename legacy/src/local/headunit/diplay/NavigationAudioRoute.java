package local.headunit.diplay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.os.IBinder;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only navigation stream selection with a per-projection media fallback. */
final class NavigationAudioRoute {
    private static final int MEDIA = AudioManager.STREAM_MUSIC;
    private static final int UNKNOWN = -1;
    private static final String BYD_SECONDARY = "STREAM_MUSIC_SECOND";
    private static final String BYD_AUDIO_INTERFACE = "android.media.IMultimediaAudioService";
    private static final String[] NAVIGATION_FIELDS = {
        "STREAM_NAVIGATION", "STREAM_NAVI", "STREAM_NAVIGATION_GUIDANCE", BYD_SECONDARY
    };
    private static final String[] RESERVED_EXTENSION_FIELDS = {
        "STREAM_ACCESSIBILITY", "STREAM_ASSISTANT", "STREAM_CALL_ASSISTANT",
        "STREAM_REROUTING", "STREAM_PATCH"
    };

    private final Context context;
    private final int selected;
    private final boolean audited;
    private final String field;
    private final String decision;
    private final int candidate;
    private final int candidateVolume;
    private final int candidateMax;
    private final int mediaVolume;
    private final int mediaMax;
    private final int alias;
    private volatile int active;
    private int accepted = UNKNOWN;

    NavigationAudioRoute(Context context, int auditedStream, String field, int candidate,
            boolean reserved, int candidateVolume, int candidateMax, int mediaVolume, int mediaMax,
            int alias, boolean bydNavigationService) {
        this.context = context;
        this.field = field;
        this.candidate = candidate;
        this.candidateVolume = candidateVolume;
        this.candidateMax = candidateMax;
        this.mediaVolume = mediaVolume;
        this.mediaMax = mediaMax;
        this.alias = alias;
        audited = auditedStream > MEDIA;
        decision = audited ? "audited_oem" : candidateDecision(field, candidate, reserved,
                candidateVolume, candidateMax, alias, bydNavigationService);
        selected = audited ? auditedStream : ("detected_oem".equals(decision) ? candidate : MEDIA);
        active = selected;
    }

    static NavigationAudioRoute detect(Context context, int auditedStream) {
        NavigationAudioRoute route = probe(context, auditedStream);
        route.record("selected", UNKNOWN, null, null);
        return route;
    }

    static JSONObject inspect(Context context, int auditedStream) {
        return probe(context, auditedStream).evidence();
    }

    private static NavigationAudioRoute probe(Context context, int auditedStream) {
        AudioManager audio = null;
        try {
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } catch (RuntimeException unavailable) {
            // Keep the media route when vendor audio services cannot be queried.
        }
        int mediaVolume = volume(audio, MEDIA, false);
        int mediaMax = volume(audio, MEDIA, true);
        if (auditedStream > MEDIA) {
            return new NavigationAudioRoute(context, auditedStream, BYD_SECONDARY, auditedStream,
                    false, volume(audio, auditedStream, false), volume(audio, auditedStream, true),
                    mediaVolume, mediaMax, UNKNOWN, true);
        }

        NavigationAudioRoute rejected = null;
        for (String field : NAVIGATION_FIELDS) {
            int stream = declaredStream(field);
            if (stream == UNKNOWN) continue;
            boolean reserved = stream < 10 || isReservedExtension(stream);
            boolean bydNavigationService = BYD_SECONDARY.equals(field) && !reserved
                    && hasBydNavigationService(context);
            NavigationAudioRoute candidate = new NavigationAudioRoute(context, UNKNOWN, field,
                    stream, reserved, reserved ? UNKNOWN : volume(audio, stream, false),
                    reserved ? UNKNOWN : volume(audio, stream, true), mediaVolume, mediaMax,
                    reserved ? UNKNOWN : streamAlias(audio, stream), bydNavigationService);
            if (candidate.stream() != MEDIA) return candidate;
            if (rejected == null) rejected = candidate;
        }
        if (rejected != null) return rejected;
        return new NavigationAudioRoute(context, UNKNOWN, "none", UNKNOWN, false, UNKNOWN,
                UNKNOWN, mediaVolume, mediaMax, UNKNOWN, false);
    }

    private static String candidateDecision(String field, int stream, boolean reserved, int volume,
            int max, int alias, boolean bydNavigationService) {
        if (stream == UNKNOWN) return "no_named_stream";
        if (reserved || stream < 10) return "stream_id_conflict";
        if (max <= 0 || volume < 0 || volume > max) return "volume_unavailable";
        if (BYD_SECONDARY.equals(field)) {
            // These BYD builds apply independent navigation gain in the car amplifier.
            // Their Java volume stream can still share the media volume index.
            return bydNavigationService ? "detected_oem" : "secondary_service_unverified";
        }
        if (alias >= 0 && alias != stream) return "shared_volume_alias";
        return "detected_oem";
    }

    private static int declaredStream(String name) {
        try {
            Field field = AudioManager.class.getField(name);
            if (field.getType() == Integer.TYPE && Modifier.isStatic(field.getModifiers())) {
                return field.getInt(null);
            }
        } catch (Exception | LinkageError unavailable) {
            // The field is optional on vendor forks of Android.
        }
        return UNKNOWN;
    }

    private static boolean isReservedExtension(int stream) {
        for (String name : RESERVED_EXTENSION_FIELDS) {
            if (declaredStream(name) == stream) return true;
        }
        return false;
    }

    private static int volume(AudioManager audio, int stream, boolean max) {
        if (audio == null || stream < 0) return UNKNOWN;
        try {
            return max ? audio.getStreamMaxVolume(stream) : audio.getStreamVolume(stream);
        } catch (RuntimeException unavailable) {
            return UNKNOWN;
        }
    }

    private static int streamAlias(AudioManager audio, int stream) {
        if (audio == null || stream < 0) return UNKNOWN;
        try {
            return (Integer) AudioManager.class.getMethod("getStreamTypeAlias", Integer.TYPE)
                    .invoke(audio, stream);
        } catch (Exception | LinkageError unavailable) {
            // Stock API 17 does not expose the AudioService alias table to apps.
            return UNKNOWN;
        }
    }

    private static boolean hasBydNavigationService(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.byd.multimediaservice", 0);
            int system = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if (info.applicationInfo == null || (info.applicationInfo.flags & system) == 0) return false;
            Class<?> contract = Class.forName(BYD_AUDIO_INTERFACE);
            contract.getMethod("multimediaNAVIAudioStart");
            contract.getMethod("multimediaNAVIAudioStop");
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("checkService", String.class)
                    .invoke(null, "multimedia_audio");
            return binder instanceof IBinder && BYD_AUDIO_INTERFACE.equals(((IBinder) binder).getInterfaceDescriptor());
        } catch (Exception | LinkageError unavailable) {
            return false;
        }
    }

    int stream() {
        return active;
    }

    boolean usesAuditedStream() {
        return audited && active == selected;
    }

    synchronized boolean fallback(int failedStream, String phase, Throwable error) {
        if (failedStream == MEDIA || failedStream != selected) return false;
        if (active != MEDIA) {
            active = MEDIA;
            accepted = UNKNOWN;
            record("fallback", failedStream, phase, error);
        }
        return true;
    }

    synchronized void trackReady(int stream) {
        if (stream != active || stream == accepted) return;
        accepted = stream;
        record("pcm_accepted", stream, null, null);
    }

    JSONObject evidence() {
        JSONObject value = new JSONObject();
        try {
            value.put("decision", decision).put("candidateField", field)
                    .put("candidateStream", candidate).put("candidateVolume", candidateVolume)
                    .put("candidateMaxVolume", candidateMax).put("alias", alias)
                    .put("mediaVolume", mediaVolume).put("mediaMaxVolume", mediaMax)
                    .put("selectedStream", selected).put("stream", active);
        } catch (JSONException ignored) { }
        return value;
    }

    private void record(String stage, int attempted, String phase, Throwable error) {
        if (context == null) return;
        try {
            String summary = "field=" + field + " candidateStream=" + candidate + " candidateVolume="
                    + candidateVolume + " candidateMax=" + candidateMax + " alias=" + alias
                    + " mediaVolume=" + mediaVolume + " mediaMax=" + mediaMax + " selectedStream=" + selected;
            if (attempted >= 0) summary += " attemptedStream=" + attempted;
            JSONObject event = new JSONObject().put("stage", stage).put("decision", decision)
                    .put("stream", active).put("diagnosticSummary", summary);
            if (phase != null) event.put("phase", phase);
            if (error != null) event.put("errorType", error.getClass().getSimpleName());
            MainActivity.record(context, "NAVIGATION_AUDIO_ROUTE", event);
        } catch (Exception ignored) { }
    }
}
