package local.headunit.diplay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Collects the OEM audio HAL libraries and mixer configuration files and
 * uploads them as gzip+base64 fragments through the normal diagnostics
 * endpoint. Lets us reverse-engineer the exact mixer control names the HAL
 * writes when it opens a capture stream, without extra car trips. Reads only
 * world-readable system files; no personal data.
 */
public final class SystemAudioHarvest {
    private static final int CHUNK = 1536;          // raw bytes per event -> 2048 base64 chars
    private static final int BODY_LIMIT = 220000;   // stay below the 250000 client / 262144 server cap
    private static final long FILE_LIMIT = 1500000; // per-file read cap

    private SystemAudioHarvest() { }

    public static void offer(final MainActivity owner) {
        new AlertDialog.Builder(owner).setTitle("采集系统音频文件")
            .setMessage("把车机系统的音频驱动库（audio.primary 等 HAL 库）、声卡配置和启动脚本打包上传到分析服务器，"
                + "用来反汇编找出打开麦克风采集开关的准确方法。只读系统目录的公开文件，不含任何个人数据。"
                + "需要网络，约 1 分钟；停车后操作。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始采集", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    owner.task("sysfile_harvest", new MainActivity.Work() {
                        public String run() throws Exception { return runHarvest(owner); }
                    });
                }
            }).show();
    }

    private static final class Artifact {
        String name; byte[] data;
        Artifact(String n, byte[] d) { name = n; data = d; }
    }

    private static String runHarvest(MainActivity owner) throws Exception {
        Context context = owner.getApplicationContext();
        List<Artifact> artifacts = new ArrayList<Artifact>();

        // Small text artifacts.
        addText(artifacts, "/proc/asound/cards");
        addText(artifacts, "/proc/asound/pcm");
        addText(artifacts, "/proc/asound/devices");
        addText(artifacts, "/system/build.prop");
        addText(artifacts, "/system/etc/audio_policy.conf");
        addText(artifacts, "/system/etc/asound.state");
        addText(artifacts, "/var/lib/alsa/asound.state");
        addText(artifacts, "/data/misc/alsa/asound.state");
        File root = new File("/");
        File[] rootFiles = root.listFiles();
        if (rootFiles != null) {
            for (File f : rootFiles) {
                String n = f.getName();
                if (n.startsWith("init") && n.endsWith(".rc")) addText(artifacts, "/" + n);
            }
        }
        StringBuilder hwList = new StringBuilder();
        File hwDir = new File("/system/lib/hw");
        File[] hwFiles = hwDir.listFiles();
        List<File> halBins = new ArrayList<File>();
        if (hwFiles != null) {
            for (File f : hwFiles) {
                hwList.append(f.getName()).append(' ').append(f.length()).append('\n');
                if (f.getName().startsWith("audio") && f.getName().endsWith(".so")) halBins.add(f);
            }
        } else {
            hwList.append("unreadable\n");
        }
        artifacts.add(new Artifact("ls:/system/lib/hw", hwList.toString().getBytes("UTF-8")));
        artifacts.add(new Artifact("exec:tinymix", exec(new String[]{"/system/bin/tinymix"})));
        artifacts.add(new Artifact("exec:tinymix -D 1", exec(new String[]{"/system/bin/tinymix", "-D", "1"})));
        artifacts.add(new Artifact("exec:alsa_amixer", exec(new String[]{"/system/bin/alsa_amixer"})));

        // Binary artifacts: the audio HAL libraries themselves.
        for (File f : halBins) addBinary(artifacts, f.getAbsolutePath());
        addBinary(artifacts, "/system/bin/tinymix");

        // Fragment everything into events.
        JSONArray events = new JSONArray();
        long now = System.currentTimeMillis();
        JSONArray meta = new JSONArray();
        for (Artifact a : artifacts) {
            byte[] gz = gzip(a.data);
            int parts = Math.max(1, (gz.length + CHUNK - 1) / CHUNK);
            meta.put(new JSONObject().put("name", a.name).put("raw", a.data.length)
                    .put("gz", gz.length).put("parts", parts));
            for (int p = 0; p < parts; p++) {
                int off = p * CHUNK;
                int len = Math.min(CHUNK, gz.length - off);
                JSONObject head = new JSONObject().put("name", a.name).put("part", p)
                        .put("parts", parts).put("gzTotal", gz.length);
                String event = "SYSFILE_GZ " + head.toString() + " "
                        + Base64.encodeToString(gz, off, len, Base64.NO_WRAP);
                events.put(new JSONObject().put("time", now).put("event", event));
            }
        }

        // Upload in bounded bodies, reusing the diagnostics endpoint.
        String server = owner.asset("server.txt");
        String token = owner.asset("token.txt");
        String version = "";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        List<String> receipts = new ArrayList<String>();
        JSONArray pending = new JSONArray();
        int pendingBytes = 0, index = 0;
        while (index < events.length()) {
            JSONObject row = events.getJSONObject(index);
            int rowBytes = row.toString().getBytes("UTF-8").length + 2;
            if (pendingBytes + rowBytes > BODY_LIMIT && pending.length() > 0) {
                receipts.add(post(server, token, version, pending));
                pending = new JSONArray();
                pendingBytes = 0;
            }
            pending.put(row);
            pendingBytes += rowBytes;
            index++;
        }
        if (pending.length() > 0) receipts.add(post(server, token, version, pending));

        JSONObject done = new JSONObject().put("artifacts", meta).put("receipts", new JSONArray(receipts));
        MainActivity.record(context, "SYSFILE_DONE", done);
        return "系统音频文件采集完成，共 " + artifacts.size() + " 个文件、" + events.length()
                + " 个分片，回执：" + receipts.toString();
    }

    private static void addText(List<Artifact> out, String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) return;
            out.add(new Artifact(path, readAll(f)));
        } catch (Throwable ignored) { }
    }

    private static void addBinary(List<Artifact> out, String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) return;
            out.add(new Artifact(path, readAll(f)));
        } catch (Throwable ignored) { }
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n, total = 0;
            while ((n = in.read(chunk)) > 0 && total < FILE_LIMIT) {
                buf.write(chunk, 0, n);
                total += n;
            }
            return buf.toByteArray();
        } finally { in.close(); }
    }

    private static byte[] exec(String[] cmd) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(cmd);
            final InputStream in = process.getInputStream();
            final InputStream err = process.getErrorStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread a = pump(in, out), b = pump(err, out);
            a.join(4000);
            b.join(1000);
            try { process.exitValue(); } catch (IllegalThreadStateException e) { process.destroy(); }
            return out.toByteArray();
        } catch (Throwable error) {
            try { return ("unavailable:" + error.getClass().getSimpleName()).getBytes("UTF-8"); }
            catch (Exception ignored) { return new byte[0]; }
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) { }
        }
    }

    private static Thread pump(final InputStream in, final ByteArrayOutputStream out) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0 && out.size() < 262144) out.write(chunk, 0, n);
                } catch (Throwable ignored) { }
            }
        });
        t.start();
        return t;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(buf);
        gz.write(data);
        gz.close();
        return buf.toByteArray();
    }

    private static String post(String server, String token, String version, JSONArray events) throws Exception {
        JSONObject body = new JSONObject()
                .put("app_version", "DiPlay-" + version)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("model", android.os.Build.MODEL)
                .put("comment", "系统音频文件采集（HAL/mixer 逆向取证）")
                .put("events", events);
        byte[] data = body.toString().getBytes("UTF-8");
        if (data.length > 250000) throw new IOException("Body too large: " + data.length);
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/diagnostics").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setUseCaches(false);
        c.setRequestProperty("Connection", "close");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + token);
        NetworkDeadline deadline = new NetworkDeadline(c, 45000);
        try {
            OutputStream out = c.getOutputStream();
            out.write(data);
            out.close();
            if (c.getResponseCode() != 200) throw new IOException("Upload HTTP " + c.getResponseCode());
            String id = ReportIo.receipt(c.getInputStream(), events.length());
            deadline.check();
            return id;
        } finally { deadline.close(); }
    }
}
