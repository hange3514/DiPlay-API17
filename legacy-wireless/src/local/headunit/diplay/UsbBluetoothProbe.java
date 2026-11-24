package local.headunit.diplay;

import android.bluetooth.BluetoothAdapter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Read-only USB Bluetooth controller probe for external dongles such as UGREEN CM591.
 * It sends standard HCI Reset, Read Local Version, Read BD_ADDR, and a bounded Classic inquiry;
 * it never uploads firmware, pairs, changes keys, or touches the vehicle's built-in Bluetooth UART.
 */
final class UsbBluetoothProbe {
    private static final int USB_CLASS_WIRELESS_CONTROLLER = 0xe0;
    private static final int USB_SUBCLASS_RF_CONTROLLER = 0x01;
    private static final int USB_PROTOCOL_BLUETOOTH = 0x01;
    private static final int HCI_COMMAND_TIMEOUT_MS = 1800;
    private static final int HCI_EVENT_MAX_BYTES = 260;

    private UsbBluetoothProbe() {}

    static List<UsbDevice> candidates(UsbManager manager) {
        ArrayList<UsbDevice> result = new ArrayList<UsbDevice>();
        if (manager == null) return result;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isCandidate(device)) result.add(device);
        }
        return result;
    }

    static boolean isCandidate(UsbDevice device) {
        if (device == null) return false;
        if (isKnownAts2851(device.getVendorId(), device.getProductId())) return true;
        if (isKnownRealtek(device.getVendorId(), device.getProductId())) return true;
        if (isHciDeviceClass(device.getDeviceClass(), device.getDeviceSubclass(), device.getDeviceProtocol())) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (isHciInterface(iface)) return true;
        }
        return false;
    }

    static boolean isKnownRealtek(int vendor, int product) {
        // Linux btusb and independent direct-HCI implementations list these RTL8761BU-family
        // products. Product IDs can change between vendors/revisions, so interface-class
        // recognition remains the general fallback.
        return (vendor == 0x2b89 && (product == 0x6275 || product == 0x8761 || product == 0x8771))
            || (vendor == 0x0bda && (product == 0xa728 || product == 0x8771 || product == 0x8761 || product == 0x6275))
            || (vendor == 0x0b05 && product == 0x190e)
            || (vendor == 0x2357 && (product == 0x0604 || product == 0x0607))
            || (vendor == 0x2c4e && product == 0x0115)
            || (vendor == 0x2550 && product == 0x8761)
            || (vendor == 0x6655 && product == 0x8771)
            || (vendor == 0x7392 && product == 0xc611);
    }

    static boolean isKnownAts2851(int vendor, int product) {
        return vendor == 0x10d7 && product == 0xb012;
    }

    static String chipsetHint(int vendor, int product) {
        if (isKnownAts2851(vendor, product)) return "Actions_ATS2851";
        if (isKnownRealtek(vendor, product)) return "Realtek_RTL8761BU_family";
        return "unknown_generic_USB_HCI";
    }

    static boolean usesBundledRtl8761BuFirmware(int vendor, int product) {
        // Known IDs that may require the bundled Realtek firmware.
        // Other vendors/controllers use generic HCI initialization until identified.
        return (vendor == 0x0bda || vendor == 0x2b89)
            && (product == 0x6275 || product == 0x8761 || product == 0x8771);
    }

    // Keep this list aligned with res/xml/usb_bluetooth.xml.
    static boolean hasUsbAttachHandler(int vendor, int product) {
        return isKnownAts2851(vendor, product) || usesBundledRtl8761BuFirmware(vendor, product);
    }

    static String label(UsbDevice device, UsbManager manager) {
        String id = String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId());
        String hint = isKnownAts2851(device.getVendorId(), device.getProductId()) ? "Actions ATS2851" : (isKnownRealtek(device.getVendorId(), device.getProductId()) ? "Realtek RTL8761BU" : (hasHciInterface(device) ? "Bluetooth HCI" : "USB Bluetooth candidate"));
        String permission = manager != null && manager.hasPermission(device) ? "  已授权" : "  需授权";
        return id + "  " + hint + permission;
    }

    static JSONObject scanEvent(UsbManager manager, BluetoothAdapter adapter) throws Exception {
        JSONObject result = new JSONObject()
            .put("stage", "usb_bt_scan")
            .put("usbDeviceCount", manager == null ? 0 : manager.getDeviceList().size())
            .put("candidateCount", candidates(manager).size())
            .put("androidBluetoothAdapterPresent", adapter != null)
            .put("androidBluetoothAdapterEnabled", adapter != null && adapter.isEnabled())
            .put("androidBluetoothStackStatus", adapter == null ? "adapter_missing" : (adapter.isEnabled() ? "enabled" : "disabled"))
            .put("usbDevicesSummary", usbDevicesSummary(manager));
        return result;
    }

    static JSONObject inspect(UsbManager manager, UsbDevice device, BluetoothAdapter adapter) {
        JSONObject out = new JSONObject();
        UsbDeviceConnection connection = null;
        UsbInterface claimed = null;
        try {
            put(out, "stage", "descriptor_inspection");
            put(out, "deviceVid", device.getVendorId());
            put(out, "devicePid", device.getProductId());
            put(out, "deviceClass", device.getDeviceClass());
            put(out, "deviceSubclass", device.getDeviceSubclass());
            put(out, "deviceProtocol", device.getDeviceProtocol());
            put(out, "usbInterfaceCount", device.getInterfaceCount());
            put(out, "devicePermission", manager.hasPermission(device));
            put(out, "knownRealtekId", isKnownRealtek(device.getVendorId(), device.getProductId()));
            put(out, "knownAts2851Id", isKnownAts2851(device.getVendorId(), device.getProductId()));
            put(out, "chipsetHint", chipsetHint(device.getVendorId(), device.getProductId()));
            put(out, "hciInterfacePresent", hasHciInterface(device));
            put(out, "usbInterfaceSummary", interfaceSummary(device));
            put(out, "androidBluetoothAdapterPresent", adapter != null);
            put(out, "androidBluetoothAdapterEnabled", adapter != null && adapter.isEnabled());
            put(out, "androidBluetoothStackStatus", adapter == null ? "adapter_missing" : (adapter.isEnabled() ? "enabled" : "disabled"));
            if (!manager.hasPermission(device)) {
                put(out, "usbBtStatus", "permission_missing");
                return out;
            }

            UsbInterface hci = findHciInterface(device);
            if (hci == null) {
                put(out, "usbBtStatus", isKnownRealtek(device.getVendorId(), device.getProductId()) ? "known_id_no_hci_descriptor" : "hci_interface_unrecognized");
                return out;
            }
            EndpointSet endpoints = endpoints(hci);
            put(out, "eventEndpointAddress", endpoints.eventIn == null ? -1 : endpoints.eventIn.getAddress());
            put(out, "aclInEndpointAddress", endpoints.aclIn == null ? -1 : endpoints.aclIn.getAddress());
            put(out, "aclOutEndpointAddress", endpoints.aclOut == null ? -1 : endpoints.aclOut.getAddress());
            put(out, "hciEndpointSetComplete", endpoints.complete());
            if (endpoints.eventIn == null || endpoints.aclIn == null || endpoints.aclOut == null) {
                put(out, "usbBtStatus", "hci_endpoints_incomplete");
                return out;
            }

            connection = manager.openDevice(device);
            if (connection == null) {
                put(out, "usbBtStatus", "open_device_failed");
                return out;
            }
            boolean interfaceClaimed = connection.claimInterface(hci, false);
            put(out, "hciInterfaceClaimed", interfaceClaimed);
            if (!interfaceClaimed) {
                // Do not steal the interface from a kernel or system Bluetooth driver.
                put(out, "usbBtStatus", "interface_claim_failed");
                return out;
            }
            claimed = hci;

            HciResult reset = command(connection, endpoints.eventIn, 0x0c03, new byte[0]);
            put(out, "hciResetAttempted", true);
            put(out, "hciResetControlResult", reset.controlResult);
            put(out, "hciResetResult", reset.statusName());
            put(out, "hciResetStatus", reset.status);
            if (reset.status != 0) {
                put(out, "usbBtStatus", reset.controlFailed ? "hci_reset_control_failed" : reset.timedOut ? "hci_reset_event_timeout" : "hci_reset_rejected");
                return out;
            }

            HciResult version = command(connection, endpoints.eventIn, 0x1001, new byte[0]);
            put(out, "hciVersionResult", version.statusName());
            put(out, "hciVersionStatus", version.status);
            if (version.status == 0 && version.returnParameters.length >= 8) {
                byte[] p = version.returnParameters;
                put(out, "hciVersion", u8(p[0]));
                put(out, "hciRevision", u16(p, 1));
                put(out, "hciLmpVersion", u8(p[3]));
                put(out, "hciManufacturer", u16(p, 4));
                put(out, "hciLmpSubversion", u16(p, 6));
            }

            HciResult address = command(connection, endpoints.eventIn, 0x1009, new byte[0]);
            put(out, "hciAddressResult", address.statusName());
            put(out, "hciAddressStatus", address.status);
            put(out, "hciAddressPresent", address.status == 0 && address.returnParameters.length >= 6);
            if (address.status == 0 && address.returnParameters.length >= 6 && adapter != null) {
                String addressText = String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                    u8(address.returnParameters[5]), u8(address.returnParameters[4]), u8(address.returnParameters[3]),
                    u8(address.returnParameters[2]), u8(address.returnParameters[1]), u8(address.returnParameters[0]));
                put(out, "systemAddressMatchesDongle", addressText.equalsIgnoreCase(safeAdapterAddress(adapter)));
            }
            put(out, "usbBtStatus", version.status == 0 && address.status == 0 ? "hci_ready" : "hci_partial");
            if (version.status == 0 && address.status == 0) {
                HciResult inquiry = command(connection, endpoints.eventIn, 0x0401,
                    new byte[] { (byte) 0x33, (byte) 0x8b, (byte) 0x9e, 0x08, 0x00 });
                put(out, "hciInquiryAttempted", true);
                put(out, "hciInquiryStatus", inquiry.status);
                put(out, "hciInquiryResult", inquiry.statusName());
                if (inquiry.status == 0) {
                    InquirySummary summary = collectInquiry(connection, endpoints.eventIn);
                    put(out, "hciInquiryComplete", summary.complete);
                    put(out, "hciInquiryDeviceCount", summary.deviceCount);
                    put(out, "hciInquiryResultEventCount", summary.resultEventCount);
                    put(out, "hciInquiryElapsedMs", summary.elapsedMs);
                    if (!summary.complete) put(out, "hciInquiryResult", "timeout");
                }
            } else {
                put(out, "hciInquiryAttempted", false);
                put(out, "hciInquiryResult", "prerequisite_failed");
            }
            return out;
        } catch (Throwable failure) {
            put(out, "usbBtStatus", "probe_exception");
            put(out, "errorType", failure.getClass().getSimpleName());
            return out;
        } finally {
            if (connection != null) {
                if (claimed != null) try { connection.releaseInterface(claimed); } catch (Throwable ignored) { }
                try { connection.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Run a bounded, receive-only classic inquiry. Device addresses and names are never retained. */
    private static InquirySummary collectInquiry(UsbDeviceConnection connection, UsbEndpoint eventIn) {
        final long started = SystemClock.elapsedRealtime();
        final long deadline = started + 12000;
        int deviceCount = 0, resultEventCount = 0;
        boolean complete = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            int remaining = (int) Math.min(Integer.MAX_VALUE, deadline - SystemClock.elapsedRealtime());
            try {
                byte[] event = readEvent(connection, eventIn, Math.max(1, remaining));
                if (event == null || event.length < 2) continue;
                int eventCode = u8(event[0]);
                int length = Math.min(u8(event[1]), event.length - 2);
                if ((eventCode == 0x02 || eventCode == 0x22) && length >= 1) {
                    int recordBytes = eventCode == 0x22 ? 14 : 13;
                    int reported = u8(event[2]);
                    int completeRecords = Math.min(reported, Math.max(0, (length - 1) / recordBytes));
                    deviceCount += completeRecords;
                    resultEventCount++;
                } else if (eventCode == 0x2f && length >= 1) {
                    deviceCount += u8(event[2]);
                    resultEventCount++;
                } else if (eventCode == 0x01 && length >= 1) {
                    complete = u8(event[2]) == 0;
                    break;
                }
            } catch (java.util.concurrent.TimeoutException timeout) {
                break;
            } catch (Throwable ignored) {
                break;
            }
        }
        return new InquirySummary(complete, deviceCount, resultEventCount,
            Math.max(0, SystemClock.elapsedRealtime() - started));
    }

    private static String usbDevicesSummary(UsbManager manager) {
        if (manager == null) return "usb_manager_missing";
        StringBuilder out = new StringBuilder();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (out.length() > 0) out.append(';');
            out.append(String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId()))
                .append('-').append(device.getDeviceClass()).append('/').append(device.getDeviceSubclass())
                .append('/').append(device.getDeviceProtocol()).append(':').append(interfaceSummary(device));
            if (out.length() >= 1000) {
                out.setLength(1000);
                break;
            }
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static HciResult command(final UsbDeviceConnection connection, UsbEndpoint eventIn,
                                     int opcode, byte[] parameters) throws Exception {
        byte[] packet = new byte[3 + parameters.length];
        packet[0] = (byte) (opcode & 0xff);
        packet[1] = (byte) ((opcode >> 8) & 0xff);
        packet[2] = (byte) parameters.length;
        System.arraycopy(parameters, 0, packet, 3, parameters.length);
        int sent = connection.controlTransfer(0x20, 0, 0, 0, packet, packet.length, HCI_COMMAND_TIMEOUT_MS);
        if (sent != packet.length) return HciResult.controlFailure(sent);
        long deadline = SystemClock.elapsedRealtime() + HCI_COMMAND_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            byte[] event;
            try {
                event = readEvent(connection, eventIn, (int) Math.max(1, deadline - SystemClock.elapsedRealtime()));
            } catch (java.util.concurrent.TimeoutException timeout) {
                return new HciResult(sent, -1, true, new byte[0]);
            }
            if (event == null || event.length < 2) continue;
            int eventCode = u8(event[0]);
            int length = Math.min(u8(event[1]), event.length - 2);
            if (eventCode == 0x0e && length >= 4 && u16(event, 3) == opcode) {
                int status = length >= 4 ? u8(event[5]) : -1;
                byte[] returned = new byte[Math.max(0, length - 4)];
                if (returned.length > 0) System.arraycopy(event, 6, returned, 0, returned.length);
                return new HciResult(sent, status, false, returned);
            }
            if (eventCode == 0x0f && length >= 4 && u16(event, 4) == opcode) {
                int status = u8(event[2]);
                return new HciResult(sent, status, false, new byte[0]);
            }
        }
        return new HciResult(sent, -1, true, new byte[0]);
    }

    private static byte[] readEvent(UsbDeviceConnection connection, UsbEndpoint endpoint, int timeoutMs) throws Exception {
        // Android's requestWait() has no timeout and is connection-wide. Wrapping it in a new
        // thread per read can leave a waiter alive after timeout, then race the next HCI read.
        // API 17 bulkTransfer supports interrupt endpoints and gives this probe a bounded,
        // synchronous read with no detached request or lingering waiter thread.
        byte[] result = new byte[HCI_EVENT_MAX_BYTES];
        int size = connection.bulkTransfer(endpoint, result, result.length, Math.max(1, timeoutMs));
        if (size <= 0) throw new java.util.concurrent.TimeoutException("HCI event endpoint read timed out or failed");
        if (size > result.length) throw new IOException("HCI event exceeded the receive buffer");
        return size == result.length ? result : java.util.Arrays.copyOf(result, size);
    }

    private static EndpointSet endpoints(UsbInterface iface) {
        EndpointSet set = new EndpointSet();
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = iface.getEndpoint(i);
            int type = endpoint.getType();
            boolean in = endpoint.getDirection() == UsbConstants.USB_DIR_IN;
            if (type == UsbConstants.USB_ENDPOINT_XFER_INT && in && set.eventIn == null) set.eventIn = endpoint;
            if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && in && set.aclIn == null) set.aclIn = endpoint;
            if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && !in && set.aclOut == null) set.aclOut = endpoint;
        }
        return set;
    }

    private static boolean hasHciInterface(UsbDevice device) { return findHciInterface(device) != null; }
    private static UsbInterface findHciInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) if (isHciInterface(device.getInterface(i))) return device.getInterface(i);
        return null;
    }

    static UsbInterface hciInterface(UsbDevice device) { return findHciInterface(device); }
    private static boolean isHciInterface(UsbInterface iface) {
        return isHciDeviceClass(iface.getInterfaceClass(), iface.getInterfaceSubclass(), iface.getInterfaceProtocol());
    }
    private static boolean isHciDeviceClass(int cls, int sub, int protocol) {
        return cls == USB_CLASS_WIRELESS_CONTROLLER && sub == USB_SUBCLASS_RF_CONTROLLER && protocol == USB_PROTOCOL_BLUETOOTH;
    }

    private static String interfaceSummary(UsbDevice device) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (out.length() > 0) out.append(';');
            out.append('i').append(iface.getId()).append(':').append(iface.getInterfaceClass()).append('/')
                .append(iface.getInterfaceSubclass()).append('/').append(iface.getInterfaceProtocol()).append('[');
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (e > 0) out.append(',');
                out.append(String.format(Locale.US, "%02X", ep.getAddress())).append(':')
                    .append(ep.getType()).append(ep.getDirection() == UsbConstants.USB_DIR_IN ? 'I' : 'O')
                    .append(':').append(ep.getMaxPacketSize());
            }
            out.append(']');
        }
        return out.toString();
    }

    private static String safeAdapterAddress(BluetoothAdapter adapter) {
        try { return adapter.getAddress(); } catch (Throwable ignored) { return ""; }
    }
    private static int u8(byte value) { return value & 0xff; }
    private static int u16(byte[] bytes, int offset) { return u8(bytes[offset]) | (u8(bytes[offset + 1]) << 8); }
    private static void put(JSONObject out, String key, Object value) {
        try { out.put(key, value); } catch (Exception ignored) { }
    }

    private static final class EndpointSet {
        UsbEndpoint eventIn, aclIn, aclOut;
        boolean complete() { return eventIn != null && aclIn != null && aclOut != null; }
    }

    private static final class InquirySummary {
        final boolean complete;
        final int deviceCount, resultEventCount;
        final long elapsedMs;
        InquirySummary(boolean complete, int deviceCount, int resultEventCount, long elapsedMs) {
            this.complete = complete; this.deviceCount = deviceCount;
            this.resultEventCount = resultEventCount; this.elapsedMs = elapsedMs;
        }
    }

    private static final class HciResult {
        final int controlResult, status;
        final boolean timedOut, controlFailed;
        final byte[] returnParameters;
        HciResult(int controlResult, int status, boolean timedOut, byte[] returned) {
            this(controlResult, status, timedOut, returned, false);
        }
        HciResult(int controlResult, int status, boolean timedOut, byte[] returned, boolean controlFailed) {
            this.controlResult = controlResult; this.status = status; this.timedOut = timedOut;
            this.returnParameters = returned; this.controlFailed = controlFailed;
        }
        static HciResult controlFailure(int code) { return new HciResult(code, -1, false, new byte[0], true); }
        String statusName() { return controlFailed ? "control_transfer_failed" : timedOut ? "timeout" : status < 0 ? "not_submitted" : status == 0 ? "success" : "controller_rejected"; }
    }
}
