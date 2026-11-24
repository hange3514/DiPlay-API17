package com.shilapi.xcertplay.usbbt

/**
 * Adaptive timing for HCI bring-up (Task: handshake timing validation).
 *
 * Background (public knowledge, no proprietary source):
 * - Bluetooth Core Spec Vol 4 Part D/E defines HCI_Reset as the mandatory
 *   first command; a healthy controller answers Command Complete quickly
 *   (typically well under a second on USB).
 * - Linux's btusb stack issues init commands with HCI_INIT_TIMEOUT (10 s in
 *   the kernel) because some dongles need seconds after USB enumeration
 *   before their HCI engine is ready.
 * - Broadcom's brcm_patchram_plus waits ~1 s ("--tosleep 1000") after a
 *   patchram upload before enabling HCI.
 * - A firmware-less Broadcom/Realtek chip never answers HCI_Reset at all;
 *   retrying forever is pointless — a few attempts with backoff, then the
 *   firmware-recovery path.
 *
 * This file is pure Kotlin (no Android dependency) so the retry/backoff
 * logic is JVM-unit-testable. [UsbBtHci] plugs its real transport in.
 */
object UsbBtTiming {

    /**
     * Retry policy for one HCI init step.
     *
     * @param timeoutMs per-attempt command timeout.
     * @param maxAttempts total attempts (1 = no retry).
     * @param backoffBaseMs sleep before the 2nd attempt; multiplied by
     * [backoffMultiplier] after each further failure.
     */
    data class RetryPolicy(
        val timeoutMs: Int,
        val maxAttempts: Int = 1,
        val backoffBaseMs: Long = 500L,
        val backoffMultiplier: Double = 2.0,
    ) {
        init {
            require(timeoutMs > 0) { "timeoutMs must be positive" }
            require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
            require(backoffBaseMs >= 0) { "backoffBaseMs must be >= 0" }
        }

        /** Backoff sleep before attempt [attemptIndex] (0-based, so attempt 1 sleeps [backoffBaseMs]). */
        fun backoffFor(attemptIndex: Int): Long {
            if (attemptIndex <= 0) return 0L
            var v = backoffBaseMs.toDouble()
            repeat(attemptIndex - 1) { v *= backoffMultiplier }
            return v.toLong().coerceAtLeast(0L)
        }
    }

    /** Measured outcome of one init step. */
    data class StepTiming(
        val name: String,
        val durationMs: Long,
        val attempts: Int,
        val success: Boolean,
        val lastError: String?,
    )

    /** Full bring-up report, suitable for the diagnostic log / timing probe. */
    data class TimingReport(
        val steps: List<StepTiming>,
        val totalMs: Long,
    ) {
        val allOk: Boolean get() = steps.all { it.success }

        fun toText(): String = buildString {
            appendLine("HCI timing report (total ${totalMs}ms):")
            for (s in steps) {
                appendLine(
                    "  ${s.name}: ${if (s.success) "OK" else "FAIL"} " +
                        "${s.durationMs}ms attempts=${s.attempts}" +
                        (s.lastError?.let { " err=$it" } ?: ""),
                )
            }
        }
    }

    /**
     * Run [block] with retry/backoff, measuring wall time.
     *
     * @param block receives the per-attempt timeout and returns
     * Result.success(value) / Result.failure(cause).
     * @param sleeper injectable sleep (tests pass a fake).
     * @param clock injectable millisecond clock (tests pass a fake).
     */
    fun <T> runTimed(
        name: String,
        policy: RetryPolicy,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        clock: () -> Long = { System.currentTimeMillis() },
        block: (timeoutMs: Int) -> Result<T>,
    ): Pair<Result<T>, StepTiming> {
        val start = clock()
        var lastError: String? = "not attempted"
        var attempts = 0
        repeat(policy.maxAttempts) { i ->
            attempts = i + 1
            if (i > 0) {
                val backoff = policy.backoffFor(i)
                if (backoff > 0) sleeper(backoff)
            }
            val result = runCatching { block(policy.timeoutMs) }.getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                return result to StepTiming(
                    name = name,
                    durationMs = clock() - start,
                    attempts = attempts,
                    success = true,
                    lastError = null,
                )
            }
            lastError = result.exceptionOrNull()?.message ?: "unknown failure"
        }
        return Result.failure<T>(RuntimeException(lastError)) to StepTiming(
            name = name,
            durationMs = clock() - start,
            attempts = attempts,
            success = false,
            lastError = lastError,
        )
    }

    // ------------------------------------------------------------------
    // Default policies, derived from the public references above.
    // ------------------------------------------------------------------

    /**
     * HCI_Reset is the canary: healthy chips answer in < 1 s; firmware-less
     * chips never answer. Short attempts with backoff, then give up and let
     * the caller try firmware recovery.
     */
    val RESET_POLICY = RetryPolicy(timeoutMs = 2_000, maxAttempts = 3, backoffBaseMs = 500)

    /** Informational reads (version/BD_ADDR/buffer size): one retry is enough. */
    val READ_POLICY = RetryPolicy(timeoutMs = 2_000, maxAttempts = 2, backoffBaseMs = 300)

    /** Configuration writes (scan enable, local name): single attempt; a
     * failure here is fatal anyway and retrying rarely helps. */
    val WRITE_POLICY = RetryPolicy(timeoutMs = 2_000, maxAttempts = 1)
}
