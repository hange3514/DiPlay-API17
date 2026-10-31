package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test

class NcmInitializationTest {
 private fun run(p:Int,resetResult:Int=0,earlyResult:Int=0,selectOk:Boolean=true):List<String>{
  val calls=mutableListOf<String>();var filters=0
  try{NcmInitialization.activate(p,{calls.add("reset");resetResult},{calls.add("probe")},
   {calls.add("select");selectOk},{calls.add("filter");if(filters++==0)earlyResult else 0},{})}
  catch(e:java.io.IOException){calls.add("failed")}
  return calls
 }
 @Test fun baselineDoesNotReset(){assertEquals(listOf("probe","select","filter"),run(0))}
 @Test fun defaultResetsBeforeAnyData(){assertEquals(3,UsbStartupProfile.DEFAULT);assertEquals(listOf("reset","probe","select","filter"),run(3))}
 @Test fun optionalResetFailureStillActivates(){for(p in listOf(3,5))assertEquals(listOf("reset","probe","select","filter"),run(p,resetResult=-32))}
 @Test fun earlyFilterOnlyWhileDisabled(){assertEquals(listOf("reset","probe","filter","select"),run(5))}
 @Test fun earlyFilterFailureRestoresOrdinaryOrder(){assertEquals(listOf("reset","probe","filter","select","filter"),run(5,earlyResult=-110))}
 @Test fun activationFailureNeverStartsData(){assertEquals(listOf("reset","probe","select","failed"),run(3,selectOk=false))}
 @Test fun callbackFailureIsNotSilentlySwallowed(){
  var selected=false
  try{NcmInitialization.activate(3,{throw IllegalStateException("disconnected")},{},{selected=true;true},{0},{});fail()}
  catch(expected:IllegalStateException){assertFalse(selected)}
 }
 @Test fun batchOrderCannotBeMutatedByCaller(){val p=UsbBatchSequence.plannedProfiles();p[0]=999;assertEquals(3,UsbBatchSequence(0).profile())}
}
