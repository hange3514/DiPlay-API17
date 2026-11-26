package local.headunit.diplay;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Short, user-initiated checks. Call start, display, snapshot and close on the main thread. */
final class VehicleSignalProbe implements java.io.Closeable {
    private static final String OEM_NIGHT = "android.NaviOne.CldStdLightReceiver";
    private static final String OEM_APP_EVENT = "APP_REV_DATA";
    private static final String OEM_CAN_EVENT = "INTENT_CAN_DATA";
    private static final String OEM_CAN_PACKAGE = "com.byd.canservice";
    private static final long GPS_FIX_MAX_AGE_NS = 15_000_000_000L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    final CanBroadcastCapture canCapture = new CanBroadcastCapture();
    final VehicleMotionProbe motion = new VehicleMotionProbe();
    private boolean started;
    private boolean closed;
    private boolean lightReceiverRegistered;
    private boolean canReceiverRegistered;
    private boolean gpsListenerRegistered;
    private boolean lightSensorRegistered;
    private boolean canPackagePresent;
    private long startedAt;
    private LocationManager location;
    private SensorManager sensors;

    private String gpsState = "unchecked";
    private int gpsFreshFixes;
    private int gpsRejectedFixes;
    private int gpsSpeedFixes;
    private int gpsMovingFixes;
    private int gpsBearingFixes;
    private String oemLight = "unknown";
    private int oemLightEvents;
    private int oemLightChanges;
    private int oemBacklightEvents;
    private int systemNight;
    private int systemNightChanges;
    private boolean lightSensorPresent;
    private int lightSensorSamples;
    private int lightSensorChanges;
    private float lastLight;
    private String canState = "unchecked";

    VehicleSignalProbe(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        if (started || closed) return;
        started = true;
        startedAt = SystemClock.elapsedRealtime();
        systemNight = readSystemNight();
        try {
            IntentFilter lights = new IntentFilter(OEM_NIGHT);
            lights.addAction(OEM_APP_EVENT);
            context.registerReceiver(lightReceiver, lights, null, main);
            lightReceiverRegistered = true;
        } catch (RuntimeException ignored) {
            // The summary distinguishes a registered but quiet listener from a registration failure.
        }
        startGps();
        startLightSensor();
        startCanSample();
        motion.start(context, main, startedAt);
    }

