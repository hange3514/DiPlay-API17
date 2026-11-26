package local.headunit.diplay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.text.SimpleDateFormat;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in environment matching, compatibility reports, and historical APK downloads. */
final class CompatibilityTools {
    private static final String API = "http://next.hzy.cloud:20001/diplay/v1/hybrid";
    private static final String PREFS = "diplay_compatibility";
    private static final String CONSENT_VERSION = "0.1.33-cm591-diagnostics";
    private static final String PENDING_HOTSPOT_ATTACHMENTS = "pending_hotspot_attachments";
    private static final String PENDING_CARLIFE_ATTACHMENTS = "pending_carlife_attachments";
    private static final SecureRandom REPORT_RANDOM = new SecureRandom();
    private static final AtomicBoolean REPORT_JOB_RUNNING = new AtomicBoolean();
    private static final int MAX_APK = 20 * 1024 * 1024;
    private static final int MAX_CATALOG_RESPONSE = 256 * 1024;
    private static final long CATALOG_DEADLINE_MS = 30000L;
    private static final long TRANSFER_DEADLINE_MS = 180000L;
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_.:/-]{1,48}");
    private static final Pattern SAFE_FAILURE_MESSAGE = Pattern.compile("[A-Za-z0-9 _.,:/;=()+#-]{1,180}");
    private static final Pattern SAFE_ALSA_PATH = Pattern.compile("/dev/snd/pcmC[0-9]{1,3}D[0-9]{1,3}c");
    private static final Pattern SAFE_ALSA_SUMMARY = Pattern.compile("[A-Za-z0-9_.,:/;=x\\[\\]()+# -]{1,1200}");
    private static final Set<String> NUMERIC_FIELDS = set(
        "elapsedMs", "waitMs", "durationMs", "sdk", "width", "height", "densityDpi",
        "refreshMilliHz", "attempt", "retryCount", "resultCode", "state", "mode",
        "stream", "volume", "maxVolume", "sampleRate", "channels", "bufferSize",
        "frames", "writtenBytes", "playbackHeadFrames", "packetCount", "timeoutCount",
        "usbDeviceCount", "usbInterfaceCount", "endpointCount", "frameCount", "fpsMilli",
        "candidateCount", "triedCount", "targetRate", "peak", "meanAbs", "errors", "capturedPackets", "carPlayStartSessionsSent", "wifiConfigurationsSent",
        "mdnsQueries", "mdnsResponses", "mdnsAnnouncements", "controlServices", "controlProbe2xx",
        "requestedRate", "requestedChannels", "rateMin", "rateMax", "channelsMin", "channelsMax", "untriedCandidateCount", "omittedCandidateCount",
        "negotiatedRate", "negotiatedChannels", "errno", "card", "controlCount", "keyCode", "keyAction", "keyRepeatCount", "keySource", "keyFlags", "wheelAction", "mediaIndex", "oemServiceVersion", "vid", "pid",
        "failureCount", "retryCount", "retryInMs", "sessionCount", "appleDevicesPresent", "connectAttempt", "installedCode",
        "payloadBytes", "transferBytes", "listenerPort", "tcpSynToPort", "startFlushedAgoMs", "firstNcmBulkOutAcceptedAgoMs", "ncmNetworkReadyAgoMs", "tcpSynBaseline", "tcpSynCurrent", "lastTcpSynAgeMs", "lastIpv4TcpSynAgeMs", "lastRxMdnsServiceAgeMs", "lastRxMdnsServiceMask", "lastRxMdnsServiceQueryMask", "lastRxMdnsServiceResponseMask",
        "ipVersion", "ipProtocol", "tcpSourcePort", "tcpDestinationPort", "tcpFlags", "frameBytes", "networkId", "scanResultAgeMs", "lastIap2MessageId", "addressCount", "probeHitCount",
        "deviceVid", "devicePid", "deviceClass", "deviceSubclass", "deviceProtocol", "usbInterfaceCount", "appleDevicesPresent", "usbSpeedMbps", "usbConfiguration", "eventEndpointAddress", "aclInEndpointAddress", "aclOutEndpointAddress", "hciResetControlResult", "hciResetStatus", "hciVersionStatus", "ats2851AuthReqBefore", "ats2851AuthReqAfter", "ats2851AuthReqSubmitted", "ats2851PeerAuthReq", "ats2851UsbSubmitResult", "hciVersion", "hciRevision", "hciLmpVersion", "hciManufacturer", "hciLmpSubversion", "hciAddressStatus", "hciInquiryStatus", "hciInquiryDeviceCount", "hciInquiryResultEventCount", "hciInquiryElapsedMs", "commandOpcode", "commandStatus", "hciEventCode", "scanEnable", "scanEnableRequested", "scanEnableUsbSubmitResult", "scanEnableWriteStatus", "numHciCommandPackets", "configuredNameBytes", "oemFrameLength", "oemHeader0", "oemHeader1", "requestedFps", "usbStartupProfile", "vpnWaitMs", "beforeServiceMs", "totalMs", "uptimeMs", "ageMs", "mtu", "localPort", "usb_discovery", "usb_permission", "descriptors", "usb_reenumeration", "configuration_takeover", "usbmux_open", "ncm_open", "usbmux_handshake", "lockdown_pairing", "carkit_tls", "iap2_setup", "mfi_identity", "vpn_bridge", "airplay_listener", "iap2_auth_and_phone_start", "mfi_authentication", "phone_availability", "phone_projection_start", "first_video", "video_ready", "linkSpeedMbps", "rssiDbm", "channel", "frequencyMhz", "wifiReadyMs", "iap2Profile", "messageId", "payloadBytes", "interfaceCount", "scanAttempt", "autoConnectElapsedMs", "autoConnectRemainingMs", "bluetoothElapsedMs", "receiverAgeMs"
    );
    private static final Set<String> BOOLEAN_FIELDS = set(
        "connected", "videoReady", "surfaceValid", "granted", "success", "muted",
        "active", "musicActive", "phonePresent", "speakerphone", "bluetoothSco",
        "bluetoothA2dp", "wiredHeadset", "microphoneMuted", "nativeProbeLoaded", "candidateListTruncated",
        "readOnly", "snapshotTruncated", "keyHandled", "keyIsSiriCandidate", "devicePermission", "usbSysfsReadable", "knownProfileMatched", "receiverRegistered",
        "serviceBusy", "paused", "launchPending", "usbLinked", "activeSession", "tunnelOpen", "bootLaunch", "autoConnectRequested", "receiverAttempted", "connecting", "connectPending", "usbPermissionPending", "connectAfterGrant", "alreadyGranted", "rfcommConnected", "addressPresent", "realtekSetupSelected", "firmwareSetupExpected", "scanReadbackKnown", "inquiryScanConfirmed", "pageScanConfirmed", "discoverableConfirmed", "ats2851UsbCommandQueued", "scanEnableUsbCommandQueued", "scanEnableWriteKnown", "scanEnableWriteAccepted", "scanEnableUsbSubmitKnown", "scanReadbackSkipped", "scanWriteFallbackUsed",
        "vpnRequired", "vpnUsed", "ipv4", "ipv6", "tcp", "udp", "tcpSynSinceNcmOpen", "tcpSynSinceStart", "tcpSynAfterStartFlushAndNetworkReady", "ipv4TcpSynSinceStart", "ipv4TcpSynAfterStartFlushAndNetworkReady", "anyTcpSynAfterStartFlushAndNetworkReady", "wifiConnected", "wifiIpReady", "passphraseConfigured", "usbDetached", "airplayTcpAccepted", "nativeNcmUsed",
        "serviceDiscoverySinceStart", "serviceDiscoveryQuiet", "addressMatchesWifiInfo", "wirelessAvailable", "androidBluetoothAdapterPresent", "androidBluetoothAdapterEnabled",
        "transportNotificationSeen", "wirelessCarPlayAvailableSeen", "credentialAvailable", "configuredNetworkMatched", "bluetoothGroup17Enabled", "usbHostGroup16Enabled", "wifiCredentialsIncluded", "bluetoothIdentifierPresent", "usbTransportIdentifierPresent", "afterStartSession", "ipv6ListenerAvailable", "apBssidPresent", "hciInterfacePresent", "hciEndpointSetComplete", "hciInterfaceClaimed", "hciResetAttempted", "hciAddressPresent", "systemAddressMatchesDongle", "hciInquiryAttempted", "hciInquiryComplete"
    );
    private static final Set<String> ENUM_FIELDS = set("stage", "phase", "outcome", "status", "reason", "strategy", "eventType", "keyDecision", "keyPath", "usbTransition", "usbVersion", "diagnosticCode", "result", "errorType", "origin", "causeType", "decision", "backend", "networkBackend", "family", "service", "connection", "localAddress", "gatewayAddress", "networkMask", "addressFamily", "interfaceName", "transportMode", "wiredState", "wirelessState", "themeAssetsState", "terminal", "credentialSource", "security", "frequencySource", "bluetoothIdentitySource", "activeIap2Transport", "controllerState", "firmware", "receiverStage", "receiverErrorType", "androidBluetoothStackStatus", "usbBtStatus", "hciResetResult", "hciVersionResult", "hciAddressResult", "hciInquiryResult", "chipsetHint");
    private static final Set<String> ALSA_PATH_FIELDS = set("devicePath", "selectedPath", "retainedPath", "fromPath", "toPath", "rememberedPath");
    private static final Set<String> ALSA_SUMMARY_FIELDS = set("candidateSummary", "attemptSummary", "alsaCardsSummary", "alsaPcmSummary", "mixerSnapshot", "usbInterfaceSummary", "usbDevicesSummary", "diagnosticSummary");
    private static final Set<String> ALSA_TOKEN_FIELDS = set("profileKey", "source", "audioType", "result", "fallbackTarget", "stage", "phase");
    private static final Pattern DIAGNOSTIC_TOKEN = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)=([^\\s;]+)");
    private static final Pattern MUSIC_SUMMARY_NUMBER = Pattern.compile("-?[0-9]{1,16}");
    private static final String VIDEO_RX_WINDOW = "video_rx_window";
    private static final String VIDEO_RENDER_WINDOW = "video_render_window";
    private static final int MAX_VIDEO_WINDOWS_PER_STAGE = 18;
    private static final Set<String> VIDEO_RECEIVE_FIELDS = set(
        "streamType", "requestedFps", "windowMs", "packets", "bytes", "receivedFrames", "receivedFpsMilli",
        "senderGapSamples", "senderGapMaxMs", "arrivalGapMaxMs", "deliveryJitterMaxMs",
        "readMaxMs", "headerReadMaxMs", "bodyReadMaxMs", "readsOver250Ms", "processMaxUs",
        "decryptAvgUs", "decryptMaxUs");
    private static final Set<String> VIDEO_RENDER_FIELDS = set(
        "streamType", "requestedFps", "windowMs", "submittedFrames", "submittedFpsMilli", "skippedFrames",
        "queueDepth", "queuePeak", "droppedFrames", "queueMaxMs", "decodeMaxMs", "surfaceGapMaxMs",
        "releaseMaxUs", "dequeueOverrunMaxUs", "inputMaxUs", "inFlightPeak", "receiveToSurfaceMaxMs",
        "paceHeldFrames", "paceHoldMaxMs", "pressureSkips", "earlyInputReleases", "inputReadyAfterEarlyRelease");
    private static final Pattern REPORT_EVENT_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Set<String> MUSIC_SUMMARY_FIELDS = set(
        "inputQueuedMs", "bufferTargetMs", "playbackRate", "decodeInputMaxUs", "queueMaxAgeMs",
        "rtpDuplicateOrLate", "rtpRecoveredGaps", "rtpSkippedPackets", "rtpResets", "rtpExpiredPackets",
        "targetMs", "bufferedMs", "recoveryTargetMs", "requestedRate", "outputRate", "senderPpm",
        "trackPpm", "generation", "confirmedDryEvents", "maxConfirmedDryMs",
        "readsOver250Ms", "seqGapAfterReadOver250Ms", "seqMissingAfterReadOver250Ms", "seqGapAtMs");
    private static final Set<String> MICROPHONE_TIMING_FIELDS = set("micEncodeMaxUs", "micSendMaxUs", "micFrameMs", "micComplexity",
        "micSealMaxUs", "micReadCalls", "micReadFrames", "micReadEmpty", "micReadErrors", "micReadMaxUs",
        "micReadGapMs", "micCapturedFrames", "micCaptureGapMs", "micProcessMaxUs", "micEncodeErrors",
        "micBurstPackets", "micLatePackets", "micQueueMaxDepth", "micQueueDropped", "micQueueUnderruns", "micPartialWindow",
        "micEncodeCpuAtMaxUs", "micEncodeCpuMaxUs", "micFirstPacketMs", "micFirstFromPcmMs",
        "micFirstEncodeUs", "micFirstEncodeCpuUs");

    private static final Pattern MICROPHONE_LEVEL_CHANNELS = Pattern.compile("-?[0-9]{1,12}(?:/-?[0-9]{1,12}){0,7}");
    private static final Set<String> MICROPHONE_LEVEL_FIELDS =
        set("micInputPeak", "micInputRails", "micInputNearRails", "micInputDc");

    private static final Set<String> DIAGNOSTIC_SUMMARY_FIELDS = set("errno", "result", "attempt", "attempts", "completed", "next", "durationMs", "elapsedMs", "waitedMs", "timeoutMs", "readTimeouts", "readErrors", "descriptor", "wiredAvailable", "usbTransport", "receivedBytes", "submitted", "polls", "cancelled", "actualBytes", "urbStatus", "inbound", "linkUp", "carrier", "connected", "interface", "configuration", "target", "expected", "observed", "length", "declaredLength", "bufferedBytes", "lastUsbReadBytes", "optionalReplyPadding", "previousProtocol", "previousLength", "nextProtocol", "nextLength", "version", "stage", "phase", "profile", "outcome", "sequence", "expectedSequence", "receivedSequence", "rejectedParameterIds", "stream", "mode", "state", "route", "method", "errorType", "frameClass", "frameMethod", "contentBytes", "payloadBytes", "transferBytes", "frameBytes", "protocol", "etherType", "ipVersion", "ipProtocol", "tcpSourcePort", "tcpDestinationPort", "tcpFlags", "baseline", "listenerPort", "tcpSynToPort", "startFlushedAgoMs", "firstNcmBulkOutAcceptedAgoMs", "ncmNetworkReadyAgoMs", "tcpSynBaseline", "tcpSynCurrent", "lastTcpSynAgeMs", "lastIpv4TcpSynAgeMs", "lastRxMdnsServiceAgeMs", "lastRxMdnsServiceMask", "lastRxMdnsServiceQueryMask", "lastRxMdnsServiceResponseMask", "tcpSynSinceNcmOpen", "tcpSynSinceStart", "tcpSynAfterStartFlushAndNetworkReady", "ipv4TcpSynSinceStart", "ipv4TcpSynAfterStartFlushAndNetworkReady", "anyTcpSynAfterStartFlushAndNetworkReady", "serviceDiscoverySinceStart", "serviceDiscoveryQuiet", "status", "decision", "focus", "usage", "content", "muted", "active", "musicActive", "phonePresent", "speakerphone", "bluetoothSco", "bluetoothA2dp", "wiredHeadset", "microphoneMuted", "sampleRate", "channels", "volume", "maxVolume", "frames", "writtenBytes", "playbackHeadFrames", "ageMs", "calls", "timeouts", "firstSubmitAgeMs", "nativeWaitMs", "nativeErrorCode", "identityReady", "nativeReady", "nativeStatus", "networkStartDurationMs", "readyBeforeUse", "rxMdns", "rxIpv6Mdns", "rxIpv4Mdns", "mdnsQueries", "mdnsResponses", "mdnsAnnouncements", "controlServices", "controlProbe2xx", "wifiIapTunnelRequested", "wifiIapTunnelReady", "lastProbe", "mfiReady", "notificationIndex", "expectedControlInterface", "dataInterface", "matchesDataInterface", "payloadLength", "linkWord", "carrierMutation", "notifications", "appleLinkNotifications", "lastCarrierAgeMs", "lastAppleLinkWord", "lastAppleLinkAgeMs", "controlInterface", "skipped", "resyncs", "count", "vid", "pid", "interfaceCount", "firmware", "bluetoothElapsedMs", "scanAttempt", "autoConnectElapsedMs", "autoConnectRemainingMs", "rfcommConnected", "addressPresent", "associated", "ssidConfigured", "frequencyMhz", "frequencySource", "channel", "networkId", "linkSpeedMbps", "rssiDbm", "localAddress", "gatewayAddress", "networkMask", "interfaceName", "credentialAvailable", "credentialSource", "security", "configuredNetworkMatched", "wifiConnected", "wifiIpReady", "transportMode", "bluetoothGroup17Enabled", "wifiCredentialsIncluded", "ipv6ListenerAvailable", "apBssidPresent", "airplayTcpAccepted", "sessionCount", "videoReady", "usbLinked", "tunnelOpen", "carPlayStartSessionsSent", "wifiConfigurationsSent", "transportNotificationSeen", "wirelessCarPlayAvailableSeen");

    private static final class EventCandidate {
        final int index;
        final JSONObject event;
        final String id;
        EventCandidate(int index, JSONObject event, String id) {
            this.index = index;
            this.event = event;
            this.id = id;
        }
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
        if (activity.busy || activity.closed || activity.isFinishing()) return;
        if (REPORT_JOB_RUNNING.get()) { activity.showOperation("正在处理日志提交，请稍候。"); return; }
        resumeOrAskIssue(activity);
    }

    private static void resumeOrAskIssue(final MainActivity activity) {
        if (activity.busy || activity.closed || activity.isFinishing()) return;
        if (REPORT_JOB_RUNNING.get()) { activity.showOperation("正在处理日志提交，请稍候。"); return; }
        if (!PendingReportStore.exists(activity)) {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final ArrayList<PendingAttachment> retry = new ArrayList<PendingAttachment>();
            for (String id : PendingAttachmentReports.read(prefs.getString(PENDING_CARLIFE_ATTACHMENTS, ""))) {
                retry.add(new PendingAttachment(id, true));
            }
            if (HotspotEvidence.supported()) {
                for (String id : PendingAttachmentReports.read(prefs.getString(PENDING_HOTSPOT_ATTACHMENTS, ""))) {
                    retry.add(new PendingAttachment(id, false));
                }
            }
            if (retry.isEmpty()) {
                askIssue(activity, "");
                return;
            }
            java.util.Collections.sort(retry, new java.util.Comparator<PendingAttachment>() {
                public int compare(PendingAttachment a, PendingAttachment b) {
                    return a.reportId.compareTo(b.reportId);
                }
            });
            String[] labels = new String[retry.size()];
            for (int i = 0; i < labels.length; i++) {
                PendingAttachment item = retry.get(i);
                labels[i] = (item.carLife ? "CarLife" : "热点") + " 附件\n" + item.reportId;
            }
            new AlertDialog.Builder(activity).setTitle("待补传附件")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        final PendingAttachment item = retry.get(which);
                        requireConsent(activity, new Runnable() { public void run() {
                            if (item.carLife) retryCarLifeAttachments(activity, item.reportId);
                            else retryHotspotAttachments(activity, item.reportId);
                        }});
                    }
                }).setNeutralButton("新建报告", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { askIssue(activity, ""); }
                }).setNegativeButton("取消", null).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("上次日志待提交")
            .setMessage("重试会提交上次确认的同一份报告，沿用当时选择的录音和诊断附件。")
            .setPositiveButton("重试上次提交", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                requireConsent(activity, new Runnable() { public void run() { sendPendingReport(activity); } });
            }})
            .setNeutralButton("管理日志", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                new AlertDialog.Builder(activity).setTitle("管理本地日志")
                    .setItems(new String[]{"重新整理待提交报告", "清空本地日志"}, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface options, int selected) {
                            if (selected == 0) confirmRebuildReport(activity);
                            else confirmClearLogs(activity);
                        }
                    }).setNegativeButton("取消", null).show();
            }})
            .setNegativeButton("稍后", null).show();
    }

    private static final class PendingAttachment {
        final String reportId;
        final boolean carLife;

        PendingAttachment(String reportId, boolean carLife) {
            this.reportId = reportId;
            this.carLife = carLife;
        }
    }

    static void checkForUpdate(final MainActivity activity) {
        if (activity.busy || activity.closed || activity.connecting) return;
        requireConsent(activity, new Runnable() { public void run() { checkCompatibleUpdate(activity); }});
    }

    private static void checkCompatibleUpdate(final MainActivity activity) {
        if (activity.busy || activity.closed || activity.connecting) return;
        activity.busy = true;
        activity.showOperation("正在检查本车机的更新…");
        activity.worker.execute(new Runnable() { public void run() {
            try {
                final PackageInfo installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                JSONArray versions = readCatalog(activity, installed.versionCode).getJSONArray("versions");
                JSONObject newest = null; int newestSort = Integer.MIN_VALUE; int updateCount = 0;
                for (int i = 0; i < versions.length(); i++) {
                    JSONObject item = versions.optJSONObject(i); if (item == null) continue;
                    if (item.optInt("versionCode", 0) <= installed.versionCode) continue;
                    updateCount++;
                    int sort = item.optInt("sortCode", item.optInt("versionCode", 0));
                    if (newest == null || sort > newestSort) { newest = item; newestSort = sort; }
                }
                final JSONObject update = newest;
                final int candidateCount = updateCount;
                try { MainActivity.record(activity, "VERSION_UPDATE_CHECK", new JSONObject().put("installedCode", installed.versionCode).put("candidateCount", candidateCount).put("status", update == null ? "up_to_date" : "update_available")); } catch (Exception ignored) {}
                activity.runOnUiThread(new Runnable() { public void run() {
                    activity.busy = false;
                    if (activity.closed || activity.isFinishing()) return;
                    if (update == null) {
                        new AlertDialog.Builder(activity).setTitle("已是最新版本").setMessage("当前车机没有更高的兼容版本。").setPositiveButton("确定", null).show();
                        return;
                    }
                    String notes = update.optString("notes", "");
                    String message = "当前：" + installed.versionName + "\n可更新：" + update.optString("versionName") + "\n\n" + notes + "\n\nAPK 将先进行 SHA-256 和版本校验，再交给系统安装器。";
                    new AlertDialog.Builder(activity).setTitle("发现无线版更新").setMessage(message)
                        .setNegativeButton("稍后", null)
                        .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) { download(activity, update); }}).show();
                }});
            } catch (final Exception e) { finishError(activity, "检查在线更新失败", e); }
        }});
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
        return "\u5728\u7ebf\u66f4\u65b0\u67e5\u8be2\u4f1a\u53d1\u9001\u8f66\u673a\u578b\u53f7\u3001Android \u7248\u672c\u3001\u5c4f\u5e55\u89c4\u683c\u548c\u5f53\u524d APK \u7248\u672c\u7528\u4e8e\u5339\u914d\u3002\u624b\u52a8\u63d0\u4ea4\u65f6\u4f1a\u4e0a\u4f20\u4f60\u586b\u5199\u7684\u95ee\u9898\u8bf4\u660e\u548c\u8bca\u65ad\u4e8b\u4ef6\uff0c\u5305\u62ec\u542f\u52a8\u81ea\u542f\u52a8\u3001USB VID/PID \u4e0e\u6743\u9650\u3001CM591 HCI/RFCOMM\u3001Wi-Fi \u7f51\u5361/IP/\u9891\u6bb5/RSSI\u3001iAP2 \u9636\u6bb5\u548c AirPlay \u63a5\u5165/\u89e3\u7801\u7ed3\u679c\u3002\u9519\u8bef\u4e8b\u4ef6\u4f1a\u9644\u4e0a\u8131\u654f\u540e\u7684\u9519\u8bef\u539f\u56e0\u6458\u8981\u3002\u4e0d\u4e0a\u4f20 Wi-Fi \u540d\u79f0\u3001\u53e3\u4ee4\u3001BSSID/\u84dd\u7259\u786c\u4ef6\u5730\u5740\u3001\u624b\u673a\u6807\u8bc6\u3001\u539f\u8f66\u7cfb\u7edf\u84dd\u7259\u5e93\u6587\u4ef6\u6216\u8fde\u7eed\u97f3\u89c6\u9891\u3002\u7cfb\u7edf\u5df2\u4fdd\u5b58\u7684 Wi-Fi \u53e3\u4ee4\u82e5\u7528\u4e8e iAP2 \u5907\u7528\u6a21\u5f0f\uff0c\u4ec5\u5728\u8fde\u63a5\u65f6\u77ed\u6682\u8bfb\u5165\u5185\u5b58\uff0c\u4e0d\u4fdd\u5b58\u548c\u4e0a\u4f20\u3002" +
            "\n\n提交日志会自动附带可读取的原厂车型代码、固件版本、ALSA 声卡和节点、通话诊断及方向盘按键事件。" +
            "\n\n主动准备的短时测试录音可在确认时选择加密附带。录音只采集车机麦克风，可用于排查 Siri、语音输入或通话中的杂音。" +
            "\n\n主动运行车机能力检测会保存大灯状态、原车 CAN 广播样本、系统陀螺仪和横摆通道的采样结果、GPS 定位计数、导航协议计数和系统编解码能力，不保存经纬度、路线内容或额外录音。" +
            "\n\n选择「附带热点排查信息」会提交热点状态、接口地址、系统可读的崩溃线索和列出的原车系统文件，发送到这套无线版的私人服务器。" +
            "\n\n\u62a5\u544a\u53ea\u4f1a\u63d0\u4ea4\u5230 USB+Wi-Fi \u5b9e\u9a8c\u7248\u7684\u72ec\u7acb\u8def\u5f84\u3002\u4e0a\u4f20\u6210\u529f\u540e\u4f1a\u6e05\u7406\u672c\u5730\u5df2\u786e\u8ba4\u6536\u5230\u7684\u65e5\u5fd7\uff0c\u670d\u52a1\u5668\u62a5\u544a\u4e0d\u81ea\u52a8\u8fc7\u671f\u3002\u5f53\u524d\u4e0a\u4f20\u4f7f\u7528 HTTP\uff0c\u8bf7\u4ec5\u5728\u63a5\u53d7\u6b64\u65b9\u5f0f\u65f6\u63d0\u4ea4\u3002";
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
                JSONObject response = readCatalog(activity, installedCode);
                final JSONArray versions = response.getJSONArray("versions");
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
        if (activity.busy || activity.closed || activity.connecting) return;
        activity.busy = true;
        activity.showOperation("正在下载并校验 APK…");
        activity.worker.execute(new Runnable() { public void run() {
            File part = null;
            try {
                String digest = item.getString("sha256");
                long size = item.getLong("size");
                if (!digest.matches("[a-f0-9]{64}") || size < 1 || size > MAX_APK) throw new Exception("版本清单或文件大小无效");
                File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiPlayVersions");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("无法创建公共下载目录");
                File target = UpdateFiles.target(folder, digest);
                if (!target.isFile() || target.length() != size || !digest.equals(sha256(target))) {
                    UpdateFiles.requireSpace(folder, size, MAX_APK);
                    part = UpdateFiles.part(folder);
                    HttpURLConnection connection = open("/apk/" + digest);
                    NetworkDeadline deadline = new NetworkDeadline(connection, TRANSFER_DEADLINE_MS);
                    OnlineUpdate.track(activity, deadline);
                    try {
                        deadline.check();
                        int status = connection.getResponseCode();
                        deadline.check();
                        if (status != 200) throw new Exception("下载 HTTP " + status);
                        int declaredSize = connection.getContentLength();
                        if (declaredSize >= 0 && declaredSize != size) throw new Exception("APK 长度与版本清单不符");
                        MessageDigest hash = MessageDigest.getInstance("SHA-256");
                        InputStream in = connection.getInputStream();
                        try {
                            FileOutputStream out = new FileOutputStream(part);
                            try {
                                byte[] buffer = new byte[16384];
                                int count;
                                long total = 0;
                                while ((count = in.read(buffer)) != -1) {
                                    deadline.check();
                                    total += count;
                                    if (total > size) throw new Exception("APK 超出清单大小");
                                    out.write(buffer, 0, count);
                                    hash.update(buffer, 0, count);
                                }
                                deadline.check();
                                if (total != size || !digest.equals(lowerHex(hash.digest()))) throw new Exception("APK 校验失败，未安装");
                                out.getFD().sync();
                            } finally { out.close(); }
                        } finally { in.close(); }
                    } catch (Exception failure) {
                        deadline.check();
                        throw failure;
                    } finally { OnlineUpdate.release(activity, deadline); }
                }
                if (activity.closed) return;
                final File candidate = part == null ? target : part;
                PackageManager packageManager = activity.getPackageManager();
                PackageInfo archive = packageManager.getPackageArchiveInfo(candidate.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                PackageInfo installed = packageManager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
                if (archive == null || !activity.getPackageName().equals(archive.packageName) ||
                    archive.versionCode != item.getInt("versionCode") ||
                    !item.getString("versionName").equals(archive.versionName)) throw new Exception("下载的 APK 与版本清单不匹配");
                if (!sameSigningIdentity(installed.signatures, archive.signatures)) {
                    if (part == null) target.delete();
                    throw new Exception("APK 签名与当前安装版本不一致，已拒绝安装");
                }
                if (activity.closed) return;
                // Publish only after the cached or freshly downloaded bytes pass every check.
                if (part != null) UpdateFiles.promote(part, target);
                final File verified = target;
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
            } catch (final Exception e) {
                finishError(activity, "下载未完成", e);
            } finally {
                if (part != null && part.exists()) part.delete();
            }
        }});
    }

    private static void askIssue(final MainActivity activity, String draft) {
        askIssue(activity, draft, null, false, false);
    }

    private static void askIssue(final MainActivity activity, String draft, String selectedMicId,
                                 boolean includeHotspot, boolean includeCarLife) {
        if (activity.busy || activity.closed || activity.isFinishing()) return;
        final CallMicCapture.ReadySample readyMic = CallMicCapture.readySample();
        final String micId = readyMic == null ? null : readyMic.getId();
        final String micStatus = CallMicCapture.status();
        final EditText input = new EditText(activity);
        input.setSingleLine(false); input.setMinLines(3); input.setMaxLines(6);
        input.setHint("例如，手机能搜到车机热点，但获取不到 IP");
        input.setText(draft);
        input.setSelection(input.length());
        TextView hint = new TextView(activity);
        hint.setText("只需描述遇到的问题。车机、声卡、通话和方向盘诊断由程序自动附带。");
        hint.setPadding(0, 0, 0, 10);
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0); box.addView(hint); box.addView(input);
        final boolean hotspotSupported = HotspotEvidence.supported();
        final CheckBox hotspot = new CheckBox(activity);
        if (hotspotSupported) {
            hotspot.setText("另附原车系统文件（热点排查）");
            hotspot.setChecked(includeHotspot);
            box.addView(hotspot);
        }
        final CheckBox carLife = new CheckBox(activity);
        if (HotspotArtifactUpload.hasCarLife(activity)) {
            carLife.setText("附带原厂百度 CarLife 程序，用于分析声音");
            carLife.setChecked(includeCarLife);
            box.addView(carLife);
        }
        android.widget.Button clearLogs = new android.widget.Button(activity);
        clearLogs.setText("清空本地日志");
        box.addView(clearLogs);
        android.widget.Button vehicleCheck = new android.widget.Button(activity);
        vehicleCheck.setText("车机能力检测");
        box.addView(vehicleCheck);
        if (hotspotSupported) {
            final Intent developerSettings = new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .setPackage("com.android.settings");
            if (developerSettings.resolveActivity(activity.getPackageManager()) != null) {
                android.widget.Button systemReport = new android.widget.Button(activity);
                systemReport.setText("打开系统错误报告设置");
                systemReport.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        try {
                            activity.startActivity(developerSettings);
                        } catch (RuntimeException unavailable) {
                            new AlertDialog.Builder(activity).setMessage("车机未开放原厂开发者选项。")
                                    .setPositiveButton("确定", null).show();
                        }
                    }
                });
                box.addView(systemReport);
            }
        }
        final CheckBox attach = new CheckBox(activity);
        if (micId != null) {
            attach.setText("附带麦克风录音（" + sampleRoleLabel(readyMic.getAudioType()) + "）");
            attach.setChecked(micId.equals(selectedMicId));
            box.addView(attach);
        } else if ("armed".equals(micStatus) || "recording".equals(micStatus)) {
            TextView micHint = new TextView(activity);
            micHint.setText("armed".equals(micStatus) ? "已准备采集。请在 5 分钟内复现语音杂音。" :
                "正在采集车机麦克风。测试结束后重新打开此页即可选择录音。");
            micHint.setPadding(0, pad / 2, 0, pad / 2);
            box.addView(micHint);
        }
        box.setFocusableInTouchMode(true);
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("提交日志")
            .setView(scroll)
            .setPositiveButton("继续", null)
            .setNeutralButton("idle".equals(micStatus) ? "测试录音" : "管理录音", null)
            .setNegativeButton("取消", null).show();
        clearLogs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                dialog.dismiss();
                confirmClearLogs(activity);
            }
        });
        vehicleCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!activity.resumed || activity.closed || activity.vehicleCapabilityCheck != null) return;
                final String text = input.getText().toString();
                final boolean withHotspot = hotspotSupported && hotspot.isChecked();
                final boolean withCarLife = carLife.isChecked();
                final String withMic = attach.isChecked() ? micId : null;
                dialog.dismiss();
                activity.vehicleCapabilityCheck = new VehicleCapabilityCheck(activity,
                    new Runnable() { @Override public void run() { askIssue(activity, text, withMic, withHotspot, withCarLife); }});
                activity.vehicleCapabilityCheck.start();
            }
        });
        box.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                String text = input.getText().toString();
                dialog.dismiss();
                editMicCapture(activity, text, hotspotSupported && hotspot.isChecked(), carLife.isChecked());
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                final String text = input.getText().toString();
                final String issue = HybridReportPayload.issueText(text);
                if (issue.isEmpty()) {
                    input.setError("请输入问题描述");
                    input.requestFocus();
                    return;
                }
                if (issue.length() > 1000) {
                    input.setError("问题描述最多 1000 个字符");
                    input.requestFocus();
                    return;
                }
                String latestMicId = CallMicCapture.readyId();
                dialog.dismiss();
                if (micId == null ? latestMicId != null : !micId.equals(latestMicId)) {
                    activity.showOperation("测试录音状态已更新，请重新确认。");
                    askIssue(activity, text, null, hotspotSupported && hotspot.isChecked(), carLife.isChecked());
                    return;
                }
                confirmIssue(activity, text, issue, attach.isChecked() ? micId : null,
                    hotspotSupported && hotspot.isChecked(), carLife.isChecked());
            }
        });
    }

    private static String sampleRoleLabel(String audioType) {
        if ("speechrecognition".equals(audioType)) return "语音识别";
        if ("telephony".equals(audioType)) return "通话";
        return "其他语音输入";
    }

    private static void editMicCapture(final MainActivity activity, final String draft,
                                       final boolean includeHotspot, final boolean includeCarLife) {
        if (activity.busy || activity.closed || activity.isFinishing()) return;
        final String state = CallMicCapture.status();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        if ("idle".equals(state)) {
            dialog.setTitle("准备测试录音")
                .setMessage("接下来 5 分钟内，首次使用 Siri、语音输入或通话时，车机会保留最多 10 秒的麦克风采样和发送前音频。建议先安静一两秒，再正常说两句。回到提交日志页可选择加密附带录音。")
                .setPositiveButton("准备采集", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                    if (!CallMicCapture.arm()) { editMicCapture(activity, draft, includeHotspot, includeCarLife); return; }
                    String notice = "已准备采集，请复现刚才有杂音的语音场景。";
                    if (activity.videoReady) activity.setProjectionMode(true);
                    activity.showOperation(notice);
                    android.widget.Toast.makeText(activity, notice, android.widget.Toast.LENGTH_LONG).show();
                }});
        } else {
            dialog.setTitle("管理测试录音")
                .setMessage("ready".equals(state) ? "测试录音已就绪。可以返回报告表单，选择是否附带这份录音。" :
                    "可以取消本次采集，并清除本次采集的数据。")
                .setPositiveButton("ready".equals(state) ? "删除录音" : "取消采集",
                    new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                        CallMicCapture.discard();
                        askIssue(activity, draft, null, includeHotspot, includeCarLife);
                    }});
        }
        dialog.setNegativeButton("返回", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
            askIssue(activity, draft, null, includeHotspot, includeCarLife);
        }}).setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface dialog) {
            askIssue(activity, draft, null, includeHotspot, includeCarLife);
        }}).show();
    }

    private static void confirmIssue(final MainActivity activity, final String draft, final String issue, final String micId,
                                     final boolean includeHotspot, final boolean includeCarLife) {
        if (activity.busy || activity.closed || activity.isFinishing()) return;
        String message = micId == null ? "将上传问题描述和诊断日志。" :
            "将上传问题描述、诊断日志，并加密附带本次测试的麦克风原音和发送前音频。";
        if (HotspotEvidence.supported()) {
            message += "\n\n日志包含断线前后的热点状态、接口信息、上游网络及系统可读的崩溃线索。";
        }
        if (includeHotspot) {
            message += "\n\n日志收到后，另行上传车载流量、车机设置的 APK 和 ODEX，以及系统框架资源。服务器已有的相同文件会直接关联。";
        }
        if (includeCarLife) {
            message += "\n\n日志收到后，另行传送原厂百度 CarLife 的 APK 及可读取的同名 ODEX。安装包内置的库会随 APK 一起传送。";
        }
        message += "\n\n车机会清理本次已确认收到的本地日志。";
        new AlertDialog.Builder(activity).setTitle("确认提交日志")
            .setMessage(message)
            .setPositiveButton(micId == null ? "确认提交" : "提交日志和录音", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    requireConsent(activity, new Runnable() { public void run() { sendReport(activity, issue, micId, includeHotspot, includeCarLife); } });
                }
            })
            .setNegativeButton("返回修改", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                askIssue(activity, draft, micId, includeHotspot, includeCarLife);
            }})
            .setOnCancelListener(new DialogInterface.OnCancelListener() { public void onCancel(DialogInterface dialog) { askIssue(activity, draft, micId, includeHotspot, includeCarLife); }})
            .show();
    }

    private static void sendReport(final MainActivity activity, final String issue, final String micSampleId,
                                   final boolean includeHotspot, final boolean includeCarLife) {
        if (!beginReportJob(activity, "正在准备日志…")) return;
        try { activity.worker.execute(new Runnable() { public void run() {
            try {
                FilteredEvents filtered = filterEvents(DiagnosticLog.reportRows(activity));
                String version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                CompatibilityEvidence.appendIdentity(filtered.events, VehicleIdentityEvidence.collect(activity));
                CompatibilityEvidence.appendAlsaInventory(filtered.events, CompatibilityEvidence.alsaEnvironment(activity));
                JSONObject body = new JSONObject().put("productId", "hybrid-usb-wifi").put("profile", profile(activity)).put("appVersion", version)
                    .put("issue", issue).put("events", filtered.events).put("oemArtifacts", new JSONArray())
                    .put("clientReportId", newClientReportId());
                if (includeCarLife) {
                    body.put("carLifeArtifactsRequested", true)
                        .put("carLifeMetadata", HotspotArtifactUpload.carLifeMetadata(activity));
                }
                if (HotspotEvidence.supported()) {
                    JSONObject hotspot;
                    try {
                        hotspot = HotspotEvidence.snapshot(activity);
                    } catch (Exception failure) {
                        hotspot = new JSONObject().put("version", 1).put("collectionStatus", "unavailable");
                    }
                    body.put("hotspotEvidence", hotspot).put("hotspotArtifactsRequested", includeHotspot);
                }
                if (micSampleId != null) {
                    CallMicCapture.Snapshot clip = CallMicCapture.attachment(micSampleId);
                    if (clip == null) throw new Exception("测试录音已失效，请重新选择提交内容");
                    body.put(MicrophoneReportAttachment.FIELD, MicrophoneReportAttachment.seal(
                        clip.getId(), clip.getSampleRate(), clip.getPcmBytes(), clip.getBeforeWav(), clip.getAfterWav()));
                    JSONObject lastEvent = filtered.events.optJSONObject(filtered.events.length() - 1);
                    long offset = lastEvent == null ? 0 : lastEvent.optLong("offsetMs", 0);
                    filtered.events.put(new JSONObject().put("type", "MICROPHONE_SAMPLE")
                        .put("offsetMs", offset).put("data", new JSONObject().put("stage", "attached")
                            .put("audioType", clip.getAudioType()).put("sampleRate", clip.getSampleRate())
                            .put("durationMs", 500L * clip.getPcmBytes() / clip.getSampleRate())));
                }
                uploadSavedReport(activity, PendingReportStore.save(activity, body, filtered.acknowledgedRows));
            } catch (OutOfMemoryError memory) { finishError(activity, "车机内存不足，日志仍保留", new IOException("可停止投屏后再次提交")); }
            catch (final Exception error) { finishError(activity, "\u63d0\u4ea4\u672a\u5b8c\u6210\uff1b\u672c\u5730\u65e5\u5fd7\u4e0d\u4f1a\u6e05\u7406\uff0c\u5982\u5f85\u53d1\u62a5\u544a\u5df2\u4fdd\u5b58\u53ef\u5728\u4e0b\u6b21\u91cd\u8bd5", error); }
            finally { REPORT_JOB_RUNNING.set(false); }
        }}); } catch (final RuntimeException rejected) {
            REPORT_JOB_RUNNING.set(false);
            finishError(activity, "\u62a5\u544a\u4fdd\u5b58\u5931\u8d25\uff1b\u672c\u5730\u65e5\u5fd7\u672a\u6e05\u7406", rejected);
        }
    }

    private static String newClientReportId() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] random = new byte[6];
        REPORT_RANDOM.nextBytes(random);
        StringBuilder out = new StringBuilder(format.format(new Date())).append('-');
        for (byte value : random) out.append(String.format(Locale.US, "%02x", value & 255));
        return out.toString();
    }

    private static void sendPendingReport(final MainActivity activity) {
        if (!beginReportJob(activity, "正在重试上次提交…")) return;
        try { activity.worker.execute(new Runnable() { public void run() {
            try {
                JSONObject pending = PendingReportStore.read(activity);
                if (pending == null) throw new Exception("No saved pending report exists");
                uploadSavedReport(activity, pending);
            } catch (PendingReportStore.InvalidReportException invalid) {
                showInvalidPendingReport(activity);
            } catch (OutOfMemoryError memory) {
                finishError(activity, "车机内存不足，待提交报告仍保留",
                    new IOException("可停止投屏后再次提交"));
            } catch (final Exception error) {
                finishError(activity, "\u91cd\u8bd5\u5931\u8d25\uff1b\u5f85\u53d1\u62a5\u544a\u4ecd\u4fdd\u7559\u5728\u672c\u673a", error);
            } finally { REPORT_JOB_RUNNING.set(false); }
        }}); } catch (final RuntimeException rejected) {
            REPORT_JOB_RUNNING.set(false);
            finishError(activity, "\u91cd\u8bd5\u672a\u542f\u52a8\uff1b\u5f85\u53d1\u62a5\u544a\u4ecd\u4fdd\u7559\u5728\u672c\u673a", rejected);
        }
    }

    private static boolean beginReportJob(MainActivity activity, String progress) {
        if (activity.busy || activity.closed || activity.isFinishing()) return false;
        if (!REPORT_JOB_RUNNING.compareAndSet(false, true)) {
            activity.showOperation("正在处理日志提交，请稍候。");
            return false;
        }
        activity.busy = true;
        activity.showOperation(progress);
        return true;
    }

    private static void showInvalidPendingReport(final MainActivity activity) {
        activity.runOnUiThread(new Runnable() { public void run() {
            activity.busy = false;
            if (activity.closed || activity.isFinishing()) return;
            new AlertDialog.Builder(activity).setTitle("无法读取上次待发报告")
                .setMessage("上次报告可能过大或已损坏。可移除这份副本，用车机当前保留的日志重新整理一份。")
                .setPositiveButton("重新整理", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) { confirmRebuildReport(activity); }
                })
                .setNegativeButton("取消", null).show();
        }});
    }

    private static void confirmRebuildReport(final MainActivity activity) {
        new AlertDialog.Builder(activity).setTitle("重新整理待提交报告")
            .setMessage("移除上次待提交的报告副本及其中的录音附件。车机当前的本地日志会保留，接下来可以新建报告。")
            .setPositiveButton("移除副本并继续", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) { discardPendingReport(activity); }
            }).setNegativeButton("取消", null).show();
    }

    private static void discardPendingReport(final MainActivity activity) {
        if (!beginReportJob(activity, "正在移除待提交报告…")) return;
        try { activity.worker.execute(new Runnable() { public void run() {
            try {
                PendingReportStore.discard(activity);
                activity.runOnUiThread(new Runnable() { public void run() {
                    activity.busy = false;
                    if (!activity.closed) {
                        activity.showOperation("已移除待发副本，本地日志仍保留。");
                        askIssue(activity, "");
                    }
                }});
            } catch (Exception error) {
                finishError(activity, "移除待提交报告失败", error);
            } finally { REPORT_JOB_RUNNING.set(false); }
        }}); } catch (RuntimeException rejected) {
            REPORT_JOB_RUNNING.set(false);
            finishError(activity, "移除待提交报告未启动", rejected);
        }
    }

    private static void confirmClearLogs(final MainActivity activity) {
        new AlertDialog.Builder(activity).setTitle("清空本地日志")
            .setMessage("删除车机当前的诊断日志。已经生成的待发报告和服务器收到的报告会保留。")
            .setPositiveButton("确认清空", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) { clearLocalLogs(activity); }
            }).setNegativeButton("取消", null).show();
    }

    private static void clearLocalLogs(final MainActivity activity) {
        if (!beginReportJob(activity, "正在清空本地日志…")) return;
        try { activity.worker.execute(new Runnable() { public void run() {
            try {
                final int removed = DiagnosticLog.clear(activity);
                if (HotspotEvidence.supported()) HotspotEvidence.clear(activity);
                activity.runOnUiThread(new Runnable() { public void run() {
                    activity.busy = false;
                    activity.showOperation("已清空 " + removed + " 条本地日志。");
                }});
            } catch (Exception error) {
                finishError(activity, "清空日志失败", error);
            } finally { REPORT_JOB_RUNNING.set(false); }
        }}); } catch (RuntimeException rejected) {
            REPORT_JOB_RUNNING.set(false);
            finishError(activity, "清空日志未启动", rejected);
        }
    }

    private static void uploadSavedReport(final MainActivity activity, JSONObject pending) throws Exception {
        final JSONObject body = pending.getJSONObject("body");
        final JSONArray acknowledgedRows = pending.getJSONArray("acknowledgedRows");
        boolean repaired = HybridReportPayload.repairOffsets(body);
        String oldIssue = body.optString("issue", "");
        if (!oldIssue.isEmpty() && HybridReportPayload.issueText(oldIssue).isEmpty()) {
            body.put("issue", "环境检查");
            repaired = true;
        }
        // Only previously invalid reports need rewriting. Valid pending payload hashes stay intact.
        if (repaired) PendingReportStore.saveRepaired(activity, pending);
        JSONObject microphoneSample = body.optJSONObject(MicrophoneReportAttachment.FIELD);
        final String micSampleId = microphoneSample == null ? "" : microphoneSample.getString("id");
        final int micSampleBytes = microphoneSample == null ? 0 : MicrophoneReportAttachment.audioBytes(microphoneSample);
        JSONObject receipt = postReport(activity, body);
        final String receiptId = receipt.optString("id", "");
        final int acceptedEvents = receipt.optInt("events", -1);
        if (!"hybrid-usb-wifi".equals(receipt.optString("productId")) ||
            !PendingAttachmentReports.isReportId(receiptId) ||
            !receiptId.equals(body.optString("clientReportId", "")) ||
            acceptedEvents != body.getJSONArray("events").length() ||
            receipt.optInt("artifacts", -1) != 0 ||
            receipt.optInt("artifactBytes", -1) != 0 ||
            !micSampleId.equals(receipt.optString("microphoneSampleId", "")) ||
            receipt.optInt("microphoneSampleBytes", 0) != micSampleBytes) {
            throw new IOException("服务器已响应，回执信息与待发报告不一致。日志仍保留。");
        }
        final boolean attachHotspot = HotspotEvidence.supported() && body.optJSONObject("hotspotEvidence") != null
            && (!body.has("hotspotArtifactsRequested") || Boolean.TRUE.equals(body.opt("hotspotArtifactsRequested")));
        final boolean attachCarLife = Boolean.TRUE.equals(body.opt("carLifeArtifactsRequested"));
        if (attachHotspot || attachCarLife) {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor attachments = prefs.edit();
            if (attachHotspot) addPendingAttachment(prefs, attachments, PENDING_HOTSPOT_ATTACHMENTS, receiptId);
            if (attachCarLife) addPendingAttachment(prefs, attachments, PENDING_CARLIFE_ATTACHMENTS, receiptId);
            if (!attachments.commit()) throw new IOException("系统未保存附件补传记录，请重试。");
        }
        String cleanupMessage;
        try {
            MainActivity.acknowledge(activity, acknowledgedRows);
            PendingReportStore.removeAcknowledged(activity, pending, receiptId);
            if (microphoneSample != null) CallMicCapture.acknowledge(micSampleId);
            cleanupMessage = "已清理本次确认收到的本地记录。";
        } catch (Exception cleanupFailure) {
            cleanupMessage = "服务器已保存这份报告，本地清理暂未完成。";
            recordReportFailure(activity, "cleanup_failed", cleanupFailure, 0);
        }
        final String attachmentMessage = (attachCarLife ? uploadCarLifeAttachments(activity, receiptId) : "") +
            (attachHotspot ? uploadHotspotAttachments(activity, receiptId) : "");
        final String resultMessage = "报告编号：" + receiptId + "\n服务器确认收到 " + acceptedEvents +
            " 条日志事件。" + cleanupMessage +
            (microphoneSample == null ? "" : "\n已确认收到麦克风录音。") + attachmentMessage;
        activity.runOnUiThread(new Runnable() { public void run() {
            activity.busy = false;
            if (!activity.closed) new AlertDialog.Builder(activity).setTitle("\u5df2\u6536\u5230").setMessage(resultMessage).setPositiveButton("\u786e\u5b9a", null).show();
        }});
    }

    private static void addPendingAttachment(SharedPreferences prefs, SharedPreferences.Editor edit,
                                             String key, String reportId) {
        edit.putString(key, PendingAttachmentReports.append(prefs.getString(key, ""), reportId));
    }

    private static boolean completePendingAttachment(Context context, String key, String reportId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(key, "");
        String remaining = PendingAttachmentReports.complete(saved, reportId);
        if (saved.equals(remaining)) return true;
        SharedPreferences.Editor edit = prefs.edit();
        if (remaining.isEmpty()) edit.remove(key);
        else edit.putString(key, remaining);
        return edit.commit();
    }

    private static void retryHotspotAttachments(final MainActivity activity, final String reportId) {
        if (!beginReportJob(activity, "正在补传热点附件…")) return;
        try {
            activity.worker.execute(new Runnable() { public void run() {
                try {
                    final String result = "报告编号：" + reportId + "\n基础日志已收到。" +
                        uploadHotspotAttachments(activity, reportId);
                    activity.runOnUiThread(new Runnable() { public void run() {
                        activity.busy = false;
                        if (!activity.closed && !activity.isFinishing()) {
                            new AlertDialog.Builder(activity).setTitle("热点附件").setMessage(result)
                                .setPositiveButton("确定", null).show();
                        }
                    }});
                } finally {
                    REPORT_JOB_RUNNING.set(false);
                }
            }});
        } catch (RuntimeException error) {
            REPORT_JOB_RUNNING.set(false);
            finishError(activity, "补传热点附件未启动", error);
        }
    }

    private static void retryCarLifeAttachments(final MainActivity activity, final String reportId) {
        if (!beginReportJob(activity, "正在补传 CarLife 附件…")) return;
        try {
            activity.worker.execute(new Runnable() { public void run() {
                try {
                    final String result = "报告编号：" + reportId + "\n基础日志已收到。" +
                        uploadCarLifeAttachments(activity, reportId);
                    activity.runOnUiThread(new Runnable() { public void run() {
                        activity.busy = false;
                        if (!activity.closed && !activity.isFinishing()) {
                            new AlertDialog.Builder(activity).setTitle("CarLife 附件").setMessage(result)
                                .setPositiveButton("确定", null).show();
                        }
                    }});
                } finally {
                    REPORT_JOB_RUNNING.set(false);
                }
            }});
        } catch (RuntimeException error) {
            REPORT_JOB_RUNNING.set(false);
            finishError(activity, "补传 CarLife 附件未启动", error);
        }
    }

    private static String uploadCarLifeAttachments(final MainActivity activity, final String reportId) {
        try {
            JSONObject info = HotspotArtifactUpload.collectCarLife(activity, reportId, new HotspotArtifactUpload.Progress() {
                public void onArtifact(String kind, final int index, final int total) {
                    activity.runOnUiThread(new Runnable() { public void run() {
                        activity.showOperation("日志已收到，正在传送 CarLife 附件（" + index + "/" + total + "）…");
                    }});
                }
            });
            StringBuilder message = new StringBuilder();
            JSONArray files = info.optJSONArray("artifacts");
            if (files == null) throw new IOException("CarLife 附件状态缺失");
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String status = file.optString("status");
                String label = "carlife-apk".equals(file.optString("kind")) ? "APK" : "ODEX";
                message.append("\nCarLife ").append(label);
                if ("uploaded".equals(status) || "reused".equals(status)) message.append(" 已确认收到。");
                else if ("missing".equals(status) || "not_installed".equals(status)) message.append(" 未找到。");
                else message.append(" 暂未收到。");
            }
            if ("complete".equals(info.optString("status"))) {
                if (!completePendingAttachment(activity, PENDING_CARLIFE_ATTACHMENTS, reportId)) {
                    message.append("\n车机暂未写入附件完成状态，可重新打开「提交日志」核对。");
                }
            } else message.append("\n再打开「提交日志」可补传 CarLife 附件。");
            return message.toString();
        } catch (Exception failure) {
            return "\nCarLife 附件未传完，再打开「提交日志」可补传。";
        }
    }

    private static String uploadHotspotAttachments(final MainActivity activity, final String reportId) {
        try {
            JSONObject info = HotspotArtifactUpload.collect(activity, reportId, new HotspotArtifactUpload.Progress() {
                public void onArtifact(String kind, final int index, final int total) {
                    activity.runOnUiThread(new Runnable() { public void run() {
                        activity.showOperation("日志已收到，正在补传热点附件（" + index + "/" + total + "）…");
                    }});
                }
            });
            int uploaded = info.optInt("uploaded"), reused = info.optInt("reused");
            int missing = info.optInt("missing"), failed = info.optInt("failed");
            final boolean complete = "complete".equals(info.optString("status"));
            final boolean stored = complete && completePendingAttachment(activity, PENDING_HOTSPOT_ATTACHMENTS, reportId);
            if (!complete) {
                MainActivity.record(activity, "HOTSPOT_ARTIFACT_UPLOAD",
                    new JSONObject().put("status", "partial").put("count", failed));
            }
            JSONArray files = info.optJSONArray("artifacts");
            JSONObject traffic = files == null ? null : files.optJSONObject(0);
            String trafficStatus = traffic == null ? "" : traffic.optString("status");
            boolean trafficReceived = traffic != null && "trafficstats-apk".equals(traffic.optString("kind")) &&
                ("uploaded".equals(trafficStatus) || "reused".equals(trafficStatus));
            return (trafficReceived ? "\n已确认收到车载流量 APK。" : "\n车载流量 APK 暂未确认收到。") +
                "\n热点附件已确认 " + (uploaded + reused) + " 项，其中复用 " + reused + " 项。" +
                (missing == 0 ? "" : " 系统没有提供 " + missing + " 项文件。") +
                (complete ? (stored ? "" : " 车机暂未写入附件完成状态，可重新打开「提交日志」核对。") :
                    " " + failed + " 项失败，再打开「提交日志」可补传。");
        } catch (Exception failure) {
            return "\n补传热点附件失败，再打开「提交日志」可重试。";
        }
    }

    private static FilteredEvents filterEvents(java.util.List<DiagnosticLog.Entry> source) throws Exception {
        final int maximumEvents = 1200;
        final int maximumBytes = 640 * 1024;
        FilteredEvents result = new FilteredEvents();
        ArrayList<EventCandidate> selected = new ArrayList<EventCandidate>();
        HashSet<String> selectedIds = new HashSet<String>();
        long first = Long.MAX_VALUE;
        for (DiagnosticLog.Entry row : source) first = Math.min(first, row.time);
        if (first == Long.MAX_VALUE) first = 0;
        int bytes = 0;
        int videoReceiveWindows = 0;
        int videoRenderWindows = 0;
        int priorityLimit = 400;
        int priorityBytes = 192 * 1024;
        // Preserve one complete CAN and motion capture alongside restart and connection evidence.
        int canBytes = 0;
        int canEvents = 0;
        int compatibilityBytes = 0;
        int compatibilityEvents = 0;
        int wheelEvents = 0, audioEvents = 0;
        for (int pass = 0; pass < 5; pass++) {
            int budget = Math.min(maximumBytes, pass == 0 ? 48 * 1024 : pass == 1 ? bytes + 320 * 1024
                    : pass == 2 ? bytes + 80 * 1024
                    : pass == 3 ? canBytes + compatibilityBytes + priorityBytes : maximumBytes);
            int limit = pass == 0 ? 64 : pass == 1 ? selected.size() + 380
                    : pass == 2 ? selected.size() + 104
                    : pass == 3 ? canEvents + compatibilityEvents + priorityLimit : maximumEvents;
            int parsed = 0;
            int startBytes = bytes;
            int startCount = selected.size();
            for (int i = source.size() - 1; i >= 0 && selected.size() < limit &&
                    budget - bytes >= 128 && parsed < limit + 128; i--) {
                DiagnosticLog.Entry row = source.get(i);
                if (selectedIds.contains(row.id) || !REPORT_EVENT_TYPE.matcher(row.type).matches()) continue;
                if (pass == 0 && !isRestartEvidence(row.type) ||
                        pass == 1 && !"VEHICLE_CAN_EVIDENCE".equals(row.type) ||
                        pass == 2 && !CompatibilityEvidence.isWheel(row.type) &&
                            !CompatibilityEvidence.isAudio(row.type) && !row.alsaDiagnostic ||
                        pass == 3 && !isHighValueDiagnostic(row.type)) continue;
                JSONObject event;
                try {
                    event = filteredEvent(row, first);
                } catch (org.json.JSONException invalidRow) {
                    continue;
                }
                String type = event.getString("type");
                boolean wheel = CompatibilityEvidence.isWheel(type);
                boolean audio = CompatibilityEvidence.isAudio(type);
                if (pass == 2 && !(wheel && wheelEvents < 32 || audio && audioEvents < 72)) continue;
                parsed++;
                String eventType = event.getJSONObject("data").optString("eventType");
                boolean videoReceive = VIDEO_RX_WINDOW.equals(eventType);
                boolean videoRender = VIDEO_RENDER_WINDOW.equals(eventType);
                if ((videoReceive && videoReceiveWindows >= MAX_VIDEO_WINDOWS_PER_STAGE) ||
                        (videoRender && videoRenderWindows >= MAX_VIDEO_WINDOWS_PER_STAGE)) continue;
                int size = event.toString().getBytes("UTF-8").length + 2;
                if (size > budget - bytes) continue;
                bytes += size;
                if (videoReceive) videoReceiveWindows++;
                if (videoRender) videoRenderWindows++;
                selectedIds.add(row.id);
                selected.add(new EventCandidate(i, event, row.id));
                if (pass == 2) {
                    if (wheel) wheelEvents++;
                    if (audio) audioEvents++;
                }
            }
            if (pass == 1) {
                canBytes = bytes - startBytes;
                canEvents = selected.size() - startCount;
            } else if (pass == 2) {
                compatibilityBytes = bytes - startBytes;
                compatibilityEvents = selected.size() - startCount;
            }
        }
        java.util.Collections.sort(selected, new java.util.Comparator<EventCandidate>() {
            public int compare(EventCandidate a, EventCandidate b) { return a.index - b.index; }
        });
        for (EventCandidate item : selected) {
            result.events.put(item.event);
            result.acknowledgedRows.put(new JSONObject().put("id", item.id));
        }
        return result;
    }

    private static boolean isRestartEvidence(String type) {
        return "PROCESS_RECOVERY".equals(type) || "JAVA_UNCAUGHT_EXCEPTION".equals(type)
            || "BOOT_AUTOSTART".equals(type) || "CONNECTION_FAILURE".equals(type)
            || "HYBRID_RUNTIME_RECOVERY".equals(type) || "HYBRID_REPORT_UPLOAD".equals(type);
    }

    private static JSONObject filteredEvent(DiagnosticLog.Entry row, long first) throws Exception {
        String type = row.type;
        JSONObject detail = row.detail();
        String detailText = detail == null ? "" : detail.optString("detail", "");
        long offset = HybridReportPayload.boundedOffset(row.time, first);
        if ("MEDIA".equals(type) && isAlsaDiagnostic(detailText)) {
            return new JSONObject().put("offsetMs", offset).put("type", "ALSA_CAPTURE")
                .put("data", parseAlsaDiagnostic(detailText));
        }
        JSONObject safe = new JSONObject();
        if (detail != null) {
            Iterator<?> keys = detail.keys();
            while (keys.hasNext()) {
                String key = String.valueOf(keys.next());
                Object value = detail.opt(key);
                if (NUMERIC_FIELDS.contains(key) && value instanceof Number) {
                    double n = ((Number) value).doubleValue();
                    if (!Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= 1000000000d) safe.put(key, value);
                } else if (BOOLEAN_FIELDS.contains(key) && value instanceof Boolean) safe.put(key, value);
                else if (ENUM_FIELDS.contains(key) && value instanceof String && SAFE_TOKEN.matcher((String) value).matches()) safe.put(key, value);
                else if ("CONNECTION_FAILURE".equals(type) && "failureMessage".equals(key) && value instanceof String && SAFE_FAILURE_MESSAGE.matcher((String) value).matches()) safe.put(key, value);
                else if (ALSA_SUMMARY_FIELDS.contains(key) && value instanceof String && SAFE_ALSA_SUMMARY.matcher((String) value).matches()) safe.put(key, value);
            }
        }
        String videoEvent = videoWindowEvent(type, detailText);
        if (videoEvent != null) {
            appendVideoWindow(safe, videoEvent, detailText);
            return new JSONObject().put("offsetMs", offset).put("type", type).put("data", safe);
        }
        String code = diagnosticCode(type, detailText);
        if (!code.isEmpty()) safe.put("diagnosticCode", code);
        String musicEvent = musicDiagnosticEvent(type, detailText);
        if (!musicEvent.isEmpty()) safe.put("eventType", musicEvent);
        String summary = diagnosticSummary(type, detailText, !musicEvent.isEmpty() || isSpeechDiagnostic(type));
        if (!summary.isEmpty()) safe.put("diagnosticSummary", summary);
        CompatibilityEvidence.addRuntimeContext(type, detail, safe);
        if ("PROCESS_RECOVERY".equals(type) && detail != null) {
            String continuity = detail.optString("recoveryContinuity");
            if (SAFE_TOKEN.matcher(continuity).matches()) {
                safe.put("outcome", continuity);
                String timing = "previousBootElapsedMs=" + detail.optLong("previousBootElapsedMs", -1)
                    + " currentBootElapsedMs=" + detail.optLong("currentBootElapsedMs", -1)
                    + " bootIdCompared=" + detail.optBoolean("bootIdCompared")
                    + " systemServerCompared=" + detail.optBoolean("systemServerCompared")
                    + " zygoteCompared=" + detail.optBoolean("zygoteCompared");
                if (detail.has("systemServerChanged")) {
                    timing += " systemServerChanged=" + detail.optBoolean("systemServerChanged");
                }
                if (detail.has("zygoteChanged")) {
                    timing += " zygoteChanged=" + detail.optBoolean("zygoteChanged");
                }
                if (detail.has("previousStageAgeMs")) {
                    timing += " previousStageAgeMs=" + detail.optLong("previousStageAgeMs");
                }
                String previous = safe.optString("diagnosticSummary");
                if (!previous.isEmpty()) timing += " " + previous.substring(0, Math.min(previous.length(), 900));
                safe.put("diagnosticSummary", timing);
            }
        }
        if (CompatibilityReportPolicy.isNetworkStackEvent(type)) {
            String networkDetail = CompatibilityReportPolicy.safeNetworkDetail(detailText);
            if (!networkDetail.isEmpty()) safe.put("networkDetail", networkDetail);
        }
        return new JSONObject().put("offsetMs", offset).put("type", type).put("data", safe);
    }

    private static String videoWindowEvent(String type, String detail) {
        if ("AIRPLAY".equals(type) && detail.startsWith("Receive: video eventType=" + VIDEO_RX_WINDOW + " ")) {
            return VIDEO_RX_WINDOW;
        }
        if ("MEDIA".equals(type) && detail.startsWith("video_progress eventType=" + VIDEO_RENDER_WINDOW + " ")) {
            return VIDEO_RENDER_WINDOW;
        }
        return null;
    }

    private static void appendVideoWindow(JSONObject out, String event, String detail) throws Exception {
        out.put("eventType", event);
        boolean receive = VIDEO_RX_WINDOW.equals(event);
        Set<String> fields = receive ? VIDEO_RECEIVE_FIELDS : VIDEO_RENDER_FIELDS;
        java.util.regex.Matcher matcher = DIAGNOSTIC_TOKEN.matcher(detail);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);
            if (receive && "ended".equals(key) && ("true".equals(value) || "false".equals(value))) {
                out.put(key, Boolean.parseBoolean(value));
            } else if (fields.contains(key) && MUSIC_SUMMARY_NUMBER.matcher(value).matches()) {
                long count = Long.parseLong(value);
                if (count >= 0 && count <= 1000000000L) out.put(key, count);
            }
        }
        long windowMs = out.optLong("windowMs");
        if (!receive && windowMs > 0 && out.has("submittedFrames") && !out.has("submittedFpsMilli")) {
            long fpsMilli = out.optLong("submittedFrames") * 1000000L / windowMs;
            if (fpsMilli <= 1000000000L) out.put("submittedFpsMilli", fpsMilli);
        }
        // Older servers drop newly introduced structured keys. Preserve the same bounded,
        // allowlisted measurements in the existing compatible summary column.
        StringBuilder summary = new StringBuilder("eventType=").append(event);
        for (String key : fields) {
            if (out.has(key)) summary.append(' ').append(key).append('=').append(out.getLong(key));
        }
        if (out.has("ended")) summary.append(" ended=").append(out.getBoolean("ended"));
        out.put("diagnosticSummary", summary.toString());
    }

    private static boolean isAlsaDiagnostic(String text) {
        return text.startsWith("microphone_alsa_") || text.startsWith("microphone_duplex_evidence ") || text.startsWith("microphone_capture_path=") || text.startsWith("microphone_capture_fallback ");
    }

    private static boolean isSpeechDiagnostic(String type) {
        return "AUDIO_SPEECH_PLAYOUT".equals(type) || "AUDIO_SPEECH_RECEIVE".equals(type);
    }

    private static boolean isHighValueDiagnostic(String type) {
        return "VIDEO_CALIBRATION_SAMPLE".equals(type) || "VIDEO_CALIBRATION_RESULT".equals(type) ||
            "VEHICLE_CAPABILITY_CHECK".equals(type) || "VEHICLE_INTEGRATION".equals(type) ||
            "VEHICLE_YAW_ESTIMATE".equals(type) ||
            "TOUCH_MULTITOUCH".equals(type) ||
            "WHEEL_KEY".equals(type) || "WHEEL_CONTROL".equals(type) || "USB_DEVICE".equals(type) ||
            "APP_STARTUP".equals(type) || "BOOT_AUTOSTART".equals(type) || "USB_DEFAULT_HANDLER".equals(type) ||
            "USB_PERMISSION_REQUEST".equals(type) || "USB_PERMISSION_RESULT".equals(type) || "USB_PERMISSION_WAIT".equals(type) ||
            "USBMUX_WRITE_TIMING".equals(type) || "NCM_WRITE_TIMING".equals(type) ||
            "START_SESSION_QUEUE".equals(type) || "CONNECTION_PREWARM".equals(type) ||
            "USB_PRESENCE".equals(type) || "USB_BT_SCAN".equals(type) || "USB_BT_CONNECT_SCAN".equals(type) || "USB_BT_PERMISSION".equals(type) || "USB_BT_PROBE".equals(type) || "USB_BT_STACK".equals(type) || "USB_BT_STACK_READY".equals(type) || "USB_BT_HCI".equals(type) || "FAILED".equals(type) || "DISCONNECTED".equals(type) || "VIDEO_READY".equals(type) ||
            "CONNECTION_FAILURE".equals(type) || "HYBRID_PROJECTION_STATE".equals(type) || "HYBRID_AIRPLAY_ACCEPT".equals(type) || "PROJECTION_STARTUP_STEP".equals(type) ||
            "HYBRID_WIFI_PEER".equals(type) || "HYBRID_WIFI_ENDPOINT".equals(type) || "HYBRID_WIFI_LISTENER".equals(type) || "HYBRID_PATH_PROBE".equals(type) || "HYBRID_WIFI_CREDENTIAL".equals(type) || "HYBRID_START_SESSION_SENT".equals(type) || "HYBRID_CARPLAY_AVAILABILITY".equals(type) ||
            "HYBRID_WIRELESS_UPDATE".equals(type) || "HYBRID_CONTROL_END".equals(type) || "HYBRID_WIFI".equals(type) || "WIFI_WAIT".equals(type) || "HYBRID_USB_DETACHED".equals(type) || "HYBRID_WIFI_SESSION_END".equals(type) ||
            "HYBRID_MDNS".equals(type) || "HYBRID_MDNS_STATE".equals(type) || "HYBRID_IAP_TUNNEL".equals(type) ||
             "HYBRID_IAP2_IDENTITY".equals(type) || "HYBRID_IAP2_IDENTIFICATION".equals(type) || "HYBRID_PREPARE_STEP".equals(type) || "HYBRID_TRANSPORT_IDENTIFIER".equals(type) || "HYBRID_IAP2_RX".equals(type) ||
            "PROCESS_RECOVERY".equals(type) || "JAVA_UNCAUGHT_EXCEPTION".equals(type) || "CONNECTION_CLEAN_EXIT".equals(type) ||
            "AUDIO_ROUTING".equals(type) || "AUDIO_FOCUS".equals(type) || "MICROPHONE_UPLINK".equals(type) ||
            "MICROPHONE_INPUT_LEVELS".equals(type) || isSpeechDiagnostic(type) || "AIRPLAY_FAILURE_RAW".equals(type) ||
            "HYBRID_REPORT_UPLOAD".equals(type) || type.startsWith("CALL_");
    }

    private static boolean isTransportDiagnostic(String type) {
        return "CONNECT_SUMMARY".equals(type) || "CONNECT_TIMING".equals(type) ||
            "CONNECT_START".equals(type) || "CONNECT_BUILD".equals(type) || "NETWORK_BACKEND_SELECTED".equals(type) || "HYBRID_TRANSPORT".equals(type) ||
            "START_SESSION_RECOVERY".equals(type) || "USB".equals(type) ||
            "USB_NATIVE".equals(type) || "NCM".equals(type) || "MUX".equals(type) || "USB_TAKEOVER".equals(type) ||
            "USB_PERMISSION".equals(type) || "PAIRING".equals(type) || "PAIRING_RETRY".equals(type) ||
            "REENUMERATION".equals(type) || "WAIT_USB".equals(type) || "PHONE_START".equals(type) ||
            "AIRPLAY".equals(type) || "AIRPLAY_HANDSHAKE".equals(type) || "AIRPLAY_ACTIVE".equals(type) || "VIDEO_SURFACE".equals(type) || "VIDEO_CONFIG".equals(type) || "MEDIA".equals(type) || "HYBRID_IAP2".equals(type) || "HYBRID_WIFI_HANDOFF".equals(type) || "CARKIT".equals(type) ||
            "AIRPLAY_ENDED".equals(type) || "CONTROL_ENDED".equals(type) || "WIFI_WAIT".equals(type) || "HYBRID_WIFI".equals(type) || "IAP2_CHANNEL_CLOSED_WIFI_WAIT".equals(type) || "USB_DETACHED_WIFI_WAIT".equals(type) || "STOPPED".equals(type) ||
            CompatibilityReportPolicy.isNetworkStackEvent(type);
    }

    private static String diagnosticCode(String type, String text) {
        if (text == null || text.length() == 0 || !(isHighValueDiagnostic(type) || isTransportDiagnostic(type))) return "";
        if ("CONNECT_TIMING".equals(type) || isSpeechDiagnostic(type) ||
                ("AIRPLAY".equals(type) && text.startsWith("audio eventType=audio_rx_window "))) return "";
        String value = text.toLowerCase(Locale.US);
        if (value.contains("errno=")) return "errno";
        if (value.contains("timed out") || value.contains("timeout")) return "timeout";
        if (value.contains("reset by the peer") || value.contains("peer reset")) return "peer_reset";
        if (value.contains("permission") || value.contains("denied")) return "permission_denied";
        if (value.contains("could not claim") || value.contains("claim failed")) return "interface_claim_failed";
        if (value.contains("not found") || value.contains("missing") || value.contains("no free")) return "component_missing";
        if (value.contains("invalid") || value.contains("protocol")) return "protocol_error";
        if (value.contains("disconnected") || value.contains("closed")) return "link_closed";
        if (type.equals("FAILED") || type.equals("CONNECTION_FAILURE")) return "connection_failed";
        return "";
    }

    private static String musicDiagnosticEvent(String type, String text) {
        if ("AIRPLAY".equals(type) && text.startsWith("audio eventType=audio_rx_window ") &&
                text.contains(" role=media ")) return "music_rx_window";
        if (!"MEDIA".equals(type)) return "";
        if (text.startsWith("audio_music_prefill ")) return "music_prefill";
        if (text.startsWith("audio_music_starved ")) return "music_starved";
        if (text.startsWith("audio_music_timeline_reset ")) return "music_timeline_reset";
        if (text.startsWith("audio_music_clock ")) return "music_clock";
        if (text.startsWith("audio_progress role=media ") || text.startsWith("audio_checkpoint role=media ")) return "music_progress";
        if (text.startsWith("audio_summary role=media ")) return "music_end";
        return "";
    }

    private static String diagnosticSummary(String type, String text, boolean audioStats) {
        if (text == null || text.length() == 0 || "USB_NETWORK".equals(type) || !(isHighValueDiagnostic(type) || isTransportDiagnostic(type))) return "";
        java.util.regex.Matcher matcher = DIAGNOSTIC_TOKEN.matcher(text);
        boolean micTiming = ("MEDIA".equals(type) || "MICROPHONE_UPLINK".equals(type)) && text.startsWith("microphone_send_timing ");
        boolean micLevels = "MICROPHONE_INPUT_LEVELS".equals(type) && text.startsWith("microphone_input_levels ");
        boolean mediaDetail = "AIRPLAY".equals(type) || "MEDIA".equals(type) || micTiming || micLevels || isSpeechDiagnostic(type);
        StringBuilder summary = new StringBuilder();
        int summaryLimit = micTiming ? 720 : 600;
        while (matcher.find() && summary.length() < summaryLimit) {
            String key = matcher.group(1), value = matcher.group(2);
            boolean startupKey = mediaDetail &&
                ("eventType".equals(key) || "reason".equals(key) || "codec".equals(key) || "width".equals(key) ||
                 "height".equals(key) || "dataPort".equals(key) || "streamType".equals(key) || "surfaceValid".equals(key) ||
                 "frameCount".equals(key) || "outputSize".equals(key) || "outputFlags".equals(key) || "statusCode".equals(key) ||
                 "audioType".equals(key) || "kind".equals(key) || "role".equals(key) || "accepted".equals(key) ||
                 "success".equals(key) || "rtpClockHz".equals(key) || "microphone".equals(key) ||
                 "interArrivalMaxMs".equals(key) || "seqForwardGaps".equals(key) || "lateOrDuplicate".equals(key) ||
                 "readMaxMs".equals(key) || "processMaxUs".equals(key) || "seqGapMax".equals(key) ||
                 "seqGapEvents".equals(key) || "ended".equals(key) || "authFailuresTotal".equals(key) ||
                 "invalidPacketsTotal".equals(key) || "decryptAvgUs".equals(key) || "decryptMaxUs".equals(key) ||
                 "rxBufferOriginalBytes".equals(key) || "rxBufferRequestedBytes".equals(key) || "rxBufferActualBytes".equals(key) ||
                 "queueSize".equals(key) || "queueDrops".equals(key) || "trackQueuedMs".equals(key) ||
                 "emptyQueuePolls".equals(key) || "targetPackets".equals(key) || "maxSendGapMs".equals(key) ||
                 "windowMs".equals(key) || "packets".equals(key) || "mutedPackets".equals(key) ||
                 "writeMaxUs".equals(key) || "dryForMs".equals(key) || "rebufferCount".equals(key));
            boolean musicNumber = audioStats && MUSIC_SUMMARY_FIELDS.contains(key) && MUSIC_SUMMARY_NUMBER.matcher(value).matches();
            boolean micNumber = micTiming && MICROPHONE_TIMING_FIELDS.contains(key) && MUSIC_SUMMARY_NUMBER.matcher(value).matches();
            boolean micEncoder = micTiming && "micEncoder".equals(key) &&
                ("native_opus".equals(value) || "java_concentus".equals(value));
            boolean micLevel = micLevels &&
                (MICROPHONE_LEVEL_FIELDS.contains(key) && MICROPHONE_LEVEL_CHANNELS.matcher(value).matches() ||
                 ("micInputFrames".equals(key) || "micInputChannels".equals(key)) && MUSIC_SUMMARY_NUMBER.matcher(value).matches());
            if ((!DIAGNOSTIC_SUMMARY_FIELDS.contains(key) && !startupKey && !musicNumber && !micNumber && !micEncoder && !micLevel)
                    || value.length() > 48 || !value.matches("[A-Za-z0-9_.:/+-]+")) continue;
            if (summary.length() + key.length() + value.length() + 2 > summaryLimit) continue;
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
        String highPass = tokens.optString("dsp");
        if ("duplex".equals(event) && highPass.matches("hpfHz=[0-9]{1,3}")) {
            StringBuilder dsp = new StringBuilder(highPass);
            for (String key : new String[] {"noiseFloorRms", "gateGain", "filteredRms", "outputRms"}) {
                String value = tokens.optString(key);
                if (value.matches("[0-9]{1,5}")) dsp.append(' ').append(key).append('=').append(value);
            }
            out.put("diagnosticSummary", dsp.toString());
        }
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

    private static JSONObject readCatalog(MainActivity activity, int installedVersionCode) throws Exception {
        JSONObject result = post("/catalog",
            new JSONObject().put("profile", profile(activity)).put("installedVersionCode", installedVersionCode),
            activity, CATALOG_DEADLINE_MS, MAX_CATALOG_RESPONSE);
        if (!"hybrid-usb-wifi".equals(result.optString("productId")) || result.optJSONArray("versions") == null) {
            throw new Exception("服务器返回的无线版本目录无效");
        }
        return result;
    }

    private static JSONObject postReport(final MainActivity activity, JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes("UTF-8");
        showReportProgress(activity, "正在上传日志（约 " + ((bytes.length + 1023) / 1024) + " KB）…");
        boolean retried = false;
        while (true) {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                return post("/report", bytes, null, TRANSFER_DEADLINE_MS, 65536);
            } catch (Exception failure) {
                boolean retry = !retried && android.os.SystemClock.elapsedRealtime() - started < 15000L &&
                    isTransientReportFailure(failure);
                recordReportFailure(activity, retry ? "retrying_transport" : "send_failed", failure, bytes.length);
                if (!retry) throw failure;
                retried = true;
                showReportProgress(activity, "上传连接短暂中断，正在重试同一份日志…");
                Thread.sleep(750);
            }
        }
    }

    private static void showReportProgress(final MainActivity activity, final String message) {
        activity.runOnUiThread(new Runnable() { public void run() {
            if (!activity.closed && !activity.isFinishing()) activity.showOperation(message);
        } });
    }

    private static boolean isTransientReportFailure(Exception failure) {
        if (failure instanceof ReportHttpFailure) {
            int status = ((ReportHttpFailure) failure).status;
            return status == 408 || status == 502 || status == 503 || status == 504;
        }
        return failure instanceof SocketException || failure instanceof EOFException ||
            failure instanceof SocketTimeoutException;
    }

    private static void recordReportFailure(Context context, String stage, Exception failure, int bytes) {
        try {
            JSONObject fields = new JSONObject().put("stage", stage).put("payloadBytes", bytes)
                .put("errorType", failure.getClass().getSimpleName());
            if (failure instanceof ReportHttpFailure) fields.put("resultCode", ((ReportHttpFailure) failure).status);
            MainActivity.record(context, "HYBRID_REPORT_UPLOAD", fields);
        } catch (Exception ignored) { }
    }

    private static final class ReportHttpFailure extends IOException {
        final int status;

        ReportHttpFailure(int status, String reason) {
            super(reason);
            this.status = status;
        }
    }

    private static JSONObject post(String path, JSONObject payload, MainActivity activity,
                                   long timeoutMs, int maxResponseBytes) throws Exception {
        return post(path, payload.toString().getBytes("UTF-8"), activity, timeoutMs, maxResponseBytes);
    }

    private static JSONObject post(String path, byte[] bytes, MainActivity activity,
                                   long timeoutMs, int maxResponseBytes) throws Exception {
        if (bytes.length > 7 * 1024 * 1024) throw new Exception("Report too large");
        HttpURLConnection c = open(path);
        NetworkDeadline deadline = new NetworkDeadline(c, timeoutMs);
        if (activity != null) OnlineUpdate.track(activity, deadline);
        try {
            deadline.check();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            java.io.OutputStream out = c.getOutputStream();
            try {
                for (int offset = 0; offset < bytes.length; offset += 16384) {
                    deadline.check();
                    out.write(bytes, offset, Math.min(16384, bytes.length - offset));
                }
            } finally { out.close(); }
            int code = c.getResponseCode();
            deadline.check();
            if (code == 413) throw new ReportHttpFailure(code, "HTTP 413，服务器拒绝报告大小（" + bytes.length + " 字节）");
            if (code != 200) {
                String detail = readHttpError(c);
                throw new ReportHttpFailure(code, "HTTP " + code + (detail.length() == 0 ? "" : "，" + detail) +
                    "（" + bytes.length + " 字节）");
            }
            byte[] response = readBounded(c.getInputStream(), maxResponseBytes);
            deadline.check();
            return new JSONObject(new String(response, "UTF-8"));
        } catch (Exception failure) {
            deadline.check();
            throw failure;
        } finally {
            if (activity == null) deadline.close();
            else OnlineUpdate.release(activity, deadline);
        }
    }

    private static String readHttpError(HttpURLConnection c) {
        InputStream in = null;
        try {
            in = c.getErrorStream();
            if (in == null) return "";
            byte[] body = readBounded(in, 4096);
            String text = new String(body, "UTF-8").trim();
            try {
                JSONObject json = new JSONObject(text);
                String value = json.optString("detail", json.optString("message", json.optString("error", "")));
                if (value.length() > 0) text = value;
            } catch (Exception ignored) { }
            StringBuilder safe = new StringBuilder();
            for (int i = 0; i < text.length() && safe.length() < 240; i++) {
                char ch = text.charAt(i);
                if (ch >= 0x20 && ch != 0x7f) safe.append(ch);
            }
            return safe.toString().trim();
        } catch (Exception ignored) {
            try { if (in != null) in.close(); } catch (Exception ignoredAgain) { }
            return "server response body unavailable";
        }
    }

    private static HttpURLConnection open(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(API + path).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(90000); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
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
        return lowerHex(digest.digest());
    }

    private static String lowerHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value & 255) >>> 4, 16));
            out.append(Character.forDigit(value & 15, 16));
        }
        return out.toString();
    }

    private static boolean sameSigningIdentity(android.content.pm.Signature[] installed,
                                               android.content.pm.Signature[] candidate) {
        if (installed == null || candidate == null || installed.length == 0 || installed.length != candidate.length) return false;
        boolean[] matched = new boolean[candidate.length];
        for (android.content.pm.Signature current : installed) {
            boolean found = false;
            for (int i = 0; i < candidate.length; i++) {
                if (!matched[i] && current.equals(candidate[i])) {
                    matched[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static void finishError(final MainActivity activity, final String prefix, final Exception error) {
        activity.runOnUiThread(new Runnable() { public void run() {
            activity.busy = false;
            if (!activity.closed) {
                String reason = error.getMessage();
                if (reason == null || reason.length() > 320) reason = error.getClass().getSimpleName();
                activity.showOperation(prefix + "：" + reason);
                new AlertDialog.Builder(activity).setTitle("未完成").setMessage(prefix + "\n" + reason)
                    .setPositiveButton("确定", null).show();
            }
        }});
    }
}
