package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
class PreparedValueTest {
 @Test fun concurrentWarmAndGetShareOneVerification(){val count=AtomicInteger();val ready=CountDownLatch(1);val value=Any();val cache=PreparedValue{count.incrementAndGet();ready.await();value};cache.warm();val pool=Executors.newFixedThreadPool(4);try{val results=(1..8).map{pool.submit(Callable{cache.get()})};ready.countDown();results.forEach{assertSame(value,it.get(2,TimeUnit.SECONDS))};assertEquals(1,count.get())}finally{pool.shutdownNow()}}
 @Test fun failedPreparationCanBeRetried(){var count=0;val c=PreparedValue{if(++count==1)error("bad identity") else 7};try{c.get();fail()}catch(_:IllegalStateException){};assertEquals(7,c.get());assertEquals(2,count)}
 @Test fun failedWorkerLaunchDoesNotLeaveAnUnstartedFuture(){val launches=AtomicInteger();val c=PreparedValue({runnable->if(launches.incrementAndGet()==1)throw IllegalStateException("thread start failed") else Thread(runnable,"prepared-value-test").apply{isDaemon=true;start()}},{9});try{c.warm();fail()}catch(_:IllegalStateException){};assertEquals(9,c.get());assertEquals(2,launches.get())}
}
