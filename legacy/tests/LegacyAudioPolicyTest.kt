package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class LegacyAudioPolicyTest {
 @Test fun bydUsesTransientMediaFocusWithoutIgnoringUnrelatedFocusLoss(){
  assertEquals(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,CallAudioFocusPolicy.gain(true))
  assertEquals(android.media.AudioManager.AUDIOFOCUS_GAIN,CallAudioFocusPolicy.gain(false))
 }
 @Test fun assistantUsesMediaOutput(){assertEquals(3,LegacyAudioPolicy.stream("assistant",10));assertEquals(3,LegacyAudioPolicy.stream("assistant",-1));assertEquals(3,LegacyAudioPolicy.stream("assistant",14))}
 @Test fun bydAirplayCallKeepsCarplaySourceAndUsesAndroidMediaFocus(){assertEquals("call",LegacyAudioPolicy.role(100,"telephony"));assertEquals("assistant",LegacyAudioPolicy.role(100,"speechRecognition"));assertFalse(LegacyAudioPolicy.usesOemCallRoute(10));assertFalse(LegacyAudioPolicy.usesPlatformPhoneStrategy(10));assertTrue(LegacyAudioPolicy.usesClientCallFocus(10));assertEquals(android.media.AudioManager.STREAM_MUSIC,LegacyAudioPolicy.stream("call",10));assertEquals(android.media.AudioManager.STREAM_MUSIC,LegacyAudioPolicy.callFocusStream(10));assertTrue(LegacyAudioPolicy.usesPlatformPhoneStrategy(-1));assertTrue(LegacyAudioPolicy.usesClientCallFocus(-1));assertEquals(android.media.AudioManager.STREAM_VOICE_CALL,LegacyAudioPolicy.stream("call",-1));assertEquals(android.media.AudioManager.STREAM_VOICE_CALL,LegacyAudioPolicy.callFocusStream(-1));assertEquals(android.media.AudioManager.MODE_IN_CALL,LegacyAudioPolicy.callMode());assertTrue(LegacyAudioPolicy.callSpeakerphone());assertEquals(android.media.AudioManager.STREAM_MUSIC,LegacyAudioPolicy.stream("assistant",10))}
 @Test fun navigationOnlyUsesAuditedSecondOutput(){assertEquals(10,LegacyAudioPolicy.stream("navigation",10));assertEquals(3,LegacyAudioPolicy.stream("media",10));assertEquals(3,LegacyAudioPolicy.stream("navigation",14));assertEquals(3,LegacyAudioPolicy.stream("navigation",-1))}
 @Test fun lowLatencyMediaIsStillMedia(){assertEquals("media",LegacyAudioPolicy.role(100,"media"));assertEquals("media",LegacyAudioPolicy.role(102,"default"));assertEquals("navigation",LegacyAudioPolicy.role(100,"default"));assertEquals("navigation",LegacyAudioPolicy.role(100,"alert"))}
 @Test fun telephonyKeepsNegotiatedPcmFormat(){assertEquals(32000,LegacyAudioPolicy.outputRate("call",32000));assertEquals(1,LegacyAudioPolicy.outputChannels("call",1));assertEquals(48000,LegacyAudioPolicy.outputRate("assistant",24000));assertEquals(2,LegacyAudioPolicy.outputChannels("assistant",1))}
}
