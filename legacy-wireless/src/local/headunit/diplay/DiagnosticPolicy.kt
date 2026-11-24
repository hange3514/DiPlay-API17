package local.headunit.diplay
object DiagnosticPolicy {
 private val importantText=listOf("failed","error","timeout","release_pending","first_pcm","audio_started","audio_setup","audio_stopped","audio_summary","audio_checkpoint","audio_music_starved","audio_modes","microphone_alsa_","microphone_duplex_evidence","microphone_capture_path=","microphone_capture_fallback","microphone_started","microphone_stopped","microphone_playback_hold","microphone_speech_mode","first_packet","video_decoder_started","video_rendered_frames=1","status=500")
 private val importantEvents=listOf("connection_failure","process_recovery","java_uncaught_exception","connection_clean_exit","boot_autostart","usb_presence","usb_device","usb_bt_connect_scan","usb_bt_permission","usb_bt_stack","usb_bt_stack_ready","wifi_wait","hybrid_wifi","hybrid_control_end","hybrid_prepare_step","hybrid_iap2_rx","hybrid_airplay_accept","hybrid_usb_detached","wheel_key","wheel_control","connect_summary","auth_complete","video_ready","disconnected","release_error","release_timeout")
 private val routineEvents=listOf("third_party_audio_state","oem_audio_state","projection_layout","connect_timing")
 @JvmStatic fun skipFrame(id:Int)=id==0x5001||id==0x5201||id==0x4158
 @JvmStatic fun routine(event:String):Boolean {
  val text=event.lowercase()
  if(importantText.any{text.contains(it)})return false
  if(importantEvents.any{text.startsWith(it)})return false
  if(routineEvents.any{text.startsWith(it)})return true
  if(event.startsWith("BYD_CALL_ROUTE ")&&text.contains("media_focus_granted"))return true
  if(event.startsWith("USB_NETWORK ")&&(event.contains("ncm outbound ipv6Bytes=")||event.contains("ncm inbound ipv6Bytes=")))return true
  return event.startsWith("IAP2 ")||event.startsWith("CONTROL ")&&event.contains("iap2 rx=")||event.startsWith("AIRPLAY ")||event.startsWith("MEDIA ")||event.contains("native read idle")||event.contains("TRACE ")
 }
}
