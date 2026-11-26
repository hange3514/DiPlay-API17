package local.headunit.diplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Locale;

/** Vehicle-state observer for the known BYD MX9A0 firmware. Start and stop on the main thread. */
final class LegacyVehicleSignals {
    private static final String CAN_EVENT = "INTENT_CAN_DATA";
    private static final String LIGHT_EVENT = "android.NaviOne.CldStdLightReceiver";
    private static final String NAV_ACTIVITY_EVENT = "android.NaviOne.ActivityStatusBroadcast";
    private static final String CAN_PACKAGE = "com.byd.canservice";
    private static final String MULTIMEDIA_PACKAGE = "com.byd.multimediaservice";
    private static final int SPEED_080 = 0x080;
    private static final int SPEED_121 = 0x121;
    private static final int PARK_BRAKE = 0x055;
    private static final int STEERING = 0x11F;
    private static final int VEHICLE_PROFILE = 0x40D;
    private static final int SAH_PROFILE = 0x20;
    static final long SPEED_FRESH_MS = 1500;
    static final long PARK_FRESH_MS = 8000;
    static final long STEERING_FRESH_MS = 1000;
    private static final int STEERING_RAW_LIMIT = 7200;
    private static final long STEERING_BUCKET_MS = STEERING_FRESH_MS / 2;
    private static final long MAX_AGREEMENT_GAP_MS = 500;
    private static final long OEM_CODE_RECHECK_MS = 20_000;
    private static final long LIGHT_QUERY_RETRY_MS = 3_000;
    private static final double SPEED_SCALE = 0.06875;
    private static final String NONE = "none";
    private static final Snapshot UNKNOWN = new Snapshot(Double.NaN, NONE, NONE, -1, null, -1, null, -1,
            null, -1, 0, -1, -1);

    interface Listener {
        void onVehicleSignalsChanged(Snapshot snapshot);
    }

    /** An immutable view of observations from this observer's current lifetime. */
    static final class Snapshot {
        final double speedKph;
        final String speedSource;
        final String speedSelector;
        final long speedAtMs;
        final Boolean parkingBrake;
        final long parkingBrakeAtMs;
        final Boolean night;
        final long nightAtMs;
        final Integer steeringRaw;
        final long steeringAtMs;
        final int steeringSwingRaw;
        final long steeringSwingAtMs;
        private final long steeringSwingFirstAtMs;

        private Snapshot(double speedKph, String speedSource, String speedSelector, long speedAtMs, Boolean parkingBrake,
                         long parkingBrakeAtMs, Boolean night, long nightAtMs, Integer steeringRaw,
                         long steeringAtMs, int steeringSwingRaw, long steeringSwingAtMs, long steeringSwingFirstAtMs) {
            this.speedKph = speedKph;
            this.speedSource = speedSource;
            this.speedSelector = speedSelector;
            this.speedAtMs = speedAtMs;
            this.parkingBrake = parkingBrake;
            this.parkingBrakeAtMs = parkingBrakeAtMs;
            this.night = night;
            this.nightAtMs = nightAtMs;
            this.steeringRaw = steeringRaw;
            this.steeringAtMs = steeringAtMs;
            this.steeringSwingRaw = steeringSwingRaw;
            this.steeringSwingAtMs = steeringSwingAtMs;
            this.steeringSwingFirstAtMs = steeringSwingFirstAtMs;
        }

        private Snapshot at(long now) {
            boolean staleSpeed = speedAtMs >= 0 && !fresh(speedAtMs, now, SPEED_FRESH_MS);
            boolean staleBrake = parkingBrakeAtMs >= 0 && !fresh(parkingBrakeAtMs, now, PARK_FRESH_MS);
            boolean staleSteering = steeringAtMs >= 0 && !fresh(steeringAtMs, now, STEERING_FRESH_MS);
            boolean staleSwing = staleSteering || (steeringSwingFirstAtMs >= 0
                    && !fresh(steeringSwingFirstAtMs, now, STEERING_FRESH_MS));
            if (!staleSpeed && !staleBrake && !staleSteering && !staleSwing) return this;
            return new Snapshot(staleSpeed ? Double.NaN : speedKph, staleSpeed ? NONE : speedSource, speedSelector,
                    staleSpeed ? -1 : speedAtMs, staleBrake ? null : parkingBrake,
                    staleBrake ? -1 : parkingBrakeAtMs, night, nightAtMs,
                    staleSteering ? null : steeringRaw, staleSteering ? -1 : steeringAtMs,
                    staleSwing ? 0 : steeringSwingRaw, staleSwing ? -1 : steeringSwingAtMs,
                    staleSwing ? -1 : steeringSwingFirstAtMs);
        }

