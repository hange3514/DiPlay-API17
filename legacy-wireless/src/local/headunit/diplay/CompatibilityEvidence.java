package local.headunit.diplay;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Adds bounded observations to the report event envelope understood by both upload routes. */
final class CompatibilityEvidence {
    private static final int SUMMARY_LIMIT = 640;
    private static final int TEXT_PART = 560;

    private CompatibilityEvidence() {}

    static JSONObject alsaEnvironment(Context context) throws JSONException {
        try {
            return AlsaCapturePathSelector.environment(context);
        } catch (Exception | LinkageError unavailable) {
            return new JSONObject().put("status", "unavailable");
        }
    }

    static void appendIdentity(JSONArray events, JSONObject identity) throws JSONException {
        append(events, "VEHICLE_IDENTITY", "vehicle_identity", fields(identity,
            "vehicleCode", "vehicleFamily", "vehicleSource", "firmwareDisplay", "firmwareIncremental",
            "oemMultimediaVersion", "oemCanServiceVersion", "oemSerialServiceVersion"));
    }

    static void appendAlsaInventory(JSONArray events, JSONObject environment) throws JSONException {
        append(events, "ALSA_ENVIRONMENT", "alsa_inventory", fields(environment,
            "status", "method", "nativeProbeLoaded", "profileKey", "rememberedPath", "rememberedRate",
            "rememberedChannels", "candidateCount", "candidateListOmittedCount", "candidateListTruncated"));
        appendText(events, "alsa_cards", environment.optString("cards"));
        appendText(events, "alsa_pcm", environment.optString("pcm"));
        JSONArray candidates = environment.optJSONArray("captureCandidates");
        if (candidates == null) return;
        for (int i = 0; i < Math.min(12, candidates.length()); i++) {
            JSONObject candidate = candidates.optJSONObject(i);
            if (candidate != null) {
                append(events, "ALSA_ENVIRONMENT", "alsa_candidate", fields(candidate,
                    "path", "source", "card", "device", "description", "exists", "read", "write"));
            }
        }
    }

    private static void appendText(JSONArray events, String stage, String text) throws JSONException {
        for (int first = 0; first < text.length(); first += TEXT_PART) {
            append(events, "ALSA_ENVIRONMENT", stage, "part=" + (first / TEXT_PART + 1) + " table=" +
                text.substring(first, Math.min(text.length(), first + TEXT_PART)));
        }
    }

    static void addRuntimeContext(String type, JSONObject detail, JSONObject safe) throws JSONException {
        if (detail == null) return;
        String summary;
        if (isWheel(type)) {
            summary = fields(detail, "knownProfileMatched", "receiverRegistered", "oemServiceVersion",
                "oemBroadcastAction", "oemNonKeyFramesSinceLastSample", "keyCandidate", "keyScanCode",
                "keyWindowFocused", "keyConsumedByDiPlay", "projectionReady", "connection");
        } else if ("AUDIO_ROUTING".equals(type)) {
            summary = fields(detail, "nativeNavigation", "callStream", "callFocusStream",
                "callDownlinkRoute", "callAndroidRoutePolicy");
        } else if ("CALL_AUDIO_ROUTE".equals(type)) {
            summary = fields(detail, "detail", "platformPhoneStrategy", "previousMode", "activeMode",
                "outputStream", "focusStream", "activeSpeakerphone", "activeVolume", "maxVolume", "volumeMaxAccepted");
        } else return;
        if (summary.length() > SUMMARY_LIMIT) summary = summary.substring(0, SUMMARY_LIMIT);
        if (!summary.isEmpty()) safe.put("diagnosticSummary", summary);
    }

    static boolean isWheel(String type) {
        return "WHEEL_KEY".equals(type) || "WHEEL_CONTROL".equals(type);
    }

    static boolean isAudio(String type) {
        return "ALSA_CAPTURE".equals(type) || "AUDIO_ROUTING".equals(type) || "CALL_AUDIO_ROUTE".equals(type)
            || "CALL_OUTPUT_DIAG".equals(type) || "AUDIO_SPEECH_PLAYOUT".equals(type)
            || "AUDIO_SPEECH_RECEIVE".equals(type) || "MICROPHONE_UPLINK".equals(type);
    }

    private static String fields(JSONObject source, String... keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            Object value = source.opt(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                String encoded = safeText(String.valueOf(value)).trim().replace(' ', '_');
                if (encoded.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(key).append('=').append(encoded);
            }
        }
        return out.toString();
    }

    private static String safeText(String text) {
        return text.replace('\r', ' ').replace('\n', ';').replace('\t', ' ')
            .replaceAll("[^A-Za-z0-9_.,:/;=x\\[\\]()+# -]", "_");
    }

    private static void append(JSONArray events, String type, String stage, String summary) throws JSONException {
        JSONObject last = events.optJSONObject(events.length() - 1);
        long offset = last == null ? 0 : Math.min(86400000L, Math.max(0L, last.optLong("offsetMs")));
        String safe = safeText(summary);
        if (safe.length() > SUMMARY_LIMIT) safe = safe.substring(0, SUMMARY_LIMIT);
        events.put(new JSONObject().put("type", type).put("offsetMs", offset)
            .put("data", new JSONObject().put("stage", stage).put("readOnly", true)
                .put("diagnosticSummary", safe)));
    }
}
