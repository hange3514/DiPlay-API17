package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.airplay.AudioStreamId
class DiagnosticPolicyTest {
 @Test fun startupPacketFloodIsEvictableButMilestonesRemain(){assertTrue(DiagnosticPolicy.routine("USB_NETWORK ncm outbound ipv6Bytes=48"));assertFalse(DiagnosticPolicy.routine("USB_NETWORK ncm first outbound ipv6 bytes=48"));assertFalse(DiagnosticPolicy.routine("USB_NETWORK failed ncm outbound ipv6Bytes=48"))}
 @Test fun navigationAndMusicDoNotFormatEveryPacket(){assertTrue(DiagnosticPolicy.skipFrame(0x5001));assertTrue(DiagnosticPolicy.skipFrame(0x5201));assertFalse(DiagnosticPolicy.skipFrame(0xAA00))}
 @Test fun legacyFloodCanBeEvicted(){assertTrue(DiagnosticPolicy.routine("IAP2 RX NowPlayingUpdate"));assertTrue(DiagnosticPolicy.routine("CONTROL iap2 rx=0x5201"))}
 @Test fun onlyCallFocusRoutineIsEvictableWhileOemRouteEventsAreRetained(){assertTrue(DiagnosticPolicy.routine("BYD_CALL_ROUTE {\"detail\":\"media_focus_granted\"}"));assertFalse(DiagnosticPolicy.routine("BYD_CALL_ROUTE {\"detail\":\"oem_phone_mode_transact_returned\"}"));assertFalse(DiagnosticPolicy.routine("CALL_AUDIO_ROUTE {\"detail\":\"phone_strategy_route_requested\"}"))}
 @Test fun startupAudioFailuresAndReleaseAreRetained(){for(s in listOf("PAIRING", "MEDIA audio_first_pcm_written", "MEDIA audio_started", "MEDIA audio_failed", "RELEASE_STEP", "FAILED", "USB_PRESENCE"))assertFalse(s,DiagnosticPolicy.routine(s))}
 @Test fun audioEvidenceSurvivesRoutineEviction(){for(s in listOf("audio_checkpoint role=media peak=0 volume=1.0", "microphone_capture_path=audited_native_start_without_oem_focus", "microphone_started_pcm", "microphone_stopped", "audio_summary"))assertFalse(s,DiagnosticPolicy.routine("MEDIA "+s));assertTrue(DiagnosticPolicy.routine("MEDIA audio_progress role=media"))}
 @Test fun modeEvidenceExcludesUnrelatedAndPrivateData(){val text=AudioModeEvidence.describe(mapOf("mainAudio" to 1,"phoneNumber" to 123456,"resources" to listOf(mapOf("resourceID" to 2,"entity" to 1,"name" to "private")),"speech" to byteArrayOf(1,2)));assertTrue(text.contains("mainAudio=1"));assertTrue(text.contains("entity=1"));assertFalse(text.contains("private"));assertFalse(text.contains("123456"));assertFalse(text.contains("speech="));assertFalse(DiagnosticPolicy.routine("AIRPLAY audio_modes "+text))}
 @Test fun oldSessionCannotStopNewSessionAudio(){val old=Any();val current=Any();val oldId=AudioStreamId(100,"media",old);val currentId=AudioStreamId(100,"media",current);val tracks=mutableMapOf(oldId to 1,currentId to 2);tracks.remove(oldId);assertEquals(2,tracks[currentId]);assertEquals(currentId,AudioStreamId(100,"media",current))}
}