        private long nextExpiry() {
            long speedExpiry = speedAtMs < 0 ? Long.MAX_VALUE : speedAtMs + SPEED_FRESH_MS;
            long parkExpiry = parkingBrakeAtMs < 0 ? Long.MAX_VALUE : parkingBrakeAtMs + PARK_FRESH_MS;
            long steerExpiry = steeringAtMs < 0 ? Long.MAX_VALUE : steeringAtMs + STEERING_FRESH_MS;
            long swingExpiry = steeringSwingFirstAtMs < 0 ? Long.MAX_VALUE : steeringSwingFirstAtMs + STEERING_FRESH_MS;
            return Math.min(Math.min(speedExpiry, parkExpiry), Math.min(steerExpiry, swingExpiry));
        }

        private boolean sameValues(Snapshot other) {
            return Double.compare(speedKph, other.speedKph) == 0 && speedSource.equals(other.speedSource)
                    && speedSelector.equals(other.speedSelector)
                    && parkingBrake == other.parkingBrake && night == other.night
                    && (steeringRaw == null ? other.steeringRaw == null : steeringRaw.equals(other.steeringRaw))
                    && steeringSwingRaw == other.steeringSwingRaw
                    && (steeringSwingAtMs < 0) == (other.steeringSwingAtMs < 0);
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean supported;
    private final boolean canQueryLight;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            handle(intent);
        }
    };
    private final Runnable expiry = new Runnable() {
        @Override public void run() {
            scheduledExpiry = Long.MAX_VALUE;
            if (state != null) publish(state.snapshot(SystemClock.elapsedRealtime()));
        }
    };
    private final Runnable refreshOemCode = new Runnable() {
        @Override public void run() {
            if (state == null) return;
            int id = preferredId(readOemVehicleCode());
            if (state.selectOemSpeedId(id)) {
                publish(state.snapshot(SystemClock.elapsedRealtime()));
            }
        }
    };
    private final Runnable retryLightQuery = new Runnable() {
        @Override public void run() {
            queryLightWhenUnknown();
        }
    };

    private SignalState state;
    private boolean registered;
    private long scheduledExpiry = Long.MAX_VALUE;
    private volatile Snapshot latest = UNKNOWN;

