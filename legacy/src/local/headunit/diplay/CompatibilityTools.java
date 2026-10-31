package local.headunit.diplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in environment matching, compatibility reports, and historical APK downloads. */
final class CompatibilityTools {
    private static final String API = "http://next.hzy.cloud:20001/diplay/v1";
    private static final String PREFS = "diplay_compatibility";
    private static final String CONSENT_VERSION = "0.44.1-call-usb-key-evidence-beta";
    private static final int MAX_APK = 20 * 1024 * 1024;
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:/-]{1,48}");
    private static final Pattern SAFE_ALSA_PATH = Pattern.compile("/dev/snd/pcmC[0-9]{1,3}D[0-9]{1,3}c");
    private static final Pattern SAFE_ALSA_SUMMARY = Pattern.compile("[A-Za-z0-9_.,:/;=x\\[\\]()+# -]{1,1200}");
    private static final Set<String> NUMERIC_FIELDS = set(
        "elapsedMs", "waitMs", "durationMs", "sdk", "width", "height", "densityDpi",
        "refreshMilliHz", "attempt", "retryCount", "resultCode", "state", "mode",
        "stream", "volume", "maxVolume", "sampleRate", "channels", "bufferSize",
        "frames", "writtenBytes", "playbackHeadFrames", "packetCount", "timeoutCount",
        "usbDeviceCount", "usbInterfaceCount", "endpointCount", "frameCount", "fpsMilli",
        "candidateCount", "triedCount", "targetRate", "peak", "meanAbs", "errors", "capturedPackets",
        "requestedRate", "requestedChannels", "rateMin", "rateMax", "channelsMin", "channelsMax", "untriedCandidateCount", "omittedCandidateCount",
        "negotiatedRate", "negotiatedChannels", "errno", "card", "controlCount", "keyCode", "keyAction", "keyRepeatCount", "keySource", "keyFlags", "wheelAction", "mediaIndex",
        "deviceVid", "devicePid", "deviceClass", "deviceSubclass", "deviceProtocol", "usbInterfaceCount", "appleDevicesPresent", "usbSpeedMbps", "usbConfiguration", "oemFrameLength", "oemHeader0", "oemHeader1", "requestedFps", "usbStartupProfile", "vpnWaitMs", "beforeServiceMs", "totalMs", "usb_discovery", "usb_permission", "descriptors", "usb_reenumeration", "configuration_takeover", "usbmux_open", "ncm_open", "usbmux_handshake", "lockdown_pairing", "carkit_tls", "iap2_setup", "mfi_identity", "vpn_bridge", "airplay_listener", "iap2_auth_and_phone_start", "mfi_authentication", "phone_availability", "phone_projection_start", "first_video", "video_ready"
    );
    private static final Set<String> BOOLEAN_FIELDS = set(
        "connected", "videoReady", "surfaceValid", "granted", "success", "muted",
        "active", "musicActive", "phonePresent", "speakerphone", "bluetoothSco",
        "bluetoothA2dp", "wiredHeadset", "microphoneMuted", "nativeProbeLoaded", "candidateListTruncated",
        "readOnly", "snapshotTruncated", "keyHandled", "keyIsSiriCandidate", "devicePermission", "usbSysfsReadable"
    );
    private static final Set<String> ENUM_FIELDS = set("stage", "phase", "outcome", "status", "reason", "strategy", "eventType", "keyDecision", "keyPath", "usbTransition", "usbVersion", "diagnosticCode", "result");
    private static final Set<String> ALSA_PATH_FIELDS = set("devicePath", "selectedPath", "retainedPath", "fromPath", "toPath", "rememberedPath");
    private static final Set<String> ALSA_SUMMARY_FIELDS = set("candidateSummary", "attemptSummary", "alsaCardsSummary", "alsaPcmSummary", "mixerSnapshot", "usbInterfaceSummary", "diagnosticSummary");
    private static final Set<String> ALSA_TOKEN_FIELDS = set("profileKey", "source", "audioType", "result", "fallbackTarget", "stage", "phase");

    private static final class EventCandidate {
        final long time; final JSONObject event; final JSONObject acknowledged;
        EventCandidate(long time, JSONObject event, JSONObject acknowledged) { this.time = time; this.event = event; this.acknowledged = acknowledged; }
    }

    private static final class FilteredEvents {
        final JSONArray events = new JSONArray();
        final JSONArray acknowledgedRows = new JSONArray();
    }

    private CompatibilityTools() {}

    private static Set<String> set(String... values) {
        HashSet<String> result = new HashSet<String>();
        for (String value : values) result.add(value);
        return result;
    }

    static void open(final MainActivity activity) {
        final String[] options = new String[] {"车型匹配与版本历史", "提交问题说明和诊断", "上传范围与隐私说明"};
        new AlertDialog.Builder(activity).setTitle("车型兼容")
            .setItems(options, new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                if (which == 0) requireConsent(activity, new Runnable() { public void run() { showCatalog(activity); } });
                else if (which == 1) requireConsent(activity, new Runnable() { public void run() { askIssue(activity); } });
                else showPrivacy(activity);
            }}).setNegativeButton("关闭", null).show();
    }

    private static void requireConsent(final MainActivity activity, final Runnable accepted) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("consent", false) && CONSENT_VERSION.equals(prefs.getString("consent_version", ""))) { accepted.run(); return; }
        new AlertDialog.Builder(activity).setTitle("上传前请阅读")
            .setMessage(privacyText())
            .setPositiveButton("同意并继续", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("consent", true).putString("consent_version", CONSENT_VERSION).commit();
                accepted.run();
            }}).setNegativeButton("拒绝", null).show();
    }

    private static String privacyText() {
        return "\u53ea\u6709\u4f60\u4e3b\u52a8\u6253\u5f00\u8f66\u673a\u517c\u5bb9\u529f\u80fd\u5e76\u786e\u8ba4\u540e\uff0c\u5e94\u7528\u624d\u4f1a\u8054\u7f51\u3002\n\n\u62a5\u544a\u4f1a\u5305\u542b\u8f66\u673a\u578b\u53f7\u3001Android \u7248\u672c\u3001\u5c4f\u5e55\u89c4\u683c\u3001DiPlay \u7248\u672c\u3001\u7b5b\u9009\u540e\u7684\u8bca\u65ad\u4e8b\u4ef6\u3001ALSA \u58f0\u5361/PCM \u4e0e\u53ea\u8bfb mixer \u503c\u3001\u9ea6\u514b\u98ce\u5019\u9009\u3001\u9519\u8bef\u9636\u6bb5/errno\u3001USB \u4ea7\u54c1\u7c7b\u578b\u7f16\u53f7/\u63a5\u53e3\u7c7b\u522b\u53ca\u63d2\u62d4\u65f6\u5e8f\u3001\u65b9\u5411\u76d8\u952e\u503c/\u6309\u4e0b\u91ca\u653e\u53ca\u5904\u7406\u7ed3\u679c\u3001\u4f60\u9009\u62e9\u7684 iPhone \u8fde\u63a5\u548c\u901a\u8bdd\u73b0\u8c61\u3002\n\n\u4e0d\u4f1a\u4e0a\u4f20 VIN\u3001IMEI\u3001USB \u5e8f\u5217\u53f7\u3001UDID\u3001MAC\u3001\u8054\u7cfb\u4eba\u3001\u7535\u8bdd\u53f7\u7801\u3001\u901a\u8bdd\u5185\u5bb9\u3001\u5c4f\u5e55\u753b\u9762\u3001USB \u539f\u59cb\u4f20\u8f93\u6216\u539f\u59cb\u63cf\u8ff0\u7b26\u3001\u5e94\u7528\u5bc6\u94a5\u3001\u5b8c\u6574\u539f\u59cb\u65e5\u5fd7\u6216\u9ea6\u514b\u98ce\u5f55\u97f3\u3002\u95ee\u9898\u8bf4\u660e\u8bf7\u52ff\u586b\u5199\u59d3\u540d\u3001\u7535\u8bdd\u53f7\u7801\u7b49\u4e2a\u4eba\u4fe1\u606f\u3002\u670d\u52a1\u5668\u4fdd\u7559\u62a5\u544a 24 \u5c0f\u65f6\u540e\u81ea\u52a8\u6e05\u7406\u3002\n\n\u62d2\u7edd\u4e0a\u4f20\u4e0d\u4f1a\u5f71\u54cd\u672c\u5730\u6709\u7ebf CarPlay\u3002\u4e0a\u4f20\u524d\u4f1a\u518d\u6b21\u786e\u8ba4\u3002\u5f53\u524d\u516c\u7f51\u62a5\u544a\u63a5\u53e3\u4f7f\u7528\u672a\u52a0\u5bc6 HTTP\uff1b\u4ecb\u610f\u65f6\u8bf7\u62d2\u7edd\u4e0a\u4f20\u3002";
    }

    private static void showPrivacy(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("发送哪些数据").setMessage(privacyText())
            .setPositiveButton("知道了", null)
            .setNegativeButton("撤回上传同意", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("consent").remove("consent_version").commit();
                android.widget.Toast.makeText(activity, "已撤回。不会再查询版本或提交报告，除非你重新同意。", android.widget.Toast.LENGTH_LONG).show();
            }}).show();
    }

    private static JSONObject profile(Context context) throws Exception {
        JSONObject p = new JSONObject();
        p.put("sdk", Build.VERSION.SDK_INT).put("release", clean(Build.VERSION.RELEASE, 40))
            .put("manufacturer", clean(Build.MANUFACTURER, 80)).put("model", clean(Build.MODEL, 80))
            .put("device", clean(Build.DEVICE, 80)).put("product", clean(Build.PRODUCT, 80))
            .put("board", clean(Build.BOARD, 80)).put("hardware", clean(Build.HARDWARE, 80));
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        p.put("width", metrics.widthPixels).put("height", metrics.heightPixels).put("densityDpi", metrics.densityDpi);
        float refresh = 0f;
        try {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = manager == null ? null : manager.getDefaultDisplay();
            if (display != null) refresh = display.getRefreshRate();
        } catch (Throwable ignored) {}
        p.put("refreshMilliHz", Math.round(refresh * 1000f));
        return p;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == ' ') out.append(c);
        }
        return out.toString().trim();
    }

    private static void showCatalog(final MainActivity activity) {
        if (activity.busy || activity.closed) return;
        activity.busy = true;
        activity.showOperation("正在读取本机匹配的版本清单…");
        activity.worker.execute(new Runnable() { public void run() {
            try {
                int installedCode = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                JSONObject response = post("/catalog", new JSONObject().put("profile", profile(activity)).put("installedVersionCode", installedCode));
                final JSONArray versions = response.optJSONArray("versions");
                final String profileLabel = response.optJSONObject("profileSummary") == null ? "本机环境" : response.optJSONObject("profileSummary").optString("label", "本机环境");
                final String[] labels = new String[versions == null ? 0 : versions.length()];
                if (versions != null) for (int i = 0; i < versions.length(); i++) {
                    JSONObject item = versions.getJSONObject(i);
                    labels[i] = item.optString("trackLabel", "历史版本") + "  ·  " + item.optString("versionName") +
                        (item.optBoolean("requiresUninstall") ? "  ·  需先卸载" : "");
                }
                activity.runOnUiThread(new Runnable() { public void run() {
                    activity.busy = false;
                    if (activity.closed) return;
                    if (labels.length == 0) { activity.showOperation("服务器暂时没有适用于本机的版本。"); return; }
                    new AlertDialog.Builder(activity).setTitle("匹配：" + profileLabel)
                        .setItems(labels, new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) { confirmDownload(activity, versions, which); }})
                        .setNegativeButton("关闭", null).show();
                }});
            } catch (final Exception e) { finishError(activity, "读取版本清单失败", e); }
        }});
    }

    private static void confirmDownload(final MainActivity activity, JSONArray versions, int which) {
        try {
            final JSONObject item = versions.getJSONObject(which);
            String message = item.optString("notes", "") + "\n\nSHA-256：" + item.optString("sha256") +
                (item.optBoolean("requiresUninstall") ? "\n\n这是旧版本。请先在车机设置中卸载当前 DiPlay，再安装下载的 APK。下载文件保存在公共 Download/DiPlayVersions 目录，卸载应用不会删除该文件；卸载会清除应用内部状态。" : "\n\n下载后会调用 Android 安装器；系统仍会显示安装确认。");
            new AlertDialog.Builder(activity).setTitle(item.optString("versionName"))
                .setMessage(message).setPositiveButton("下载 APK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int w) { download(activity, item); }})
                .setNegativeButton("取消", null).show();
        } catch (Exception e) { activity.showOperation("版本信息读取失败。"); }
    }

    private static void download(final MainActivity activity, final JSONObject item) {
        if (activity.busy || activity.closed) return;
        activity.busy = true;
        activity.showOperation("正在下载并校验 APK…");
        activity.worker.execute(new Runnable() { public void run() {
            try {
                String digest = item.getString("sha256");
                long size = item.getLong("size");
                if (!digest.matches("[a-f0-9]{64}") || size < 1 || size > MAX_APK) throw new Exception("版本清单或文件大小无效");
                File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiPlayVersions");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("无法创建公共下载目录");
                File target = new File(folder, "DiPlay-" + digest + ".apk");
                if (!target.isFile() || target.length() != size || !digest.equals(sha256(target))) {
                    File part = new File(folder, "DiPlay-" + digest + ".part");
                    if (part.exists()) part.delete();
                    HttpURLConnection connection = open("/apk/" + digest);
                    try {
                        if (connection.getResponseCode() != 200) throw new Exception("下载 HTTP " + connection.getResponseCode());
                        InputStream in = connection.getInputStream();
                        FileOutputStream out = new FileOutputStream(part);
                        try {
                            byte[] buffer = new byte[8192]; int n; long total = 0;
                            while ((n = in.read(buffer)) != -1) { total += n; if (total > size || total > MAX_APK) throw new Exception("APK 超出清单大小"); out.write(buffer, 0, n); }
                        } finally { try { in.close(); } catch (Exception ignored) {} try { out.close(); } catch (Exception ignored) {} }
                    } finally { connection.disconnect(); }
                    if (part.length() != size || !digest.equals(sha256(part))) { part.delete(); throw new Exception("APK 校验失败，未安装"); }
                    if (target.exists() && !target.delete()) { part.delete(); throw new Exception("无法替换不完整的 APK"); }
                    if (!part.renameTo(target)) { part.delete(); throw new Exception("无法保存已校验的 APK"); }
                }
                final File verified = target;
                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(verified.getAbsolutePath(), 0);
                if (archive == null || !activity.getPackageName().equals(archive.packageName) ||
                    archive.versionCode != item.getInt("versionCode") ||
                    !item.getString("versionName").equals(archive.versionName)) throw new Exception("下载的 APK 与版本清单不匹配");
                activity.runOnUiThread(new Runnable() { public void run() {
                    activity.busy = false;
                    if (activity.closed) return;
                    activity.showOperation("APK 已校验并保存到：" + verified.getAbsolutePath());
                    if (item.optBoolean("requiresUninstall")) {
                        new AlertDialog.Builder(activity).setTitle("旧版已下载")
                            .setMessage("文件已保存到公共下载目录：\n" + verified.getAbsolutePath() +
                                "\n\n请先在车机设置中卸载当前 DiPlay，然后用文件管理器打开这个 APK 安装。下载文件会保留，DiPlay 的应用内部数据会被清除。")
                            .setPositiveButton("知道了", null).show();
                        return;
                    }
                    try {
                        Intent install = new Intent(Intent.ACTION_VIEW);
                        install.setDataAndType(Uri.fromFile(verified), "application/vnd.android.package-archive");
                        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(install);
                    } catch (Exception e) {
                        new AlertDialog.Builder(activity).setTitle("APK 已下载")
                            .setMessage("已保存到公共下载目录：\n" + verified.getAbsolutePath() + "\n请用文件管理器打开安装。")
                            .setPositiveButton("知道了", null).show();
                    }
                }});
            } catch (final Exception e) { finishError(activity, "下载未完成", e); }
        }});
    }

    private static void askIssue(final MainActivity activity) {
        final EditText input = new EditText(activity);
        input.setSingleLine(false); input.setMinLines(2); input.setMaxLines(5);
        input.setHint("\u63cf\u8ff0\u73b0\u8c61\u548c\u590d\u73b0\u6b65\u9aa4");
        final String[] micLabels = new String[] {"\u672a\u6d4b\u8bd5", "\u5bf9\u65b9\u542c\u5230\u8f66\u673a\u9ea6\u514b\u98ce\uff0c\u58f0\u97f3\u6e05\u695a", "\u5bf9\u65b9\u542c\u5230\u4f46\u6709\u6742\u97f3", "\u5bf9\u65b9\u542c\u4e0d\u5230\u8f66\u673a\u9ea6\u514b\u98ce"};
        final String[] speakerLabels = new String[] {"\u672a\u6d4b\u8bd5", "\u8f66\u673a\u626c\u58f0\u5668\u80fd\u542c\u5230\u5bf9\u65b9\uff0c\u58f0\u97f3\u6e05\u695a", "\u8f66\u673a\u542c\u5230\u4f46\u6709\u6742\u97f3", "\u8f66\u673a\u5b8c\u5168\u65e0\u58f0"};
        final String[] connectionLabels = new String[] {"\u672a\u6d4b\u8bd5", "\u8fde\u63a5\u540e\u89c2\u5bdf\u671f\u5185\u4e00\u76f4\u7a33\u5b9a", "\u89c2\u5bdf\u671f\u5185\u81ea\u52a8\u65ad\u5f00", "\u672c\u6b21\u65e0\u6cd5\u5efa\u7acb\u8fde\u63a5"};
        final String[] keyLabels = new String[] {"\u672a\u6d4b\u8bd5", "\u6309\u540e Siri \u6210\u529f\u54cd\u5e94", "\u6309\u540e\u65e0\u53cd\u5e94", "\u6709\u53cd\u5e94\uff0c\u4f46\u7ed3\u679c\u4e0d\u6b63\u5e38"};
        final Spinner i12Connection = createSpinner(activity, connectionLabels);
        final Spinner i12Mic = createSpinner(activity, micLabels);
        final Spinner i12Speaker = createSpinner(activity, speakerLabels);
        final Spinner i15Connection = createSpinner(activity, connectionLabels);
        final Spinner i15Mic = createSpinner(activity, micLabels);
        final Spinner i15Speaker = createSpinner(activity, speakerLabels);
        final Spinner voiceKey = createSpinner(activity, keyLabels);
        final EditText i12Ios = new EditText(activity), i15Ios = new EditText(activity);
        i12Ios.setSingleLine(true); i15Ios.setSingleLine(true);
        i12Ios.setHint("26.2.1"); i15Ios.setHint("26.2.1");
        TextView hint = new TextView(activity);
        hint.setText("\u505c\u8f66\u540e\uff0c\u4f7f\u7528\u540c\u4e00\u6761\u7ebf\u548c\u540c\u4e00 USB \u53e3\u5206\u522b\u6d4b\u8bd5\u4e24\u53f0\u624b\u673a\uff1a\u5404\u8fde\u63a5\u7ea6 3 \u5206\u949f\u6216\u76f4\u5230\u65ad\u5f00\uff0c\u5404\u6253\u4e00\u6b21\u7535\u8bdd\u5e76\u53cc\u5411\u8bf4\u51e0\u53e5\u8bdd\u3002\u539f\u5382\u8bed\u97f3\u52a9\u624b\u7ef4\u6301\u5df2\u505c\u7528\u72b6\u6001\uff0c\u6309\u4e00\u6b21\u65b9\u5411\u76d8\u8bed\u97f3\u952e\uff0c\u518d\u6309\u4e00\u4e2a\u5df2\u77e5\u6709\u6548\u7684\u97f3\u91cf\u952e\u4f5c\u5bf9\u7167\u3002\u6ca1\u6d4b\u7684\u624b\u673a\u548c\u901a\u8bdd\u65b9\u5411\u4fdd\u6301\u201c\u672a\u6d4b\u8bd5\u201d\uff1b\u4e0d\u4f1a\u5f55\u5236\u6216\u4e0a\u4f20\u901a\u8bdd\u97f3\u9891\u3002");
        hint.setPadding(0, 0, 0, 10);
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        box.addView(hint); box.addView(input);
        addChoice(box, activity, "iPhone 12 \u8fde\u63a5\u7a33\u5b9a\u6027", i12Connection);
        addChoice(box, activity, "iPhone 12 \u901a\u8bdd\uff1a\u5bf9\u65b9\u662f\u5426\u542c\u5230\u8f66\u673a\u9ea6\u514b\u98ce", i12Mic);
        addChoice(box, activity, "iPhone 12 \u901a\u8bdd\uff1a\u8f66\u673a\u662f\u5426\u542c\u5230\u5bf9\u65b9", i12Speaker);
        addChoice(box, activity, "iPhone 15 Pro \u8fde\u63a5\u7a33\u5b9a\u6027", i15Connection);
        addChoice(box, activity, "iPhone 15 Pro \u901a\u8bdd\uff1a\u5bf9\u65b9\u662f\u5426\u542c\u5230\u8f66\u673a\u9ea6\u514b\u98ce", i15Mic);
        addChoice(box, activity, "iPhone 15 Pro \u901a\u8bdd\uff1a\u8f66\u673a\u662f\u5426\u542c\u5230\u5bf9\u65b9", i15Speaker);
        addChoice(box, activity, "\u8bed\u97f3\u952e\u7ed3\u679c", voiceKey);
        addText(box, activity, "iPhone 12 iOS \u7248\u672c\uff08\u53ef\u4e0d\u586b\uff09", i12Ios);
        addText(box, activity, "iPhone 15 Pro iOS \u7248\u672c\uff08\u53ef\u4e0d\u586b\uff09", i15Ios);
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("\u63cf\u8ff0\u9047\u5230\u7684\u95ee\u9898").setView(scroll)
            .setPositiveButton("\u63d0\u4ea4\u95ee\u9898\u548c\u7b5b\u9009\u65e5\u5fd7", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                submit(activity, input.getText().toString(), new int[] {i12Connection.getSelectedItemPosition(), i12Mic.getSelectedItemPosition(), i12Speaker.getSelectedItemPosition(), i15Connection.getSelectedItemPosition(), i15Mic.getSelectedItemPosition(), i15Speaker.getSelectedItemPosition(), voiceKey.getSelectedItemPosition()}, new String[] {i12Ios.getText().toString(), i15Ios.getText().toString()});
            }})
            .setNegativeButton("\u53d6\u6d88", null).show();
    }

    private static Spinner createSpinner(Activity activity, String[] labels) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private static void addChoice(LinearLayout box, Activity activity, String title, Spinner spinner) {
        TextView label = new TextView(activity);
        label.setText(title); label.setPadding(0, 10, 0, 2);
        box.addView(label); box.addView(spinner);
    }

    private static void addText(LinearLayout box, Activity activity, String title, EditText input) {
        TextView label = new TextView(activity);
        label.setText(title); label.setPadding(0, 10, 0, 2);
        box.addView(label); box.addView(input);
    }

    private static String callOutcome(int position) {
        switch (position) {
            case 1: return "clear";
            case 2: return "noisy";
            case 3: return "no_sound";
            default: return "not_tested";
        }
    }

    private static String connectionOutcome(int position) {
        switch (position) { case 1: return "stable"; case 2: return "disconnected"; case 3: return "connect_failed"; default: return "not_tested"; }
    }

    private static String keyOutcome(int position) {
        switch (position) { case 1: return "siri_opened"; case 2: return "no_response"; case 3: return "other"; default: return "not_tested"; }
    }

    private static String cleanIosVersion(String value) {
        String version = value == null ? "" : value.trim();
        return version.matches("[0-9]{1,3}(\\.[0-9]{1,3}){0,2}") ? version : "";
    }

    private static JSONObject phoneTest(String connection, String mic, String speaker, String iosVersion) throws Exception {
        return new JSONObject().put("connection", connection).put("remoteHeardCarMic", mic).put("inCarHeardRemote", speaker).put("iosVersion", cleanIosVersion(iosVersion));
    }

    private static void submit(final MainActivity activity, final String issueText, final int[] choices, final String[] iosVersions) {
        final String issue = issueText == null ? "" : issueText.trim();
        if (issue.length() == 0) { new AlertDialog.Builder(activity).setMessage("\u8bf7\u5148\u586b\u5199\u95ee\u9898\u73b0\u8c61\uff1b\u4ec5\u62a5\u544a\u73af\u5883\u65f6\u53ef\u5199\u201c\u73af\u5883\u517c\u5bb9\u6027\u6d4b\u8bd5\u201d\u3002").setPositiveButton("\u8fd4\u56de", null).show(); return; }
        if (issue.length() > 1000) { new AlertDialog.Builder(activity).setMessage("\u95ee\u9898\u8bf4\u660e\u6700\u591a 1000 \u4e2a\u5b57\u7b26\u3002").setPositiveButton("\u8fd4\u56de", null).show(); return; }
        if (choices == null || choices.length != 7 || iosVersions == null || iosVersions.length != 2) { new AlertDialog.Builder(activity).setMessage("\u6d4b\u8bd5\u7ed3\u679c\u4e0d\u5b8c\u6574\uff0c\u8bf7\u91cd\u65b0\u63d0\u4ea4\u3002").setPositiveButton("\u8fd4\u56de", null).show(); return; }
        final String i12Mic = callOutcome(choices[1]), i12Speaker = callOutcome(choices[2]);
        final String i15Mic = callOutcome(choices[4]), i15Speaker = callOutcome(choices[5]);
        final String i12Connection = connectionOutcome(choices[0]), i15Connection = connectionOutcome(choices[3]);
        final String micOutcome = !"not_tested".equals(i12Mic) ? i12Mic : i15Mic;
        final String speakerOutcome = !"not_tested".equals(i12Speaker) ? i12Speaker : i15Speaker;
        final JSONObject phoneTests;
        try { phoneTests = new JSONObject().put("iphone12", phoneTest(i12Connection, i12Mic, i12Speaker, iosVersions[0])).put("iphone15Pro", phoneTest(i15Connection, i15Mic, i15Speaker, iosVersions[1])); }
        catch (Exception e) { new AlertDialog.Builder(activity).setMessage("\u65e0\u6cd5\u751f\u6210\u624b\u673a\u6d4b\u8bd5\u7ed3\u679c\uff0c\u8bf7\u91cd\u8bd5\u3002").setPositiveButton("\u8fd4\u56de", null).show(); return; }
        final String voiceKeyOutcome = keyOutcome(choices[6]);
        new AlertDialog.Builder(activity).setTitle("\u786e\u8ba4\u63d0\u4ea4")
            .setMessage("\u5c06\u4e0a\u4f20\u7ecf\u7b5b\u9009\u7684\u65e5\u5fd7\u3001USB \u4ea7\u54c1\u7c7b\u578b/\u63a5\u53e3\u4fe1\u606f\u3001\u65b9\u5411\u76d8\u952e\u503c\u4e0e\u5904\u7406\u7ed3\u679c\uff0c\u4ee5\u53ca\u4f60\u5206\u522b\u9009\u62e9\u7684 iPhone 12 / iPhone 15 Pro \u901a\u8bdd\u548c\u65ad\u8fde\u7ed3\u679c\u3002\u4e0d\u4f1a\u4e0a\u4f20 USB \u5e8f\u5217\u53f7/UDID\u3001\u539f\u59cb USB \u6570\u636e\u6216\u901a\u8bdd\u5f55\u97f3\u3002\u516c\u7f51\u4f20\u8f93\u4f7f\u7528 HTTP\u3002\u7ee7\u7eed\uff1f")
            .setPositiveButton("\u786e\u8ba4\u63d0\u4ea4", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) { sendReport(activity, issue, micOutcome, speakerOutcome, phoneTests, voiceKeyOutcome); }})
            .setNegativeButton("\u8fd4\u56de\u4fee\u6539", null).show();
    }

    private static void sendReport(final MainActivity activity, final String issue, final String micOutcome, final String speakerOutcome, final JSONObject phoneTests, final String voiceKeyOutcome) {
        if (activity.busy || activity.closed) return;
        activity.busy = true;
        activity.showOperation("正在筛选并提交兼容性报告…");
        activity.worker.execute(new Runnable() { public void run() {
            try {
                UsbPresenceLog.recordCurrentPhones(activity, "report_submit");
                FilteredEvents filtered = filterEvents(MainActivity.snapshot(activity));
                String version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                JSONObject alsaEnvironment;
                try { alsaEnvironment = AlsaCapturePathSelector.environment(activity); }
                catch (Throwable ignored) { alsaEnvironment = new JSONObject().put("status", "unavailable"); }
                JSONObject body = new JSONObject().put("profile", profile(activity)).put("appVersion", version)
                    .put("issue", issue).put("events", filtered.events).put("alsaEnvironment", alsaEnvironment)
                    .put("callObservation", new JSONObject().put("remoteHeardCarMic", micOutcome).put("inCarHeardRemote", speakerOutcome))
                    .put("phoneTests", phoneTests).put("keyObservation", new JSONObject().put("voiceKey", voiceKeyOutcome));
                JSONObject receipt = post("/report", body);
                final String message = "提交成功。报告编号：" + receipt.optString("id") +
                    "\n上传事件：" + receipt.optInt("events") + " 条；已清除本次上传的本地记录，未上传或并发新增记录保留。服务器将在 24 小时后自动清理原始报告。";
                MainActivity.acknowledge(activity, filtered.acknowledgedRows);
                activity.runOnUiThread(new Runnable() { public void run() {
                    activity.busy = false;
                    if (!activity.closed) new AlertDialog.Builder(activity).setTitle("已收到").setMessage(message).setPositiveButton("确定", null).show();
                }});
            } catch (final Exception e) { finishError(activity, "报告提交失败；本地诊断日志保留未改动", e); }
        }});
    }

    private static FilteredEvents filterEvents(JSONArray source) throws Exception {
        FilteredEvents result = new FilteredEvents();
        ArrayList<EventCandidate> selected = new ArrayList<EventCandidate>();
        long first = Long.MAX_VALUE;
        for (int i = 0; i < source.length(); i++) first = Math.min(first, source.getJSONObject(i).optLong("time", 0));
        if (first == Long.MAX_VALUE) first = 0;
        int alsaCount = 0, priorityCount = 0, transportCount = 0;
        // Keep the newest evidence so old unsubmitted logs cannot crowd out this test run.
        // Reserve ALSA, then direct key/USB/call evidence, then transport details before routine logs.
        for (int pass = 0; pass < 4 && selected.size() < 200; pass++) {
            for (int i = source.length() - 1; i >= 0 && selected.size() < 200; i--) {
                JSONObject row = source.optJSONObject(i); if (row == null) continue;
                String raw = row.optString("event", ""); int brace = raw.indexOf('{');
                String type = (brace >= 0 ? raw.substring(0, brace) : raw).trim();
                if (!type.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) continue;
                JSONObject detail = null;
                if (brace >= 0) try { detail = new JSONObject(raw.substring(brace)); } catch (Exception ignored) {}
                String detailText = detail == null ? "" : detail.optString("detail", "");
                boolean alsa = "MEDIA".equals(type) && isAlsaDiagnostic(detailText);
                boolean priority = isHighValueDiagnostic(type);
                boolean transport = isTransportDiagnostic(type);
                if (pass == 0 && (!alsa || alsaCount >= 40) || pass == 1 && (alsa || !priority || transport || priorityCount >= 100) ||
                    pass == 2 && (alsa || !transport || transportCount >= 60) || pass == 3 && (alsa || priority)) continue;
                long time = row.optLong("time", first);
                JSONObject safeEvent;
                if (alsa) {
                    safeEvent = new JSONObject().put("offsetMs", Math.max(0, time - first))
                        .put("type", "ALSA_CAPTURE").put("data", parseAlsaDiagnostic(detailText));
                    alsaCount++;
                } else {
                    JSONObject safe = new JSONObject();
                    if (detail != null) {
                        Iterator<?> keys = detail.keys();
                        while (keys.hasNext()) {
                            String key = String.valueOf(keys.next()); Object value = detail.opt(key);
                            if (NUMERIC_FIELDS.contains(key) && value instanceof Number) {
                                double n = ((Number)value).doubleValue(); if (!Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= 1000000000d) safe.put(key, value);
                            } else if (BOOLEAN_FIELDS.contains(key) && value instanceof Boolean) safe.put(key, value);
                            else if (ENUM_FIELDS.contains(key) && value instanceof String && SAFE_TOKEN.matcher((String)value).matches()) safe.put(key, value);
                            else if (ALSA_SUMMARY_FIELDS.contains(key) && value instanceof String && SAFE_ALSA_SUMMARY.matcher((String)value).matches()) safe.put(key, value);
                        }
                    }
                    String code = diagnosticCode(type, detailText);
                    String summary = diagnosticSummary(type, detailText);
                    if (!code.isEmpty()) safe.put("diagnosticCode", code);
                    if (!summary.isEmpty()) safe.put("diagnosticSummary", summary);
                    safeEvent = new JSONObject().put("offsetMs", Math.max(0, time - first)).put("type", type).put("data", safe);
                    if (priority) priorityCount++;
                    else if (transport) transportCount++;
                }
                selected.add(new EventCandidate(time, safeEvent, new JSONObject().put("id", row.getString("id"))));
            }
        }
        java.util.Collections.sort(selected, new java.util.Comparator<EventCandidate>() {
            public int compare(EventCandidate left, EventCandidate right) { return left.time < right.time ? -1 : left.time == right.time ? 0 : 1; }
        });
        ArrayList<EventCandidate> bounded = new ArrayList<EventCandidate>();
        int remainingBytes = 42000;
        for (int i = selected.size() - 1; i >= 0; i--) {
            EventCandidate item = selected.get(i);
            int size = item.event.toString().getBytes("UTF-8").length + 8;
            if (size > remainingBytes) continue;
            bounded.add(item); remainingBytes -= size;
        }
        java.util.Collections.sort(bounded, new java.util.Comparator<EventCandidate>() {
            public int compare(EventCandidate left, EventCandidate right) { return left.time < right.time ? -1 : left.time == right.time ? 0 : 1; }
        });
        for (EventCandidate item : bounded) { result.events.put(item.event); result.acknowledgedRows.put(item.acknowledged); }
        return result;
    }

    private static boolean isAlsaDiagnostic(String text) {
        return text.startsWith("microphone_alsa_") || text.startsWith("microphone_duplex_evidence ") || text.startsWith("microphone_capture_path=") || text.startsWith("microphone_capture_fallback ");
    }

    private static boolean isHighValueDiagnostic(String type) {
        return "WHEEL_KEY".equals(type) || "WHEEL_CONTROL".equals(type) || "USB_DEVICE".equals(type) ||
            "USB_PRESENCE".equals(type) || "FAILED".equals(type) || "DISCONNECTED".equals(type) ||
            "AUDIO_ROUTING".equals(type) || "AUDIO_FOCUS".equals(type) || type.startsWith("CALL_");
    }

    private static boolean isTransportDiagnostic(String type) {
        return "CONNECT_SUMMARY".equals(type) || "CONNECT_TIMING".equals(type) || "USB".equals(type) ||
            "USB_NATIVE".equals(type) || "NCM".equals(type) || "MUX".equals(type) || "USB_TAKEOVER".equals(type) ||
            "USB_PERMISSION".equals(type) || "PAIRING".equals(type) || "PAIRING_RETRY".equals(type) ||
            "REENUMERATION".equals(type) || "WAIT_USB".equals(type) || "PHONE_START".equals(type) ||
            "AIRPLAY_ENDED".equals(type) || "CONTROL_ENDED".equals(type) || "AUTO_USB".equals(type);
    }

    private static String diagnosticCode(String type, String text) {
        if (text == null || text.length() == 0 || !(isHighValueDiagnostic(type) || isTransportDiagnostic(type))) return "";
        String value = text.toLowerCase(Locale.US);
        if (value.contains("errno=")) return "errno";
        if (value.contains("timed out") || value.contains("timeout")) return "timeout";
        if (value.contains("reset by the peer") || value.contains("peer reset")) return "peer_reset";
        if (value.contains("permission") || value.contains("denied")) return "permission_denied";
        if (value.contains("could not claim") || value.contains("claim failed")) return "interface_claim_failed";
        if (value.contains("not found") || value.contains("missing") || value.contains("no free")) return "component_missing";
        if (value.contains("invalid") || value.contains("protocol")) return "protocol_error";
        if (value.contains("disconnected") || value.contains("closed")) return "link_closed";
        if (type.equals("FAILED")) return "connection_failed";
        return "";
    }

    private static String diagnosticSummary(String type, String text) {
        if (text == null || text.length() == 0 || "USB_NETWORK".equals(type) || !(isHighValueDiagnostic(type) || isTransportDiagnostic(type))) return "";
        java.util.regex.Matcher matcher = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)=([^\\s;]+)").matcher(text);
        Set<String> allowed = set("errno", "result", "attempt", "attempts", "completed", "next", "durationMs", "waitedMs", "timeoutMs", "readTimeouts", "receivedBytes", "submitted", "polls", "cancelled", "actualBytes", "urbStatus", "inbound", "linkUp", "carrier", "connected", "interface", "configuration", "target", "expected", "observed", "length", "declaredLength", "bufferedBytes", "lastUsbReadBytes", "optionalReplyPadding", "previousProtocol", "previousLength", "nextProtocol", "nextLength", "version", "stage", "phase", "profile", "outcome", "sequence", "expectedSequence", "receivedSequence", "stream", "mode", "state", "route", "focus", "usage", "content", "muted", "active", "musicActive", "phonePresent", "speakerphone", "bluetoothSco", "bluetoothA2dp", "wiredHeadset", "microphoneMuted", "sampleRate", "channels", "volume", "maxVolume", "frames", "writtenBytes", "playbackHeadFrames", "ageMs", "calls", "timeouts", "firstSubmitAgeMs", "nativeWaitMs");
        StringBuilder summary = new StringBuilder();
        while (matcher.find() && summary.length() < 450) {
            String key = matcher.group(1), value = matcher.group(2);
            if (!allowed.contains(key) || value.length() > 48 || !value.matches("[A-Za-z0-9_.:/+-]+")) continue;
            if (summary.length() > 0) summary.append(' ');
            summary.append(key).append('=').append(value);
        }
        return summary.toString();
    }

    private static JSONObject parseAlsaDiagnostic(String text) throws Exception {
        JSONObject out = new JSONObject();
        int space = text.indexOf(' ');
        String head = space < 0 ? text : text.substring(0, space);
        String event = head.startsWith("microphone_alsa_") ? head.substring("microphone_alsa_".length())
            : (head.startsWith("microphone_duplex_evidence") ? "duplex" : (head.startsWith("microphone_capture_path=") ? "path" : "fallback"));
        out.put("eventType", safeToken(event, "unknown"));
        JSONObject tokens = new JSONObject();
        java.util.regex.Matcher matcher = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)=([^\\s]+)").matcher(text);
        while (matcher.find()) tokens.put(matcher.group(1), matcher.group(2));
        putToken(out, tokens, "profile", "profileKey");
        putToken(out, tokens, "source", "source");
        putToken(out, tokens, "kind", "audioType");
        putToken(out, tokens, "reason", "reason");
        putToken(out, tokens, "stage", "stage");
        putToken(out, tokens, "phase", "phase");
        putToken(out, tokens, "result", "result");
        putToken(out, tokens, "native", "nativeProbeLoaded");
        putToken(out, tokens, "truncated", "candidateListTruncated");
        putToken(out, tokens, "path", "devicePath");
        putToken(out, tokens, "selected", "selectedPath");
        putToken(out, tokens, "retained", "retainedPath");
        putToken(out, tokens, "from", "fromPath");
        putToken(out, tokens, "to", "toPath");
        putToken(out, tokens, "fallback", "fallbackTarget");
        putToken(out, tokens, "attempts", "attemptSummary");
        putToken(out, tokens, "mixer", "mixerSnapshot");
        putToken(out, tokens, "candidates", "candidateSummary");
        putNumber(out, tokens, "candidates", "candidateCount");
        putNumber(out, tokens, "tried", "triedCount");
        putNumber(out, tokens, "untriedCandidates", "untriedCandidateCount");
        putNumber(out, tokens, "omittedCandidates", "omittedCandidateCount");
        putNumber(out, tokens, "hwRate", "sampleRate");
        putNumber(out, tokens, "rate", "sampleRate");
        putNumber(out, tokens, "targetRate", "targetRate");
        putNumber(out, tokens, "hwChannels", "channels");
        putNumber(out, tokens, "channels", "channels");
        putNumber(out, tokens, "peak", "peak");
        putNumber(out, tokens, "windowPeak", "peak");
        putNumber(out, tokens, "meanAbs", "meanAbs");
        putNumber(out, tokens, "windowMeanAbs", "meanAbs");
        putNumber(out, tokens, "errors", "errors");
        putNumber(out, tokens, "capturedPackets", "capturedPackets");
        putNumber(out, tokens, "requestedRate", "requestedRate");
        putNumber(out, tokens, "requestedChannels", "requestedChannels");
        putNumber(out, tokens, "rateMin", "rateMin");
        putNumber(out, tokens, "rateMax", "rateMax");
        putNumber(out, tokens, "channelsMin", "channelsMin");
        putNumber(out, tokens, "channelsMax", "channelsMax");
        putNumber(out, tokens, "negotiatedRate", "negotiatedRate");
        putNumber(out, tokens, "negotiatedChannels", "negotiatedChannels");
        putNumber(out, tokens, "errno", "errno");
        putNumber(out, tokens, "card", "card");
        putNumber(out, tokens, "controlCount", "controlCount");
        if (tokens.has("readOnly")) out.put("readOnly", "true".equals(tokens.optString("readOnly")));
        if (tokens.has("snapshotTruncated")) out.put("snapshotTruncated", "true".equals(tokens.optString("snapshotTruncated")));
        if ("AudioRecord".equals(tokens.optString("path"))) out.put("fallbackTarget", "AudioRecord");
        if (tokens.has("native")) out.put("nativeProbeLoaded", "true".equals(tokens.optString("native")));
        if (tokens.has("truncated")) out.put("candidateListTruncated", "true".equals(tokens.optString("truncated")));
        String cards = between(text, " cards=", " pcmCapture=");
        String pcm = between(text, " pcmCapture=", " candidates=");
        if (cards != null) out.put("alsaCardsSummary", sanitizeSummary(cards, 512));
        if (pcm != null) out.put("alsaPcmSummary", sanitizeSummary(pcm, 768));
        return out;
    }

    private static String between(String text, String start, String end) {
        int a = text.indexOf(start); if (a < 0) return null;
        a += start.length(); int b = text.indexOf(end, a); if (b < 0) return null;
        return text.substring(a, b);
    }

    private static String sanitizeSummary(String text, int max) {
        String safe = text.replaceAll("[^A-Za-z0-9_.,:/;=x\\[\\]()+# -]", "_").trim();
        return safe.substring(0, Math.min(max, safe.length()));
    }

    private static String safeToken(String value, String fallback) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : fallback;
    }

    private static void putToken(JSONObject out, JSONObject tokens, String source, String target) throws Exception {
        if (!tokens.has(source)) return;
        String value = tokens.optString(source, "");
        if (ALSA_PATH_FIELDS.contains(target)) {
            if (SAFE_ALSA_PATH.matcher(value).matches()) out.put(target, value);
        } else if (ALSA_SUMMARY_FIELDS.contains(target)) {
            String safe = value.replaceAll("[^A-Za-z0-9_.,:/;=x\\[\\]()+# -]", "_");
            if (safe.length() > 1200) safe = safe.substring(0, 1200);
            if (!safe.isEmpty() && SAFE_ALSA_SUMMARY.matcher(safe).matches()) out.put(target, safe);
        } else if (ALSA_TOKEN_FIELDS.contains(target)) {
            if ("profileKey".equals(target)) { if (value.matches("[a-fA-F0-9]{1,40}")) out.put(target, value); }
            else if (SAFE_TOKEN.matcher(value).matches()) out.put(target, value);
        }
    }

    private static void putNumber(JSONObject out, JSONObject tokens, String source, String target) throws Exception {
        if (!tokens.has(source) || out.has(target)) return;
        try { long value = Long.parseLong(tokens.optString(source)); if (value >= 0 && value <= 1000000000L) out.put(target, value); }
        catch (NumberFormatException ignored) { }
    }

    private static JSONObject post(String path, JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes("UTF-8");
        if (bytes.length > 65536) throw new Exception("请求超过允许大小");
        HttpURLConnection c = open(path);
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            java.io.OutputStream out = c.getOutputStream(); try { out.write(bytes); } finally { out.close(); }
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("服务器 HTTP " + code);
            return new JSONObject(new String(readBounded(c.getInputStream(), 65536), "UTF-8"));
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection open(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(API + path).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        c.setRequestProperty("Connection", "close");
        return c;
    }

    private static byte[] readBounded(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { byte[] b = new byte[2048]; int n; while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new Exception("服务器响应超出大小限制"); out.write(b, 0, n); } }
        finally { in.close(); }
        return out.toByteArray();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); InputStream in = new java.io.FileInputStream(file);
        try { byte[] buffer = new byte[16384]; int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
        finally { in.close(); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 255)); return out.toString();
    }

    private static void finishError(final MainActivity activity, final String prefix, final Exception error) {
        activity.runOnUiThread(new Runnable() { public void run() {
            activity.busy = false;
            if (!activity.closed) {
                String reason = error.getMessage();
                if (reason == null || reason.length() > 180) reason = error.getClass().getSimpleName();
                activity.showOperation(prefix + "：" + reason);
                new AlertDialog.Builder(activity).setTitle("未完成").setMessage(prefix + "\n" + reason)
                    .setPositiveButton("确定", null).show();
            }
        }});
    }
}
