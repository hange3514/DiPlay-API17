package local.headunit.diplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupWriteBudgetTest {
 @Test fun firstStartupWaitCanOnlyBeClaimedOnce() {
  val budget=StartupWriteBudget()
  assertTrue(budget.claim())
  assertTrue(budget.isConsumed())
  assertFalse(budget.claim())
 }

 @Test fun preSessionWarmupConsumesBudgetEvenWhenAsyncSubmitIsUnsupported() {
  val budget=StartupWriteBudget()
  // The warmup attempt consumes the budget before its async/sync transport result is known.
  budget.consume()
  assertTrue(budget.isConsumed())
  assertFalse(budget.claim())
 }
}
