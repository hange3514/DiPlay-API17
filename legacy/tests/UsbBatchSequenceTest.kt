package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class UsbBatchSequenceTest {
 private fun poll(s:UsbBatchSequence,t:Long,busy:Boolean=false,ready:Boolean=false,ended:Boolean=false,front:Boolean=true,phone:Boolean=true)=s.poll(t,busy,ready,ended,front,phone)
 @Test fun allTwelveTrialsWaitForReleaseAndDurableSave(){
  val s=UsbBatchSequence(0);var t=0L
  for(p in listOf(3,6,7,8,9,10,10,9,8,7,6,3)){
   val idle=s.idleMillis();val hold=s.observeMillis()
   assertEquals(UsbBatchSequence.NONE,poll(s,t,busy=true));assertEquals(UsbBatchSequence.NONE,poll(s,t+100))
   val start=t+100+idle
   assertEquals(UsbBatchSequence.START,poll(s,start));assertEquals(p,s.profile())
   assertEquals(UsbBatchSequence.NONE,poll(s,start+100,busy=true,ready=true))
   val end=start+100+hold
   assertEquals(UsbBatchSequence.CAPTURE,poll(s,end,busy=true,ready=true));assertTrue(s.success())
   assertEquals(UsbBatchSequence.NONE,poll(s,end+100));assertEquals(UsbBatchSequence.NONE,poll(s,end+3000))
   s.saved(end+3100);t=end+4000
  }
  assertEquals(UsbBatchSequence.NONE,poll(s,t));assertEquals(UsbBatchSequence.FINISH,poll(s,t+1200));assertEquals("complete",s.reason())
  assertEquals(UsbBatchSequence.NONE,poll(s,t+2000))
 }
 @Test fun stuckOwnerNeverStartsAnotherTrial(){val s=UsbBatchSequence(0);assertEquals(UsbBatchSequence.NONE,poll(s,19999,true));assertEquals(UsbBatchSequence.ABORT,poll(s,20000,true));assertEquals("cleanup_timeout",s.reason())}
 @Test fun idleMustRemainStable(){val s=UsbBatchSequence(0);poll(s,0);poll(s,1000,true);assertEquals(UsbBatchSequence.NONE,poll(s,1200));assertEquals(UsbBatchSequence.START,poll(s,2400))}
 @Test fun transientFirstFrameDoesNotPass(){val s=UsbBatchSequence(0);poll(s,0);poll(s,1200);poll(s,2000,true,true);poll(s,4000,true,false);assertEquals(UsbBatchSequence.NONE,poll(s,4500,true,true));assertEquals(UsbBatchSequence.CAPTURE,poll(s,7000,true,true))}
 @Test fun twoFailuresStopRemainingExperimentsAfterCleanup(){val s=UsbBatchSequence(0);var t=0L;repeat(2){poll(s,t);assertEquals(UsbBatchSequence.START,poll(s,t+1200));assertEquals(UsbBatchSequence.CAPTURE,poll(s,t+1400,ended=true));assertFalse(s.success());s.saved(t+1500);t+=2000};poll(s,t);assertEquals(UsbBatchSequence.FINISH,poll(s,t+1200));assertEquals("consecutive_failures",s.reason())}
 @Test fun timeoutIsPerTrial(){val s=UsbBatchSequence(0);poll(s,0);poll(s,1200);assertEquals(UsbBatchSequence.NONE,poll(s,48699,true));assertEquals(UsbBatchSequence.CAPTURE,poll(s,48700,true));assertEquals("connection_timeout",s.reason())}
 @Test fun backgroundAndUnplugAbortIncludingSaveBarrier(){for(front in listOf(true,false)){val s=UsbBatchSequence(0);assertEquals(UsbBatchSequence.ABORT,poll(s,10,front=front,phone=!front));assertEquals(UsbBatchSequence.NONE,poll(s,2000))}}
 @Test fun cancelNeverStartsFurtherWork(){val s=UsbBatchSequence(0);s.cancel();assertEquals(UsbBatchSequence.NONE,poll(s,2000))}
 @Test fun totalDeadlineCannotRunIndefinitely(){val s=UsbBatchSequence(0);assertEquals(UsbBatchSequence.ABORT,poll(s,1200000));assertEquals("batch_deadline",s.reason())}
 @Test fun everyVariantHasEveryRecoveryAndObservationWindow(){
  val s=UsbBatchSequence(0);var t=0L;val groups=mutableSetOf<String>()
  repeat(12){groups.add("${s.profile()}:${s.idleMillis()}:${s.observeMillis()}");poll(s,t);t+=s.idleMillis();assertEquals(UsbBatchSequence.START,poll(s,t));poll(s,t+1,true,true);t+=1+s.observeMillis();assertEquals(UsbBatchSequence.CAPTURE,poll(s,t,true,true));s.saved(t);t+=1}
  assertEquals(12,groups.size)
 }
 @Test fun endedConnectionCannotPassEvenWithStaleReady(){val s=UsbBatchSequence(0);poll(s,0);poll(s,1200);poll(s,1300,true,true);assertEquals(UsbBatchSequence.CAPTURE,poll(s,4000,ready=true,ended=true));assertFalse(s.success())}
 @Test fun profilesVaryOnlyIntendedFactor(){assertFalse(UsbStartupProfile.queued(1));assertTrue(UsbStartupProfile.queued(0));assertTrue(UsbStartupProfile.waitCarrier(2));assertTrue(UsbStartupProfile.waitCarrier(4));assertFalse(UsbStartupProfile.refreshFilter(2));assertTrue(UsbStartupProfile.refreshFilter(4));assertTrue(UsbStartupProfile.resetAlternate(3));for(p in listOf(-1,11,999))assertEquals(0,UsbStartupProfile.valid(p))}
}
