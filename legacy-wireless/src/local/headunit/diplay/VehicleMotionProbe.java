package local.headunit.diplay;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only, time-bounded sensor capture on the same clock as the OEM CAN sample. */
final class VehicleMotionProbe {
    static final int DURATION_MS = 60_000;
    private final Channel gyro = new Channel("android_gyro", "rad_s", 3);
    private final Channel esp = new Channel("vendor_yaw_candidate", "unknown", 1);
    private SensorManager manager;
    private boolean closed;
    private boolean featureDeclared;
    private int sensorCount = -1;
    private int yawCandidates;

    void start(Context context, Handler callbackHandler, long startedAt) {
        try {
            featureDeclared = context.getPackageManager().hasSystemFeature("android.hardware.sensor.gyroscope");
            manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (manager == null) {
                gyro.initialState = esp.initialState = "no_sensor_service";
                return;
            }
            List<Sensor> available = manager.getSensorList(Sensor.TYPE_ALL);
            sensorCount = available.size();
            Sensor yaw = null;
            for (Sensor sensor : available) {
                if (sensor.getType() == Sensor.TYPE_GYROSCOPE || !isYawRateLabel(sensor.getName())) continue;
                yawCandidates++;
                if (yaw == null || (!isEspLabel(yaw.getName()) && isEspLabel(sensor.getName()))) yaw = sensor;
            }
            gyro.start(manager, manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), callbackHandler, startedAt);
            esp.start(manager, yaw, callbackHandler, startedAt);
        } catch (RuntimeException failure) {
            gyro.noteLookupFailure();
            esp.noteLookupFailure();
        }
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    static boolean isYawRateLabel(String label) {
        String normalized = normalizeLabel(label);
        return normalized.contains("yawrate") || normalized.contains("横摆角速度")
            || normalized.contains("偏航角速度") || normalized.contains("偏航率");
    }

    private static boolean isEspLabel(String label) {
        return normalizeLabel(label).contains("esp");
    }

    String display(String canState, int canFrames) {
        String vendorStatus = esp.describe(closed);
        if (esp.valid > 0) vendorStatus += "，待确认是否来自 ESP";
        return "车机陀螺仪：" + gyro.describe(closed)
            + "\nESP 横摆：" + vendorStatus
            + (canFrames > 0 ? "，原厂 CAN 原始报文已保留" : "，CAN 状态 " + canState);
    }

    String inventory(String scan, String canState, int canFrames) {
        return "scan=" + scan + " androidSensorCount=" + sensorCount + " gyroFeature=" + featureDeclared
            + " yawNamedSensors=" + yawCandidates + " espCanRoute=unverified canState=" + canState
            + " canFrames=" + canFrames;
    }

    List<String> reportRows(String scan) {
        List<String> result = new ArrayList<String>();
        gyro.appendRows(result, scan, closed);
        esp.appendRows(result, scan, closed);
        return result;
    }

    void close() {
        if (closed) return;
        closed = true;
        gyro.stop(manager);
        esp.stop(manager);
    }

    static final class Channel implements SensorEventListener {
        private static final int BUCKET_MS = 200;
        // The report server limits a diagnosticSummary field to 768 characters.
        private static final int SAMPLES_PER_ROW = 7;
        private final String source;
        private final String unit;
        private final int requiredAxes;
        private final Sample[] samples = new Sample[DURATION_MS / BUCKET_MS];
        private Sensor sensor;
        private long startedAt;
        private boolean active;
        private boolean registered;
        private String initialState = "unchecked";
        private int valid;
        private int invalid;

        Channel(String source, String unit, int requiredAxes) {
            this.source = source;
            this.unit = unit;
            this.requiredAxes = requiredAxes;
        }

        void start(SensorManager manager, Sensor selected, Handler handler, long startMs) {
            sensor = selected;
            startedAt = startMs;
            if (sensor == null) {
                initialState = "no_sensor";
                return;
            }
            try {
                // This Android 4.2 overload delivers on the diagnostic's Handler.
                registered = manager.registerListener(this, sensor, 40_000, handler);
            } catch (RuntimeException failure) {
                initialState = "registration_error";
                return;
            }
            active = registered;
            initialState = registered ? "listening" : "registration_rejected";
        }

        void noteLookupFailure() {
            if ("unchecked".equals(initialState)) initialState = "lookup_error";
        }

