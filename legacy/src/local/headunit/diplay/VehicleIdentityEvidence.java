package local.headunit.diplay;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.Locale;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Build and OEM family metadata for an explicitly submitted compatibility report. */
final class VehicleIdentityEvidence {
    private static final String UNKNOWN = "unknown";
    private static final String VEHICLE_CODE_PROPERTY = "persist.sys.vechilecode";
    private static final Pattern FIRMWARE_LABEL = Pattern.compile("[A-Za-z0-9 ._-]{1,80}");
    private static final Pattern OEM_MODEL_CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,7}");

    private VehicleIdentityEvidence() {}

    static JSONObject collect(Context context) throws JSONException {
        PackageManager packages = context.getPackageManager();
        int multimedia = systemVersion(packages, "com.byd.multimediaservice");
        int can = systemVersion(packages, "com.byd.canservice");
        int serial = systemVersion(packages, "com.byd.serialservice");
        String code = can > 0 ? readVehicleCode() : UNKNOWN;

        return new JSONObject()
            .put("vehicleCode", code)
            .put("vehicleFamily", "SAH".equals(code) ? "byd_song_dm" : UNKNOWN)
            .put("vehicleSource", UNKNOWN.equals(code) ? "unavailable" : "oem_property")
            .put("firmwareDisplay", firmwareLabel(Build.DISPLAY))
            .put("firmwareIncremental", firmwareLabel(Build.VERSION.INCREMENTAL))
            .put("oemMultimediaVersion", multimedia)
            .put("oemCanServiceVersion", can)
            .put("oemSerialServiceVersion", serial);
    }

    private static int systemVersion(PackageManager packages, String name) {
        try {
            PackageInfo info = packages.getPackageInfo(name, 0);
            int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            if (info.applicationInfo != null && (info.applicationInfo.flags & systemFlags) != 0) {
                return Math.max(0, info.versionCode);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // The firmware can omit the OEM service or restrict access to its metadata.
        }
        return 0;
    }

    private static String readVehicleCode() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties.getMethod("get", String.class)
                .invoke(null, VEHICLE_CODE_PROPERTY);
            return allowedVehicleCode(value);
        } catch (Exception unavailable) {
            return UNKNOWN;
        }
    }

    private static String allowedVehicleCode(String value) {
        if (value == null) return UNKNOWN;
        String code = value.trim().toUpperCase(Locale.US);
        // Keep bounded codes from the OEM model property even when no verified family name is known.
        return OEM_MODEL_CODE.matcher(code).matches() && !code.matches("[0-9]+") &&
            !"UNKNOWN".equals(code) && !"NULL".equals(code) && !"NONE".equals(code) ? code : UNKNOWN;
    }

    private static String firmwareLabel(String value) {
        if (value == null) return UNKNOWN;
        String label = value.trim();
        return FIRMWARE_LABEL.matcher(label).matches() ? label : UNKNOWN;
    }
}
