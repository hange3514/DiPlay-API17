package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class BydCallUsbAssertTest {
 @Test fun usbSourceFrameMatchesOemSerialLayout(){
  // AA55 + length + sequence(0, filled by SerialService) + payload [1,2,10,0,0,0]
  assertArrayEquals(byteArrayOf(0xaa.toByte(),0x55,11,0,1,2,10,0,0,0,0),BydCallUsbAssert.frame(BydCallUsbAssert.sourceMessage(10)))
 }
 @Test fun modeVolumeFrameUsesVolume4Mx9AndUsbSource(){
  assertArrayEquals(byteArrayOf(0xaa.toByte(),0x55,10,0,4,4,0,31,10,0),BydCallUsbAssert.frame(BydCallUsbAssert.modeVolumeMessage(31)))
 }
 @Test fun frameRejectsInvalidPayload(){
  try{BydCallUsbAssert.frame(null);fail()}catch(e:IllegalArgumentException){}
  try{BydCallUsbAssert.frame(ByteArray(251));fail()}catch(e:IllegalArgumentException){}
 }
 @Test fun deviceGateIsExactAuditedFirmware(){
  assertTrue(BydCallUsbAssert.supportsDevice(17,"MX9A0",382,12))
  assertFalse(BydCallUsbAssert.supportsDevice(17,"MX9A0",382,11))
  assertFalse(BydCallUsbAssert.supportsDevice(17,"MX9A0",381,12))
  assertFalse(BydCallUsbAssert.supportsDevice(18,"MX9A0",382,12))
  assertFalse(BydCallUsbAssert.supportsDevice(17,"other",382,12))
 }
 @Test fun reassertOnlyWhenMcuLeavesUsbLaneDuringCall(){
  // Off the USB lane while the call is active: re-assert.
  assertTrue(BydCallUsbAssert.shouldReassert(true,13,10000,0,0))
  // Already on the USB lane: no traffic.
  assertFalse(BydCallUsbAssert.shouldReassert(true,10,10000,0,0))
  // No call: never touch the source.
  assertFalse(BydCallUsbAssert.shouldReassert(false,13,10000,0,0))
  // Rate limited.
  assertFalse(BydCallUsbAssert.shouldReassert(true,13,1000,500,0))
  // Bounded per call.
  assertFalse(BydCallUsbAssert.shouldReassert(true,13,100000,0,8))
  // Unknown sentinel never triggers a source write.
  assertFalse(BydCallUsbAssert.shouldReassert(true,-1,100000,0,0))
 }
 @Test fun toneScheduleMatchesPlannedOffsets(){
  assertEquals(0,CallToneProbe.pendingStage(3000,0))
  assertEquals(-1,CallToneProbe.pendingStage(2999,0))
  assertEquals(1,CallToneProbe.pendingStage(8000,1))
  assertEquals(-1,CallToneProbe.pendingStage(7999,1))
  assertEquals(2,CallToneProbe.pendingStage(13000,2))
  assertEquals(-1,CallToneProbe.pendingStage(60000,3))
 }
 @Test fun tonePcmIsLittleEndianAndSizedForCallFormat(){
  val pcm=CallToneProbe.tonePcm(660.0,32000,1,500)
  assertEquals(32000*500/1000*2,pcm.size)
  var peak=0
  for(i in pcm.indices step 2){val v=Math.abs(((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt());if(v>peak)peak=v}
  assertTrue(peak in 9000..11000)
 }
}
