package local.headunit.diplay

import org.junit.Assert.*
import org.junit.Test

class CallPcmRingBufferTest {
 @Test fun retainsChronologicalTailAcrossWrapAndClearsAfterTake(){
  val ring=CallPcmRingBuffer();ring.append(ByteArray(30000){1},8000,1);ring.append(ByteArray(30000){2},8000,1)
  assertTrue(ring.hasAtLeast(4096));val clip=ring.take(4096)!!
  assertEquals(48000,clip.pcm.size);assertEquals(8000,clip.sampleRate);assertEquals(1,clip.channels)
  assertTrue(clip.pcm.take(18000).all{it.toInt()==1});assertTrue(clip.pcm.drop(18000).all{it.toInt()==2})
  assertFalse(ring.hasAtLeast(1));assertNull(ring.take(1))
 }
 @Test fun capsLargestSupportedFormatAndIgnoresFormatChanges(){
  val ring=CallPcmRingBuffer();ring.append(ByteArray(700000){7},48000,2);ring.append(ByteArray(10000){9},32000,1)
  val clip=ring.take(1)!!;assertEquals(CallPcmRingBuffer.MAX_BYTES,clip.pcm.size);assertEquals(48000,clip.sampleRate);assertEquals(2,clip.channels)
  assertTrue(clip.pcm.all{it.toInt()==7})
 }
 @Test fun rejectsTinySilentCallTailUntilAtLeastHalfASecondIsAvailable(){
  val ring=CallPcmRingBuffer();ring.append(ByteArray(4000),8000,1);assertFalse(ring.hasUsefulAudio());ring.append(ByteArray(4000),8000,1)
  assertTrue(ring.hasUsefulAudio());assertEquals(8000,ring.takeUsefulAudio()!!.pcm.size)
 }
}
