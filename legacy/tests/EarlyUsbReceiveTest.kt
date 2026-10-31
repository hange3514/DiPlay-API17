package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EarlyUsbReceiveTest {
 @Test fun handoffKeepsCompletedBytesAndUsesOneReader(){
  val reader=EarlyUsbReceive();val lock=Any();val entered=CountDownLatch(1);val finish=CountDownLatch(1)
  val reads=AtomicInteger();val bytes=AtomicInteger();val errors=AtomicInteger()
  reader.start(lock,{reads.incrementAndGet();entered.countDown();check(finish.await(2,TimeUnit.SECONDS));bytes.set(140);true},{errors.incrementAndGet()})
  assertTrue(entered.await(2,TimeUnit.SECONDS));reader.handoff();finish.countDown();reader.await()
  synchronized(lock){assertEquals(140,bytes.get())};assertEquals(1,reads.get());assertEquals(0,errors.get())
 }
 @Test fun handoffBeforeStartDoesNotTouchUsb(){val r=EarlyUsbReceive();val calls=AtomicInteger();r.handoff();r.start(Any(),{calls.incrementAndGet();true},{calls.incrementAndGet()});r.await();assertEquals(0,calls.get())}
 @Test fun failureIsReportedOnce(){val r=EarlyUsbReceive();val calls=AtomicInteger();r.start(Any(),{throw java.io.IOException("detached")},{calls.incrementAndGet()});r.await();assertEquals(1,calls.get())}
 @Test fun emptyReadsStopAtHandoff(){val r=EarlyUsbReceive();val calls=AtomicInteger();r.start(Any(),{calls.incrementAndGet();r.handoff();false},{fail()});r.await();assertEquals(1,calls.get())}
 @Test fun twoAsyncCompletionOwnersAreImpossibleForEverySupportedProfile(){for(p in 0..10)assertFalse(UsbStartupProfile.queued(p)&&UsbStartupProfile.queuedReceive(p))}
 @Test fun receiveComparisonUsesMatchedSyncSender(){assertFalse(UsbStartupProfile.queued(6));assertFalse(UsbStartupProfile.queued(7));assertFalse(UsbStartupProfile.queued(9));assertFalse(UsbStartupProfile.queuedReceive(6));assertTrue(UsbStartupProfile.queuedReceive(7));assertTrue(UsbStartupProfile.queuedReceive(9));assertTrue(UsbStartupProfile.earlyReceive(8));assertTrue(UsbStartupProfile.earlyReceive(9))}
}
