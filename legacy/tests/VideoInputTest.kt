package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class VideoInputTest {
 private val idr=byteArrayOf(0,0,0,1,0x65,1)
 private val delta=byteArrayOf(0,0,1,0x41,2)
 @Test fun repeatedConfigDoesNotDropQueuedFramesOrRestart(){val q=VideoInput();val config=byteArrayOf(1,2);assertTrue(q.configure(config));q.offer(idr);assertFalse(q.configure(config.copyOf()));val a=q.poll()!!;q.offer(delta);val b=q.poll()!!;assertSame(a.config,b.config);assertArrayEquals(delta,b.bytes)}
 @Test fun newConfigurationDropsOldQueuedFrames(){val q=VideoInput();q.configure(byteArrayOf(1));q.offer(idr);q.configure(byteArrayOf(2));q.offer(delta);assertNull(q.poll());q.offer(idr);assertArrayEquals(byteArrayOf(2),q.poll()!!.config)}
 @Test fun polledFrameRetainsItsConfiguration(){val q=VideoInput();q.configure(byteArrayOf(1));q.offer(idr);val frame=q.poll()!!;q.configure(byteArrayOf(2));assertArrayEquals(byteArrayOf(1),frame.config)}
 @Test fun overflowRequiresKeyframe(){val q=VideoInput(1);q.configure(byteArrayOf(1));q.offer(idr);assertFalse(q.offer(delta));assertNull(q.poll());q.offer(delta);assertNull(q.poll());q.offer(idr);assertNotNull(q.poll())}
 @Test fun copiesInputOwnership(){val q=VideoInput();val config=byteArrayOf(1);q.configure(config);config[0]=2;val input=idr.copyOf();q.offer(input);input[5]=9;val frame=q.poll()!!;assertEquals(1,frame.config[0].toInt());assertArrayEquals(idr,frame.bytes)}
 @Test fun uniqueDecryptedFrameMovesWithoutAllocation(){val q=VideoInput();q.configure(byteArrayOf(1));val frame=idr.copyOf();assertTrue(q.offerOwned(frame));assertSame(frame,q.poll()!!.bytes)}
 @Test fun ownedOverflowStillRequiresReferenceRecovery(){val q=VideoInput(1);q.configure(byteArrayOf(1));q.offerOwned(idr.copyOf());assertFalse(q.offerOwned(delta.copyOf()));assertNull(q.poll(0));assertFalse(q.offerOwned(delta.copyOf()));val next=idr.copyOf();assertTrue(q.offerOwned(next));assertSame(next,q.poll(0)!!.bytes)}
 @Test fun oversizedReferenceCannotLeaveFollowingDeltasInBrokenGop(){val q=VideoInput();q.configure(byteArrayOf(1));q.offer(idr);q.poll(0);assertFalse(q.offerOwned(ByteArray(1024*1024+1)));assertFalse(q.offerOwned(delta));assertNull(q.poll(0));assertTrue(q.offerOwned(idr));assertSame(idr,q.poll(0)!!.bytes)}
 @Test fun emptyPacketDoesNotReachCodecOrBreakRecovery(){val q=VideoInput();q.configure(byteArrayOf(1));assertTrue(q.offerOwned(byteArrayOf()));assertNull(q.poll(0));assertFalse(q.offerOwned(delta));q.offerOwned(idr);q.poll(0);assertTrue(q.offerOwned(byteArrayOf()));assertNull(q.poll(0));assertTrue(q.offerOwned(delta))}
 @Test fun defaultQueueBoundsLatencyAndResynchronizes(){val q=VideoInput();q.configure(byteArrayOf(1));q.offer(idr);repeat(5){assertTrue(q.offer(delta))};assertEquals(6,q.size());assertFalse(q.offer(delta));assertEquals(0,q.size());assertFalse(q.offer(delta));assertTrue(q.offer(idr));assertEquals(1,q.size())}
 @Test fun idlePollBudgetCanBeShortForDecoderDrain(){val q=VideoInput();val at=System.nanoTime();assertNull(q.poll(1));assertTrue(System.nanoTime()-at<1000000000L)}
 @Test fun arrivalClockIsLocalAndMonotonic(){val q=VideoInput();q.configure(byteArrayOf(1));val before=System.nanoTime();q.offer(idr);val frame=q.poll()!!;assertTrue(frame.arrivedNs>=before);assertTrue(frame.arrivedNs<=System.nanoTime())}
 @Test fun avccLength257IsNotMisreadAsThreeByteStartCode(){val bytes=ByteArray(261);bytes[2]=1;bytes[3]=1;bytes[4]=0x65;assertTrue(VideoInput.isKeyframe(bytes));bytes[4]=0x41;assertFalse(VideoInput.isKeyframe(bytes))}
 @Test fun bothAvccAndAnnexBAccepted(){assertTrue(VideoInput.isKeyframe(idr));assertTrue(VideoInput.isKeyframe(byteArrayOf(0,0,0,2,0x65,1)));assertFalse(VideoInput.isKeyframe(delta));assertFalse(VideoInput.isKeyframe(byteArrayOf(0,0,0,20,0x65)));assertFalse(VideoInput.isKeyframe(byteArrayOf()))}
}
