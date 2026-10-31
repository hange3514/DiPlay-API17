package local.headunit.diplay
object DiagnosticPolicy {
 @JvmStatic fun skipFrame(id:Int)=id==0x5001||id==0x5201||id==0x4158
 @JvmStatic fun routine(event:String):Boolean {
  val text=event.lowercase()
  if(listOf("failed","error","timeout","release_pending","first_pcm","audio_started","audio_setup","audio_stopped","audio_summary","audio_checkpoint","audio_modes","microphone_alsa_","microphone_duplex_evidence","microphone_capture_path=alsa_dynamic","microphone_capture_fallback","microphone_started","microphone_stopped","microphone_playback_hold","microphone_speech_mode","first_packet","video_decoder_started","video_rendered_frames=1","status=500").any{text.contains(it)})return false
  if(event.startsWith("BYD_CALL_ROUTE ")&&text.contains("media_focus_granted"))return true
  if(event.startsWith("USB_NETWORK ")&&(event.contains("ncm outbound ipv6Bytes=")||event.contains("ncm inbound ipv6Bytes=")))return true
  return event.startsWith("IAP2 ")||event.startsWith("CONTROL ")&&event.contains("iap2 rx=")||event.startsWith("AIRPLAY ")||event.startsWith("MEDIA ")||event.contains("native read idle")||event.contains("TRACE ")
 }
}
