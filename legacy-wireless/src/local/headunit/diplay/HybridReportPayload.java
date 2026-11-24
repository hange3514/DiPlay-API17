package local.headunit.diplay;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Normalizes report values to the hybrid endpoint's accepted format. */
final class HybridReportPayload {
    private static final long MAX_OFFSET_MS = 86_400_000L;

    private HybridReportPayload() { }

    static long boundedOffset(long time, long first) {
        if (time <= first) return 0L;
        long difference = time - first;
        // When time is later than first, a negative difference means subtraction overflowed.
        return difference < 0L ? MAX_OFFSET_MS : Math.min(difference, MAX_OFFSET_MS);
    }

    static boolean repairOffsets(JSONObject body) throws JSONException {
        JSONArray events = body.optJSONArray("events");
        if (events == null) return false;
        boolean changed = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) continue;
            Object value = event.opt("offsetMs");
            if (!(value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte)) continue;
            long offset = ((Number) value).longValue();
            long repaired = boundedOffset(offset, 0L);
            if (offset != repaired) {
                event.put("offsetMs", repaired);
                changed = true;
            }
        }
        return changed;
    }

    static String issueText(String value) {
        if (value == null) return "";
        int first = 0;
        int end = value.length();
        while (first < end && issueWhitespace(value.charAt(first))) first++;
        while (end > first && issueWhitespace(value.charAt(end - 1))) end--;
        return value.substring(first, end);
    }

    private static boolean issueWhitespace(char ch) {
        // Match Python str.strip() even on older Android Unicode tables.
        return (ch >= 0x09 && ch <= 0x0d) || (ch >= 0x1c && ch <= 0x20)
                || ch == 0x85 || ch == 0xa0 || ch == 0x1680
                || (ch >= 0x2000 && ch <= 0x200a) || ch == 0x2028 || ch == 0x2029
                || ch == 0x202f || ch == 0x205f || ch == 0x3000;
    }
}
