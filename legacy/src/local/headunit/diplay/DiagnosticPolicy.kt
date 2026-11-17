package local.headunit.diplay
object DiagnosticPolicy {
 @JvmStatic fun skipFrame(id:Int)=id==0x5001||id==0x5201||id==0x4158
 @JvmStatic fun routine(event:String):Boolean {
  val text=event.lowercase()
  if(listOf("failed","error","timeout","release_pending","first_pcm","audio_started","audio_setup","audio_stopped","audio_summary","audio_checkpoint","audio_modes","microphone_alsa_","microphone_duplex_evidence","microphone_capture_path=","microphone_capture_fallback","microphone_started","microphone_stopped","microphone_playback_hold","microphone_speech_mode","first_packet","video_decoder_started","video_rendered_frames=1","status=500").any{text.contains(it)})return false
  if(listOf("connection_failure","process_recovery","java_uncaught_exception","connection_clean_exit","auto_usb_state","usb_presence","usb_device","wheel_key","wheel_control","connect_summary","auth_complete","video_ready","disconnected","release_error","release_timeout").any{text.startsWith(it)})return false
  if(listOf("third_party_audio_state","oem_audio_state","projection_layout","connect_timing").any{text.startsWith(it)})return true
  if(event.startsWith("BYD_CALL_ROUTE ")&&text.contains("media_focus_granted"))return true
  if(event.startsWith("USB_NETWORK ")&&(event.contains("ncm outbound ipv6Bytes=")||event.contains("ncm inbound ipv6Bytes=")))return true
  return event.startsWith("IAP2 ")||event.startsWith("CONTROL ")&&event.contains("iap2 rx=")||event.startsWith("AIRPLAY ")||event.startsWith("MEDIA ")||event.contains("native read idle")||event.contains("TRACE ")
 }
}
