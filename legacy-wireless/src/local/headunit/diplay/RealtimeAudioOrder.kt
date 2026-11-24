package local.headunit.diplay

import java.util.concurrent.TimeUnit

/** Single-consumer RTP sequencing. Contiguous packets take the direct path. */
internal class RealtimeAudioOrder(
    private val queue: AudioInput,
    clockRate: Int,
    gapWaitMs: Long,
    private val now: () -> Long = System::nanoTime,
) {
    constructor(
        queue: AudioInput,
        clockRate: Int = 48_000,
        now: () -> Long = System::nanoTime,
    ) : this(queue, clockRate, 25L, now)

    private data class RestartCandidate(val packet: AudioInput.Packet, val sequence: Int)
    private data class HeldPacket(val packet: AudioInput.Packet, val observedNs: Long)

    init { require(clockRate > 0) }
    private val gapWaitNs = TimeUnit.MILLISECONDS.toNanos(gapWaitMs)

    private val maxPacketSamples = (clockRate * 120L / 1000).coerceAtLeast(1)
    private val sameSequenceJumpSamples = (clockRate / 20L).coerceAtLeast(1)
    private val restartLeadSamples = (clockRate / 10L).coerceAtLeast(1)
    private val timelineJumpSamples = (clockRate / 4L).coerceAtLeast(1)
    private val immediateSeekSamples = clockRate * 2L
    private val pending = HashMap<Int, HeldPacket>()
    private val retiredSources = LinkedHashMap<Int, Long>()
    private val playedSequences = IntArray(PLAYED_HISTORY_SIZE) { -1 }
    private val playedSamples = IntArray(PLAYED_HISTORY_SIZE)
    private val prefillSeen = LongArray(FORWARD_SEQUENCE_LIMIT / Long.SIZE_BITS + 1)
    private var pendingFrames = 0
    private var nextSequence = -1
    private var activeSsrc = 0
    private var restartCandidate: RestartCandidate? = null
    private var lastSample = 0
    private var gapObservedNs: Long? = null
    private var duplicateOrLate = 0L
    private var recoveredGaps = 0L
    private var skippedPackets = 0L
    var generation = 0L
        private set
    private var expiredPackets = 0L

    fun poll(timeoutMs: Long, allowGapWait: () -> Boolean): AudioInput.Packet? {
        val end = now() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        repeat(MAX_PACKETS_PER_POLL) {
            pruneExpired()
            if (pending.isNotEmpty()) {
                takePending(nextSequence)?.let {
                    restartCandidate = null
                    return deliver(it, nextSequence)
                }
            }

            // Try already available data even when the audio device has no runway for waiting.
            val packet = queue.poll(0) ?: run {
                val time = now()
                val gapWaitNs = remainingGapNs(time)
                if (pending.isNotEmpty() && (gapWaitNs < ONE_MS_NS || !allowGapWait())) {
                    return skipGap()
                }
                val remainingNs = minOf(end - time, gapWaitNs)
                val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNs)
                if (waitMs <= 0) return null
                queue.poll(waitMs) ?: return if (
                    pending.isNotEmpty() && remainingGapNs(now()) < ONE_MS_NS
                ) skipGap() else null
            }
            accept(packet)?.let { return it }
            if (pending.size >= MAX_PENDING_PACKETS) {
                return queuedExpectedPacket()?.let(::accept) ?: skipGap()
            }
        }
        // Bound work per poll during a burst of duplicates or late datagrams.
        return if (pending.isNotEmpty() && (remainingGapNs(now()) < ONE_MS_NS || !allowGapWait())) {
            skipGap()
        } else null
    }

    fun bufferedFrames(): Int {
        pruneExpired()
        return pendingFrames
    }

    fun awaitPrefill(first: AudioInput.Packet, targetFrames: Int, maxWaitMs: Long): Int =
        queue.awaitPrefill(first, targetFrames, maxWaitMs) { queued -> countPrefill(first, queued) }

    private fun countPrefill(first: AudioInput.Packet, queued: Iterable<AudioInput.Packet>): Int {
        prefillSeen.fill(0L)
        val current = now()
        var frames = first.audioFrames.toLong()
        for ((sequence, held) in pending) {
            if (!queue.expired(held.packet) && countPrefillSequence(sequenceDistance(sequence))) {
                frames += held.packet.audioFrames
            }
        }
        for (packet in queued) {
            val source = ssrc(packet.bytes)
            if (source != activeSsrc) {
                val retiredAt = retiredSources[source]
                if (retiredAt != null && current - retiredAt <= RETIRED_SOURCE_NS) continue
                return -1 // The orderer, not this snapshot, applies the new source.
            }
            val distance = sequenceDistance(sequence(packet.bytes))
            val sampleDelta = (packet.sample - first.sample).toLong()
            if (distance > FORWARD_SEQUENCE_LIMIT && sampleDelta >= -timelineJumpSamples) return -1
            if (distance == 0 && (sampleDelta < -immediateSeekSamples ||
                    (pending.isNotEmpty() && sampleDelta > immediateSeekSamples))) return -1
            if (distance < 0 || sampleDelta < 0) continue
            if (sampleDelta > (distance + 1L) * maxPacketSamples + timelineJumpSamples) continue
            if (countPrefillSequence(distance)) frames += packet.audioFrames
        }
        return frames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun countPrefillSequence(distance: Int): Boolean {
        if (distance !in 0..FORWARD_SEQUENCE_LIMIT) return false
        val word = distance / Long.SIZE_BITS
        val bit = 1L shl (distance % Long.SIZE_BITS)
        if (prefillSeen[word] and bit != 0L) return false
        prefillSeen[word] = prefillSeen[word] or bit
        return true
    }

    fun summary(): String =
        "rtpDuplicateOrLate=$duplicateOrLate rtpRecoveredGaps=$recoveredGaps " +
            "rtpSkippedPackets=$skippedPackets rtpResets=$generation rtpExpiredPackets=$expiredPackets"

    private fun accept(packet: AudioInput.Packet): AudioInput.Packet? {
        val sequence = sequence(packet.bytes)
        val ssrc = ssrc(packet.bytes)
        if (nextSequence < 0) {
            activeSsrc = ssrc
            return deliver(packet, sequence)
        }
        if (ssrc != activeSsrc) {
            val time = now()
            retiredSources.values.removeAll { time - it > RETIRED_SOURCE_NS }
            if (retiredSources.containsKey(ssrc)) {
                duplicateOrLate++
                return null
            }
            if (retiredSources.size == MAX_RETIRED_SOURCES) {
                retiredSources.remove(retiredSources.keys.first())
            }
            retiredSources[activeSsrc] = time
            activeSsrc = ssrc
            return resetAndDeliver(packet, sequence)
        }

        // Signed modular subtraction handles both RTP sequence and timestamp wrap.
        val delta = sequenceDistance(sequence)
        val sampleDelta = (packet.sample - lastSample).toLong()
        val slot = sequence % PLAYED_HISTORY_SIZE
        val reusedSequence = delta < 0 && playedSequences[slot] == sequence
        if (delta == 0 && (sampleDelta < -immediateSeekSamples ||
                (pending.isNotEmpty() && sampleDelta > immediateSeekSamples))) {
            return resetAndDeliver(packet, sequence)
        }
        val possibleRestart = when {
            reusedSequence -> kotlin.math.abs((packet.sample - playedSamples[slot]).toLong()) >= sameSequenceJumpSamples
            delta < 0 -> sampleDelta > restartLeadSamples ||
                -sampleDelta > (1L - delta) * maxPacketSamples + timelineJumpSamples
            else -> sampleDelta < -timelineJumpSamples
        }
        if (possibleRestart) {
            val previous = restartCandidate
            if (previous != null && !queue.expired(previous.packet) &&
                packet.arrivedNs - previous.packet.arrivedNs <= RESTART_CONFIRM_NS
            ) {
                val sequenceStep = (sequence - previous.sequence).toShort().toInt()
                val sampleStep = (packet.sample - previous.packet.sample).toLong()
                if (sequenceStep == 1 && sampleStep in 1..maxPacketSamples) {
                    val first = resetAndDeliver(previous.packet, previous.sequence)
                    hold(packet, sequence)
                    return first
                }
                if (sequenceStep == -1 && -sampleStep in 1..maxPacketSamples) {
                    val first = resetAndDeliver(packet, sequence)
                    hold(previous.packet, previous.sequence)
                    return first
                }
            }
            restartCandidate = RestartCandidate(packet, sequence)
            return null
        }
        if (delta == 0) {
            restartCandidate = null
            if (pending.isNotEmpty()) recoveredGaps++
            return deliver(packet, sequence)
        }
        if (delta > FORWARD_SEQUENCE_LIMIT) return resetAndDeliver(packet, sequence)
        if (delta < 0 || pending.containsKey(sequence)) {
            duplicateOrLate++
            return null
        }
        hold(packet, sequence)
        return null
    }

    private fun hold(packet: AudioInput.Packet, sequence: Int) {
        val observedNs = now()
        pending[sequence] = HeldPacket(packet, observedNs)
        pendingFrames += packet.audioFrames
        if (gapObservedNs == null) gapObservedNs = observedNs
    }

    private fun resetAndDeliver(packet: AudioInput.Packet, sequence: Int): AudioInput.Packet {
        pending.clear()
        pendingFrames = 0
        restartCandidate = null
        playedSequences.fill(-1)
        generation++
        return deliver(packet, sequence)
    }

    private fun deliver(packet: AudioInput.Packet, sequence: Int): AudioInput.Packet {
        nextSequence = (sequence + 1) and 0xffff
        lastSample = packet.sample
        val slot = sequence % PLAYED_HISTORY_SIZE
        playedSequences[slot] = sequence
        playedSamples[slot] = packet.sample
        if (pending.isEmpty()) gapObservedNs = null
        return packet
    }

    private fun remainingGapNs(time: Long): Long =
        gapObservedNs?.let { gapWaitNs - (time - it) } ?: Long.MAX_VALUE

    private fun sequenceDistance(sequence: Int): Int = (sequence - nextSequence).toShort().toInt()

    private fun queuedExpectedPacket(): AudioInput.Packet? = queue.pollForReorder(MAX_PACKETS_PER_POLL) { packet ->
        val distance = sequenceDistance(sequence(packet.bytes))
        val sampleDelta = (packet.sample - lastSample).toLong()
        when {
            ssrc(packet.bytes) != activeSsrc || distance > FORWARD_SEQUENCE_LIMIT ||
                sampleDelta < -timelineJumpSamples -> AudioInput.ReorderSearch.BOUNDARY
            distance != 0 -> AudioInput.ReorderSearch.CONTINUE
            sampleDelta !in 0..immediateSeekSamples -> AudioInput.ReorderSearch.BOUNDARY
            else -> AudioInput.ReorderSearch.MATCH
        }
    }

    private fun sequence(bytes: ByteArray): Int =
        ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)

    private fun ssrc(bytes: ByteArray): Int =
        (bytes[8].toInt() shl 24) or ((bytes[9].toInt() and 255) shl 16) or
            ((bytes[10].toInt() and 255) shl 8) or (bytes[11].toInt() and 255)

    private fun takePending(sequence: Int): AudioInput.Packet? {
        val held = pending.remove(sequence) ?: return null
        pendingFrames -= held.packet.audioFrames
        if (held.observedNs == gapObservedNs) updateGapObservation()
        return held.packet
    }

    private fun skipGap(): AudioInput.Packet? {
        pruneExpired()
        val nearest = pending.keys.minByOrNull(::sequenceDistance) ?: return null
        skippedPackets += sequenceDistance(nearest).toLong()
        return deliver(takePending(nearest)!!, nearest)
    }

    private fun pruneExpired() {
        if (pending.isEmpty()) return
        var oldestRemoved = false
        val entries = pending.values.iterator()
        while (entries.hasNext()) {
            val held = entries.next()
            if (queue.expired(held.packet)) {
                pendingFrames -= held.packet.audioFrames
                expiredPackets++
                queue.drop()
                entries.remove()
                if (held.observedNs == gapObservedNs) oldestRemoved = true
            }
        }
        if (oldestRemoved) updateGapObservation()
    }

    /** Each remaining gap is timed from the earliest packet that still demonstrates it. */
    private fun updateGapObservation() {
        gapObservedNs = pending.values.minOfOrNull { it.observedNs }
    }

    private companion object {
        const val MAX_PENDING_PACKETS = 8
        const val MAX_PACKETS_PER_POLL = 16
        const val MAX_RETIRED_SOURCES = 8
        const val PLAYED_HISTORY_SIZE = 128
        const val FORWARD_SEQUENCE_LIMIT = 512
        const val ONE_MS_NS = 1_000_000L
        const val RESTART_CONFIRM_NS = 160 * ONE_MS_NS
        const val RETIRED_SOURCE_NS = 950 * ONE_MS_NS
    }
}
