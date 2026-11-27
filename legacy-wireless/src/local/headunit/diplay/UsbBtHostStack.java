package local.headunit.diplay;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.SystemClock;

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream;
import com.shilapi.xcertplay.transport.WakeableBlockingDuplexByteStream;
import com.shilapi.xcertplay.usbbt.UsbBtBluetoothAdapter;

import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Service-facing lifecycle and iAP2 byte stream for the APK-owned USB Bluetooth controller. */
public final class UsbBtHostStack implements Closeable {
    private static final String IPHONE_IAP2_UUID = "02030302-1d19-415f-86f2-22a2106a0a77";
    private static final int MAX_PHONE_CANDIDATES = 24;
    private static final int INQUIRY_AND_PAGE_SCAN = 3;
    private static final int PAGE_SCAN = 2;
    private static final long PREFERRED_ABORT_WAIT_MS = 3_000L;
    private static final byte[] END_OF_STREAM = new byte[0];

    private final Object stateLock = new Object();
    private final Context context;
    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbBtBluetoothAdapter adapter;
    private final boolean outboundOnly;
    private final long startedAt = SystemClock.elapsedRealtime();
    private final int nameBytes;

    // Guarded by stateLock except for lastState, which also feeds diagnostic status displays.
    private volatile String lastState = "not_started";
    private boolean started;
    private boolean closed;
    private boolean ready;
    private boolean deferPhoneConnections;
    private boolean preferredDirectCancelled;
    private boolean peerClosed;
    private boolean inquiryRunning;
    private String bluetoothAddress = "";
    private String rfcommPeerAddress;
    private Thread startupWorker;
    private Thread outboundWorker;
    private BlockingDuplexByteStream stream;
    private IOException failure;
    private OutboundFailure outboundFailure;
    private OutboundFailure rememberedFailure;

