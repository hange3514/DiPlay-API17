package local.headunit.diplay

/** Main-thread state machine. Durations use elapsedRealtime, independent of wall-clock changes. */
class AutoConnectPolicy {
 private var attached=false
 private var absentSince=-1L
 private var stableSince=0L
 private var paused=false
 private var busyBefore=false
 private var launchUntil=0L
 private var nextAttempt=0L
 private var failures=0
 private var readyAt=0L
 private var hasReady=false
 private val stableConnectionMs=120_000L
 fun isPaused()=paused
 fun pause(){paused=true;launchUntil=0}
 fun manualResume(){paused=false;failures=0;nextAttempt=0;launchUntil=0}
 /** A first frame proves startup, not a stable session; only a long-lived session clears backoff. */
 fun ready(now:Long){if(!hasReady){readyAt=now;hasReady=true};launchUntil=0}
 /** Meet a pending debounce/backoff deadline without increasing the idle USB scan rate. */
 fun nextPollDelay(now:Long,present:Boolean,busy:Boolean):Long {
  if(!present||busy||paused)return 2000
  val deadline=when {
   now-stableSince<800 -> stableSince+800
   nextAttempt>now -> nextAttempt
   launchUntil>now -> launchUntil
   else -> return 2000
  }
  return (deadline-now).coerceIn(100,2000)
 }
 fun poll(now:Long,present:Boolean,busy:Boolean):Boolean {
  if(!present){
   if(absentSince<0)absentSince=now
   // Never treat an active iPhone re-enumeration as a new insertion.
   if(!busy&&now-absentSince>=2000){attached=false;paused=false;failures=0;launchUntil=0;nextAttempt=0;readyAt=0;hasReady=false}
   busyBefore=busy;return false
  }
  absentSince=-1
  if(!attached){attached=true;stableSince=now}
  if(busy){busyBefore=true;launchUntil=0;return false}
  if(busyBefore){
   busyBefore=false;launchUntil=0
   if(hasReady&&now-readyAt>=stableConnectionMs){failures=0;nextAttempt=0}
   readyAt=0;hasReady=false
   delay(now)
  }
  if(launchUntil>0){if(now<launchUntil)return false;launchUntil=0;delay(now)}
  if(paused||now-stableSince<800||now<nextAttempt)return false
  launchUntil=now+120000;return true
 }
 fun launchFailed(now:Long){busyBefore=false;launchUntil=0;if(now>=nextAttempt)delay(now)}
 private fun delay(now:Long){val delays=longArrayOf(3000,8000,20000,60000);nextAttempt=now+delays[failures.coerceAtMost(delays.lastIndex)];failures=(failures+1).coerceAtMost(4)}
}
