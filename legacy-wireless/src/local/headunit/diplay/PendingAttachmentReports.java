package local.headunit.diplay;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Ordered report IDs per attachment kind, including the old single-ID preference. */
final class PendingAttachmentReports {
    private static final Pattern REPORT_ID = Pattern.compile("[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}");

    private PendingAttachmentReports() { }

    static boolean isReportId(String value) {
        return REPORT_ID.matcher(value).matches();
    }

    static List<String> read(String saved) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (String id : saved.split("\n")) {
            if (isReportId(id)) ids.add(id);
        }
        return new ArrayList<String>(ids);
    }

    static String append(String saved, String reportId) {
        if (!isReportId(reportId)) throw new IllegalArgumentException("Invalid report ID");
        List<String> ids = read(saved);
        if (!ids.contains(reportId)) ids.add(reportId);
        return encode(ids);
    }

    static String complete(String saved, String reportId) {
        List<String> ids = read(saved);
        ids.remove(reportId);
        return encode(ids);
    }

    private static String encode(List<String> ids) {
        StringBuilder text = new StringBuilder();
        for (String id : ids) {
            if (text.length() != 0) text.append('\n');
            text.append(id);
        }
        return text.toString();
    }
}
