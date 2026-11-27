package local.headunit.diplay

import android.content.Context
import android.media.AudioManager

/** CarPlay roles remain distinct even when an OEM exposes only one Android output. */
object LegacyAudioPolicy {
 enum class CallRoute(
  val outputStream:Int,
  val platformPhoneStrategy:Boolean,
  val maximizeVolume:Boolean,
  val diagnosticProfile:String
 ) {
  BYD_MX9A0_MEDIA(AudioManager.STREAM_MUSIC,false,true,"byd_mx9a0_media"),
  BYD_SAH_MX9_MEDIA(AudioManager.STREAM_MUSIC,false,false,"byd_sah_mx9_410_media"),
  DEFAULT_MEDIA(AudioManager.STREAM_MUSIC,false,false,"default_media")
 }

 @JvmStatic fun resolveCallRoute(context:Context,secondMusic:Int):CallRoute =
  selectCallRoute(secondMusic,secondMusic!=10 && SahMx9CallProfile.matches(context))

 internal fun selectCallRoute(secondMusic:Int,sahMx9:Boolean):CallRoute = when {
  secondMusic==10 -> CallRoute.BYD_MX9A0_MEDIA
  sahMx9 -> CallRoute.BYD_SAH_MX9_MEDIA
  else -> CallRoute.DEFAULT_MEDIA
 }

 /** Only used by a route that explicitly selects Android's phone strategy. */
 @JvmStatic fun callMode():Int=android.media.AudioManager.MODE_IN_CALL
 @JvmStatic fun callSpeakerphone():Boolean=true
 /** CarPlay call focus uses the same stream as call playback. */
 @JvmStatic fun callFocusStream(secondMusic:Int):Int=stream("call",secondMusic)
 @JvmStatic fun usesPlatformPhoneStrategy(secondMusic:Int):Boolean=selectCallRoute(secondMusic,false).platformPhoneStrategy
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
  "call" -> selectCallRoute(secondMusic,false).outputStream
  "navigation" -> if(secondMusic==10)10 else android.media.AudioManager.STREAM_MUSIC
  else -> android.media.AudioManager.STREAM_MUSIC
 }
 /** CarPlay must never impersonate the OEM Bluetooth Phone source on this device. */
 @JvmStatic fun usesOemCallRoute(secondMusic:Int):Boolean=false
 /** Use the exact AirPlay PCM format for telephony; the MX9A0 A/B replay was clear at 32 kHz mono. */
 @JvmStatic fun outputChannels(role:String,inputChannels:Int):Int=if(role=="assistant"&&inputChannels==1)2 else inputChannels
 @JvmStatic fun outputRate(role:String,inputRate:Int):Int=if(role=="assistant"&&inputRate==24000)48000 else inputRate
}
