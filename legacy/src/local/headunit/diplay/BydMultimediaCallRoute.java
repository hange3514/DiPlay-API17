package local.headunit.diplay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.Closeable;
import org.json.JSONObject;

/** Uses the audited BYD multimedia Binder phone-state path for CarPlay telephony. */
public final class BydMultimediaCallRoute implements Closeable {
    static final String SERVICE_PACKAGE = "com.byd.multimediaservice";
    static final String SERVICE_CLASS = "com.byd.multimediaservice.MultimediaService";
    static final String DESCRIPTOR = "com.byd.multimediaservice.IMultimediaService";
    private static final byte GROUP_SOURCE = 2;
    private static final byte SUB_SOURCE_ON = 1;
    private static final byte SUB_SOURCE_OFF = 2;
    private static final byte SOURCE_BLUETOOTH_PHONE = 13;
    private static final int TRANSACTION_CONTROL = 1;

    private final Context context;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            String descriptor = null;
            try { descriptor = service.getInterfaceDescriptor(); }
            catch (Exception e) { log("oem_service_descriptor_failed", e.getClass().getSimpleName()); }
            boolean matches = DESCRIPTOR.equals(descriptor);
            synchronized (BydMultimediaCallRoute.this) {
                serviceBinder = matches ? service : null;
                if (!matches) phoneModeMayBeActive = false;
            }
            log("oem_service_connected", matches ? "descriptor_match" : "descriptor_mismatch");
            if (matches) postReconcile();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (BydMultimediaCallRoute.this) {
                serviceBinder = null;
                // A disconnected Binder means the OEM service process died; its in-memory
                // phone state is gone and will be re-entered on reconnect if the call lives.
                phoneModeMayBeActive = false;
            }
            log("oem_service_disconnected", "binder_process_lost");
        }
    };

    private HandlerThread workerThread;
    private Handler worker;
    private IBinder serviceBinder;
    private boolean started;
    private boolean supported;
    private int serviceVersion = -1;
    private boolean bindRequested;
    private boolean bindInProgress;
    private volatile boolean phoneModeMayBeActive;
    private volatile boolean callActive;
    private volatile boolean closed;
    private int enterRetries;
    private int restoreRetries;

    public BydMultimediaCallRoute(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        serviceVersion = multimediaVersion(context);
        supported = supportsFirmware(Build.VERSION.SDK_INT, Build.MODEL, serviceVersion);
        log("oem_phone_route_capability", supported ? "supported_firmware" : "unsupported_firmware");
        if (!supported) return;
        workerThread = new HandlerThread("DiPlay-BYD-call-route");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        requestBind();
    }

    public void callStarted() {
        synchronized (this) {
            if (closed || callActive) return;
            callActive = true;
            enterRetries = 0;
            restoreRetries = 0;
        }
        OemAudioObserver.beginCallRouteCapture();
        log("carplay_telephony_started", supported ? "oem_phone_state_pending" : "oem_phone_state_not_applicable");
        if (supported) {
            requestBind();
            postReconcile();
        }
    }

    public void callEnded() {
        synchronized (this) {
            if (!callActive) return;
            callActive = false;
            restoreRetries = 0;
        }
        OemAudioObserver.beginCallRouteExitCapture();
        log("carplay_telephony_ended", "oem_phone_state_restore_queued");
        if (supported) postReconcile();
    }

    static boolean supportsFirmware(int sdk, String model, int multimediaVersion) {
        return sdk == 17 && "MX9A0".equals(model)
                && multimediaVersion == 382;
    }

    static byte[] phoneModeStartPayload() { return new byte[] { SOURCE_BLUETOOTH_PHONE }; }
    static byte[] phoneModeStopPayload() { return new byte[] { SOURCE_BLUETOOTH_PHONE, 0 }; }

    synchronized boolean phoneModeMayBeActive() { return phoneModeMayBeActive; }

    /** Exact generated-AIDL Parcel contract, kept separate so it can be tested with a Binder fixture. */
    static ControlReply transactControl(IBinder remote, byte group, byte subId, byte[] payload)
            throws RemoteException {
        if (remote == null || !remote.isBinderAlive()) throw new RemoteException("oem_binder_unavailable");
        String descriptor = remote.getInterfaceDescriptor();
        if (!DESCRIPTOR.equals(descriptor)) throw new RemoteException("oem_descriptor_mismatch");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        byte[] inOut = payload == null ? new byte[] { 0 } : payload.clone();
        String[] returnedStrings = new String[1];
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeByte(group);
            data.writeByte(subId);
            data.writeStringArray(null);
            data.writeByteArray(inOut);
            data.writeStringArray(returnedStrings);
            boolean handled = remote.transact(TRANSACTION_CONTROL, data, reply, 0);
            if (!handled) return new ControlReply(false, inOut, returnedStrings);
            reply.readException();
            reply.readByteArray(inOut);
            reply.readStringArray(returnedStrings);
            return new ControlReply(true, inOut, returnedStrings);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static final class ControlReply {
        final boolean handled;
        final byte[] returnedBytes;
        final String[] returnedStrings;
        ControlReply(boolean handled, byte[] bytes, String[] strings) {
            this.handled = handled;
            this.returnedBytes = bytes;
            this.returnedStrings = strings;
        }
    }

    private void requestBind() {
        synchronized (this) {
            if (closed || !supported || bindRequested || bindInProgress) return;
            bindInProgress = true;
        }
        Intent intent = new Intent(DESCRIPTOR);
        intent.setClassName(SERVICE_PACKAGE, SERVICE_CLASS);
        boolean accepted = false;
        String error = null;
        try { accepted = context.bindService(intent, connection, Context.BIND_AUTO_CREATE); }
        catch (Exception e) { error = e.getClass().getSimpleName(); }
        boolean unbindAfterClose;
        synchronized (this) {
            bindInProgress = false;
            bindRequested = accepted;
            unbindAfterClose = accepted && closed;
            if (unbindAfterClose) bindRequested = false;
        }
        log("oem_service_bind_requested", accepted ? "accepted" : (error == null ? "rejected" : error));
        if (unbindAfterClose) {
            try { context.unbindService(connection); }
            catch (Exception e) { log("oem_service_unbind_failed", e.getClass().getSimpleName()); }
        }
    }

    private void postReconcile() {
        Handler h;
        synchronized (this) { h = worker; }
        if (h != null) h.post(new Runnable() { @Override public void run() { reconcilePhoneMode(); } });
    }

    /** Runs only on the private worker: synchronous OEM Binder calls never block audio/UI threads. */
    private void reconcilePhoneMode() {
        IBinder remote;
        boolean wantPhoneMode;
        boolean modeMayBeActive;
        synchronized (this) {
            remote = serviceBinder;
            wantPhoneMode = callActive;
            modeMayBeActive = phoneModeMayBeActive;
        }
        if (remote == null || !remote.isBinderAlive()) {
            log("oem_phone_mode_waiting_for_service", wantPhoneMode ? "call_active" : "no_call");
            return;
        }
        if (wantPhoneMode && !modeMayBeActive) {
            sendPhoneMode(SUB_SOURCE_ON, phoneModeStartPayload(), "enter");
            if (!callActive) postReconcile();
        } else if (!wantPhoneMode && modeMayBeActive) {
            sendPhoneMode(SUB_SOURCE_OFF, phoneModeStopPayload(), "restore");
        }
    }

    private void sendPhoneMode(byte subId, byte[] payload, String phase) {
        IBinder remote;
        synchronized (this) { remote = serviceBinder; }
        try {
            ControlReply result = transactControl(remote, GROUP_SOURCE, subId, payload);
            if (!result.handled) {
                if ("enter".equals(phase)) synchronized (this) { phoneModeMayBeActive = false; }
                log("oem_phone_mode_transact_unhandled", phase);
                retryReconcile(phase);
                return;
            }
            synchronized (this) {
                phoneModeMayBeActive = "enter".equals(phase);
                if ("enter".equals(phase)) enterRetries = 0;
                else restoreRetries = 0;
            }
            log("oem_phone_mode_transact_returned", phase + "; group=2; subId=" + subId
                    + "; source=13; payloadBytes=" + payload.length
                    + "; replyBytes=" + result.returnedBytes.length
                    + "; replyStrings=" + result.returnedStrings.length);
        } catch (Exception e) {
            // If a synchronous call fails after reaching the service, an exit request is still
            // sent when the AirPlay call ends; never describe this Binder return as MCU ACK.
            if ("enter".equals(phase)) synchronized (this) { phoneModeMayBeActive = false; }
            log("oem_phone_mode_transact_failed", phase + "; " + e.getClass().getSimpleName());
            retryReconcile(phase);
        }
    }

    private void retryReconcile(String phase) {
        Handler h;
        synchronized (this) {
            if (closed) return;
            if ("enter".equals(phase)) {
                if (!callActive || enterRetries >= 2) return;
                enterRetries++;
            } else {
                if (callActive || !phoneModeMayBeActive || restoreRetries >= 2) return;
                restoreRetries++;
            }
            h = worker;
        }
        if (h != null) h.postDelayed(new Runnable() {
            @Override public void run() { reconcilePhoneMode(); }
        }, 500);
    }

    private static int multimediaVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(SERVICE_PACKAGE, 0).versionCode; }
        catch (Exception e) { return -1; }
    }

    private void unbind() {
        boolean shouldUnbind;
        synchronized (this) {
            shouldUnbind = bindRequested && !bindInProgress;
            if (shouldUnbind) bindRequested = false;
            serviceBinder = null;
        }
        if (shouldUnbind) {
            try { context.unbindService(connection); }
            catch (Exception e) { log("oem_service_unbind_failed", e.getClass().getSimpleName()); }
        }
    }

    private void log(String detail, String result) {
        try {
            MainActivity.record(context, "BYD_CALL_ROUTE", new JSONObject()
                    .put("detail", detail)
                    .put("result", result)
                    .put("servicePackage", SERVICE_PACKAGE)
                    .put("serviceVersion", serviceVersion)
                    .put("serviceDescriptor", DESCRIPTOR)
                    .put("carplayPcmStream", AudioManager.STREAM_MUSIC)
                    .put("oemServiceFocusStream", AudioManager.STREAM_MUSIC)
                    .put("oemPhoneSource", SOURCE_BLUETOOTH_PHONE)
                    .put("oemModeMayBeActive", phoneModeMayBeActive)
                    .put("callActive", callActive)
                    .put("downlinkTransport", "carplay_airplay_over_usb")
                    .put("evidenceScope", "Binder transact only; MCU feedback is logged separately"));
        } catch (Exception ignored) { }
    }

    @Override public void close() {
        boolean endCapture;
        Handler h;
        HandlerThread thread;
        synchronized (this) {
            if (closed) return;
            endCapture = callActive;
            callActive = false;
            closed = true;
            h = worker;
            thread = workerThread;
        }
        if (endCapture) {
            OemAudioObserver.endCallRouteCapture();
            log("carplay_telephony_ended", "close_restore_queued");
        }
        if (h != null) h.post(new Runnable() {
            @Override public void run() {
                reconcilePhoneMode();
                unbind();
                if (thread != null) thread.quit();
            }
        });
        else unbind();
    }
}
