package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class WheelKeyPolicyTest {
 @Test fun measuredKeys(){for((code,action) in listOf(4 to 1,3 to 2,94 to 3,95 to 4,18 to 1,19 to 2,93 to 5))assertEquals(action,WheelKeyPolicy.decode(byteArrayOf(4,1,code.toByte())))}
 @Test fun unknownEnvelopesIgnored(){for(b in listOf(null,byteArrayOf(4,1),byteArrayOf(4,1,3,0),byteArrayOf(3,1,4),byteArrayOf(4,2,4),byteArrayOf(4,1,92)))assertEquals(0,WheelKeyPolicy.decode(b))}
 @Test fun debounceAndAndroidEcho(){val p=WheelKeyPolicy();assertTrue(p.accept(1,1000));assertFalse(p.accept(1,1050));assertTrue(p.duplicateAndroid(88,1450));assertFalse(p.duplicateAndroid(87,1450));assertFalse(p.duplicateAndroid(88,1900));assertTrue(p.accept(1,1250));assertTrue(p.accept(2,1251));assertTrue(p.duplicateAndroid(87,1700))}
 @Test fun unknownActionsCannotSuppressVolume(){val p=WheelKeyPolicy();assertFalse(p.accept(0,0));assertFalse(p.accept(6,0));assertFalse(p.duplicateAndroid(24,0));assertFalse(p.duplicateAndroid(25,0))}
 @Test fun voiceKeyDebouncedLikeOtherActions(){val p=WheelKeyPolicy();assertTrue(p.accept(5,1000));assertFalse(p.accept(5,1050));assertTrue(p.accept(5,1300))}
 @Test fun androidKeyPathFirstSuppressesBroadcastEcho(){val p=WheelKeyPolicy();assertTrue(p.acceptAndroid(1,1000));assertFalse(p.accept(1,1050));assertFalse(p.acceptAndroid(1,1050));assertTrue(p.acceptAndroid(1,1300));assertFalse(p.accept(1,1450))}
 @Test fun broadcastFirstSuppressesAndroidEcho(){val p=WheelKeyPolicy();assertTrue(p.accept(2,2000));assertFalse(p.acceptAndroid(2,2400));assertTrue(p.acceptAndroid(2,2950));assertFalse(p.accept(2,3100))}
 @Test fun androidActionMapping(){assertEquals(1,WheelKeyPolicy.androidAction(88));assertEquals(2,WheelKeyPolicy.androidAction(87));assertEquals(0,WheelKeyPolicy.androidAction(24));assertEquals(0,WheelKeyPolicy.androidAction(304))}
}
