package local.headunit.diplay

import kotlin.math.abs
import kotlin.math.roundToInt

/** Per-stream network timing. Inter-arrival burst gaps size the buffer, never the playback rate. */
internal class MusicPlayoutTuning(
    initialTargetMs: Int = WirelessMusicBuffer.START_MS,
) {
    private val windows = IntArray(3)
    private var windowIndex = 0
    private var windowStartNs = 0L
    private var greatestGapMs = 0
    private var previousArrivalNs: Long? = null
    private var expectedTargetMs = initialTargetMs.coerceIn(MIN_MS, MAX_MS)
    private var cushionMs = 0
    private var lastShortDryNs: Long? = null
    private var calmWindows = 0
    private var currentWindowDry = false

    val targetMs: Int
        get() = (expectedTargetMs + cushionMs).coerceAtMost(MAX_MS)

    fun packetArrived(atNs: Long) {
        val previous = previousArrivalNs
        if (previous != null && atNs <= previous) return
        previousArrivalNs = atNs
        if (previous == null) {
            windowStartNs = atNs
            return
        }
        val gapMs = (atNs - previous) / NS_PER_MS
        if (gapMs >= LONG_GAP_NS / NS_PER_MS) {
            // An idle stream or a prolonged outage is not an ordinary arrival gap.
            windowStartNs = atNs
            greatestGapMs = 0
            calmWindows = 0
            lastShortDryNs = null
            currentWindowDry = false
            return
        }
        greatestGapMs = maxOf(greatestGapMs, gapMs.toInt())
        if (atNs - windowStartNs < WINDOW_NS) return

        windows[windowIndex] = greatestGapMs
        windowIndex = (windowIndex + 1) % windows.size
        windowStartNs = atNs
        greatestGapMs = 0
        val observedTarget = ((windows.maxOrNull() ?: 0) + HEADROOM_MS).coerceIn(MIN_MS, MAX_MS)
        expectedTargetMs = maxOf(observedTarget, expectedTargetMs - 35)
        if (!currentWindowDry && cushionMs > 0 && ++calmWindows >= CALM_WINDOWS) {
            cushionMs = (cushionMs - 40).coerceAtLeast(0)
        }
        currentWindowDry = false
    }

    fun afterDry(dryNs: Long, atNs: Long): Int? {
        if (dryNs < SHORT_DRY_NS) return null
        calmWindows = 0
        currentWindowDry = true
        if (dryNs >= LONG_GAP_NS) {
            lastShortDryNs = null
            return targetMs
        }
        val extra = if (dryNs >= WirelessMusicBuffer.DRY_THRESHOLD_NS) {
            85
        } else {
            val previous = lastShortDryNs
            if (previous != null && atNs <= previous) return null
            if (previous == null || atNs - previous > SHORT_DRY_WINDOW_NS) {
                lastShortDryNs = atNs
                return null
            }
            45
        }
        lastShortDryNs = null
        cushionMs = (cushionMs + extra).coerceAtMost(255)
        return targetMs
    }

    companion object {
        const val MIN_MS = 250
        const val MAX_MS = 700
        private const val HEADROOM_MS = 65
        private const val NS_PER_MS = 1_000_000L
        private const val WINDOW_NS = 4_000 * NS_PER_MS
        private const val SHORT_DRY_NS = 30 * NS_PER_MS
        private const val SHORT_DRY_WINDOW_NS = 10_000 * NS_PER_MS
        private const val LONG_GAP_NS = 1_000 * NS_PER_MS
        private const val CALM_WINDOWS = 6
    }
}

/**
 * Measures slopes of the independent sender and playback clocks. Keeping the highest phase in a
 * window selects the end of a wireless burst and avoids reading a temporarily late packet as drift.
 * Reset on loss, pause, a reset playback head or an actual underrun. Tune only after both slopes
 * agree across three windows, and only once while this AudioTrack is running at its nominal rate.
 */
internal class MusicClockCalibration(private val nominalRate: Int, rtpClockRate: Int) {
    private val sender = ClockWindows(rtpClockRate)
    private val player = ClockWindows(nominalRate)
    private var settled = false
    private var lastPlaybackObservationNs: Long? = null

    val needsObservation: Boolean
        get() = !settled

