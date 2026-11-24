package local.headunit.diplay

/** CarPlay roles remain distinct even when an OEM exposes only one Android output. */
object LegacyAudioPolicy {
 /** Only non-BYD fallback devices use Android's phone strategy. BYD CarPlay stays on its active MCU source. */
 @JvmStatic fun callMode():Int=android.media.AudioManager.MODE_IN_CALL
 @JvmStatic fun callSpeakerphone():Boolean=true
 /** BYD CarPlay phone PCM is rendered on the same Android media output as CarPlay music. */
 @JvmStatic fun callFocusStream(secondMusic:Int):Int=stream("call",secondMusic)
 @JvmStatic fun usesPlatformPhoneStrategy(secondMusic:Int):Boolean=secondMusic!=10
 @JvmStatic fun usesClientCallFocus(secondMusic:Int):Boolean=true
 @JvmStatic fun role(type:Int,kind:String):String=when(kind.lowercase(java.util.Locale.US)) {
  "telephony" -> "call"
  "speechrecognition" -> "assistant"
  "media" -> "media"
  "default", "alert", "compatibility" -> if(type==102) "media" else "navigation"
  else -> if(type==102) "media" else "navigation"
 }
 @JvmStatic fun stream(role:String,secondMusic:Int):Int=when(role) {
  // Do not select SOURCE_BLUETOOTH_PHONE (13): that MCU source expects BT HFP
  // audio, while CarPlay telephony arrives as PCM over the wired AirPlay stream.
  // Preserve the current MCU source and render the PCM on Android's media stream.
  "call" -> if(secondMusic==10)android.media.AudioManager.STREAM_MUSIC else android.media.AudioManager.STREAM_VOICE_CALL
  "navigation" -> if(secondMusic==10)10 else android.media.AudioManager.STREAM_MUSIC
  else -> android.media.AudioManager.STREAM_MUSIC
 }
 /** CarPlay must never impersonate the OEM Bluetooth Phone source on this device. */
 @JvmStatic fun usesOemCallRoute(secondMusic:Int):Boolean=false
 /** Use the exact AirPlay PCM format for telephony; the MX9A0 A/B replay was clear at 32 kHz mono. */
 @JvmStatic fun outputChannels(role:String,inputChannels:Int):Int=if(role=="assistant"&&inputChannels==1)2 else inputChannels
 @JvmStatic fun outputRate(role:String,inputRate:Int):Int=if(role=="assistant"&&inputRate==24000)48000 else inputRate
}
