package local.headunit.diplay

/** Bounded counters for an uplink that may capture and send on separate workers. */
internal class MicrophoneUplinkTiming(
    private val frameMillis: Int,
    private val windowNs: Long = 5_000_000_000L,
) {
    private val frameNs = frameMillis * 1_000_000L
    private var windowStartNs = UNSET
    private var lastReadNs = UNSET
    private var lastFrameNs = UNSET
    private var lastSendNs = UNSET
    private var dirty = false
    private var readCalls = 0
    private var readFrames = 0L
    private var readEmpty = 0
    private var readErrors = 0
    private var maxReadNs = 0L
    private var maxReadGapNs = 0L
    private var capturedFrames = 0
    private var maxFrameGapNs = 0L
    private var maxProcessNs = 0L
    private var packets = 0
    private var maxSendGapNs = 0L
    private var maxEncodeNs = 0L
    private var cpuAtMaxEncodeNs = -1L
    private var maxEncodeCpuNs = -1L
    private var maxSealNs = 0L
    private var maxSendNs = 0L
    private var encodeErrors = 0
    private var burstPackets = 0
    private var latePackets = 0
    private var queueMaxDepth = 0
    private var queueDropped = 0
    private var queueUnderruns = 0

    init {
        require(frameMillis > 0)
        require(windowNs > 0)
    }

    /** [framesOrError] counts hardware frames, or holds a negative read error. */
    @Synchronized
    fun onRead(startedNs: Long, completedNs: Long, framesOrError: Int) {
        markActive(startedNs)
        readCalls++
        maxReadNs = maxOf(maxReadNs, completedNs - startedNs)
        when {
            framesOrError > 0 -> {
                readFrames += framesOrError
                if (lastReadNs != UNSET) maxReadGapNs = maxOf(maxReadGapNs, completedNs - lastReadNs)
                lastReadNs = completedNs
            }
            framesOrError == 0 -> readEmpty++
            else -> readErrors++
        }
    }

    /** Counts one complete frame at the negotiated rate after local PCM processing. */
    @Synchronized
    fun onFrame(completedNs: Long, processNs: Long) {
        markActive(completedNs)
        capturedFrames++
        maxProcessNs = maxOf(maxProcessNs, processNs)
        if (lastFrameNs != UNSET) maxFrameGapNs = maxOf(maxFrameGapNs, completedNs - lastFrameNs)
        lastFrameNs = completedNs
    }

    /** Measures local UDP submission. Delivery to the iPhone is outside this measurement. */
    @Synchronized
    fun onSend(completedNs: Long, encodeNs: Long, sealNs: Long, sendNs: Long, encodeCpuNs: Long = -1L) {
        markActive(completedNs)
        packets++
        recordEncode(encodeNs, encodeCpuNs)
        maxSealNs = maxOf(maxSealNs, sealNs)
        maxSendNs = maxOf(maxSendNs, sendNs)
        if (lastSendNs != UNSET) {
            val gapNs = completedNs - lastSendNs
            maxSendGapNs = maxOf(maxSendGapNs, gapNs)
            if (gapNs in 0 until frameNs / 2) burstPackets++
            if (gapNs > frameNs + frameNs / 2) latePackets++
        }
        lastSendNs = completedNs
    }

    @Synchronized
    fun onEncodeFailure(completedNs: Long, encodeNs: Long, encodeCpuNs: Long = -1L) {
        markActive(completedNs)
        encodeErrors++
        recordEncode(encodeNs, encodeCpuNs)
    }

    private fun recordEncode(wallNs: Long, cpuNs: Long) {
        if (wallNs > maxEncodeNs || (wallNs == maxEncodeNs && cpuAtMaxEncodeNs < 0)) {
            maxEncodeNs = wallNs
            cpuAtMaxEncodeNs = cpuNs
        }
        maxEncodeCpuNs = maxOf(maxEncodeCpuNs, cpuNs)
    }

    private fun microsOrUnknown(nanos: Long): Long = if (nanos < 0) -1L else nanos / 1_000L

    @Synchronized
    fun onQueue(depth: Int, droppedFrames: Int = 0) {
        queueMaxDepth = maxOf(queueMaxDepth, depth)
        queueDropped += droppedFrames
    }

    @Synchronized
    fun onQueueUnderrun(nowNs: Long) {
        markActive(nowNs)
        queueUnderruns++
    }

    @Synchronized
    fun reportIfDue(nowNs: Long): String? = emit(nowNs, force = false)

    @Synchronized
    fun finish(nowNs: Long): String? = emit(nowNs, force = true)

    private fun markActive(nowNs: Long) {
        if (windowStartNs == UNSET) windowStartNs = nowNs
        dirty = true
    }

    private fun emit(nowNs: Long, force: Boolean): String? {
        if (!dirty) return null
        val elapsed = (nowNs - windowStartNs).coerceAtLeast(0L)
        if (!force && elapsed < windowNs) return null

        val report = "micFrameMs=$frameMillis packets=$packets maxSendGapMs=${maxSendGapNs / 1_000_000L}" +
            " micEncodeMaxUs=${maxEncodeNs / 1_000L} micSealMaxUs=${maxSealNs / 1_000L}" +
            " micSendMaxUs=${maxSendNs / 1_000L} windowMs=${elapsed / 1_000_000L}" +
            " micReadCalls=$readCalls micReadFrames=$readFrames micReadEmpty=$readEmpty micReadErrors=$readErrors" +
            " micReadMaxUs=${maxReadNs / 1_000L} micReadGapMs=${maxReadGapNs / 1_000_000L}" +
            " micCapturedFrames=$capturedFrames micCaptureGapMs=${maxFrameGapNs / 1_000_000L}" +
            " micProcessMaxUs=${maxProcessNs / 1_000L} micEncodeErrors=$encodeErrors" +
            " micBurstPackets=$burstPackets micLatePackets=$latePackets micQueueMaxDepth=$queueMaxDepth" +
            " micQueueDropped=$queueDropped micQueueUnderruns=$queueUnderruns" +
            " micPartialWindow=${if (elapsed < windowNs) 1 else 0}" +
            " micEncodeCpuAtMaxUs=${microsOrUnknown(cpuAtMaxEncodeNs)} micEncodeCpuMaxUs=${microsOrUnknown(maxEncodeCpuNs)}"

        windowStartNs = nowNs
        dirty = false
        readCalls = 0
        readFrames = 0L
        readEmpty = 0
        readErrors = 0
        maxReadNs = 0L
        maxReadGapNs = 0L
        capturedFrames = 0
        maxFrameGapNs = 0L
        maxProcessNs = 0L
        packets = 0
        maxSendGapNs = 0L
        maxEncodeNs = 0L
        cpuAtMaxEncodeNs = -1L
        maxEncodeCpuNs = -1L
        maxSealNs = 0L
        maxSendNs = 0L
        encodeErrors = 0
        burstPackets = 0
        latePackets = 0
        queueMaxDepth = 0
        queueDropped = 0
        queueUnderruns = 0
        return report
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
    }
}