    fun received(sample: Int, arrivedNs: Long) {
        if (!settled) sender.observe(sample.toLong() and UINT_MASK, arrivedNs)
    }

    /** Observe the track before refilling it, so a completed write cannot hide an empty buffer. */
    fun playedBeforeWrite(head: Long, queuedFrames: Long, nowNs: Long) {
        if (settled) return
        if (queuedFrames <= 0 || head < 0) {
            reset()
            return
        }
        val previous = lastPlaybackObservationNs
        if (previous != null && nowNs - previous < PLAYBACK_OBSERVATION_NS) return
        lastPlaybackObservationNs = nowNs
        player.observe(head, nowNs)
    }

    fun reset() {
        if (settled) return
        sender.reset()
        player.reset()
        lastPlaybackObservationNs = null
    }

    fun rearm() {
        settled = false
        reset()
    }

    fun correction(): Correction? {
        if (settled) return null
        val sourcePpm = sender.driftPpm() ?: return null
        val trackPpm = player.driftPpm() ?: return null
        // Bound individual clocks, not only their difference, to reject pauses and timestamp jumps.
        if (abs(sourcePpm) > 2_000 || abs(trackPpm) > 12_000) return null
        if (abs(trackPpm - sourcePpm) < 3_000) {
            settled = true
            return null
        }
        val desired = (nominalRate * (1_000_000.0 + sourcePpm) / (1_000_000.0 + trackPpm)).roundToInt()
        val bound = nominalRate / 100
        settled = true
        return Correction(desired.coerceIn(nominalRate - bound, nominalRate + bound), sourcePpm, trackPpm)
    }

    data class Correction(val rate: Int, val sourcePpm: Int, val trackPpm: Int)

    private class ClockWindows(private val rate: Int) {
        private data class Peak(val atNs: Long, val phaseNs: Double)

        private val peaks = arrayOfNulls<Peak>(4)
        private var peakIndex = 0
        private var totalFrames = 0L
        private var lastCounter = 0L
        private var lastNs: Long? = null
        private var originNs = 0L
        private var windowStartNs = 0L
        private var highPhaseNs = Double.NEGATIVE_INFINITY
        private var highAtNs = 0L

        fun observe(counter: Long, atNs: Long) {
            val previous = lastNs
            if (previous != null && atNs <= previous) return
            val frames = (counter - lastCounter) and UINT_MASK
            if (previous == null || atNs - previous >= 1_000_000_000L || frames > rate) {
                reset()
                originNs = atNs
                windowStartNs = atNs
            } else {
                totalFrames += frames
            }
            lastCounter = counter
            lastNs = atNs
            if (atNs - windowStartNs >= WINDOW_NS) {
                peaks[peakIndex] = Peak(highAtNs, highPhaseNs)
                peakIndex = (peakIndex + 1) % peaks.size
                windowStartNs = atNs
                highPhaseNs = Double.NEGATIVE_INFINITY
            }
            val phase = totalFrames * 1_000_000_000.0 / rate - (atNs - originNs)
            if (phase > highPhaseNs) {
                highPhaseNs = phase
                highAtNs = atNs
            }
        }

        fun reset() {
            peaks.fill(null)
            peakIndex = 0
            lastNs = null
            totalFrames = 0
            highPhaseNs = Double.NEGATIVE_INFINITY
        }

        fun driftPpm(): Int? {
            val first = peaks[peakIndex] ?: return null
            var prior = first
            var smallest = Double.POSITIVE_INFINITY
            var largest = Double.NEGATIVE_INFINITY
            for (index in 1 until peaks.size) {
                val next = peaks[(peakIndex + index) % peaks.size] ?: return null
                val elapsed = next.atNs - prior.atNs
                if (elapsed < WINDOW_NS / 3) return null
                val ppm = (next.phaseNs - prior.phaseNs) * 1_000_000.0 / elapsed
                smallest = minOf(smallest, ppm)
                largest = maxOf(largest, ppm)
                prior = next
            }
            if (largest - smallest > 1200.0) return null
            return ((prior.phaseNs - first.phaseNs) * 1_000_000.0 / (prior.atNs - first.atNs)).roundToInt()
        }
    }

    private companion object {
        const val UINT_MASK = 0xffffffffL
        const val WINDOW_NS = 8_000_000_000L
        const val PLAYBACK_OBSERVATION_NS = 80_000_000L
    }
}
