package local.headunit.diplay

/** Distinguishes app voice capture from guidance that shares the default audio type. */
object AppMicrophoneRequestPolicy {
    @JvmStatic fun wantsMicrophone(audioType: String, stream: Map<String, Any?>): Boolean = when (audioType) {
        "telephony", "speechrecognition" -> true
        "default", "compatibility" -> when (val input = stream["input"]) {
            is Boolean -> input
            is Number -> input.toInt() != 0
            else -> (stream["dataPort"] as? Number)?.toInt()?.let { it in 1..65535 } == true
        }
        else -> false
    }
}
