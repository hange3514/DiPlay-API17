package com.shilapi.xcertplay.usbbt

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Bluetooth USB HCI on a connection already authorized by Android.
 *
 * Commands use endpoint zero, events use interrupt IN, and ACL uses bulk.
 * Jelly Bean implements bulkTransfer via USBDEVFS_BULK, whose synchronous
 * kernel path also supports interrupt endpoints. Keeping positive transfer
 * timeouts avoids an uncancellable requestWait() on API 17.
 */
class UsbBtHci private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val btInterface: UsbInterface,
    eventEndpoint: UsbEndpoint,
    aclInEndpoint: UsbEndpoint,
    private val aclOutEndpoint: UsbEndpoint,
) : Closeable {

    sealed class HciResult<out T> {
        data class Ok<T>(val value: T) : HciResult<T>()
        data class Failed(val reason: String, val status: Int = -1) : HciResult<Nothing>()
    }

    data class InitResult(
        val bdAddr: String,
        val bufferSize: HciProtocol.BufferSize,
        val steps: List<String>,
        val advertisingName: String,
        val scanEnableRequested: Int,
        val scanEnableWriteStatus: Int,
        val scanEnable: Int?,
        val scanEnableReadStatus: Int?,
        val controllerManufacturer: Int?,
        val controllerHciVersion: Int?,
        val realtekFirmwareSelected: Boolean,
        val scanEnableUsbSubmitResult: Int?,
        val scanEnableWriteCount: Int,
    )

    companion object {
        private const val TAG = "UsbBtHci"
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 2_000
        private const val USB_IO_TIMEOUT_MS = 500

        /** The caller retains ownership of [connection] if opening fails. */
        fun open(device: UsbDevice, connection: UsbDeviceConnection): UsbBtHci? {
            if (connection.fileDescriptor < 0) return null
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val ordered = interfaces.sortedByDescending { intf ->
                intf.interfaceClass == HciProtocol.USB_CLASS_WIRELESS_CONTROLLER &&
                    intf.interfaceSubclass == HciProtocol.USB_SUBCLASS_RF &&
                    intf.interfaceProtocol == HciProtocol.USB_PROTOCOL_BLUETOOTH
            }
            for (intf in ordered) {
                val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint)
                fun endpoint(type: Int, direction: Int): UsbEndpoint? = endpoints.firstOrNull {
                    it.type == type && it.direction == direction && it.maxPacketSize in 1..1024
                }
                val event = endpoint(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_IN) ?: continue
                val aclIn = endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN) ?: continue
                val aclOut = endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_OUT) ?: continue
                if (!connection.claimInterface(intf, true)) continue
                return try {
                    Log.i(TAG, "USB Bluetooth interface=${intf.id} event=${event.address}/${event.maxPacketSize}" +
                        " aclIn=${aclIn.address}/${aclIn.maxPacketSize} aclOut=${aclOut.address}/${aclOut.maxPacketSize}")
                    UsbBtHci(device, connection, intf, event, aclIn, aclOut)
                } catch (error: Exception) {
                    runCatching { connection.releaseInterface(intf) }
                    Log.w(TAG, "Unable to start the HCI USB reader", error)
                    null
                }
            }
            return null
        }
    }

    private val closed = AtomicBoolean(false)
    private val usbLock = ReentrantReadWriteLock()
    private val aclTxLock = Any()
    private val aclRxLock = Any()
    private val aclWindow = HciAclWindow()
    @Volatile private var packetSize = 0
    @Volatile private var scanSubmitResult: Int? = null
    @Volatile private var scanWriteCount = 0

    private val aclReader = EndpointReader(aclInEndpoint, HciUsbFrameAssembler(isAcl = true))
    private val pendingAcl = ArrayDeque<HciProtocol.AclPacket>()
    private val eventReader = EndpointReader(eventEndpoint, HciUsbFrameAssembler(isAcl = false))

    private val dispatcher = HciEventDispatcher(
        readEvent = ::readHciEvent,
        writeCommand = { payload, timeoutMs ->
            val opcode = (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)
            val sent = usbTransfer {
                connection.controlTransfer(
                    HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT, HciProtocol.USB_BT_CONTROL_REQUEST,
                    0, btInterface.id, payload, payload.size, bounded(timeoutMs),
                )
            }
            if (opcode == HciProtocol.OP_WRITE_SCAN_ENABLE && payload.size == 4) {
                scanWriteCount++
                scanSubmitResult = if (sent == payload.size) 0 else if (sent < 0) sent else -1
            }
            sent
        },
    )

    private inline fun usbTransfer(action: () -> Int): Int = usbLock.read {
        if (closed.get()) -1 else action()
    }

    private fun bounded(timeoutMs: Int): Int = timeoutMs.coerceIn(1, USB_IO_TIMEOUT_MS)

    private inner class EndpointReader(
        private val endpoint: UsbEndpoint,
        private val assembler: HciUsbFrameAssembler,
    ) {
        // Reading one USB packet lets the API 17 client keep an exactly-full packet,
        // even if the controller doesn't send a following short packet or ZLP.
        private val packet = ByteArray(endpoint.maxPacketSize)
        private val complete = ArrayDeque<ByteArray>()

        fun next(timeoutMs: Int): ByteArray? {
            complete.pollFirst()?.let { return it }
            val before = System.nanoTime()
            val received = usbTransfer {
                connection.bulkTransfer(endpoint, packet, packet.size, bounded(timeoutMs))
            }
            if (received < 0) {
                // Avoid spinning if the OEM USB stack reports a detach immediately.
                if (!closed.get() && System.nanoTime() - before < 1_000_000L) Thread.sleep(2)
                return null
            }
            complete.addAll(assembler.push(packet, received, endOfTransfer = received < packet.size))
            return complete.pollFirst()
        }

        fun removeQueued(): ByteArray? = complete.pollFirst()
    }

    private fun readHciEvent(timeoutMs: Int): HciProtocol.HciEvent? {
        val frame = eventReader.next(timeoutMs) ?: return null
        val event = HciProtocol.parseEvent(frame) ?: return null
        when (event.code) {
            HciProtocol.EVT_NUM_COMPLETED_PACKETS -> {
                accountCompleted(event.params)
                return null
            }
            HciProtocol.EVT_CONNECTION_COMPLETE -> {
                val connected = HciProtocol.parseConnectionComplete(event)
                if (connected?.status == 0 && event.params[9] == 1.toByte()) {
                    aclWindow.connected(connected.handle)
                }
            }
            HciProtocol.EVT_DISCONNECTION_COMPLETE -> {
                val disconnected = HciProtocol.parseDisconnectionComplete(event)
                if (disconnected?.status == 0) aclWindow.disconnected(disconnected.handle)
            }
        }
        return event
    }

    private fun accountCompleted(params: ByteArray) {
        val count = params.firstOrNull()?.toInt()?.and(0xff) ?: return
        if (params.size != 1 + count * 4) return
        repeat(count) { index ->
            val offset = 1 + index * 4
            val handle = (params[offset].toInt() and 255) or ((params[offset + 1].toInt() and 15) shl 8)
            val completed = (params[offset + 2].toInt() and 255) or ((params[offset + 3].toInt() and 255) shl 8)
            aclWindow.completed(handle, completed)
        }
    }

    /** Wait for the relevant Command Complete or Command Status event. */
    fun sendCommand(
        opcode: Int,
        params: ByteArray = byteArrayOf(),
        timeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
    ): HciResult<ByteArray> = dispatcher.sendCommand(opcode, params, timeoutMs).fold(
        onSuccess = { HciResult.Ok(it) },
        onFailure = {
            HciResult.Failed(it.message ?: it.javaClass.simpleName, (it as? HciCommandFailure)?.status ?: -1)
        },
    )

    fun initialize(
        firmwareLoader: UsbBtFirmwareLoader? = null,
        bluetoothName: String? = null,
        eirProfile: Int = 0,
        outboundOnly: Boolean = false,
        deferPhoneConnections: Boolean = false,
    ): HciResult<InitResult> {
        val steps = mutableListOf<String>()
        fun command(name: String, opcode: Int, params: ByteArray = byteArrayOf(), timeout: Int = 900): HciResult<ByteArray> {
            val outcome = sendCommand(opcode, params, timeout)
            val step = "$name: " + if (outcome is HciResult.Failed) outcome.reason else "OK"
            steps.add(step)
            Log.i(TAG, step)
            return outcome
        }
        fun returns(name: String, opcode: Int): ByteArray? =
            (command(name, opcode) as? HciResult.Ok)?.value

        var reset: HciResult<ByteArray> = HciResult.Failed("controller not started")
        for (attempt in 1..3) {
            if (closed.get()) return HciResult.Failed("USB connection closed")
            reset = command("Reset", HciProtocol.OP_RESET, timeout = 2000)
            if (reset is HciResult.Ok) break
        }
        if (reset is HciResult.Failed) return reset

        val manufacturerReply = returns("Read_Local_Version", HciProtocol.OP_READ_LOCAL_VERSION)
            ?: return HciResult.Failed("Could not identify Bluetooth controller")
        val manufacturer = RealtekFirmware.Version.parse(manufacturerReply)
            ?: return HciResult.Failed("Malformed controller version reply")
        val cm390Id = (device.vendorId == 0x0bda || device.vendorId == 0x2b89) &&
            (device.productId == 0x8771 || device.productId == 0x6275 || device.productId == 0x8761)
        val ats2851 = device.vendorId == 0x10d7 && device.productId == 0xb012
        if (cm390Id && manufacturer.manufacturer != RealtekFirmware.COMPANY_ID) {
            return HciResult.Failed("CM390 USB ID does not match a Realtek controller")
        }
        val setupRealtek = cm390Id && manufacturer.manufacturer == RealtekFirmware.COMPANY_ID
        if (setupRealtek) {
            val firmware = firmwareLoader?.loadForDevice(device.vendorId, device.productId, manufacturerReply)
                ?: return HciResult.Failed("Realtek firmware loader is unavailable")
            when (firmware) {
                is UsbBtFirmwareLoader.LoadResult.Uploaded -> {
                    steps.add("Realtek firmware loaded: ${firmware.detail}")
                    val patched = command("Post_Firmware_Reset", HciProtocol.OP_RESET, timeout = 2000)
                    if (patched is HciResult.Failed) return patched
                }
                is UsbBtFirmwareLoader.LoadResult.Skipped -> steps.add(firmware.detail)
                is UsbBtFirmwareLoader.LoadResult.Failed -> return HciResult.Failed(firmware.reason)
                else -> return HciResult.Failed("Required Realtek firmware configuration is unavailable")
            }
        }

        val bdAddr = returns("Read_BD_ADDR", HciProtocol.OP_READ_BD_ADDR)?.let(HciProtocol::parseBdAddr)
            ?.takeUnless { it == "00:00:00:00:00:00" }
            ?: return HciResult.Failed("Controller did not provide a Bluetooth address")
        val bufferSize = returns("Read_Buffer_Size", HciProtocol.OP_READ_BUFFER_SIZE)?.let(HciProtocol::parseBufferSize)
            ?.takeIf { it.aclDataPacketLength in 27..8192 && it.totalNumAclPackets > 0 }
            ?: return HciResult.Failed("Controller did not provide a usable ACL buffer size")
        aclWindow.initialize(bufferSize.totalNumAclPackets)
        packetSize = bufferSize.aclDataPacketLength
        val name = bluetoothName?.takeIf { it.isNotBlank() } ?: "DiPlay"
        // Keep the tested name-only pairing profile's car-audio class.
        val identity = if (eirProfile == 1) UsbBtIdentity(name) else UsbBtIdentity(name, classOfDevice = 0x000420)
        val mask = command("Set_Event_Mask", HciProtocol.OP_SET_EVENT_MASK, HciProtocol.encodeSetEventMask())
        if (mask is HciResult.Failed) return mask
        if (manufacturer.hci >= 0x0c) {
            // Core 5.3+ Encryption Change v2 is Page 2 bit 25. Keep v1 configured too.
            command("Set_Event_Mask_Page_2", HciProtocol.OP_SET_EVENT_MASK_PAGE_2,
                HciProtocol.encodeSetEventMask(1L shl 25))
        }
        val configuredName = command("Write_Local_Name", HciProtocol.OP_WRITE_LOCAL_NAME, HciProtocol.encodeLocalName(name))
        if (configuredName is HciResult.Failed) return configuredName
        val ssp = command("Write_Simple_Pairing_Mode", HciProtocol.OP_WRITE_SIMPLE_PAIRING_MODE, byteArrayOf(1))
        if (ssp is HciResult.Failed) return ssp
        command("Write_Authentication_Enable", HciProtocol.OP_WRITE_AUTHENTICATION_ENABLE, byteArrayOf(0))
        command("Write_Inquiry_Mode", HciProtocol.OP_WRITE_INQUIRY_MODE, byteArrayOf(2))
        command("Write_Class_Of_Device", HciProtocol.OP_WRITE_CLASS_OF_DEVICE,
            HciProtocol.encodeWriteClassOfDevice(identity.classOfDevice))

        val txPower = if (ats2851 || eirProfile == 1) {
            returns("Read_Inquiry_Response_TX_Power", HciProtocol.OP_READ_INQUIRY_RESPONSE_TX_POWER)?.getOrNull(1)?.toInt()
        } else null
        val eir = if (eirProfile == 1) identity.extendedInquiryResponse(txPower) else nameEir(name, txPower)
        command("Write_Extended_Inquiry_Response", HciProtocol.OP_WRITE_EXTENDED_INQUIRY_RESPONSE,
            HciProtocol.encodeWriteExtendedInquiryResponse(eir))
        val initialized = InitResult(
            bdAddr, bufferSize, steps, name, -1, -1, null, null,
            manufacturer.manufacturer, manufacturer.hci, setupRealtek, null, 0,
        )
        val requested = when {
            deferPhoneConnections -> HciProtocol.SCAN_DISABLED
            outboundOnly -> HciProtocol.SCAN_PAGE
            else -> HciProtocol.SCAN_INQUIRY_AND_PAGE
        }
        return configureScan(initialized, requested)
    }

    internal fun configureScan(initialized: InitResult, requested: Int): HciResult<InitResult> {
        val scan = sendCommand(HciProtocol.OP_WRITE_SCAN_ENABLE, byteArrayOf(requested.toByte()), 900)
        if (scan is HciResult.Failed) return scan
        val skipReadback = device.vendorId == 0x10d7 && device.productId == 0xb012
        val readback = if (skipReadback) null else sendCommand(HciProtocol.OP_READ_SCAN_ENABLE, timeoutMs = 900)
        val mode = (readback as? HciResult.Ok)?.value?.getOrNull(1)?.toInt()?.and(0xff)
        val modeStatus = when (readback) {
            is HciResult.Failed -> readback.status
            is HciResult.Ok -> if (mode == null) -1 else 0
            null -> null
        }
        if (requested == HciProtocol.SCAN_DISABLED && mode != null && mode != requested) {
            return HciResult.Failed("Bluetooth Controller did not disable scanning")
        }
        return HciResult.Ok(initialized.copy(
            scanEnableRequested = requested,
            scanEnableWriteStatus = 0,
            scanEnable = mode,
            scanEnableReadStatus = modeStatus,
            scanEnableUsbSubmitResult = scanSubmitResult,
            scanEnableWriteCount = scanWriteCount,
        ))
    }

    private fun nameEir(name: String, txPower: Int?): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        val maxBytes = if (txPower == null) 238 else 235
        var end = minOf(raw.size, maxBytes)
        if (end < raw.size) {
            while (end > 0 && raw[end].toInt() and 0xc0 == 0x80) end--
        }
        val kind = if (end == raw.size) 0x09 else 0x08
        val field = byteArrayOf((end + 1).toByte(), kind.toByte()) + raw.copyOfRange(0, end)
        return if (txPower == null) field else field + byteArrayOf(2, 10, txPower.toByte())
    }

    /** The caller supplies one complete L2CAP PDU; fragments share its ACL handle. */
    fun sendAcl(handle: Int, l2capPacket: ByteArray, timeoutMs: Int = 2000): Boolean = synchronized(aclTxLock) {
        val size = packetSize
        if (size <= 0 || l2capPacket.isEmpty() || handle !in 0..0x0eff || closed.get()) return false
        val link = aclWindow.find(handle) ?: return false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong().coerceAtLeast(1))
        var offset = 0
        while (offset < l2capPacket.size) {
            if (!aclWindow.acquire(link, deadline)) return false
            val length = minOf(size, l2capPacket.size - offset)
            val packet = HciProtocol.encodeAcl(handle, l2capPacket, offset, length, continuation = offset != 0)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                aclWindow.returnUnsent(link)
                return false
            }
            val sent = usbTransfer {
                if (!aclWindow.isCurrent(link)) -1 else {
                    connection.bulkTransfer(aclOutEndpoint, packet, packet.size,
                        bounded(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                }
            }
            if (sent != packet.size) {
                aclWindow.returnUnsent(link)
                Log.w(TAG, "ACL bulk OUT accepted $sent of ${packet.size} bytes")
                return false
            }
            offset += length
        }
        true
    }

    fun pollAcl(timeoutMs: Int = 500): HciProtocol.AclPacket? = synchronized(aclRxLock) {
        if (pendingAcl.isEmpty()) pendingAcl.addAll(pollAclPackets(timeoutMs))
        pendingAcl.pollFirst()
    }

    fun pollAclPackets(timeoutMs: Int = 100): List<HciProtocol.AclPacket> = synchronized(aclRxLock) {
        val result = ArrayList<HciProtocol.AclPacket>()
        while (pendingAcl.isNotEmpty()) result.add(pendingAcl.removeFirst())
        var frame = if (result.isEmpty()) aclReader.next(timeoutMs) else aclReader.removeQueued()
        while (frame != null) {
            HciProtocol.parseAcl(frame)?.let(result::add)
            frame = aclReader.removeQueued()
        }
        result
    }

    fun pollEvent(timeoutMs: Int = 500): HciProtocol.HciEvent? = dispatcher.pollEvent(timeoutMs)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        aclWindow.close()
        dispatcher.close()
        usbLock.write {
            runCatching { connection.releaseInterface(btInterface) }
            connection.close()
        }
    }
}
