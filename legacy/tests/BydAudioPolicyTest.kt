package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class BydAudioPolicyTest {
 @Test fun matchesCollectedFirmware(){assertTrue(BydAudioPolicy.supported(17,"MX9A0",182));assertTrue(BydAudioPolicy.supported(17,"Freescale MX9A0",182))}
 @Test fun rejectsOtherFirmware(){assertFalse(BydAudioPolicy.supported(17,"MX9A0",183));assertFalse(BydAudioPolicy.supported(28,"MX9A0",182));assertFalse(BydAudioPolicy.supported(17,"other",182))}
 @Test fun callFocusGrantLogIsOncePerActiveCall(){assertTrue(BydAudioPolicy.shouldLogCallFocusGrant(true,false));assertFalse(BydAudioPolicy.shouldLogCallFocusGrant(true,true));assertFalse(BydAudioPolicy.shouldLogCallFocusGrant(false,false))}
 @Test fun oemPhoneStateRouteIsAllowlistedToAuditedHeadUnit(){assertTrue(BydMultimediaCallRoute.supportsFirmware(17,"MX9A0",382));assertFalse(BydMultimediaCallRoute.supportsFirmware(17,"Freescale MX9A0",382));assertFalse(BydMultimediaCallRoute.supportsFirmware(17,"MX9A0",381));assertFalse(BydMultimediaCallRoute.supportsFirmware(18,"MX9A0",382));assertFalse(BydMultimediaCallRoute.supportsFirmware(17,"other",382))}
 @Test fun oemPhoneModeCommandsMatchOriginalBtService(){assertArrayEquals(byteArrayOf(13),BydMultimediaCallRoute.phoneModeStartPayload());assertArrayEquals(byteArrayOf(13,0),BydMultimediaCallRoute.phoneModeStopPayload())}
}
