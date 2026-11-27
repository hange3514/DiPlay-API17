package local.headunit.diplay

import android.content.Context
import android.os.Build

/** Identifies the OEM firmware observed in the SAH call-route report. */
internal object SahMx9CallProfile {
    fun matches(context: Context): Boolean {
        if (!matchesBuild(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DISPLAY)) return false
        val identity = runCatching { VehicleIdentityEvidence.collect(context) }.getOrNull() ?: return false
        return matchesOem(identity.optString("vehicleCode"), identity.optInt("oemMultimediaVersion"))
    }

    fun matchesBuild(sdk: Int, manufacturer: String?, model: String?, display: String?): Boolean =
        sdk == 17 &&
            manufacturer.equals("freescale", ignoreCase = true) &&
            model.equals("mx9", ignoreCase = true) &&
            display?.trim().equals("V4.2.2.30.180428", ignoreCase = true)

    fun matchesOem(vehicleCode: String, multimediaVersion: Int): Boolean =
        vehicleCode == "SAH" && multimediaVersion == 410
}
