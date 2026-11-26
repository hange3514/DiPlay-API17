package local.headunit.diplay;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.SystemClock;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONObject;

/** A bounded, operator-started snapshot using the existing CarPlay connection. */
final class VehicleCapabilityCheck {
    private static final long SAMPLE_MS = VehicleMotionProbe.DURATION_MS;

    private final MainActivity activity;
    private final Runnable returnToReport;
    private final VehicleSignalProbe signals;
    private final TextView body;
    private final Spinner factoryCluster;
    private final AlertDialog dialog;
    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); }
    };
    private final long startedAt = SystemClock.elapsedRealtime();
    private final String connection = WiredService.currentConnectionId();
    private final int[] initialRouteCounts = WiredService.routeMetadataCounts();
    private int[] routeCounts = initialRouteCounts;
    private String siri = WiredService.siriDiagnosticSummary();
    private Api17PlaybackCapability.Result playback;
    private ClusterLinkInventory.Result clusterLink;
    private boolean connectionChanged;
    private boolean stored;
    private boolean closed;

    VehicleCapabilityCheck(MainActivity activity, Runnable returnToReport) {
        this.activity = activity;
        this.returnToReport = returnToReport;
        signals = new VehicleSignalProbe(activity);
        body = new TextView(activity);
        int padding = activity.dp(18);
        body.setTextSize(15);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        TextView question = new TextView(activity);
        question.setText("原车高德播报转向时，仪表或 HUD 有没有显示箭头或距离？");
        content.addView(question);
        factoryCluster = new Spinner(activity);
        ArrayAdapter<String> answers = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
            new String[]{"未试或原车高德还没播报", "有箭头或距离", "有播报，仪表无提示"});
        answers.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        factoryCluster.setAdapter(answers);
        content.addView(factoryCluster);
        content.addView(body);
        ScrollView viewport = new ScrollView(activity);
        viewport.addView(content);
        dialog = new AlertDialog.Builder(activity).setTitle("车机能力检测").setView(viewport)
            .setNegativeButton("返回日志", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface ignored, int which) {
                    if (!activity.closed && activity.resumed) returnToReport.run();
                }
            }).setPositiveButton("结束并记录", null).create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface ignored) {
                close();
                if (activity.vehicleCapabilityCheck == VehicleCapabilityCheck.this) {
                    activity.vehicleCapabilityCheck = null;
                }
            }
        });
    }

    void start() {
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (stored) {
                    dialog.dismiss();
                    if (!activity.closed && activity.resumed) returnToReport.run();
                } else finish("user_finished");
            }
        });
        signals.start();
        try {
            activity.worker.execute(new Runnable() { @Override public void run() {
                final Api17PlaybackCapability.Result result = Api17PlaybackCapability.inspect(activity.getApplicationContext());
                final ClusterLinkInventory.Result link = ClusterLinkInventory.inspect(activity.getApplicationContext());
                activity.uiHandler.post(new Runnable() { @Override public void run() {
                    if (closed || stored) return;
                    playback = result;
                    clusterLink = link;
                    render();
                }});
            }});
        } catch (RejectedExecutionException shuttingDown) {
            // The report leaves the component inventory incomplete if the Activity is shutting down.
        }
        update();
    }

    private void update() {
        if (closed || stored) return;
        updateSession();
        if (elapsed() >= SAMPLE_MS) {
            finish("timeout_complete");
            return;
        }
        render();
        activity.uiHandler.postDelayed(tick, 1000);
    }

    private void updateSession() {
        if (connection.isEmpty() || connectionChanged) return;
        if (!connection.equals(WiredService.currentConnectionId())) {
            connectionChanged = true;
            return;
        }
        routeCounts = WiredService.routeMetadataCounts();
        siri = WiredService.siriDiagnosticSummary();
    }

    private void render() {
        long seconds = Math.max(0L, (SAMPLE_MS - elapsed() + 999) / 1000);
        String instruction = stored ? "结果已加入本地日志。请点「去提交日志」。"
            : "检测剩余 " + seconds + " 秒。请在停车时打开检测。可静置采集传感器基线，切换大灯后复原。想验证导航数据，可先在 CarPlay 启动导航再打开此页。";
        String routeStatus = connection.isEmpty() ? "检测开始时尚未连接" :
            "导航更新 " + routeCounts[0] + " 条 / 转向更新 " + routeCounts[1] + " 条" +
                (connectionChanged ? "（连接中断，保留中断前统计）" : "");
        body.setText(instruction + "\n\n" +
            (clusterLink == null ? "正在查询仪表接口…" : clusterLink.display) + "\n" +
            "iPhone 导航数据：" + routeStatus + "\n\n" +
            signals.display() + "\n\n" +
            (playback == null ? "正在查询系统编解码能力…" : playback.display));
    }

    static String factoryClusterObservation(int selected) {
        if (selected == 1) return "shown";
        if (selected == 2) return "no_prompt_during_guidance";
        return "not_tested";
    }

    private long elapsed() { return SystemClock.elapsedRealtime() - startedAt; }

    private void finish(String outcome) {
        if (closed || stored) return;
        updateSession();
        signals.close();
        activity.uiHandler.removeCallbacks(tick);
        try {
            record("vehicle_signals", signals.snapshot(), outcome);
            record("vehicle_runtime", WiredService.vehicleDiagnosticSummary(), outcome);
            record("cluster_link", (clusterLink == null ? "navigationLink=incomplete" : clusterLink.summary) +
                " factoryCluster=" + factoryClusterObservation(factoryCluster.getSelectedItemPosition()), outcome);
            record("navigation", "navSession=" +
                (connection.isEmpty() ? "absent" : connectionChanged ? "changed" : "present") +
                " routeUpdates=" + routeCounts[0] + " maneuverUpdates=" + routeCounts[1] +
                " routeDuring=" + Math.max(0, routeCounts[0] - initialRouteCounts[0]) +
                " maneuverDuring=" + Math.max(0, routeCounts[1] - initialRouteCounts[1]), outcome);
            record("playback", playback == null ? "codecCheck=incomplete" : playback.summary, outcome);
            record("siri", siri, outcome);
            for (String sample : signals.canCapture.sampleRows()) recordCan("can_sample", sample, outcome);
            for (String marker : signals.canCapture.contextRows()) recordCan("can_context", marker, outcome);
            recordCan("can_capture", signals.canCapture.summary(), outcome);
            for (String row : signals.motion.reportRows(signals.canCapture.scanId())) {
                recordCan("motion_sample", row, outcome);
            }
            recordCan("motion_inventory", signals.motionInventory(), outcome);
            stored = true;
            factoryCluster.setEnabled(false);
            render();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("去提交日志");
        } catch (Exception failure) {
            body.setText("写入检测结果失败，可以再次点「结束并记录」重试。");
        }
    }

    private void record(String stage, String summary, String outcome) throws Exception {
        record("VEHICLE_CAPABILITY_CHECK", stage, summary, outcome);
    }

    private void recordCan(String stage, String summary, String outcome) throws Exception {
        record("VEHICLE_CAN_EVIDENCE", stage, summary, outcome);
    }

    private void record(String event, String stage, String summary, String outcome) throws Exception {
        MainActivity.record(activity, event, new JSONObject().put("stage", stage)
            .put("outcome", outcome).put("durationMs", elapsed()).put("readOnly", true)
            .put("diagnosticSummary", summary));
    }

    void dismiss() {
        close();
        dialog.dismiss();
    }

    private void close() {
        if (closed) return;
        closed = true;
        activity.uiHandler.removeCallbacks(tick);
        signals.close();
    }
}
