package local.headunit.diplay;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Runs short, comparable tests on independent CarPlay connections, on the Activity thread. */
final class VideoCalibrationPanel {
    private static final long POLL_MS = 180L;
    private static final long RELEASE_TIMEOUT_MS = 20_000L;
    private static final long CONNECT_TIMEOUT_MS = 65_000L;
    private static final long PREPARE_MS = 7_000L;
    private static final long MOTION_LEAD_IN_MS = 1_600L;
    private static final long SAMPLE_MS = 15_000L;
    private static final long SAMPLE_COMPLETION_MARGIN_MS = 40L;

    enum Group {
        BASIC("基础帧率", false),
        HIGH("高帧率", true);

        final String label;
        private final int[] rates;

        Group(String label, boolean high) {
            this.label = label;
            this.rates = PlaybackOptions.calibrationRates(high);
        }

        String describeRates() {
            StringBuilder description = new StringBuilder();
            for (int rate : rates) {
                if (description.length() > 0) description.append('、');
                description.append(rate);
            }
            return description.append(" FPS").toString();
        }
    }

    private enum Phase { IDLE, RELEASE, WAIT_VIDEO, PREPARE, START_MOTION, SAMPLE, RESULT, RESTORE }

    private final MainActivity activity;
    private final List<VideoCalibration.Sample> samples = new ArrayList<>(4);
    private final Runnable update = new Runnable() { public void run() { tick(); } };
    private Phase phase = Phase.IDLE;
    private Group group;
    private int[] rates;
    private UsbDevice adapter;
    private int leg;
    private long deadline;
    private String trialId = "";
    private boolean originalTrial;
    private LinearLayout guide;
    private TextView hint;
    private Button cancelButton;
    private AlertDialog resultDialog;

    VideoCalibrationPanel(MainActivity activity) { this.activity = activity; }

    boolean isActive() { return phase != Phase.IDLE; }

