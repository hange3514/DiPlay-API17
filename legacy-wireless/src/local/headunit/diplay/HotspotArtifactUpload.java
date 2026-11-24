package local.headunit.diplay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Streams selected installed system binaries after the report has been acknowledged. */
final class HotspotArtifactUpload {
    private static final String API = "http://next.hzy.cloud:20001/diplay/v1/hybrid/hotspot-artifact/";
    private static final String TRAFFIC_PACKAGE = "com.byd.trafficstats";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String CARLIFE_PACKAGE = "com.baidu.carlifevehicle";
    private static final int MIB = 1024 * 1024;
    private static final int MAX_RECEIPT_BYTES = 4096;
    private static final long MAX_JOB_MS = 90_000L;
    private static final Pattern REPORT_ID = Pattern.compile("[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}");
    private static final int HOTSPOT_FILE_COUNT = 5;
    private static final int CARLIFE_FILE_COUNT = 2;

    interface Progress {
        void onArtifact(String kind, int index, int total);
    }

    private HotspotArtifactUpload() {}

    static boolean hasCarLife(Context context) {
        if (Build.VERSION.SDK_INT != 17) return false;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(CARLIFE_PACKAGE, 0);
            return isSystemPackage(info.applicationInfo);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    static JSONObject carLifeMetadata(Context context) {
        JSONObject result = new JSONObject();
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(CARLIFE_PACKAGE, 0);
            ApplicationInfo app = info.applicationInfo;
            if (!isSystemPackage(app)) {
                put(result, "status", "not_system_package");
                return result;
            }
            put(result, "status", "installed");
            put(result, "versionCode", info.versionCode);
            if (info.versionName != null && info.versionName.matches("[A-Za-z0-9._+-]{1,80}")) {
                put(result, "versionName", info.versionName);
            }
            if (info.sharedUserId != null && info.sharedUserId.matches("[A-Za-z0-9_.]{1,100}")) {
                put(result, "sharedUserId", info.sharedUserId);
            }
            put(result, "targetSdk", app.targetSdkVersion);
            put(result, "updatedSystemPackage", (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
            put(result, "apkReadable", new File(app.sourceDir).canRead());
            put(result, "nativeLibraryScope", nativeLibraryScope(app.nativeLibraryDir));
            int platformSignature = manager.checkSignatures("android", CARLIFE_PACKAGE);
            if (platformSignature != PackageManager.SIGNATURE_UNKNOWN_PACKAGE) {
                put(result, "platformSignatureMatch", platformSignature == PackageManager.SIGNATURE_MATCH);
            }
            putPermission(manager, result, "recordAudioGranted", "android.permission.RECORD_AUDIO");
            putPermission(manager, result, "modifyAudioSettingsGranted", "android.permission.MODIFY_AUDIO_SETTINGS");
            putPermission(manager, result, "modifyPhoneStateGranted", "android.permission.MODIFY_PHONE_STATE");
            putPermission(manager, result, "captureAudioOutputGranted", "android.permission.CAPTURE_AUDIO_OUTPUT");
        } catch (PackageManager.NameNotFoundException unavailable) {
            put(result, "status", "not_installed");
        } catch (RuntimeException unavailable) {
            put(result, "status", "partial");
        }
        return result;
    }

    private static void putPermission(PackageManager manager, JSONObject result, String key, String permission) {
        put(result, key, manager.checkPermission(permission, CARLIFE_PACKAGE) == PackageManager.PERMISSION_GRANTED);
    }

    private static String nativeLibraryScope(String path) {
        if (path == null || path.isEmpty()) return "unavailable";
        File dir = new File(path);
        String source = dir.getPath();
        if (source.equals("/system/lib") || source.equals("/vendor/lib") || source.equals("/system/vendor/lib")) {
            return "shared_system";
        }
        // Report only the directory's classification. The APK supplies packaged ABIs and dependencies.
        for (File part = dir; part != null; part = part.getParentFile()) {
            String name = part.getName();
            if (name.equals(CARLIFE_PACKAGE) || name.startsWith(CARLIFE_PACKAGE + "-")) return "package_named";
        }
        return "other";
    }

    static JSONObject collect(Context context, String reportId) {
        return collect(context, reportId, null);
    }

    static JSONObject collect(Context context, String reportId, Progress progress) {
        return collect(context, reportId, progress, false);
    }

    static JSONObject collectCarLife(Context context, String reportId, Progress progress) {
        return collect(context, reportId, progress, true);
    }

    private static JSONObject collect(Context context, String reportId, Progress progress, boolean carLife) {
        JSONArray entries = new JSONArray();
        JSONObject result = new JSONObject();
        if (reportId == null || !REPORT_ID.matcher(reportId).matches()) {
            put(result, "status", "invalid_report_id");
            return result;
        }
        long jobEnd = SystemClock.elapsedRealtime() + MAX_JOB_MS;
        PackageManager packages = context.getPackageManager();
        byte[] buffer = new byte[32 * 1024];
        if (carLife) {
            collectPackage(packages, "carlife", CARLIFE_PACKAGE, 48 * MIB, 16 * MIB, entries,
                progress, CARLIFE_FILE_COUNT, reportId, jobEnd, buffer);
        } else {
            collectPackage(packages, "trafficstats", TRAFFIC_PACKAGE, 12 * MIB, 8 * MIB, entries,
                progress, HOTSPOT_FILE_COUNT, reportId, jobEnd, buffer);
            collectPackage(packages, "settings", SETTINGS_PACKAGE, 16 * MIB, 16 * MIB, entries,
                progress, HOTSPOT_FILE_COUNT, reportId, jobEnd, buffer);
            JSONObject framework = newEntry("framework-res-apk", "android", null);
            attach(entries, progress, HOTSPOT_FILE_COUNT, framework, new File("/system/framework/framework-res.apk"),
                32 * MIB, reportId, jobEnd, buffer);
        }

        int uploaded = 0;
        int reused = 0;
        int missing = 0;
        int failed = 0;
        for (int i = 0; i < entries.length(); i++) {
            String status = entries.optJSONObject(i).optString("status");
            if ("uploaded".equals(status)) uploaded++;
            else if ("reused".equals(status)) reused++;
            else if ("not_installed".equals(status) || "missing".equals(status)) missing++;
            else failed++;
        }
        // A missing ODEX is normal for a self-contained APK. A missing APK still needs attention.
        boolean missingCarLifeApk = carLife && missing > 0 &&
            !"uploaded".equals(entries.optJSONObject(0).optString("status")) &&
            !"reused".equals(entries.optJSONObject(0).optString("status"));
        put(result, "status", failed == 0 && !missingCarLifeApk ? "complete" : "partial");
        put(result, "artifacts", entries);
        put(result, "uploaded", uploaded);
        put(result, "reused", reused);
        put(result, "missing", missing);
        put(result, "failed", failed);
        return result;
    }

    private static void collectPackage(PackageManager manager, String fileKind, String packageName,
            int apkLimit, int odexLimit, JSONArray entries, Progress progress, int total,
            String reportId, long jobEnd, byte[] buffer) {
        PackageInfo info = null;
        File apk = null;
        String status = "not_installed";
        try {
            info = manager.getPackageInfo(packageName, 0);
            ApplicationInfo app = info.applicationInfo;
            if (isSystemPackage(app)) {
                File installed = new File(app.sourceDir).getCanonicalFile();
                if (installed.getName().endsWith(".apk")) apk = installed;
                else status = "unsupported_source";
            } else status = "unsupported_source";
        } catch (PackageManager.NameNotFoundException missing) {
            // Both files are unavailable when the system package is absent.
        } catch (IOException | RuntimeException unreadable) {
            status = "source_unreadable";
        }
        JSONObject apkEntry = newEntry(fileKind + "-apk", packageName, info);
        JSONObject odexEntry = newEntry(fileKind + "-odex", packageName, info);
        if (apk == null) {
            put(apkEntry, "status", status);
            put(odexEntry, "status", status);
            entries.put(apkEntry).put(odexEntry);
            return;
        }
        attach(entries, progress, total, apkEntry, apk, apkLimit, reportId, jobEnd, buffer);
        String name = apk.getName();
        File odex = new File(apk.getParentFile(), name.substring(0, name.length() - 4) + ".odex");
        attach(entries, progress, total, odexEntry, odex, odexLimit, reportId, jobEnd, buffer);
    }

    private static boolean isSystemPackage(ApplicationInfo app) {
        return app != null && app.sourceDir != null &&
            (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    private static JSONObject newEntry(String kind, String packageName, PackageInfo info) {
        JSONObject entry = new JSONObject();
        put(entry, "kind", kind);
        put(entry, "package", packageName);
        if (info != null) {
            put(entry, "versionCode", info.versionCode);
            if (info.versionName != null && info.versionName.matches("[A-Za-z0-9._+-]{1,80}")) {
                put(entry, "versionName", info.versionName);
            }
        }
        return entry;
    }

    private static void attach(JSONArray entries, Progress progress, int total, JSONObject entry,
            File file, int limit, String reportId, long jobEnd, byte[] buffer) {
        entries.put(entry);
        try {
            if (!file.isFile()) {
                put(entry, "status", "missing");
                return;
            }
            long length = file.length();
            put(entry, "bytes", length);
            if (length <= 0L || length > limit) {
                put(entry, "status", "size_out_of_bounds");
                return;
            }
            if (!file.canRead()) {
                put(entry, "status", "source_unreadable");
                return;
            }
            checkDeadline(jobEnd);
            String kind = entry.optString("kind");
            if (progress != null) progress.onArtifact(kind, entries.length(), total);
            String digest = sha256(file, length, jobEnd, buffer);
            put(entry, "sha256", digest);
            URL url = new URL(API + kind);
            Receipt result = post(url, null, (int) length, kind, reportId, digest, jobEnd, buffer);
            String status = "reused";
            if (result.status == HttpURLConnection.HTTP_NOT_FOUND) {
                result = post(url, file, (int) length, kind, reportId, digest, jobEnd, buffer);
                status = "uploaded";
            }
            put(entry, "httpStatus", result.status);
            put(entry, "status", result.confirmed ? status :
                (result.status == HttpURLConnection.HTTP_OK ? "receipt_mismatch" : "upload_rejected"));
        } catch (SocketTimeoutException timedOut) {
            put(entry, "status", "timed_out");
        } catch (IOException | IllegalArgumentException | SecurityException failed) {
            put(entry, "status", "upload_failed");
            put(entry, "errorType", failed.getClass().getSimpleName());
        }
    }

    private static String sha256(File file, long expectedBytes, long jobEnd, byte[] buffer)
            throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
        long read = 0;
        FileInputStream input = new FileInputStream(file);
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkDeadline(jobEnd);
                read += count;
                if (read > expectedBytes) throw new IOException("Artifact size increased while hashing");
                digest.update(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        if (read != expectedBytes) throw new IOException("Artifact size changed while hashing");
        char[] hexDigits = "0123456789abcdef".toCharArray();
        byte[] bytes = digest.digest();
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            hex[2 * i] = hexDigits[(bytes[i] & 0xff) >>> 4];
            hex[2 * i + 1] = hexDigits[bytes[i] & 0x0f];
        }
        return new String(hex);
    }

    private static Receipt post(URL url, File file, int artifactBytes, String kind, String reportId,
            String digest, long jobEnd, byte[] buffer) throws IOException {
        long remaining = checkDeadline(jobEnd);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout((int) Math.min(4000L, remaining));
        connection.setReadTimeout((int) Math.min(12_000L, remaining));
        connection.setRequestMethod("POST");
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setRequestProperty("X-DiPlay-Report-ID", reportId);
        connection.setRequestProperty("X-Artifact-SHA256", digest);
        connection.setFixedLengthStreamingMode(file == null ? 0 : artifactBytes);
        NetworkDeadline deadline = new NetworkDeadline(connection, Math.min(remaining,
            file == null ? 8000L : 45_000L));
        try {
            try {
                OutputStream output = connection.getOutputStream();
                try {
                    if (file != null) writeFile(file, artifactBytes, buffer, output, deadline);
                } finally {
                    output.close();
                }
                deadline.check();
                int status = connection.getResponseCode();
                deadline.check();
                if (status != HttpURLConnection.HTTP_OK) return new Receipt(status, false);
                InputStream response = connection.getInputStream();
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    int count;
                    while ((count = response.read(buffer, 0, Math.min(buffer.length, MAX_RECEIPT_BYTES))) != -1) {
                        deadline.check();
                        if (out.size() + count > MAX_RECEIPT_BYTES) return new Receipt(status, false);
                        out.write(buffer, 0, count);
                    }
                    return new Receipt(status, confirmed(out.toString("UTF-8"), kind,
                        reportId, digest, artifactBytes));
                } finally {
                    response.close();
                }
            } catch (IOException error) {
                deadline.check();
                throw error;
            }
        } finally {
            deadline.close();
        }
    }

    private static void writeFile(File file, int expected, byte[] buffer, OutputStream output,
            NetworkDeadline deadline) throws IOException {
        int sent = 0;
        FileInputStream source = new FileInputStream(file);
        try {
            int count;
            while ((count = source.read(buffer)) != -1) {
                deadline.check();
                if (count > expected - sent) throw new IOException("Artifact grew before upload");
                output.write(buffer, 0, count);
                sent += count;
            }
        } finally {
            source.close();
        }
        if (sent != expected) throw new IOException("Artifact size changed before upload");
    }

    private static boolean confirmed(String text, String kind, String reportId, String digest, int bytes) {
        try {
            JSONObject receipt = new JSONObject(text);
            return kind.equals(receipt.optString("kind")) && reportId.equals(receipt.optString("reportId")) &&
                digest.equals(receipt.optString("sha256")) && bytes == receipt.optLong("bytes", -1L);
        } catch (JSONException malformed) {
            return false;
        }
    }

    private static long checkDeadline(long end) throws SocketTimeoutException {
        long remaining = end - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new SocketTimeoutException("Hotspot evidence deadline exceeded");
        return remaining;
    }

    private static void put(JSONObject entry, String key, Object value) {
        try { entry.put(key, value); } catch (JSONException ignored) { }
    }

    private static final class Receipt {
        final int status;
        final boolean confirmed;
        Receipt(int status, boolean confirmed) {
            this.status = status;
            this.confirmed = confirmed;
        }
    }
}
