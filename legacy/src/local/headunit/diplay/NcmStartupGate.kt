package local.headunit.diplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Hold outbound traffic until the CarPlay start message is on the control channel.
 * Closing must wake an unauthenticated session without releasing a packet. */
class NcmStartupGate {
 private val warmup=CountDownLatch(1)
 private val ready=CountDownLatch(1)
 private val closed=AtomicBoolean(false)
 fun startWarmup():Boolean {if(closed.get())return false;warmup.countDown();return !closed.get()}
 fun start():Boolean {if(closed.get())return false;warmup.countDown();ready.countDown();return !closed.get()}
 fun close(){closed.set(true);warmup.countDown();ready.countDown()}
 fun awaitWarmup():Boolean {warmup.await();return !closed.get()}
 fun awaitReady():Boolean {ready.await();return !closed.get()}
 fun isReady():Boolean = ready.count==0L&&!closed.get()
 fun await():Boolean = awaitReady()
}