    public UsbBtHostStack(Context context, UsbDevice device, UsbDeviceConnection connection,
            String bluetoothName, int eirProfile, boolean outboundOnly) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.connection = connection;
        this.outboundOnly = outboundOnly;
        this.nameBytes = bluetoothName.getBytes(Charset.forName("UTF-8")).length;
        this.adapter = new UsbBtBluetoothAdapter(this.context, device, connection, bluetoothName,
                eirProfile == 1 ? 1 : 0, outboundOnly, new UsbBtBluetoothAdapter.Listener() {
                    @Override public boolean onIncomingStream(String address, BlockingDuplexByteStream incoming) {
                        return !outboundOnly && attachStream(address, incoming);
                    }

                    @Override public void onFailure(String message) {
                        fail("usb_transport_failed", new IOException(message));
                    }

                    @Override public void onSecurityResult(String stage, int status, int eventCode, String outcome) {
                        try {
                            JSONObject row = new JSONObject().put("commandStatus", status).put("decision", outcome);
                            if (eventCode >= 0) row.put("hciEventCode", eventCode);
                            record(stage, row);
                        } catch (org.json.JSONException ignored) { }
                    }
                });
    }

    public void start() throws IOException {
        start(false);
    }

    public void start(boolean deferPhoneConnections) throws IOException {
        synchronized (stateLock) {
            if (closed) throw new IOException("USB Bluetooth stack is closed");
            if (started) return;
            if (connection == null || connection.getFileDescriptor() < 0) {
                throw new IOException("Android did not provide a valid USB file descriptor");
            }
            started = true;
            this.deferPhoneConnections = deferPhoneConnections;
            lastState = "starting";
            startupWorker = startWorker("api17-usb-bt-init", new Runnable() {
                @Override public void run() {
                    initialize();
                }
            });
        }
    }

    private void initialize() {
        try {
            String legacyPhoneRecord = context.getSharedPreferences("hybrid_settings", Context.MODE_PRIVATE)
                    .getString(MainActivity.PREF_USB_BT_PEER, null);
            adapter.start(deferPhoneConnections, legacyPhoneRecord);
            record("legacy_link_key_migration", new JSONObject()
                    .put("eventType", adapter.getLegacyLinkKeyStatus()));
            String address = canonicalAddress(adapter.getLocalAddress());
            if (address == null) throw new IOException("USB Bluetooth controller returned no valid address");
            synchronized (stateLock) {
                if (closed || failure != null) return;
                bluetoothAddress = address;
                ready = true;
                if (stream == null) lastState = "ready";
                stateLock.notifyAll();
            }
            record("controller_ready", new JSONObject()
                    .put("hciVersion", getControllerHciVersion())
                    .put("hciManufacturer", getControllerManufacturer())
                    .put("scanEnableRequested", getScanEnableRequested())
                    .put("scanEnableWriteStatus", getScanEnableWriteStatus())
                    .put("scanEnableWriteAccepted", isScanEnableWriteAccepted())
                    .put("scanEnableStatus", getScanEnableStatus())
                    .put("scanEnable", getScanEnable()));
        } catch (org.json.JSONException ignored) {
            // Diagnostic serialization must not affect the controller.
        } catch (Exception error) {
            fail("hci_start_failed", asIOException(error));
        } finally {
            synchronized (stateLock) { startupWorker = null; }
        }
    }

    public boolean awaitReady(long timeoutMillis) throws IOException, InterruptedException {
        long deadline = deadlineAfter(timeoutMillis);
        synchronized (stateLock) {
            while (!ready && failure == null && !closed && waitForChange(deadline)) {
                // Recheck the predicates after each notification.
            }
            if (failure != null) throw failure;
            return ready && !closed;
        }
    }

    /** Only called once AP configuration and the AirPlay listener are ready. */
    public void allowPhoneConnections() throws IOException {
        synchronized (stateLock) {
            if (failure != null) throw failure;
            if (!ready || closed || outboundWorker != null) {
                throw new IOException("USB Bluetooth is not ready for phone connections");
            }
        }
        adapter.allowPhoneConnections();
    }

    /** Returns whether this controller acknowledged disabling classic discovery. */
    public boolean stopDiscoveryForHandoff() {
        synchronized (stateLock) {
            if (!ready || closed || failure != null) return false;
        }
        return adapter.stopDiscoveryForHandoff();
    }

    public List<String> bondedPeerAddresses() {
        return adapter.bondedPeerAddresses();
    }

    OutboundFailure lastRememberedFailure() {
        synchronized (stateLock) { return rememberedFailure; }
    }

    /** A bounded, bond-only attempt that cannot be preempted by a different incoming phone. */
    public boolean tryRememberedPeer(String address, long timeoutMillis) throws IOException, InterruptedException {
        final String peer = canonicalAddress(address);
        if (peer == null || timeoutMillis <= 0) return false;
        final long deadline = deadlineAfter(timeoutMillis);
        final Thread worker;
        try {
            synchronized (stateLock) {
                if (failure != null) throw failure;
                rememberedFailure = null;
                if (!ready || closed || !deferPhoneConnections || outboundWorker != null
                        || inquiryRunning || peerClosed || stream != null) {
                    return false;
                }
                preferredDirectCancelled = false;
                lastState = "remembered_peer_connecting";
                worker = startWorker("api17-usb-bt-known-peer", new Runnable() {
                    @Override public void run() {
                        openOutbound(peer, true);
                    }
                });
                outboundWorker = worker;
                while (stream == null && failure == null && !closed && outboundWorker == worker
                        && waitForChange(deadline)) {
                    // The worker owns the attempt. Wait without starting inbound scan.
                }
                if (failure != null) throw failure;
                if (stream != null && !closed && !peerClosed) return true;
                if (outboundWorker != worker || closed) return false;
                preferredDirectCancelled = true;
            }
        } catch (InterruptedException interrupted) {
            synchronized (stateLock) {
                preferredDirectCancelled = true;
                interruptWorker(outboundWorker);
            }
            throw interrupted;
        }

        interruptWorker(worker);
        final long abortDeadline = deadlineAfter(PREFERRED_ABORT_WAIT_MS);
        synchronized (stateLock) {
            while (!closed && outboundWorker == worker && waitForChange(abortDeadline)) {
                // Inbound fallback cannot begin while the outgoing worker still owns an ACL.
            }
            if (failure != null) throw failure;
            if (!closed && outboundWorker == worker) {
                throw new IOException("已配对的 iPhone 蓝牙连接未能安全结束");
            }
        }
        return false;
    }

    public boolean awaitRfcomm(long timeoutMillis) throws IOException, InterruptedException {
        long deadline = deadlineAfter(timeoutMillis);
        synchronized (stateLock) {
            while (stream == null && failure == null && outboundFailure == null
                    && !closed && !peerClosed && waitForChange(deadline)) {
                // Recheck the predicates after each notification.
            }
            if (failure != null) throw failure;
            if (outboundFailure != null) throw outboundFailure;
            return stream != null && !closed && !peerClosed;
        }
    }

    public static final class Peer {
        public final String address;
        public final String name;

        private Peer(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    public static boolean isBluetoothAddress(String value) {
        return WirelessPhoneReconnect.isBluetoothAddress(value);
    }

    public List<Peer> discoverPhones(long timeoutMillis) throws IOException, InterruptedException {
        synchronized (stateLock) {
            if (failure != null) throw failure;
            if (closed) throw new InterruptedException("Connection stopped");
            if (!ready || !outboundOnly || outboundWorker != null || stream != null) {
                throw new IOException("蓝牙主动搜索尚未就绪");
            }
            if (inquiryRunning) throw new IOException("蓝牙搜索正在进行");
            inquiryRunning = true;
        }

        List<UsbBtBluetoothAdapter.Peer> results;
        try {
            results = adapter.discoverPhones(timeoutMillis);
            synchronized (stateLock) {
                if (failure != null) throw failure;
                if (closed) throw new InterruptedException("Connection stopped");
            }
        } finally {
            synchronized (stateLock) { inquiryRunning = false; }
        }
        LinkedHashMap<String, Peer> phones = new LinkedHashMap<String, Peer>();
        for (UsbBtBluetoothAdapter.Peer result : results) {
            String address = canonicalAddress(result.getAddress());
            if (address == null) continue;
            String name = cleanName(result.getName());
            boolean phoneClass = ((result.getClassOfDevice() >> 8) & 0x1f) == 2;
            if (!phoneClass && !name.toLowerCase(Locale.US).contains("iphone")) continue;
            Peer prior = phones.get(address);
            if (prior == null && phones.size() == MAX_PHONE_CANDIDATES) continue;
            if (prior == null || (prior.name.isEmpty() && !name.isEmpty())) {
                phones.put(address, new Peer(address, name));
            }
        }
        return new ArrayList<Peer>(phones.values());
    }

    public void connectPeer(String address) throws IOException {
        final String peer = canonicalAddress(address);
        synchronized (stateLock) {
            if (failure != null) throw failure;
            if (!outboundOnly || closed || !ready || peer == null) {
                throw new IOException("无法开始连接已选 iPhone");
            }
            if (peerClosed || stream != null || outboundWorker != null || inquiryRunning) {
                throw new IOException("蓝牙通道无法用于新的连接请求");
            }
            outboundFailure = null;
            lastState = "rfcomm_connecting";
            outboundWorker = startWorker("api17-usb-bt-connect", new Runnable() {
                @Override public void run() {
                    openOutbound(peer, false);
                }
            });
        }
    }

    private void openOutbound(String address, boolean rememberedProbe) {
        OutboundFailure error = null;
        try {
            BlockingDuplexByteStream opened = rememberedProbe
                    ? adapter.openRememberedRfcommStream(address, IPHONE_IAP2_UUID)
                    : adapter.openRfcommStream(address, IPHONE_IAP2_UUID);
            if (opened == null) return;
            boolean accepted;
            synchronized (stateLock) {
                accepted = (!rememberedProbe || !preferredDirectCancelled) && attachStream(address, opened);
            }
            if (!accepted) opened.close();
        } catch (Exception problem) {
            if (problem instanceof UsbBtBluetoothAdapter.ConnectionFailure) {
                UsbBtBluetoothAdapter.ConnectionFailure rejected = (UsbBtBluetoothAdapter.ConnectionFailure) problem;
                error = new OutboundFailure(rejected.getStage(), rejected.getStatus(), problem);
            } else {
                error = new OutboundFailure("outbound_rfcomm_failed", -1, problem);
            }
        } finally {
            synchronized (stateLock) {
                outboundWorker = null;
                if (!closed && failure == null && error != null) {
                    if (rememberedProbe) rememberedFailure = error;
                    else outboundFailure = error;
                    lastState = error.stage;
                }
                stateLock.notifyAll();
            }
        }
        if (error != null) {
            try {
                record("outbound_failed", new JSONObject().put("eventType", error.stage)
                        .put("commandStatus", error.status));
            } catch (org.json.JSONException ignored) { }
        }
    }

    static final class OutboundFailure extends IOException {
        final String stage;
        final int status;

        OutboundFailure(String stage, int status, Throwable cause) {
            super("连接 iPhone 失败，" + stage + "（状态 " + status + "）", cause);
            this.stage = stage;
            this.status = status;
        }
    }

    /** The readback must confirm that both inquiry and page scan are enabled. */
    public boolean awaitDiscoverable(long timeoutMillis) throws InterruptedException {
        return awaitPairingVisibilityEvidence(timeoutMillis, false);
    }

    /** A controller without scan readback can expose an accepted write as separate evidence. */
    public boolean awaitPairingVisibilityEvidence(long timeoutMillis, boolean allowAcceptedWriteFallback)
            throws InterruptedException {
        long deadline = deadlineAfter(timeoutMillis);
        synchronized (stateLock) {
            while (!ready && !closed && failure == null && waitForChange(deadline)) {
                // Adapter startup synchronously completes the scan command and any supported readback.
            }
            return !closed && failure == null && (isScanEnableConfirmed()
                    || (allowAcceptedWriteFallback && isScanEnableWriteAccepted()));
        }
    }

    public String getBluetoothAddress() {
        synchronized (stateLock) { return bluetoothAddress; }
    }

    public String getRfcommPeerAddress() {
        synchronized (stateLock) { return rfcommPeerAddress; }
    }

    public String getLastState() { return lastState; }

    boolean hasPhoneDisconnected() {
        synchronized (stateLock) { return adapter.phoneClosedStream(stream); }
    }
    public int getScanEnable() { return adapter.getScanEnable(); }
    public int getScanEnableStatus() { return adapter.getScanEnableStatus(); }
    public boolean isScanEnableKnown() { return getScanEnableStatus() == 0 && getScanEnable() >= 0; }
    public boolean isScanEnableConfirmed() {
        return isScanEnableKnown() && getScanEnable() == INQUIRY_AND_PAGE_SCAN;
    }
    public boolean isScanEnableWriteKnown() { return getScanEnableWriteStatus() >= 0; }
    public int getScanEnableWriteStatus() { return adapter.getScanEnableWriteStatus(); }
    public boolean isScanEnableWriteAccepted() {
        return adapter.getScanEnableAccepted()
                && getScanEnableRequested() == (outboundOnly ? PAGE_SCAN : INQUIRY_AND_PAGE_SCAN);
    }
    public boolean isScanEnableUsbSubmitKnown() { return getScanEnableUsbSubmitResult() >= 0; }
    public int getScanEnableUsbSubmitResult() { return adapter.getScanEnableUsbSubmitResult(); }
    public int getScanEnableRequested() { return adapter.getScanEnableRequested(); }
    public int getScanEnableWriteCount() { return adapter.getScanEnableWriteCount(); }
    public boolean isRealtekSetupSelected() { return adapter.getRealtekSetupSelected(); }
    public int getControllerManufacturer() { return adapter.getControllerManufacturer(); }
    public int getControllerHciVersion() { return adapter.getControllerHciVersion(); }

    public void send(byte[] data) throws IOException {
        BlockingDuplexByteStream active;
        synchronized (stateLock) {
            if (failure != null) throw failure;
            if (closed || peerClosed || stream == null) throw new IOException("USB RFCOMM is not connected");
            active = stream;
        }
        active.send(data);
    }

    public byte[] receive(int maxBytes, long timeoutMillis) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis must not be negative");
        BlockingDuplexByteStream active;
        synchronized (stateLock) {
            if (closed) return END_OF_STREAM;
            if (failure != null) throw failure;
            if (peerClosed) return END_OF_STREAM;
            active = stream;
        }
        if (active == null) throw new IOException("USB RFCOMM is not connected");
        byte[] received = active.recv(maxBytes, timeoutMillis);
        synchronized (stateLock) {
            if (closed) return END_OF_STREAM;
            if (failure != null) throw failure;
            if (received != null && received.length == 0) {
                peerClosed = true;
                rfcommPeerAddress = null;
                lastState = "rfcomm_closed";
                stateLock.notifyAll();
            }
        }
        return received;
    }

    public void wakeReceiver() {
        BlockingDuplexByteStream active;
        synchronized (stateLock) { active = stream; }
        wakeStream(active);
    }

    private static void wakeStream(BlockingDuplexByteStream active) {
        if (active instanceof WakeableBlockingDuplexByteStream) {
            ((WakeableBlockingDuplexByteStream) active).wakeReceiver();
        }
    }

    @Override public void close() {
        Thread initializing;
        Thread connecting;
        BlockingDuplexByteStream active;
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
            lastState = "closed";
            rfcommPeerAddress = null;
            active = stream;
            stream = null;
            initializing = startupWorker;
            connecting = outboundWorker;
            stateLock.notifyAll();
        }
        wakeStream(active);
        interruptWorker(initializing);
        interruptWorker(connecting);
        // Disconnect the peer while the controller can still transmit HCI commands.
        // The service also closes the USB connection if the adapter never claimed it.
        try {
            if (active != null) active.close();
        } finally {
            adapter.close();
        }
    }

    private boolean attachStream(String address, BlockingDuplexByteStream candidate) {
        String peer = canonicalAddress(address);
        if (peer == null || candidate == null) return false;
        synchronized (stateLock) {
            if (closed || failure != null || peerClosed || stream != null) return false;
            stream = candidate;
            rfcommPeerAddress = peer;
            lastState = "rfcomm_connected";
            stateLock.notifyAll();
            return true;
        }
    }

    private void fail(String stage, IOException error) {
        BlockingDuplexByteStream active;
        synchronized (stateLock) {
            if (closed || failure != null) return;
            failure = error;
            lastState = stage;
            rfcommPeerAddress = null;
            active = stream;
            stateLock.notifyAll();
        }
        wakeStream(active);
        try {
            record(stage, new JSONObject().put("errorType", error.getClass().getSimpleName()));
        } catch (org.json.JSONException ignored) { }
    }

    private void record(String stage, JSONObject row) {
        try {
            row.put("stage", stage).put("backend", "userspace_usb_classic")
                    .put("deviceVid", device.getVendorId()).put("devicePid", device.getProductId())
                    .put("realtekSetupSelected", isRealtekSetupSelected())
                    .put("configuredNameBytes", nameBytes)
                    .put("elapsedMs", Math.max(0L, SystemClock.elapsedRealtime() - startedAt));
            MainActivity.record(context, "USB_BT_HCI", row);
        } catch (Exception ignored) { }
    }

    private boolean waitForChange(long deadline) throws InterruptedException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) return false;
        stateLock.wait(remaining);
        return true;
    }

    private static long deadlineAfter(long timeoutMillis) {
        long now = SystemClock.elapsedRealtime();
        return now + Math.min(Math.max(0L, timeoutMillis), Long.MAX_VALUE - now);
    }

    private static Thread startWorker(String name, Runnable work) {
        Thread worker = new Thread(work, name);
        worker.setDaemon(true);
        worker.start();
        return worker;
    }

    private static void interruptWorker(Thread worker) {
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
    }

    private static IOException asIOException(Exception error) {
        return error instanceof IOException ? (IOException) error
                : new IOException("USB Bluetooth initialization failed", error);
    }

    private static String canonicalAddress(String address) {
        return isBluetoothAddress(address) ? address.toUpperCase(Locale.US) : null;
    }

    private static String cleanName(String name) {
        if (name == null) return "";
        StringBuilder cleaned = new StringBuilder(72);
        for (int i = 0; i < name.length() && cleaned.length() < 72;) {
            int codePoint = name.codePointAt(i);
            int width = Character.charCount(codePoint);
            i += width;
            if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            if (cleaned.length() + width > 72) break;
            cleaned.appendCodePoint(codePoint);
        }
        return cleaned.toString().trim();
    }
}
