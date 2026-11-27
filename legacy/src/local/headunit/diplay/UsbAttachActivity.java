package local.headunit.diplay;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Keeps USB authorization in the foreground until its system result arrives. */
public final class UsbAttachActivity extends Activity {
    private static final long PERMISSION_TIMEOUT_MS = 45000L;
    private static final long DEVICE_CHECK_INTERVAL_MS = 500L;
    private static final long MISSING_DEVICE_SETTLE_MS = 1000L;
    private static final ExecutorService USB_EVENT_LOGGER = Executors.newSingleThreadExecutor();
    private final long activityStartedAt = SystemClock.elapsedRealtime();
    private static volatile boolean permissionGuard;
    private static volatile long activityPermissionDeniedAt;
    private static UsbDevice activityPermissionDeniedDevice;

    static synchronized boolean beginPermissionFlow() {
        if (permissionGuard) return false;
        activityPermissionDeniedAt = 0L;
        activityPermissionDeniedDevice = null;
        permissionGuard = true;
        return true;
    }

    static synchronized void endPermissionFlow(UsbDevice deniedDevice) {
        if (deniedDevice != null) {
            activityPermissionDeniedAt = SystemClock.elapsedRealtime();
            activityPermissionDeniedDevice = deniedDevice;
        }
        permissionGuard = false;
    }

    static boolean permissionFlowActive() { return permissionGuard; }

    static synchronized boolean consumeRecentActivityPermissionDenial(UsbDevice device) {
        if (!sameDevice(activityPermissionDeniedDevice, device)) return false;
        boolean recent = SystemClock.elapsedRealtime() - activityPermissionDeniedAt <= PERMISSION_TIMEOUT_MS;
        activityPermissionDeniedAt = 0L;
        activityPermissionDeniedDevice = null;
        return recent;
    }

