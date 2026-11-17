package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import local.headunit.diplay.LegacyInterface as UsbInterface
import local.headunit.diplay.*
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class NeighborWarmupWriteResult(
    val transferred: Int,
    val expectedBytes: Int,
    val durationMs: Long,
) {
    val complete: Boolean get() = transferred == expectedBytes
}

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val startupProfile: Int,
    private val controlInterface: Int,
    private val dataInterface: Int,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    // This bridge exclusively owns connection. close() takes BOTH operation locks
    // before closing it; no bulk operation can race descriptor reuse. Avoid two
    // extra syscalls/JNI transitions (dup + close) for every packet on this hot path.
    private val ioDescriptor = connection.fileDescriptor
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var writeDiagnosticBudget = 8
    private var notReadyWriteCalls = 0
    private var wroteFirstBlock = false
    /** Monotonic time when the first complete NCM bulk-OUT block was accepted by USB. */
    @Volatile var firstNcmBulkOutAcceptedAtNanos: Long? = null
        private set
    // USB host acceptance and partial inbound transfers do not prove the peer-side
    // NCM network is ready. Use carrier-up or the first complete NCM frame.
    private val firstNetworkReadyAtNanos = AtomicLong()
    val ncmNetworkReadyAtNanos: Long? get() = firstNetworkReadyAtNanos.get().takeIf { it > 0L }
    @Volatile private var receivedFirstBlock = false
    private val startupWaitBudget = StartupWriteBudget()
    private val startupPending = AtomicBoolean(true)
    // A pre-session warmup must remain pending when the phone starts sending NCM
    // input; only close/disconnect may cancel this one replayable NDP transfer.
    private val warmupPending = AtomicBoolean(true)
    private val receivePending = AtomicBoolean(true)
    private val earlyReceive = EarlyUsbReceive()
    private var queuedReceiveUsed=false
    private var initialReadCalls=0
    private var initialReadTimeouts=0
    private var firstReadAt=0L
    private var readWaitMs=0L
    private val openedAt = System.nanoTime()
    @Volatile private var linkUpAt = 0L
    @Volatile private var carrierUp = false
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private val frameDecoder = Ntb16StreamDecoder(
        inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE,
        onDiagnostic,
    )
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // API17 vendor UsbRequest cancellation can stall cleanup. Use the same bounded
    // native USBDEVFS_BULK path already proven by USBMUX; NTB reassembly handles 16K splits.
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusNotificationCount = AtomicLong()
    private val statusReadTimeoutCount = AtomicLong()
    private val statusReadErrorCount = AtomicLong()
    private val appleLinkNotificationCount = AtomicLong()
    private val appleLinkNotificationReported = AtomicBoolean(false)
    @Volatile private var lastAppleLinkWord = -1L
    @Volatile private var lastAppleLinkAtNanos = 0L
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    fun startEarlyReceive() {
        if(!UsbStartupProfile.earlyReceive(startupProfile))return
        checkOpen()
        earlyReceive.start(readLock,{
            checkOpen()
            val length=readChunk(500)
            if(length!=null){appendBuffered(readBuffer,length);true}else false
        },{error->
            synchronized(stateLock){if(!closed)failSession("NCM early receive failed",error)}
        })
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int): Boolean =
        send(frame, 0, frame.size, timeoutMillis)

    /** One short link-local neighbor-discovery write before iAP2 start. If NCM is not
     * ready yet, leave the first useful packet to the normal bounded startup path. */
    fun sendNeighborWarmup(frame: ByteArray, offset: Int, length: Int): NeighborWarmupWriteResult = synchronized(writeLock) {
        checkOpen()
        require(StartupUsbWrite.neighborDiscovery(frame, offset, length)) { "warmup accepts only IPv6 neighbor discovery" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, offset, length, sequence)
        val at = System.nanoTime()
        // This pre-session attempt is the one startup readiness wait for this link,
        // including the 100 ms fallback when USBDEVFS_SUBMITURB is unsupported.
        startupWaitBudget.consume()
        onDiagnostic("ncm startup wait budget consumed by pre-session NDP")
        val transferred = if (UsbStartupProfile.queuedPreSessionWarmup(startupProfile)) {
            val metrics = IntArray(6)
            val queued = NativeUsb.startupWrite(
                ioDescriptor,
                outEndpoint.address,
                block,
                PRESESSION_NDP_TIMEOUT_MS,
                warmupPending,
                metrics,
            )
            onDiagnostic(
                "ncm ndp prewarm queued result=$queued expected=${block.size} submitted=${metrics[0]} " +
                    "polls=${metrics[1]} durationMs=${metrics[2]} cancelled=${metrics[3]} " +
                    "actualBytes=${metrics[4]} urbStatus=${metrics[5]} inbound=$receivedFirstBlock",
            )
            if (queued == -20001) {
                onDiagnostic("ncm ndp prewarm queued unsupported before submit; short synchronous fallback")
                NativeUsb.write(ioDescriptor, outEndpoint.address, block, WARMUP_WRITE_TIMEOUT_MS)
            } else {
                queued
            }
        } else NativeUsb.write(ioDescriptor, outEndpoint.address, block, WARMUP_WRITE_TIMEOUT_MS)
        val durationMs = (System.nanoTime() - at) / 1_000_000
        onDiagnostic("ncm ndp prewarm result=$transferred expected=${block.size} durationMs=$durationMs")
        if (transferred < 0 && transferred != -11 && transferred != -110) {
            throw failSession("NCM warmup native write errno=${-transferred}")
        }
        if (transferred <= 0) {
            if (!wroteFirstBlock) notReadyWriteCalls++
            return@synchronized NeighborWarmupWriteResult(transferred, block.size, durationMs)
        }
        if (transferred != block.size) throw failSession("NCM warmup write transferred $transferred of ${block.size} bytes")
        if (!wroteFirstBlock) {
            val notReady = notReadyWriteCalls
            wroteFirstBlock = true
            firstNcmBulkOutAcceptedAtNanos = System.nanoTime()
            onDiagnostic("ncm bulk-out accepted after $notReady not-ready write calls bytes=$transferred durationMs=${(System.nanoTime() - at) / 1000000}")
        }
        NeighborWarmupWriteResult(transferred, block.size, durationMs)
    }

    /** Sends only the valid range of a reusable frame buffer. */
    fun send(frame: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Boolean = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, offset, length, sequence)
        val fd=ioDescriptor
        var attempts=0
        val at=System.nanoTime()
        val replayable=StartupUsbWrite.retryable(frame,offset,length,block.size)
        val waitForReady=replayable&&!wroteFirstBlock&&!receivedFirstBlock&&startupWaitBudget.claim()
        val transferred=if(waitForReady&&UsbStartupProfile.queuedStartupWrite(startupProfile)){
            if(UsbStartupProfile.waitCarrier(startupProfile)){
                val gateAt=System.nanoTime()
                NcmCarrierGate.await({carrierUp},{receivedFirstBlock},{checkOpen()},{System.nanoTime()/1000000},{Thread.sleep(25)})
                checkOpen()
                onDiagnostic("ncm carrier gate waitedMs=${(System.nanoTime()-gateAt)/1000000} carrier=$carrierUp inbound=$receivedFirstBlock profile=$startupProfile")
                if(UsbStartupProfile.refreshFilter(startupProfile)&&carrierUp&&!receivedFirstBlock){
                    val rc=NcmPacketFilter.configure(controlInterface){type,request,value,index,timeout -> connection.controlTransfer(type,request,value,index,null,0,timeout)}
                    onDiagnostic("ncm carrier filter refresh result=$rc applied=${rc==0}")
                }
            }
            val metrics=IntArray(6)
            val queued=NativeUsb.startupWrite(fd,outEndpoint.address,block,15000,startupPending,metrics)
            onDiagnostic("ncm startup queued result=$queued submitted=${metrics[0]} polls=${metrics[1]} durationMs=${metrics[2]} cancelled=${metrics[3]} actualBytes=${metrics[4]} urbStatus=${metrics[5]} inbound=$receivedFirstBlock linkUp=${linkUpAt!=0L}")
            if(queued == -20001){
                onDiagnostic("ncm startup queued unsupported before submit; synchronous fallback")
                StartupUsbWrite.awaitReady(
                    {budget->attempts++;NativeUsb.write(fd,outEndpoint.address,block,budget)},
                    {checkOpen()},{!receivedFirstBlock},{System.nanoTime()/1000000},{Thread.sleep(25)})
            }else if(queued == -125) -110 else queued
        }
        else if(waitForReady){
            onDiagnostic("ncm startup write mode=bounded_sync_neighbor_replay profile=$startupProfile")
            StartupUsbWrite.awaitReady(
                {budget->attempts++;NativeUsb.write(fd,outEndpoint.address,block,budget)},
                {checkOpen()},{!receivedFirstBlock},{System.nanoTime()/1000000},{Thread.sleep(25)})
        }
        else StartupUsbWrite.write(replayable,StartupUsbWrite.timeoutMillis(replayable,wroteFirstBlock,timeoutMillis),
            {budget->attempts++;NativeUsb.write(fd,outEndpoint.address,block,budget)},
            {checkOpen()},{Thread.sleep(25)})
        if(attempts>1 && writeDiagnosticBudget>0){writeDiagnosticBudget--;onDiagnostic("NCM startup write attempts=$attempts result=$transferred durationMs=${(System.nanoTime()-at)/1000000} continuous=$waitForReady inbound=$receivedFirstBlock")}
        if(transferred<0&&transferred!=-110&&transferred!=-11)throw failSession("NCM native write errno=${-transferred}")
        // A timeout is not proof of detach. Preserve the native errno and let later packets retry.
        if (transferred <= 0) {
            if (!wroteFirstBlock) notReadyWriteCalls++
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                onDiagnostic("ncm bulk-out unavailable result=$transferred; bounded retry exhausted or non-replayable frame")
            }
            return@synchronized false
        }
        if (transferred != block.size) {
            throw failSession("NCM write transferred $transferred of ${block.size} bytes")
        }
        if(!wroteFirstBlock){val notReady=notReadyWriteCalls;wroteFirstBlock=true;firstNcmBulkOutAcceptedAtNanos=System.nanoTime();onDiagnostic("ncm bulk-out accepted after $notReady not-ready write calls bytes=$transferred durationMs=${(System.nanoTime()-at)/1000000}")}
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            onDiagnostic("ncm bulk-out became ready")
        }
        true
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        earlyReceive.handoff()
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        startupPending.set(false)
        warmupPending.set(false)
        receivePending.set(false)
        earlyReceive.handoff()
        synchronized(stateLock) { if(closed)return;closed=true }
        statusThread?.interrupt()
        statusThread?.let { if(it!==Thread.currentThread())runCatching{it.join(750L)} }
        val now=System.nanoTime()
        val lastAppleAge=lastAppleLinkAtNanos.takeIf{it>0L}?.let{((now-it)/1_000_000L).coerceAtLeast(0L)}?:-1L
        val lastCarrierAge=linkUpAt.takeIf{it>0L}?.let{((now-it)/1_000_000L).coerceAtLeast(0L)}?:-1L
        onDiagnostic("ncm status summary notifications=${statusNotificationCount.get()} readTimeouts=${statusReadTimeoutCount.get()} readErrors=${statusReadErrorCount.get()} appleLinkNotifications=${appleLinkNotificationCount.get()} carrier=$carrierUp lastCarrierAgeMs=$lastCarrierAge lastAppleLinkWord=${if(lastAppleLinkWord<0)"none" else "0x${java.lang.Long.toHexString(lastAppleLinkWord)}"} lastAppleLinkAgeMs=$lastAppleAge controlInterface=$controlInterface dataInterface=$dataInterface")
        synchronized(readLock){synchronized(writeLock){
            for(usbInterface in claimedInterfaces.asReversed())runCatching{connection.releaseInterface(usbInterface)}
            connection.close()
        }}
        earlyReceive.await()
        onDiagnostic("NCM closed native_reader_released=true")
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        val decoder=NcmStatusNotificationDecoder()
        var logBudget = 8
        val fd = synchronized(stateLock) {
            if (closed) return
            NativeUsb.duplicate(connection.fileDescriptor)
        }
        if (fd < 0) {
            statusReadErrorCount.incrementAndGet()
            onDiagnostic("ncm status reader unavailable duplicateErrno=${-fd}")
            return
        }
        onDiagnostic("ncm status reader active timeoutMs=$STATUS_READ_TIMEOUT_MILLIS descriptor=thread_owned")
        try {
            while (statusRunning.get()) {
                // Keep one interrupt transfer pending. A 20 ms read followed by a 500 ms sleep
                // leaves a blind interval in which one-shot link notifications may be missed.
                // A persistent duplicate also avoids two fd syscalls for every poll cycle.
                val transferred = try {
                    NativeUsb.read(fd, endpoint.address, buffer, STATUS_READ_TIMEOUT_MILLIS)
                } catch (error: RuntimeException) {
                    statusReadErrorCount.incrementAndGet()
                    if (logBudget > 0) {
                        logBudget--
                        onDiagnostic("ncm status read exception=${error.javaClass.simpleName}")
                    }
                    return
                }
                if (transferred == -110) {
                    statusReadTimeoutCount.incrementAndGet()
                    continue
                }
                if (transferred == -4) {
                    if (!statusRunning.get()) return
                    continue
                }
                if (transferred <= 0) {
                    statusReadErrorCount.incrementAndGet()
                    if (logBudget > 0) {
                        logBudget--
                        onDiagnostic("ncm status read result=$transferred errno=${if(transferred<0)-transferred else 0}")
                    }
                    try {
                        Thread.sleep(if (transferred == 0) STATUS_EMPTY_READ_BACKOFF_MILLIS else STATUS_ERROR_BACKOFF_MILLIS)
                    } catch (_: InterruptedException) {
                        return
                    }
                    continue
                }
            for(notification in decoder.feed(buffer,transferred)) {
                statusNotificationCount.incrementAndGet()
                val connected=NcmLinkNotification.connected(notification,controlInterface)
                if(connected!=null){
                    carrierUp=connected
                    if(connected&&linkUpAt==0L)linkUpAt=System.nanoTime()
                    if(connected)markNcmNetworkReady("carrier")
                    onDiagnostic("ncm carrier connected=$connected code=0x${notification.code.toString(16)} notificationIndex=${notification.index} ageMs=${(System.nanoTime()-openedAt)/1000000}")
                } else if(notification.requestType==0xa1&&notification.code==0&&notification.payload.isEmpty()&&notification.index!=controlInterface) {
                    onDiagnostic("ncm carrier notification ignored notificationIndex=${notification.index} expectedControlInterface=$controlInterface dataInterface=$dataInterface carrierMutation=false")
                }
                val observedWord=notification.observedAppleLinkWord()
                if(notification.code==0x2b&&notification.index==dataInterface&&observedWord!=null){
                    appleLinkNotificationCount.incrementAndGet()
                    lastAppleLinkWord=observedWord
                    lastAppleLinkAtNanos=System.nanoTime()
                    if(appleLinkNotificationReported.compareAndSet(false,true)){
                        onDiagnostic("ncm apple link notification observed code=0x2b notificationIndex=${notification.index} matchesDataInterface=true payloadLength=${notification.payload.size} linkWord=0x${java.lang.Long.toHexString(observedWord)} ageMs=${(System.nanoTime()-openedAt)/1_000_000} carrierMutation=false")
                    }
                } else if(notification.code==0x2b&&appleLinkNotificationReported.compareAndSet(false,true)) {
                    onDiagnostic("ncm apple link notification observed code=0x2b notificationIndex=${notification.index} matchesDataInterface=${notification.index==dataInterface} payloadLength=${notification.payload.size} ageMs=${(System.nanoTime()-openedAt)/1_000_000} carrierMutation=false")
                }
            }
            if (logBudget > 0) {
                logBudget--
                onDiagnostic("ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}")
            }
            }
        } finally {
            NativeUsb.closeDescriptor(fd)
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val decodedFrames = frameDecoder.append(source, length)
        if (decodedFrames.isNotEmpty()) markNcmNetworkReady("first_ncm_frame")
        for (frame in decodedFrames) enqueueFrame(frame)
    }
    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        // recv owns readLock for the whole transfer, including NTB reassembly.
        checkOpen()
        val tracing=!receivedFirstBlock
        val begin=if(tracing)System.nanoTime()else 0L
        if(tracing){initialReadCalls++;if(firstReadAt==0L){firstReadAt=begin;onDiagnostic("ncm first receive submitted ageMs=${(begin-openedAt)/1000000} profile=$startupProfile")}}
        val length=if(UsbStartupProfile.queuedReceive(startupProfile)&&!queuedReceiveUsed){
            check(!UsbStartupProfile.queued(startupProfile)){"Conflicting asynchronous USB completion owners"}
            queuedReceiveUsed=true
            val metrics=IntArray(6)
            val n=NativeUsb.startupRead(ioDescriptor,inEndpoint.address,readBuffer,15000,receivePending,metrics)
            onDiagnostic("ncm startup receive result=$n submitted=${metrics[0]} polls=${metrics[1]} durationMs=${metrics[2]} cancelled=${metrics[3]} actualBytes=${metrics[4]} urbStatus=${metrics[5]}")
            if(n == -20001)NativeUsb.read(ioDescriptor,inEndpoint.address,readBuffer,500) else n
        }else NativeUsb.read(ioDescriptor,inEndpoint.address,readBuffer,timeoutMillis.coerceIn(1,500).toInt())
        if(tracing)readWaitMs+=(System.nanoTime()-begin)/1000000
        if(length==0||length == -110){if(tracing)initialReadTimeouts++;return null}
        if(length<0)throw failSession("NCM native read errno=${-length}")
        if(length>0&&!receivedFirstBlock){
            onDiagnostic("ncm startup receive summary calls=$initialReadCalls timeouts=$initialReadTimeouts nativeWaitMs=$readWaitMs firstSubmitAgeMs=${(firstReadAt-openedAt)/1000000}")
            receivedFirstBlock=true
            startupPending.set(false)
            val at=System.nanoTime();val link=linkUpAt
            onDiagnostic("ncm first raw bulk-in bytes=$length ageMs=${(at-openedAt)/1000000} afterCarrierMs=${if(link==0L)-1 else (at-link)/1000000}")
        }
        return length
    }

    private fun markNcmNetworkReady(source: String) {
        val at = System.nanoTime()
        if (firstNetworkReadyAtNanos.compareAndSet(0L, at)) {
            onDiagnostic("ncm network readiness observed source=$source ageMs=${(at-openedAt)/1_000_000}")
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 16 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_READ_TIMEOUT_MILLIS = 500
        private const val STATUS_EMPTY_READ_BACKOFF_MILLIS = 10L
        private const val STATUS_ERROR_BACKOFF_MILLIS = 100L
        private const val WARMUP_WRITE_TIMEOUT_MS = 100
        private const val PRESESSION_NDP_TIMEOUT_MS = 15_000
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            profile: Int = 0,
            probe: Boolean = false,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            var bridge: NcmUsbBridge? = null
            onDiagnostic("ncm endpoints control=${function.control.id} data=${function.data.id}/${function.data.alternateSetting} in=${function.bulkIn.address} out=${function.bulkOut.address} status=${function.statusIn?.address} maxPacketIn=${function.bulkIn.maxPacketSize} maxPacketOut=${function.bulkOut.maxPacketSize} startupProfile=$profile probe=$probe")
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                // Begin draining the interrupt endpoint before selecting data alt=1. Some newer
                // iPhones emit a private link notification as a consequence of that transition.
                // This listener is observational only and must not gate or mutate NCM readiness.
                val activeBridge = NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    UsbStartupProfile.valid(profile),
                    function.control.id,
                    function.data.id,
                    onDiagnostic,
                )
                bridge = activeBridge
                onDiagnostic("ncm status monitor armed before data alternate statusEndpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"} controlInterface=${function.control.id} dataInterface=${function.data.id}")
                NcmInitialization.activate(profile,
                    reset={NativeUsb.alternate(connection.fileDescriptor,function.data.id,0)},
                    probe={if(probe){
                        val parameters=ByteArray(28)
                        val n=connection.controlTransfer(0xa1,0x80,0,function.control.id,parameters,parameters.size,250)
                        onDiagnostic("ncm parameters result=$n raw="+if(n>0)parameters.take(minOf(n,28)).joinToString(""){"%02x".format(it.toInt() and 255)}else "unavailable")
                    }},
                    select={connection.setInterface(function.data)},
                    filter={NcmPacketFilter.configure(function.control.id){type,request,value,index,timeout ->
                        connection.controlTransfer(type,request,value,index,null,0,timeout)
                    }},
                    log=onDiagnostic)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return activeBridge
            } catch (error: Throwable) {
                val activeBridge=bridge
                if(activeBridge!=null) {
                    runCatching { activeBridge.close() }
                } else {
                    for (usbInterface in claimed.asReversed()) {
                        try {
                            connection.releaseInterface(usbInterface)
                        } catch (_: RuntimeException) {
                            // The connection close below is authoritative.
                        }
                    }
                    connection.close()
                }
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
