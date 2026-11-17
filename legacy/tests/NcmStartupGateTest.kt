package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class NcmStartupGateTest {
 @Test fun authenticationMustFinishBeforeOutboundCanRun(){
  val gate=NcmStartupGate();val worker=Executors.newSingleThreadExecutor()
  try{val entered=CountDownLatch(1);val result=worker.submit(Callable{entered.countDown();gate.await()})
   assertTrue(entered.await(1,TimeUnit.SECONDS))
   try{result.get(30,TimeUnit.MILLISECONDS);fail("released before start")}catch(_:TimeoutException){}
   gate.start();assertTrue(result.get(1,TimeUnit.SECONDS))
  }finally{gate.close();worker.shutdownNow()}
 }
 @Test fun closingDuringAuthenticationWakesWithoutSending(){
  val gate=NcmStartupGate();val worker=Executors.newSingleThreadExecutor()
  try{val result=worker.submit(Callable{gate.await()});gate.close();assertFalse(result.get(1,TimeUnit.SECONDS))}
  finally{gate.close();worker.shutdownNow()}
 }
 @Test fun startBeforeWorkerAndDuplicateStartAreSafe(){val g=NcmStartupGate();g.start();g.start();assertTrue(g.await())}
 @Test fun lateStartCannotResurrectClosedSession(){val g=NcmStartupGate();g.close();g.start();assertFalse(g.await())}
 @Test fun startReportsWhetherGateWasAlreadyClosed(){val open=NcmStartupGate();assertTrue(open.start());assertTrue(open.await());val closed=NcmStartupGate();closed.close();assertFalse(closed.start());assertFalse(closed.await())}
 @Test fun interruptedWaitDoesNotReleasePacket(){
  val g=NcmStartupGate();val done=CountDownLatch(1);var released=false
  val t=Thread{try{released=g.await()}catch(_:InterruptedException){}finally{done.countDown()}}
  try{t.start();t.interrupt();assertTrue(done.await(1,TimeUnit.SECONDS));assertFalse(released)}finally{g.close();t.join(1000)}
 }
}
