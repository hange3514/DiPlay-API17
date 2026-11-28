package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test

class AudioInputTest {
 @Test fun burstDropsOldestRatherThanDiscardingNewSpeech(){val q=AudioInput(3){0};for(n in 0..4)q.offer(ByteArray(12),n);assertEquals(3,q.size());assertEquals(2,q.drops());assertEquals(listOf(2,3,4),List(3){q.poll(0)!!.sample});assertNull(q.poll(0))}
 @Test fun oldQueuedAndHeldPacketsHaveSameLatencyBound(){var now=0L;val q=AudioInput(3){now};q.offer(ByteArray(12),1);val held=q.poll(0)!!;q.offer(ByteArray(12),2);now=251000000L;assertTrue(q.expired(held));assertNull(q.poll(0));assertEquals(1,q.drops());q.offer(ByteArray(12),3);assertEquals(3,q.poll(0)!!.sample)}
 @Test fun queuedDataOwnsItsBuffer(){val q=AudioInput(2){0};val bytes=ByteArray(12){1};q.offer(bytes,7);bytes[0]=9;assertEquals(1,q.poll(0)!!.bytes[0].toInt());q.offer(ByteArray(12),2);q.clear();assertNull(q.poll(0))}
 @Test fun malformedOrOversizedPacketsCannotGrowQueue(){val q=AudioInput(2){0};q.offer(ByteArray(11),0);q.offer(ByteArray(16385),0);assertEquals(2,q.drops());assertEquals(0,q.size())}
 @Test fun prefillCountsFramesWithoutConsumingOrCopyingPackets() {
  val q = AudioInput(4) { 0L }
  val firstBytes = ByteArray(32)
  q.offerOwned(firstBytes, 0, 240)
  val first = q.poll(0)!!
  assertSame(firstBytes, first.bytes)
  q.offerOwned(ByteArray(36), 240, 480)
  q.offerOwned(ByteArray(32), 720, 240)
  assertEquals(960, q.awaitPrefill(first, targetFrames = 960, maxWaitMs = 0))
  assertEquals(2, q.size())
  assertEquals(240, q.poll(0)!!.sample)
 }

 @Test fun prefillUsesOnlyLiveFramesAndBoundsQueueBytes() {
  var now = 0L
  val q = AudioInput(8, maxQueuedBytes = 64) { now }
  q.offerOwned(ByteArray(36), 0, 240)
  q.offerOwned(ByteArray(36), 240, 240)
  assertEquals(1, q.drops())
  val first = q.poll(0)!!
  assertEquals(240, first.sample)
  assertEquals(240, q.awaitPrefill(first, targetFrames = 960, maxWaitMs = 0))
  now = 251_000_000L
  assertEquals(0, q.awaitPrefill(first, targetFrames = 960, maxWaitMs = 60))
 }
}
