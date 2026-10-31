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
    private var wroteFirstBlock = false
    @Volatile private var receivedFirstBlock = false
    private var startupWaitUsed = false
    private val startupPending = AtomicBoolean(true)
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
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // API17 vendor UsbRequest cancellation can stall cleanup. Use the same bounded
    // native USBDEVFS_BULK path already proven by USBMUX; NTB reassembly handles 16K splits.
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
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
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        val fd=ioDescriptor
        var attempts=0
        val at=System.nanoTime()
        val replayable=StartupUsbWrite.retryable(frame,block.size)
        val waitForReady=replayable&&!startupWaitUsed&&!wroteFirstBlock&&!receivedFirstBlock
        if(waitForReady)startupWaitUsed=true
        val transferred=if(waitForReady&&UsbStartupProfile.queued(startupProfile)){
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
        else if(waitForReady)StartupUsbWrite.awaitReady(
            {budget->attempts++;NativeUsb.write(fd,outEndpoint.address,block,budget)},
            {checkOpen()},{!receivedFirstBlock},{System.nanoTime()/1000000},{Thread.sleep(25)})
        else StartupUsbWrite.write(replayable,timeoutMillis,
            {budget->attempts++;NativeUsb.write(fd,outEndpoint.address,block,budget)},
            {checkOpen()},{Thread.sleep(25)})
        if(attempts>1 && writeDiagnosticBudget>0){writeDiagnosticBudget--;onDiagnostic("NCM startup write attempts=$attempts result=$transferred durationMs=${(System.nanoTime()-at)/1000000} continuous=$waitForReady inbound=$receivedFirstBlock")}
        if(transferred<0&&transferred!=-110&&transferred!=-11)throw failSession("NCM native write errno=${-transferred}")
        // A timeout is not proof of detach. Preserve the native errno and let later packets retry.
        if (transferred <= 0) {
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                onDiagnostic("ncm bulk-out unavailable result=$transferred; bounded retry exhausted or non-replayable frame")
            }
            return@synchronized
        }
        if (transferred != block.size) {
            throw failSession("NCM write transferred $transferred of ${block.size} bytes")
        }
        if(!wroteFirstBlock){wroteFirstBlock=true;onDiagnostic("ncm first successful bulk-out bytes=$transferred durationMs=${(System.nanoTime()-at)/1000000}")}
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            onDiagnostic("ncm bulk-out became ready")
        }
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
                drainFrames()
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
        receivePending.set(false)
        earlyReceive.handoff()
        synchronized(stateLock) { if(closed)return;closed=true }
        statusThread?.interrupt()
        statusThread?.let { if(it!==Thread.currentThread())runCatching{it.join(750L)} }
        synchronized(readLock){synchronized(writeLock){
            for(usbInterface in claimedInterfaces.asReversed())runCatching{connection.releaseInterface(usbInterface)}
            connection.close()
        }}
        earlyReceive.await()
        onDiagnostic("NCM closed native_reader_released=true")
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var logBudget = 8
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                val fd=synchronized(stateLock){if(closed)return;NativeUsb.duplicate(connection.fileDescriptor)}
                if(fd<0)return
                try{NativeUsb.read(fd,endpoint.address,buffer,if(UsbStartupProfile.continuousStatus(startupProfile))500 else STATUS_POLL_TIMEOUT_MILLIS)}finally{NativeUsb.closeDescriptor(fd)}
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(if(UsbStartupProfile.continuousStatus(startupProfile))1 else STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            val connected=NcmLinkNotification.connected(buffer,transferred)
            if(connected!=null){
                carrierUp=connected
                if(connected&&linkUpAt==0L)linkUpAt=System.nanoTime()
                if(logBudget>0)onDiagnostic("ncm carrier connected=$connected ageMs=${(System.nanoTime()-openedAt)/1000000}")
            }
            if (logBudget > 0) {
                logBudget--
                onDiagnostic("ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}")
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            if (bufferedSize < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                throw failSession("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            val padded = blockLength % USB_PACKET_SIZE == 0
            val wireLength = blockLength + if (padded) 1 else 0
            if (bufferedSize < wireLength) return
            if (padded && buffered[blockLength].toInt() != 0) {
                throw failSession("Invalid NTB16 short-packet pad")
            }
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - wireLength
            buffered.copyInto(buffered, 0, wireLength, bufferedSize)
            bufferedSize = remaining
        }
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
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
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
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
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    UsbStartupProfile.valid(profile),
                    function.control.id,
                    onDiagnostic,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
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
