package local.headunit.diplay;

import android.content.Context;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable one-report outbox. A report remains here until a matching server receipt is verified. */
final class PendingReportStore {
    private static final String FILE_NAME = "pending-hybrid-report.json";
    // Compact text reports plus the longest supported test microphone attachment fit here.
    private static final int MAX_ENVELOPE_BYTES = 4 * 1024 * 1024;

    static final class InvalidReportException extends IOException {
        InvalidReportException(String message) { super(message); }
    }

    private PendingReportStore() { }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    static synchronized boolean exists(Context context) {
        File base = file(context);
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    static synchronized JSONObject read(Context context) throws Exception {
        File base = file(context);
        File backup = new File(base.getPath() + ".bak");
        if (!base.isFile() && !backup.isFile()) return null;
        File saved = backup.isFile() ? backup : base;
        if (saved.length() > MAX_ENVELOPE_BYTES) {
            throw new InvalidReportException("旧待提交报告过大，可从本地日志重新整理");
        }
        AtomicFile atomic = new AtomicFile(base);
        FileInputStream stream = atomic.openRead();
        byte[] bytes;
        try {
            // Measure the restored file on its open descriptor before allocating once.
            long length = stream.getChannel().size();
            if (length > MAX_ENVELOPE_BYTES) {
                throw new InvalidReportException("Saved report exceeds the local safety limit");
            }
            bytes = new byte[(int) length];
            DataInputStream input = new DataInputStream(stream);
            input.readFully(bytes);
            if (input.read() != -1) throw new InvalidReportException("Saved report grew during read");
        } catch (EOFException truncated) {
            throw new InvalidReportException("Saved report was truncated");
        } finally {
            stream.close();
        }
        final JSONObject pending;
        try {
            pending = new JSONObject(new String(bytes, "UTF-8"));
        } catch (JSONException invalidJson) {
            throw new InvalidReportException("Saved report is not valid JSON");
        }
        JSONObject body = pending.optJSONObject("body");
        JSONArray acknowledgedRows = pending.optJSONArray("acknowledgedRows");
        if (pending.optInt("schema", 0) != 1 || body == null || acknowledgedRows == null) {
            throw new InvalidReportException("Saved report is incomplete");
        }
        if (!body.optString("clientReportId", "").matches("[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}") || body.optJSONArray("events") == null) {
            throw new InvalidReportException("Saved report identity or event list is invalid");
        }
        for (int i = 0; i < acknowledgedRows.length(); i++) {
            JSONObject row = acknowledgedRows.optJSONObject(i);
            if (row == null || !(row.opt("id") instanceof String) || row.optString("id").isEmpty()) {
                throw new InvalidReportException("Saved report log reference is invalid");
            }
        }
        return pending;
    }

    static synchronized JSONObject save(Context context, JSONObject body, JSONArray acknowledgedRows) throws Exception {
        if (exists(context)) throw new IOException("已有一份待提交日志，请先重试");
        if (body == null || acknowledgedRows == null) throw new IOException("Cannot save an incomplete report");
        if (!body.optString("clientReportId", "").matches("[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}") || body.optJSONArray("events") == null) {
            throw new IOException("Report identity or event list is invalid; local logs were not cleared");
        }
        JSONObject pending = new JSONObject().put("schema", 1).put("body", body)
                .put("acknowledgedRows", acknowledgedRows);
        write(context, pending);
        return pending;
    }

    /** Persist a compatibility repair before transmitting it under the same report ID. */
    static synchronized void saveRepaired(Context context, JSONObject pending) throws Exception {
        if (!exists(context)) throw new IOException("待提交报告已不存在");
        write(context, pending);
    }

    private static void write(Context context, JSONObject pending) throws Exception {
        // The serialized envelope includes the entire body and has the stricter limit.
        byte[] bytes = pending.toString().getBytes("UTF-8");
        if (bytes.length > MAX_ENVELOPE_BYTES) {
            throw new IOException("Saved report envelope exceeds the local retry limit; local logs were not cleared");
        }
        AtomicFile atomic = new AtomicFile(file(context));
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (Exception error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    static synchronized void removeAcknowledged(Context context, JSONObject pending, String reportId) throws Exception {
        // REPORT_JOB_RUNNING excludes all other outbox edits during send and acknowledgment.
        if (!reportId.equals(pending.getJSONObject("body").optString("clientReportId", ""))) {
            throw new IOException("回执与待提交报告编号不一致");
        }
        delete(context);
    }

    /** Explicit confirmation in the UI already authorized discarding even an unreadable envelope. */
    static synchronized void discard(Context context) throws IOException {
        delete(context);
    }

    private static void delete(Context context) throws IOException {
        new AtomicFile(file(context)).delete();
        if (exists(context)) throw new IOException("无法移除待提交报告");
    }
}
