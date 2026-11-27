package local.headunit.diplay
import org.junit.Assert.*
import org.junit.Test
class AutoConnectPolicyTest {
 @Test fun anotherPhoneHasAFirstAttemptEvenAfterOldPhoneWasPaused(){
  val p=AutoConnectPolicy()
  assertFalse(p.poll(0,true,false,false))
  assertTrue(p.poll(800,true,false,false))
  p.pause()
  p.poll(900,true,true,false)
  assertFalse(p.poll(1000,true,false,false))
  p.deviceReplaced(1100)
  assertFalse(p.poll(1899,true,false,false))
  assertTrue(p.poll(1900,true,false,false))
 }
 @Test fun newPhoneCannotClearPersistedCrashGuard(){
  val p=AutoConnectPolicy()
  p.deviceReplaced(1100)
  assertFalse(p.poll(1900,true,false,true,true))
  assertTrue(p.isPaused())
 }
 @Test fun permissionActivityDoesNotConsumeTheReplacementPhonesFirstAttempt(){
  val p=AutoConnectPolicy()
  p.deviceReplaced(100)
  assertFalse(p.poll(1400,true,true,false,sessionBusy=false))
  assertTrue(p.poll(1500,true,false,false,sessionBusy=false))
  p.poll(1600,true,true,false,sessionBusy=true)
  assertFalse(p.poll(1700,true,false,false,sessionBusy=false))
  assertFalse(p.poll(4800,true,false,false,sessionBusy=false))
 }
 @Test fun noPhoneDoesNotLaunch(){val p=AutoConnectPolicy();for(n in 0L..10000L step 1000)assertFalse(p.poll(n,false,false))}
 @Test fun attachDebouncedAndLaunchDeduplicated(){val p=AutoConnectPolicy();assertFalse(p.poll(0,true,false));assertTrue(p.poll(800,true,false));assertFalse(p.poll(900,true,false));assertFalse(p.poll(5000,true,true));assertFalse(p.poll(9000,true,true))}
 @Test fun failureWaitsBeforeRetry(){val p=AutoConnectPolicy();p.poll(0,true,false);p.poll(800,true,false);p.poll(900,true,true);assertFalse(p.poll(1000,true,false));assertFalse(p.poll(3999,true,false));assertTrue(p.poll(4000,true,false))}
 @Test fun disabledAutoReconnectAllowsFirstAttemptButBlocksFailureRetry(){val p=AutoConnectPolicy();assertFalse(p.poll(0,true,false,false));assertTrue(p.poll(800,true,false,false));assertFalse(p.poll(900,true,true,false));assertFalse(p.poll(1000,true,false,false));assertFalse(p.poll(3999,true,false,false));assertTrue(p.poll(4000,true,false,true))}
 @Test fun stopWaitsForActualUnplug(){val p=AutoConnectPolicy();p.poll(0,true,false);p.pause();assertFalse(p.poll(800,true,false));p.poll(1000,false,false);assertFalse(p.poll(2000,true,false));p.poll(3000,false,false);p.poll(5001,false,false);assertFalse(p.poll(6000,true,false));assertTrue(p.poll(6800,true,false))}
 @Test fun activeReenumerationDoesNotRearmPause(){val p=AutoConnectPolicy();p.poll(0,true,true);p.pause();p.poll(1000,false,true);p.poll(10000,false,true);assertFalse(p.poll(10001,true,true));assertFalse(p.poll(20000,true,false));assertFalse(p.poll(60000,true,false))}
 @Test fun persistentCrashPauseSurvivesShortUnplugAndReattach(){val p=AutoConnectPolicy();assertFalse(p.poll(0,true,false,true,true));assertFalse(p.poll(1000,false,false,true,true));assertFalse(p.poll(3500,false,false,true,true));assertTrue(p.isPaused());assertFalse(p.poll(4000,true,false,true,true));assertFalse(p.poll(5000,true,false,true,true));assertTrue(p.isPaused());p.manualResume();assertTrue(p.poll(5100,true,false,true,false));assertFalse(p.poll(5900,true,false,true,false))}
 @Test fun persistentPauseClearsOnlyAfterGuardIsRemoved(){val p=AutoConnectPolicy();assertFalse(p.poll(0,true,false,true,true));assertFalse(p.poll(1000,false,false,true,true));assertFalse(p.poll(7000,false,false,true,false));assertFalse(p.isPaused());assertFalse(p.poll(7100,true,false,true,false));assertTrue(p.poll(7900,true,false,true,false))}
 @Test fun manualResumeOverridesPause(){val p=AutoConnectPolicy();p.poll(0,true,false);p.pause();p.manualResume();assertTrue(p.poll(800,true,false))}
 @Test fun abandonedLaunchIsNotAnImmediateLoop(){val p=AutoConnectPolicy();p.poll(0,true,false);assertTrue(p.poll(800,true,false));assertFalse(p.poll(120800,true,false));assertFalse(p.poll(123799,true,false));assertTrue(p.poll(123800,true,false))}
 @Test fun shortLivedVideoSessionDoesNotResetRetryBackoff(){val p=AutoConnectPolicy();p.poll(0,true,false);assertTrue(p.poll(800,true,false));p.poll(900,true,true);p.ready(1000);assertFalse(p.poll(10000,true,true));assertFalse(p.poll(45000,true,false));assertFalse(p.poll(47999,true,false));assertTrue(p.poll(48000,true,false))}
 @Test fun stableVideoSessionResetsRetryBackoffAfterItEnds(){val p=AutoConnectPolicy();p.poll(0,true,false);p.launchFailed(1000);p.launchFailed(5000);p.poll(13000,true,true);p.ready(14000);assertFalse(p.poll(140000,true,true));assertFalse(p.poll(150000,true,false));assertFalse(p.poll(152999,true,false));assertTrue(p.poll(153000,true,false))}
 @Test fun duplicateFailureDoesNotDoubleBackoff(){val p=AutoConnectPolicy();p.poll(0,true,false);p.poll(800,true,false);p.poll(900,true,true);assertFalse(p.poll(1000,true,false));p.launchFailed(1100);assertTrue(p.poll(4000,true,false))}
 @Test fun debounceDeadlineDoesNotWaitForAnotherTwoSecondScan(){val p=AutoConnectPolicy();assertFalse(p.poll(200,true,false));assertEquals(800,p.nextPollDelay(200,true,false));assertTrue(p.poll(1000,true,false));assertEquals(2000,p.nextPollDelay(1000,true,false))}
 @Test fun backoffUsesRemainingDelayButIdleAndPausedStayLowFrequency(){val p=AutoConnectPolicy();p.poll(0,true,false);p.poll(800,true,false);p.poll(900,true,true);p.poll(1000,true,false);assertEquals(2000,p.nextPollDelay(1000,true,false));assertFalse(p.poll(3000,true,false));assertEquals(1000,p.nextPollDelay(3000,true,false));assertTrue(p.poll(4000,true,false));p.pause();assertEquals(2000,p.nextPollDelay(4000,true,false));assertEquals(2000,p.nextPollDelay(4000,false,false));assertEquals(2000,p.nextPollDelay(4000,true,true))}
}