        @Override public void onSensorChanged(SensorEvent event) {
            if (active && event.sensor == sensor) {
                observe(SystemClock.elapsedRealtime() - startedAt, event.timestamp, event.accuracy, event.values);
            }
        }

        @Override public void onAccuracyChanged(Sensor changed, int accuracy) {}

        void observe(long timeMs, long sensorTimeNs, int accuracy, float[] axes) {
            if (timeMs < 0 || timeMs >= DURATION_MS) return;
            if (axes == null || axes.length < requiredAxes) {
                invalid++;
                return;
            }
            int count = Math.min(3, axes.length);
            float peak = 0;
            for (int i = 0; i < count; i++) {
                float value = axes[i];
                if (Float.isNaN(value) || Float.isInfinite(value)) {
                    invalid++;
                    return;
                }
                peak = Math.max(peak, Math.abs(value));
            }
            valid++;
            int bucket = (int) timeMs / BUCKET_MS;
            Sample prior = samples[bucket];
            if (prior == null || peak > prior.peak) {
                samples[bucket] = new Sample(timeMs, sensorTimeNs, accuracy, peak, java.util.Arrays.copyOf(axes, count));
            }
        }

        void stop(SensorManager manager) {
            if (!active) return;
            active = false;
            try { manager.unregisterListener(this, sensor); } catch (RuntimeException ignored) {}
        }

        String state(boolean closed) {
            if (valid != 0) return "sampled";
            if (invalid != 0) return "invalid_samples";
            return closed && registered ? "no_sample" : initialState;
        }

        String describe(boolean closed) {
            if (valid > 0) return "收到 " + valid + " 条样本";
            switch (state(closed)) {
                case "no_sensor": return "系统未列出相应传感器";
                case "no_sensor_service": return "系统未提供传感器服务";
                case "lookup_error": return "传感器枚举失败";
                case "registration_error":
                case "registration_rejected": return "传感器监听注册失败";
                case "invalid_samples": return "回调数据无效";
                case "no_sample": return "已注册，本轮无样本";
                default: return "等待样本";
            }
        }

        void appendRows(List<String> result, String scan, boolean closed) {
            String prefix = "scan=" + scan + " source=" + source + " unit=" + unit;
            StringBuilder row = new StringBuilder();
            int count = 0;
            int retained = 0;
            for (Sample sample : samples) {
                if (sample == null) continue;
                if (row.length() > 0) row.append(';');
                row.append(sample.timeMs).append(',').append(sample.sensorTimeNs).append(',').append(sample.accuracy);
                for (float axis : sample.axes) row.append(',').append(axis);
                count++;
                retained++;
                if (count == SAMPLES_PER_ROW) {
                    result.add(prefix + " values=[" + row + "]");
                    count = 0;
                    row.setLength(0);
                }
            }
            if (count > 0) result.add(prefix + " values=[" + row + "]");
            result.add(prefix + " state=" + state(closed) + " registered=" + registered + " callbacks="
                + (valid + invalid) + " valid=" + valid + " invalid=" + invalid + " retained=" + retained
                + " axes=" + (requiredAxes == 3 ? "device_native_xyz" : "unverified")
                + " columns=arrival_elapsed_ms,sensor_time_ns,accuracy,values windowMs=" + BUCKET_MS
                + (sensor == null ? "" : " sensorType=" + sensor.getType() + " minDelayUs=" + sensor.getMinDelay()
                    + " name=" + reportLabel(sensor.getName()) + " vendor=" + reportLabel(sensor.getVendor())));
        }

        private static String reportLabel(String value) {
            if (value == null || value.isEmpty()) return "unknown";
            StringBuilder label = new StringBuilder();
            for (int i = 0; i < value.length() && label.length() < 80; i++) {
                char c = value.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.') label.append(c);
                else if (c <= 127) label.append('_');
                else label.append("_u").append(Integer.toHexString(c));
            }
            return label.toString();
        }
    }

    private static final class Sample {
        final long timeMs;
        final long sensorTimeNs;
        final int accuracy;
        final float peak;
        final float[] axes;

        Sample(long timeMs, long sensorTimeNs, int accuracy, float peak, float[] axes) {
            this.timeMs = timeMs;
            this.sensorTimeNs = sensorTimeNs;
            this.accuracy = accuracy;
            this.peak = peak;
            this.axes = axes;
        }
    }
}