    private void startGps() {
        try {
            location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (location == null || !location.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                gpsState = "no_provider";
                return;
            }
            if (context.checkCallingOrSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                gpsState = "permission_denied";
                return;
            }
            gpsState = location.isProviderEnabled(LocationManager.GPS_PROVIDER) ? "waiting_fix" : "disabled";
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener, main.getLooper());
            gpsListenerRegistered = true;
        } catch (SecurityException denied) {
            gpsState = "permission_denied";
        } catch (RuntimeException failure) {
            gpsState = "error";
        }
    }

    private void startLightSensor() {
        try {
            sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            Sensor sensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
            lightSensorPresent = sensor != null;
            if (sensor != null) {
                lightSensorRegistered = sensors.registerListener(lightSensor, sensor, SensorManager.SENSOR_DELAY_NORMAL, main);
            }
        } catch (RuntimeException ignored) {
            // Some legacy firmware exposes the sensor but refuses the listener.
        }
    }

    private void startCanSample() {
        try {
            context.getPackageManager().getPackageInfo(OEM_CAN_PACKAGE, 0);
            canPackagePresent = true;
        } catch (PackageManager.NameNotFoundException absent) {
            canState = "no_service";
            return;
        } catch (RuntimeException failure) {
            canState = "error";
            return;
        }
        if (!"MX9A0".equalsIgnoreCase(Build.MODEL)) {
            canState = "different_model";
            return;
        }
        try {
            context.registerReceiver(canReceiver, new IntentFilter(OEM_CAN_EVENT), null, main);
            canReceiverRegistered = true;
            canState = "sampling";
        } catch (RuntimeException denied) {
            canState = "error";
        }
    }

    private final BroadcastReceiver lightReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (closed) return;
            try {
                if (OEM_NIGHT.equals(intent.getAction())) {
                    String mode = intent.getStringExtra("CLDLIGHT");
                    if (!"MODE_NIGHT".equals(mode) && !"MODE_DAY".equals(mode)) return;
                    String next = "MODE_NIGHT".equals(mode) ? "night" : "day";
                    if (!next.equals(oemLight)) {
                        if (oemLightEvents > 0) oemLightChanges++;
                        canCapture.mark(elapsedMs(), mode);
                    }
                    oemLight = next;
                    oemLightEvents++;
                } else if (OEM_APP_EVENT.equals(intent.getAction())) {
                    byte[] payload = intent.getByteArrayExtra("list");
                    // Stock McuControl emits this two-byte invalidation after changing its backlight level.
                    if (payload != null && payload.length == 2 && payload[0] == 6 && payload[1] == 6) {
                        oemBacklightEvents++;
                    }
                }
            } catch (RuntimeException ignored) {
                // A malformed intent does not constitute a hardware observation.
            }
        }
    };

    private final BroadcastReceiver canReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (closed || !canReceiverRegistered || !OEM_CAN_EVENT.equals(intent.getAction())) return;
            try {
                canCapture.observe(elapsedMs(), intent.getIntArrayExtra("list"));
            } catch (RuntimeException ignored) {
                canCapture.observe(elapsedMs(), null);
            }
        }
    };

    private long elapsedMs() { return SystemClock.elapsedRealtime() - startedAt; }

    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location fix) {
            if (closed || fix == null || !LocationManager.GPS_PROVIDER.equals(fix.getProvider())) return;
            long tick = fix.getElapsedRealtimeNanos();
            long now = SystemClock.elapsedRealtimeNanos();
            if (tick <= 0 || tick > now || now - tick > GPS_FIX_MAX_AGE_NS) {
                gpsRejectedFixes++;
                return;
            }
            gpsFreshFixes++;
            gpsState = "fresh_fix";
            float speed = fix.getSpeed();
            if (fix.hasSpeed() && finite(speed) && speed >= 0f) {
                gpsSpeedFixes++;
                if (speed > 0.5f) gpsMovingFixes++;
            }
            float bearing = fix.getBearing();
            if (fix.hasBearing() && finite(bearing) && bearing >= 0f && bearing < 360f) gpsBearingFixes++;
        }

        @Override public void onProviderEnabled(String provider) {
            if (!closed && LocationManager.GPS_PROVIDER.equals(provider)) gpsState = "waiting_fix";
        }

        @Override public void onProviderDisabled(String provider) {
            if (!closed && LocationManager.GPS_PROVIDER.equals(provider)) gpsState = "disabled";
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    private final SensorEventListener lightSensor = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            if (closed || event.values.length == 0) return;
            float lux = event.values[0];
            if (!finite(lux) || lux < 0f) return;
            if (lightSensorSamples > 0 && lux != lastLight) lightSensorChanges++;
            lightSensorSamples++;
            lastLight = lux;
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private int readSystemNight() {
        return context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    }

    private void refreshSystemNight() {
        if (!started || closed) return;
        int value = readSystemNight();
        if (systemNight != value) systemNightChanges++;
        systemNight = value;
    }

    private String systemNightLabel() {
        if (systemNight == Configuration.UI_MODE_NIGHT_YES) return "night";
        if (systemNight == Configuration.UI_MODE_NIGHT_NO) return "day";
        return "undefined";
    }

    String snapshot() {
        refreshSystemNight();
        return "gpsState=" + gpsState + " gpsFixes=" + gpsFreshFixes + " gpsRejected=" + gpsRejectedFixes
                + " gpsSpeedFixes=" + gpsSpeedFixes + " gpsMovingFixes=" + gpsMovingFixes + " gpsBearingFixes=" + gpsBearingFixes
                + " oemLightRx=" + lightReceiverRegistered + " oemLight=" + oemLight + " oemLightEvents=" + oemLightEvents
                + " oemLightChanges=" + oemLightChanges + " oemBacklight=" + oemBacklightEvents
                + " systemNight=" + systemNightLabel() + " systemNightChanges=" + systemNightChanges
                + " lightSensor=" + lightSensorPresent + " lightSensorSamples=" + lightSensorSamples + " lightSensorChanges=" + lightSensorChanges
                + " canPackage=" + canPackagePresent + " canState=" + canState + " canFrames=" + canCapture.observed()
                + " " + canCapture.summary()
                + " wheelSpeed=unverified tractionSoc=unverified";
    }

    String display() {
        refreshSystemNight();
        String gpsDisplay;
        if ("fresh_fix".equals(gpsState)) gpsDisplay = "收到 " + gpsFreshFixes + " 次新定位";
        else if ("no_provider".equals(gpsState)) gpsDisplay = "系统没有 GPS 提供者";
        else if ("permission_denied".equals(gpsState)) gpsDisplay = "系统未授予精确定位权限";
        else if ("disabled".equals(gpsState)) gpsDisplay = "系统 GPS 已关闭";
        else if ("error".equals(gpsState)) gpsDisplay = "读取 GPS 接口失败";
        else gpsDisplay = "正在等待新定位，可在室外静置一会儿";

        String lightDisplay = oemLightEvents == 0 ? (lightReceiverRegistered ? "等候原车广播" : "无法注册监听")
                : ("night".equals(oemLight) ? "夜间" : "日间") + "，切换 " + oemLightChanges + " 次";
        String canDisplay = canCapture.observed() > 0 ? "收到 " + canCapture.observed() + " 条广播，保留 " + canCapture.saved() + " 条原始样本"
                : ("sampling".equals(canState) ? "正在监听原车广播" : "本轮未观测到信号");
        if ("no_service".equals(canState)) canDisplay = "未发现原车 CAN 服务包";
        if ("different_model".equals(canState)) canDisplay = "未匹配 MX9A0 固件路径";
        if ("error".equals(canState)) canDisplay = "无法注册监听";
        long seconds = started ? (SystemClock.elapsedRealtime() - startedAt) / 1_000L : 0L;
        return "已采样 " + seconds + " 秒\n车机 GPS：" + gpsDisplay
                + "\nGPS 速度 / 航向样本：" + gpsSpeedFixes + " / " + gpsBearingFixes
                + "\n原车日夜模式：" + lightDisplay + "，背光刷新 " + oemBacklightEvents + " 次"
                + "\n系统日夜变化：" + systemNightChanges + " 次，环境光样本 " + lightSensorSamples + " 次"
                + "\n原车 CAN：" + canDisplay + "\n" + motion.display(canState, canCapture.observed())
                + "\n轮速与动力电池电量：数据来源待确认";
    }

    String motionInventory() {
        return motion.inventory(canCapture.scanId(), canState, canCapture.observed());
    }

    @Override public void close() {
        if (closed) return;
        refreshSystemNight();
        closed = true;
        motion.close();
        if (canReceiverRegistered) {
            canReceiverRegistered = false;
            canCapture.finishBucket();
            canState = canCapture.observed() == 0 ? "no_event" : "observed";
            try { context.unregisterReceiver(canReceiver); } catch (RuntimeException ignored) {}
        }
        if (lightReceiverRegistered) {
            try { context.unregisterReceiver(lightReceiver); } catch (RuntimeException ignored) {}
        }
        if (gpsListenerRegistered) {
            try { location.removeUpdates(gpsListener); } catch (RuntimeException ignored) {}
        }
        if (lightSensorRegistered) {
            try { sensors.unregisterListener(lightSensor); } catch (RuntimeException ignored) {}
        }
    }
}