    void attach(FrameLayout root) {
        guide = new LinearLayout(activity);
        guide.setGravity(Gravity.CENTER_VERTICAL);
        guide.setPadding(activity.dp(12), 0, activity.dp(4), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xe6101d2b);
        background.setCornerRadius(activity.dp(12));
        guide.setBackgroundDrawable(background);
        hint = new TextView(activity);
        hint.setTextColor(0xfff4f8fb);
        hint.setTextSize(14);
        guide.addView(hint);
        cancelButton = new Button(activity);
        cancelButton.setText("取消测试");
        cancelButton.setTextSize(13);
        cancelButton.setAllCaps(false);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { cancel(); }
        });
        LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-2, activity.dp(48));
        button.leftMargin = activity.dp(8);
        guide.addView(cancelButton, button);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        layout.setMargins(activity.dp(10), activity.dp(8), activity.dp(10), 0);
        root.addView(guide, layout);
        guide.setVisibility(View.GONE);
    }

    void start(Group group) {
        if (isActive() || activity.closed || !activity.resumed || !activity.videoReady || !WiredService.isVideoReady()) return;
        adapter = authorizedAdapter(WiredService.currentUsbBluetoothDevice());
        if (adapter == null) {
            activity.showOperation("无法确认当前 USB 蓝牙适配器。请重新连上 CarPlay 后再测试。");
            return;
        }
        int currentFps = WiredService.currentFps();
        String currentId = WiredService.currentConnectionId();
        if (currentId.isEmpty() || !activity.connectionId.equals(currentId) || currentFps != PlaybackOptions.normalize(currentFps)) {
            activity.showOperation("当前投屏尚未稳定，请稍后重新开始对比。");
            return;
        }
        this.group = group;
        rates = group.rates.clone();
        samples.clear();
        leg = 0;
        int liveIndex = Arrays.binarySearch(rates, currentFps);
        if (liveIndex < 0) {
            activity.stopConnectionForCalibration();
            prepareNextConnection();
            return;
        }
        System.arraycopy(rates, 0, rates, 1, liveIndex);
        rates[0] = currentFps;
        trialId = currentId;
        originalTrial = true;
        activity.setProjectionMode(true);
        prepareMotion();
        schedule(0);
    }

    private UsbDevice authorizedAdapter(UsbDevice expected) {
        UsbManager usb = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        UsbDevice current = MainActivity.currentUsbDevice(usb, expected);
        return current != null && UsbBluetoothProbe.isCandidate(current) && usb.hasPermission(current) ? current : null;
    }

    private void prepareNextConnection() {
        phase = Phase.RELEASE;
        deadline = SystemClock.elapsedRealtime() + RELEASE_TIMEOUT_MS;
        showGuide(progress() + " · 正在重连", true);
        schedule(0);
    }

    private String progress() { return (leg + 1) + "/" + rates.length + " · " + rates[leg] + " FPS"; }

    private void prepareMotion() {
        phase = Phase.PREPARE;
        deadline = SystemClock.elapsedRealtime() + PREPARE_MS;
        showGuide(progress() + " · 请打开同一个列表，7 秒后开始", true);
    }

    private void schedule(long delayMs) {
        activity.uiHandler.removeCallbacks(update);
        if (!activity.closed && activity.resumed && isActive()) activity.uiHandler.postDelayed(update, delayMs);
    }

    private void tick() {
        if (activity.closed || !activity.resumed) return;
        long now = SystemClock.elapsedRealtime();
        switch (phase) {
            case RELEASE:
                if (now >= deadline) { showResults("上一轮连接未能及时释放。"); return; }
                if (WiredService.isBusy()) { schedule(POLL_MS); return; }
                UsbDevice current = authorizedAdapter(adapter);
                if (current == null) { showResults("USB 蓝牙适配器已断开或失去授权。"); return; }
                phase = Phase.WAIT_VIDEO;
                deadline = now + CONNECT_TIMEOUT_MS;
                activity.connectPort(current, rates[leg]);
                if (!activity.connecting) { completeTrial(null); return; }
                trialId = activity.connectionId;
                break;
            case WAIT_VIDEO:
                if (now >= deadline) { completeTrial(null); return; }
                if (currentVideoIsReady()) prepareMotion();
                break;
            case PREPARE:
                if (!currentVideoIsReady()) { completeTrial(null); return; }
                if (now >= deadline) {
                    phase = Phase.START_MOTION;
                    deadline = now + MOTION_LEAD_IN_MS;
                    showGuide(progress() + " · 现在持续上下滑动，直到本轮结束", true);
                }
                break;
            case START_MOTION:
                if (!currentVideoIsReady()) { completeTrial(null); return; }
                if (now >= deadline) {
                    // Gather data only after the operator has seen the instruction and can start moving.
                    hideGuide();
                    if (!WiredService.beginVideoCalibration(trialId)) { completeTrial(null); return; }
                    phase = Phase.SAMPLE;
                    // Capture uses nanoTime and closes its own 15-second window; avoid rounding short here.
                    deadline = SystemClock.elapsedRealtime() + SAMPLE_MS + SAMPLE_COMPLETION_MARGIN_MS;
                }
                break;
            case SAMPLE:
                if (!currentVideoIsReady()) { completeTrial(null); return; }
                if (now >= deadline) { completeTrial(WiredService.finishVideoCalibration(trialId)); return; }
                break;
            case RESTORE:
                if (now >= deadline) { finishRestore("连接资源释放超时，请点击「连接」重试。"); return; }
                if (WiredService.isBusy()) { schedule(POLL_MS); return; }
                UsbDevice restoreAdapter = authorizedAdapter(adapter);
                if (restoreAdapter == null) { finishRestore("请接回 USB 蓝牙适配器，然后点击「连接」。"); return; }
                finishRestore(null);
                activity.connectPort(restoreAdapter);
                return;
            default:
                return;
        }
        schedule(POLL_MS);
    }

    private boolean currentVideoIsReady() {
        return !trialId.isEmpty() && activity.videoReady && WiredService.isVideoReady()
            && trialId.equals(WiredService.currentConnectionId());
    }

    boolean connectionEnded(String connectionId) {
        if (trialId.isEmpty() || !trialId.equals(connectionId)) return false;
        completeTrial(null);
        return true;
    }

    private void completeTrial(VideoCalibration.Sample sample) {
        if (sample == null) sample = new VideoCalibration.Sample(rates[leg], 0, 0, 0, null, null, 0, 0, 0, 0, false, null);
        samples.add(sample);
        stopTrial();
        if (++leg < rates.length) prepareNextConnection();
        else showResults(null);
    }

    private void stopTrial() {
        String current = WiredService.currentConnectionId();
        boolean ownsCurrent = current.isEmpty() || trialId.equals(current);
        if (clearTrial() && ownsCurrent) activity.stopConnectionForCalibration();
    }

    private boolean clearTrial() {
        if (trialId.isEmpty()) return false;
        WiredService.cancelVideoCalibration(trialId);
        trialId = "";
        originalTrial = false;
        return true;
    }

    private void showResults(String additionalNote) {
        activity.uiHandler.removeCallbacks(update);
        stopTrial();
        if (!activity.resumed || activity.closed) { restore(); return; }
        phase = Phase.RESULT;
        hideGuide();
        final VideoCalibration.Result result = VideoCalibration.evaluate(samples, group.rates);
        final Integer recommended = result.getRecommendedFps();
        logResult(result);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
            .setTitle(group.label + "对比结果")
            .setMessage(resultText(result, additionalNote))
            .setNegativeButton("保留设置并连接", null);
        if (recommended != null) dialog.setPositiveButton("应用 " + recommended + " FPS 并连接",
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface value, int which) {
                    if (phase != Phase.RESULT || activity.closed || !activity.resumed) return;
                    PlaybackOptions.save(activity, recommended);
                    activity.updateFrameRateButton();
                }
            });
        resultDialog = dialog.create();
        resultDialog.setCanceledOnTouchOutside(false);
        resultDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface dialog) {
                if (resultDialog == dialog) resultDialog = null;
                if (phase == Phase.RESULT && !activity.closed) restore();
            }
        });
        resultDialog.show();
    }

    private String resultText(VideoCalibration.Result result, String additionalNote) {
        StringBuilder text = new StringBuilder();
        Integer recommended = result.getRecommendedFps();
        if (recommended == null) text.append("没有取得足够的稳定数据，建议保留当前设置。");
        else text.append(result.getProvisional() ? "根据已有样本，暂推荐 " : "推荐 ").append(recommended).append(" FPS。");
        for (VideoCalibration.Evaluation evaluation : result.getEvaluations()) {
            VideoCalibration.Sample sample = evaluation.getSample();
            text.append("\n\n").append(sample.getRequestedFps()).append(" FPS · ").append(reason(evaluation.getReason()));
            if (!sample.getCompleted() || sample.getDurationMs() <= 0) continue;
            double receivedFps = sample.getReceivedFrames() * 1000.0 / sample.getDurationMs();
            double submittedFps = sample.getSurfaceSubmittedFrames() * 1000.0 / sample.getDurationMs();
            text.append(String.format(Locale.ROOT, "\n从 iPhone 接收 %.1f / 提交屏幕 %.1f FPS · 提交最长间隔 %d ms",
                receivedFps, submittedFps, sample.getSurfaceGapMaxMs()));
        }
        if (additionalNote != null) text.append("\n\n").append(additionalNote);
        return text.append("\n\n接收统计来自网络视频流，提交统计来自车机解码输出。屏幕实际显示帧数未测量。\n当前设置：")
            .append(PlaybackOptions.read(activity)).append(" FPS").toString();
    }

    private static String reason(VideoCalibration.Reason reason) {
        switch (reason) {
            case SUSTAINED: return "持续稳定";
            case INCOMPLETE: return "连接或采样未完成";
            case MOTION_UNVERIFIED:
            case INSUFFICIENT_MOTION_EVIDENCE: return "有效画面变化不足";
            case LOW_SOURCE_FRAME_RATE: return "实际接收帧率不足";
            case LOW_AVERAGE_RATE:
            case LOW_WORST_WINDOW_RATE: return "帧率不足";
            case LONG_FRAME_GAP: return "出现明显长间隔";
            case PRESENTATION_PRESSURE: return "积压或跳帧过多";
            case HIGH_PIPELINE_DELAY: return "处理延迟偏高";
            default: return "证据不足";
        }
    }

    private void logResult(VideoCalibration.Result result) {
        Integer recommended = result.getRecommendedFps();
        try {
            MainActivity.record(activity, "VIDEO_CALIBRATION_RESULT", new JSONObject().put("stage", "result")
                .put("diagnosticSummary", "recommendedFps=" + (recommended == null ? 0 : recommended)
                    + " status=" + result.getStatus().name() + " provisional=" + result.getProvisional()));
        } catch (Exception ignored) {}
    }

    private void showGuide(String message, boolean allowCancel) {
        if (guide == null) return;
        hint.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        if (!message.isEmpty()) hint.setText(message);
        cancelButton.setVisibility(allowCancel ? View.VISIBLE : View.GONE);
        guide.setVisibility(View.VISIBLE);
    }

    private void hideGuide() { if (guide != null) guide.setVisibility(View.GONE); }

    void cancel() {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.RESTORE) { finishRestore("测试已退出，可点击「连接」。"); return; }
        restore();
    }

    private void restore() {
        if (originalTrial && currentVideoIsReady()) {
            clearTrial();
            finishRestore(null);
            return;
        }
        stopTrial();
        phase = Phase.RESTORE;
        if (resultDialog != null) resultDialog.dismiss();
        deadline = SystemClock.elapsedRealtime() + RELEASE_TIMEOUT_MS;
        showGuide("正在按所选帧率重新连接…", false);
        schedule(0);
    }

    private void finishRestore(String message) {
        phase = Phase.IDLE;
        adapter = null;
        activity.uiHandler.removeCallbacks(update);
        hideGuide();
        if (message != null) activity.showOperation(message);
    }

    void onPause() {
        if (!isActive()) return;
        restore();
        hideGuide();
    }

    void onResume() {
        if (phase != Phase.RESTORE) return;
        deadline = SystemClock.elapsedRealtime() + RELEASE_TIMEOUT_MS;
        showGuide("正在按所选帧率重新连接…", false);
        schedule(0);
    }

    void destroy() {
        if (originalTrial) clearTrial();
        else stopTrial();
        phase = Phase.IDLE;
        if (resultDialog != null) resultDialog.dismiss();
        adapter = null;
        activity.uiHandler.removeCallbacks(update);
        hideGuide();
    }
}