    private final Handler handler = new Handler();
    private UsbManager usb;
    private UsbDevice pendingDevice;
    private UsbDevice queuedDevice;
    private BroadcastReceiver permissionReceiver;
    private boolean receiverRegistered;
    private boolean requestPending;
    private boolean completed;
    private boolean ownsPermissionFlow;
    private long requestStartedAt;
    private long missingSince = -1L;
    private String permissionAction;
    private int permissionRequestId;

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (!requestPending || completed) return;
            requestPending = false;
            record("permission_wait_timeout", pendingDevice, SystemClock.elapsedRealtime() - requestStartedAt);
            if (continueWithReplacement()) return;
            if (!isDevicePresent(pendingDevice)) { finishAndStartWatcher(); return; }
            if (usb.hasPermission(pendingDevice)) { finishAndStartWatcher(); return; }
            releasePermissionFlow(true);
            AutoUsbService.pauseForDevice(UsbAttachActivity.this, pendingDevice);
            finishQuietly();
        }
    };

    private final Runnable checkDevice = new Runnable() {
        @Override public void run() {
            if (!requestPending || completed) return;
            final long now = SystemClock.elapsedRealtime();
            try {
                if (isDevicePresent(pendingDevice)) {
                    missingSince = -1L;
                    if (usb.hasPermission(pendingDevice)) {
                        record("permission_observed_granted", pendingDevice, now - requestStartedAt);
                        finishAndStartWatcher();
                        return;
                    }
                } else {
                    if (missingSince < 0L) missingSince = now;
                    if (now - missingSince >= MISSING_DEVICE_SETTLE_MS) {
                        record("permission_device_missing", pendingDevice, now - requestStartedAt);
                        if (!continueWithReplacement()) finishAndStartWatcher();
                        return;
                    }
                }
            } catch (RuntimeException error) {
                record("permission_status_error", pendingDevice, now - requestStartedAt);
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this, DEVICE_CHECK_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        permissionAction = getPackageName() + ".USB_ATTACH_PERMISSION." + UUID.randomUUID();
        handle(getIntent());
    }

    @Override protected void onPause() {
        record("activity_paused", pendingDevice,
                requestPending ? SystemClock.elapsedRealtime() - requestStartedAt : 0L);
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        record("activity_resumed", pendingDevice,
                requestPending ? SystemClock.elapsedRealtime() - requestStartedAt : 0L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        UsbDevice next = attachedAppleDevice(intent);
        if (next == null) return;
        if (completed) {
            AutoUsbService.start(this);
            return;
        }
        if (requestPending) {
            if (!sameDevice(pendingDevice, next)) queuedDevice = next;
            handler.removeCallbacks(checkDevice);
            handler.post(checkDevice);
            return;
        }
        processDevice(next);
    }

    private void handle(Intent intent) {
        UsbDevice device = attachedAppleDevice(intent);
        if (device == null) {
            completed = true;
            finish();
            return;
        }
        processDevice(device);
    }

    private UsbDevice attachedAppleDevice(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) return null;
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        return device != null && device.getVendorId() == 0x05ac ? device : null;
    }

    private void processDevice(UsbDevice device) {
        pendingDevice = device;
        record("attach_received", device, 0L);
        try {
            if (!isDevicePresent(device)) {
                record("stale_attach_ignored", device, 0L);
                finishAndStartWatcher();
                return;
            }
            // A default USB-handler Activity is also launched for iPhone mode re-enumeration.
            // Check the new device's grant before consulting the connection service: the service
            // may still be waiting on the previous UsbDevice instance while Android has already
            // granted this replacement enumeration. Showing our own foreground wait page here
            // can cover the system permission sheet that the service is waiting for.
            if (usb != null && usb.hasPermission(device)) {
                record("permission_already_granted", device, 0L);
                finishAndStartWatcher();
                return;
            }
            for (UsbDevice present : usb.getDeviceList().values()) {
                if (present.getVendorId() == 0x05ac && !sameDevice(present, device)) {
                    record("permission_deferred_multiple_devices", device, 0L);
                    finishAndStartWatcher();
                    return;
                }
            }
            if (WiredService.awaitingPermission() || (!ownsPermissionFlow && permissionFlowActive())) {
                record("permission_flow_owned_elsewhere", device, 0L);
                finishQuietly();
                return;
            }
            requestPermission(device);
        } catch (Throwable error) {
            record("permission_request_error", device, 0L);
            releasePermissionFlow(true);
            AutoUsbService.pauseForDevice(this, device);
            finishQuietly();
        }
    }

    private void requestPermission(UsbDevice device) {
        pendingDevice = device;
        queuedDevice = null;
        if (!ownsPermissionFlow && !beginPermissionFlow()) {
            record("permission_flow_owned_elsewhere", device, 0L);
            finishQuietly();
            return;
        }
        ownsPermissionFlow = true;
        requestPending = true;
        requestStartedAt = SystemClock.elapsedRealtime();
        missingSince = -1L;
        showWaiting("\u6b63\u5728\u7b49\u5f85\u8f66\u673a USB \u6388\u6743\u7a97\u53e3\u3002\u8bf7\u70b9\u51fb\u7cfb\u7edf\u7a97\u53e3\u4e2d\u7684\u201c\u5141\u8bb8\u201d\uff0c\u5e94\u7528\u4f1a\u81ea\u52a8\u7ee7\u7eed\u3002");
        registerPermissionReceiver();
        Intent permission = new Intent(permissionAction).setPackage(getPackageName())
                .putExtra("request_id", ++permissionRequestId);
        PendingIntent result = PendingIntent.getBroadcast(this, permissionRequestId, permission, PendingIntent.FLAG_ONE_SHOT);
        usb.requestPermission(device, result);
        record("permission_requested", device, 0L);
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, PERMISSION_TIMEOUT_MS);
        handler.removeCallbacks(checkDevice);
        handler.postDelayed(checkDevice, DEVICE_CHECK_INTERVAL_MS);
    }

    private void registerPermissionReceiver() {
        if (receiverRegistered) return;
        permissionReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!requestPending || completed) return;
                UsbDevice resultDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                    if (sameDevice(pendingDevice, resultDevice)) {
                        handler.removeCallbacks(checkDevice);
                        handler.post(checkDevice);
                    }
                    return;
                }
                if (!permissionAction.equals(intent.getAction())) return;
                if (intent.getIntExtra("request_id", 0) != permissionRequestId || !sameDevice(pendingDevice, resultDevice)) {
                    record("stale_result_ignored", resultDevice, 0L);
                    return;
                }
                requestPending = false;
                handler.removeCallbacks(timeout);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                long waited = Math.max(0L, SystemClock.elapsedRealtime() - requestStartedAt);
                record(granted ? "permission_granted" : "permission_denied", pendingDevice, waited);
                if (continueWithReplacement()) return;
                if (!isDevicePresent(pendingDevice)) { finishAndStartWatcher(); return; }
                if (!granted && !usb.hasPermission(pendingDevice)) {
                    releasePermissionFlow(true);
                    AutoUsbService.pauseForDevice(UsbAttachActivity.this, pendingDevice);
                    finishQuietly();
                    return;
                }
                finishAndStartWatcher();
            }
        };
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(permissionReceiver, filter);
        receiverRegistered = true;
    }

    private boolean isDevicePresent(UsbDevice device) {
        return device != null && usb != null && sameDevice(device, usb.getDeviceList().get(device.getDeviceName()));
    }

    private boolean continueWithReplacement() {
        UsbDevice next = queuedDevice;
        if (isDevicePresent(pendingDevice) || !isDevicePresent(next)) return false;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(checkDevice);
        record("replacement_received", next, 0L);
        processDevice(next);
        return true;
    }

    private void showWaiting(String message) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(30), dp(24), dp(30), dp(24));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xf0122030);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0xff36aeca);
        card.setBackgroundDrawable(cardBg);
        TextView title = new TextView(this);
        title.setText("DiPlay USB \u6388\u6743");
        title.setTextColor(0xfff1f7fc);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = new TextView(this);
        detail.setText(message);
        detail.setTextColor(0xffc1d1df);
        detail.setTextSize(15);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(14), 0, 0);
        card.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0x99040a11);
        frame.addView(card, new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels - dp(48), dp(520)), -2, Gravity.CENTER));
        setContentView(frame);
    }

    private void finishAndStartWatcher() {
        if (completed) return;
        completed = true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        releasePermissionFlow(false);
        cleanupReceiver();
        handler.removeCallbacksAndMessages(null);
        AutoUsbService.start(this);
        finish();
    }

    private void finishQuietly() {
        if (completed) return;
        completed = true;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        releasePermissionFlow(false);
        cleanupReceiver();
        handler.removeCallbacksAndMessages(null);
        finish();
    }

    private void releasePermissionFlow(boolean denied) {
        if (!ownsPermissionFlow) return;
        ownsPermissionFlow = false;
        endPermissionFlow(denied ? pendingDevice : null);
    }

    private void cleanupReceiver() {
        if (!receiverRegistered || permissionReceiver == null) return;
        try { unregisterReceiver(permissionReceiver); } catch (Exception ignored) { }
        receiverRegistered = false;
    }

    private void record(String stage, UsbDevice device, long waitedMs) {
        try {
            final JSONObject event = new JSONObject().put("stage", stage)
                    .put("elapsedMs", Math.max(0L, SystemClock.elapsedRealtime() - activityStartedAt))
                    .put("waitMs", Math.max(0L, waitedMs));
            if (device != null) event.put("deviceVid", device.getVendorId()).put("devicePid", device.getProductId());
            final Context app = getApplicationContext();
            USB_EVENT_LOGGER.execute(new Runnable() {
                @Override public void run() {
                    try { MainActivity.record(app, "USB_DEFAULT_HANDLER", event); }
                    catch (Exception ignored) { }
                }
            });
        } catch (Exception ignored) { }
    }

    static boolean sameDevice(UsbDevice left, UsbDevice right) {
        return left != null && right != null && left.getVendorId() == right.getVendorId()
                && left.getProductId() == right.getProductId()
                && left.getDeviceName().equals(right.getDeviceName());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (requestPending && isDevicePresent(pendingDevice)) {
            releasePermissionFlow(true);
            AutoUsbService.pauseForDevice(this, pendingDevice);
        }
        finishQuietly();
    }

    @Override protected void onDestroy() {
        if (!completed) releasePermissionFlow(false);
        handler.removeCallbacksAndMessages(null);
        cleanupReceiver();
        super.onDestroy();
    }
}