    LegacyVehicleSignals(Context context, Listener listener) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.listener = listener;
        supported = matchesProfile(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DEVICE,
                Build.PRODUCT, Build.BOARD, Build.HARDWARE) && hasSystemPackage(this.context, CAN_PACKAGE);
        canQueryLight = supported && hasSystemPackage(this.context, MULTIMEDIA_PACKAGE);
    }

    boolean isSupported() {
        return supported;
    }

    void start() {
        if (!supported || state != null) return;
        state = new SignalState(preferredId(readOemVehicleCode()));
        IntentFilter filter = new IntentFilter(LIGHT_EVENT);
        filter.addAction(CAN_EVENT);
        Intent initial;
        try {
            initial = context.registerReceiver(receiver, filter, null, main);
            registered = true;
        } catch (RuntimeException unavailable) {
            state = null;
            return;
        }
        // The verified OEM code uses sendBroadcast rather than sendStickyBroadcast.
        // Accept an actual retained OEM lighting intent if a firmware variant supplies one.
        main.postDelayed(refreshOemCode, OEM_CODE_RECHECK_MS);
        if (initial != null && LIGHT_EVENT.equals(initial.getAction())) handle(initial);
        if (queryLightWhenUnknown()) main.postDelayed(retryLightQuery, LIGHT_QUERY_RETRY_MS);
    }

    void stop() {
        state = null;
        main.removeCallbacks(refreshOemCode);
        main.removeCallbacks(retryLightQuery);
        if (registered) {
            registered = false;
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // A queued delivery is ignored after the observation state is cleared.
            }
        }
        publish(UNKNOWN);
    }

    Snapshot snapshot() {
        return latest.at(SystemClock.elapsedRealtime());
    }

    private boolean queryLightWhenUnknown() {
        if (!canQueryLight || state == null || state.night != null) return false;
        // The stock navigation-start handler republishes its held display mode, including a 1.5 s replay.
        Intent query = new Intent(NAV_ACTIVITY_EVENT).setPackage(MULTIMEDIA_PACKAGE);
        query.putExtra("Status", "Startup");
        try {
            context.sendBroadcast(query);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void handle(Intent intent) {
        if (state == null || intent == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean accepted;
        try {
            if (CAN_EVENT.equals(intent.getAction())) {
                accepted = state.observeCan(intent.getIntArrayExtra("list"), now);
            } else if (LIGHT_EVENT.equals(intent.getAction())) {
                accepted = state.observeLight(intent.getStringExtra("CLDLIGHT"), now);
                if (accepted) main.removeCallbacks(retryLightQuery);
            } else {
                return;
            }
        } catch (RuntimeException malformedExtras) {
            return;
        }
        if (accepted) publish(state.snapshot(now));
    }

    private void publish(Snapshot next) {
        Snapshot prior = latest;
        latest = next;
        if (listener == null) return;
        long due = next.nextExpiry();
        if (due < scheduledExpiry || due == Long.MAX_VALUE) {
            main.removeCallbacks(expiry);
            scheduledExpiry = due;
            if (due != Long.MAX_VALUE) main.postDelayed(expiry, Math.max(0, due - SystemClock.elapsedRealtime()));
        }
        if (!next.sameValues(prior)) listener.onVehicleSignalsChanged(next);
    }

    static boolean matchesProfile(int sdk, String manufacturer, String model, String device,
                                  String product, String board, String hardware) {
        return sdk == 17 && "freescale".equalsIgnoreCase(manufacturer) && "mx9a0".equalsIgnoreCase(model)
                && "sabresd_6dq".equalsIgnoreCase(device) && "sabresd_6dq".equalsIgnoreCase(product)
                && "sabresd".equalsIgnoreCase(board) && "freescale".equalsIgnoreCase(hardware);
    }

    private static boolean hasSystemPackage(Context context, String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            return info.enabled && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    private static String readOemVehicleCode() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) properties.getMethod("get", String.class).invoke(null, "persist.sys.vechilecode");
        } catch (Exception unavailable) {
            return "";
        }
    }

    static int preferredId(String oemCode) {
        if (oemCode == null) return 0;
        // StateManager.SpeedIn121VechileCodeList and known codes from DecodeSystemInfo/isEv.
        switch (oemCode.trim().toUpperCase(Locale.US)) {
            case "SA": case "VB": case "VBH": case "SC": case "SAH": case "SCH":
            case "MEF": case "MEH": case "HAC": case "SEH": case "HAEA": case "5AEV":
            case "5BH": case "5BHE": case "5BHI": case "SAHG": case "SAHE":
                return SPEED_121;
            case "3B": case "E6": case "S6": case "5B": case "T5": case "3A":
            case "G5": case "T9": case "SE": case "HA": case "5A": case "VBEV":
            case "E6H": case "SAEV": case "SAEA": case "SCEA":
                return SPEED_080;
            default:
                return 0;
        }
    }

    private static boolean fresh(long at, long now, long timeout) {
        return at >= 0 && now >= at && now - at < timeout;
    }

    private static boolean byteLike(int value) {
        return value >= -128 && value <= 255;
    }

    static final class SignalState {
        private int preferredId;
        private boolean speedIdInferredFromCan;
        private int speed080 = -1;
        private int speed121 = -1;
        private long speed080At = -1;
        private long speed121At = -1;
        private Boolean parkingBrake;
        private long parkingBrakeAt = -1;
        private Boolean night;
        private long nightAt = -1;
        private int steeringRaw;
        private long steeringAt = -1;
        private SteeringBucket currentSteering = new SteeringBucket();
        private SteeringBucket previousSteering = new SteeringBucket();

        SignalState(int preferredId) {
            this.preferredId = preferredId;
        }

        boolean selectOemSpeedId(int id) {
            if (id == 0 || (id == preferredId && !speedIdInferredFromCan)) return false;
            preferredId = id;
            speedIdInferredFromCan = false;
            return true;
        }

        boolean observeCan(int[] frame, long now) {
            // The verified OEM layouts use one ID followed by eight payload bytes.
            if (frame == null || frame.length != 9) return false;
            int id = frame[0];
            if (id == STEERING) return observeSteering(frame[1], frame[2], now);
            if (id == VEHICLE_PROFILE) {
                // The stock CanRxControl maps this observed model code to SAH.
                if (preferredId != 0 || frame[2] != SAH_PROFILE) return false;
                preferredId = SPEED_121;
                speedIdInferredFromCan = true;
                return true;
            }
            if (id == PARK_BRAKE) {
                if (!byteLike(frame[6])) return false;
                // UEControl uses this specific bit for the parking-brake release warning.
                parkingBrake = (frame[6] & 1) != 0;
                parkingBrakeAt = now;
                return true;
            }
            if ((id != SPEED_080 && id != SPEED_121) || (preferredId != 0 && id != preferredId)) return false;
            // UEControl uses these two alternate 12-bit vehicle-speed fields.
            int lowOffset = id == SPEED_080 ? 4 : 1;
            int low = frame[lowOffset];
            int high = frame[lowOffset + 1];
            if (!byteLike(low) || !byteLike(high)) return false;
            int raw = (high & 15) << 8 | (low & 255);
            if (id == SPEED_080) {
                speed080 = raw == 4095 ? -1 : raw;
                speed080At = speed080 < 0 ? -1 : now;
            } else {
                speed121 = raw == 4095 ? -1 : raw;
                speed121At = speed121 < 0 ? -1 : now;
            }
            return true;
        }

        private boolean observeSteering(int low, int high, long now) {
            if (!byteLike(low) || !byteLike(high)) return false;
            // CanRxControl uses the first two payload bytes as a signed steering position.
            int raw = (short) ((high & 255) << 8 | (low & 255));
            if (raw < -STEERING_RAW_LIMIT || raw > STEERING_RAW_LIMIT) {
                if (steeringAt < 0) return false;
                steeringAt = -1;
                currentSteering.index = -1;
                previousSteering.index = -1;
                return true;
            }
            long index = now / STEERING_BUCKET_MS;
            if (!fresh(steeringAt, now, STEERING_FRESH_MS)) {
                currentSteering.index = -1;
                previousSteering.index = -1;
            }
            if (currentSteering.index != index) {
                // Rotate two fixed buckets. The oldest sample included is at most one second old.
                SteeringBucket reused = previousSteering;
                previousSteering = currentSteering;
                currentSteering = reused;
                currentSteering.reset(index, now, raw);
            } else {
                currentSteering.include(now, raw);
            }
            steeringRaw = raw;
            steeringAt = now;
            return true;
        }

        boolean observeLight(String mode, long now) {
            if ("MODE_NIGHT".equals(mode)) night = true;
            else if ("MODE_DAY".equals(mode)) night = false;
            else return false;
            // This OEM event describes the display mode, and does not have a periodic heartbeat.
            nightAt = now;
            return true;
        }

        Snapshot snapshot(long now) {
            boolean fresh080 = fresh(speed080At, now, SPEED_FRESH_MS);
            boolean fresh121 = fresh(speed121At, now, SPEED_FRESH_MS);
            int speed = -1;
            String source = NONE;
            long speedAt = -1;
            if (preferredId == SPEED_080 && fresh080) {
                speed = speed080;
                source = "can_080";
                speedAt = speed080At;
            } else if (preferredId == SPEED_121 && fresh121) {
                speed = speed121;
                source = "can_121";
                speedAt = speed121At;
            } else if (preferredId == 0 && fresh080 && fresh121 && speed080 == speed121
                    && Math.abs(speed080At - speed121At) <= MAX_AGREEMENT_GAP_MS) {
                speed = speed080;
                source = "can_agreement";
                speedAt = Math.min(speed080At, speed121At);
            }
            boolean freshBrake = fresh(parkingBrakeAt, now, PARK_FRESH_MS);
            String selector = speedIdInferredFromCan ? "can_model_sah"
                    : preferredId != 0 ? "oem_property" : speedAt >= 0 ? "dual_agreement" : NONE;
            boolean freshSteering = fresh(steeringAt, now, STEERING_FRESH_MS);
            long firstSwingSample = -1;
            long lastSwingSample = -1;
            int swing = 0;
            boolean previousRecent = freshSteering && previousSteering.index >= 0
                    && previousSteering.index == currentSteering.index - 1
                    && fresh(previousSteering.firstAt, now, STEERING_FRESH_MS);
            boolean currentRecent = freshSteering && currentSteering.multiple
                    && fresh(currentSteering.firstAt, now, STEERING_FRESH_MS);
            if (previousRecent || currentRecent) {
                SteeringBucket low = previousRecent && previousSteering.min <= currentSteering.min
                        ? previousSteering : currentSteering;
                SteeringBucket high = previousRecent && previousSteering.max >= currentSteering.max
                        ? previousSteering : currentSteering;
                firstSwingSample = previousRecent ? previousSteering.firstAt : currentSteering.firstAt;
                lastSwingSample = Math.max(low.minAt, high.maxAt);
                swing = high.max - low.min;
            }
            return new Snapshot(speed < 0 ? Double.NaN : speed * SPEED_SCALE, source, selector, speedAt,
                    freshBrake ? parkingBrake : null, freshBrake ? parkingBrakeAt : -1, night, nightAt,
                    freshSteering ? steeringRaw : null, freshSteering ? steeringAt : -1,
                    swing, lastSwingSample, firstSwingSample);
        }
    }

    private static final class SteeringBucket {
        long index = -1;
        long firstAt;
        int min;
        int max;
        long minAt;
        long maxAt;
        boolean multiple;

        void reset(long newIndex, long now, int raw) {
            index = newIndex;
            firstAt = now;
            min = max = raw;
            minAt = maxAt = now;
            multiple = false;
        }

        void include(long now, int raw) {
            if (raw < min) {
                min = raw;
                minAt = now;
            }
            if (raw > max) {
                max = raw;
                maxAt = now;
            }
            multiple = true;
        }
    }
}
