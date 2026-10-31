package local.headunit.diplay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Keeps the BYD MCU on the USB audio lane during a CarPlay telephony stream.
 *
 * Evidence behind this candidate: the 0.53.52 report showed the OEM multimedia
 * service entering its phone state by itself at ring time (APP_REV_DATA source
 * 13) while DiPlay sent nothing; media-audible periods correlate with source
 * 22; the conversation window had no source broadcast at all. If the MCU sits
 * on the Bluetooth-phone lane (no signal, Bluetooth disconnected) the whole
 * Android output path is inaudible even though AudioTrack consumes PCM.
 *
 * This class sends the documented OEM USB-source + mode-volume + unmute serial
 * sequence, watches raw MCU source-state frames (group 1 / command 2) for the
 * whole call, and re-asserts (rate-limited) whenever the readback leaves the
 * USB lane. Direct serial frames do not touch the OEM service's remembered
 * source, so the OEM hang-up restore path is unaffected.
 */
public final class BydCallUsbAssert implements Closeable, ServiceConnection {
    static final String MULTIMEDIA_DESCRIPTOR = "com.byd.multimediaservice.IMultimediaService";
    static final String SERIAL_DESCRIPTOR = "com.byd.serialservice.ISerialService";
    private static final String MULTIMEDIA_PACKAGE = "com.byd.multimediaservice";
    private static final String MULTIMEDIA_SERVICE = "com.byd.multimediaservice.MultimediaService";
    private static final String SERIAL_PACKAGE = "com.byd.serialservice";
    private static final String SERIAL_SERVICE = "com.byd.serialservice.SerialService";
    static final byte SOURCE_USB = 10;
    static final long[] TIMED_ASSERT_MS = { 600, 2000, 5000, 10000 };
    static final long REASSERT_MIN_INTERVAL_MS = 1500;
    static final int MAX_REASSERT_PER_CALL = 8;
    private static final String[] OEM_VOLUME_KEYS = new String[] {
            "Volume", "volume4Phone", "volume4Radio", "volume4Mx9", "volume4Aux",
            "volume4PhoneMusic", "VolumeNavigtion", "UERemindVolume"
    };
    private static final ThreadPoolExecutor COMMANDS = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1));

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile IBinder multimedia, serial;
    private boolean multimediaBound, serialBound;
    private volatile boolean callActive;
    private long callStartElapsed;
    private long lastAssertElapsed;
    private int reassertCount;
    private int timedAssertIndex;
    private boolean usbLaneConfirmed;

    public BydCallUsbAssert(Context c) {
        context = c.getApplicationContext();
    }

    static boolean supportsDevice(int sdk, String model, int multimediaVersion, int serialVersion) {
        return sdk == 17 && "MX9A0".equals(model) && multimediaVersion == 382 && serialVersion == 12;
    }

    public void start() {
        try {
            PackageInfo mm = context.getPackageManager().getPackageInfo(MULTIMEDIA_PACKAGE, 0);
            PackageInfo ss = context.getPackageManager().getPackageInfo(SERIAL_PACKAGE, 0);
            if (!supportsDevice(Build.VERSION.SDK_INT, Build.MODEL, mm.versionCode, ss.versionCode)) {
                log("unsupported_device multimedia=" + mm.versionCode + " serial=" + ss.versionCode);
                close();
                return;
            }
            Intent a = new Intent(MULTIMEDIA_DESCRIPTOR).setComponent(new ComponentName(MULTIMEDIA_PACKAGE, MULTIMEDIA_SERVICE));
            multimediaBound = context.bindService(a, this, Context.BIND_AUTO_CREATE);
            Intent b = new Intent(SERIAL_DESCRIPTOR).setComponent(new ComponentName(SERIAL_PACKAGE, SERIAL_SERVICE));
            serialBound = context.bindService(b, this, Context.BIND_AUTO_CREATE);
            log("bind_multimedia=" + multimediaBound + " bind_serial=" + serialBound);
            if (!multimediaBound || !serialBound) close();
        } catch (Exception e) {
            log("bind_failed=" + e.getClass().getSimpleName());
            close();
        }
    }

    public synchronized void callStarted() {
        if (closed.get() || callActive) return;
        callActive = true;
        callStartElapsed = SystemClock.elapsedRealtime();
        lastAssertElapsed = 0;
        reassertCount = 0;
        timedAssertIndex = 0;
        usbLaneConfirmed = false;
        OemAudioObserver.beginWholeCallCapture();
        OemAudioObserver.setMcuSourceListener(new OemAudioObserver.McuSourceListener() {
            @Override public void onMcuSource(int source, long elapsedMs) { onMcuSourceState(source, elapsedMs); }
        });
        log("call_active; timed_usb_asserts=" + TIMED_ASSERT_MS.length);
        scheduleNextTimedAssert();
    }

    public synchronized void callEnded() {
        if (!callActive) return;
        callActive = false;
        OemAudioObserver.setMcuSourceListener(null);
        OemAudioObserver.beginCallRouteExitCapture();
        log("call_ended; usb_lane_confirmed=" + usbLaneConfirmed + " reasserts=" + reassertCount);
    }

    private void scheduleNextTimedAssert() {
        final int index;
        synchronized (this) {
            if (!callActive || closed.get() || timedAssertIndex >= TIMED_ASSERT_MS.length) return;
            index = timedAssertIndex++;
        }
        long delay = TIMED_ASSERT_MS[index] - (SystemClock.elapsedRealtime() - callStartElapsed);
        main.postDelayed(new Runnable() { public void run() {
            synchronized (BydCallUsbAssert.this) { if (!callActive || closed.get()) return; }
            assertUsbLane("timed_" + index);
            scheduleNextTimedAssert();
        }}, Math.max(delay, 0));
    }

    private void onMcuSourceState(int source, long elapsedMs) {
        boolean fire;
        synchronized (this) {
            if (source == SOURCE_USB) usbLaneConfirmed = true;
            fire = shouldReassert(callActive, source, elapsedMs, lastAssertElapsed, reassertCount);
            if (fire) { lastAssertElapsed = elapsedMs; reassertCount++; }
        }
        log("mcu_source_state_during_call source=" + source + " usbConfirmed=" + usbLaneConfirmed + " reassert=" + fire);
        if (fire) assertUsbLane("reactive_source_" + source);
    }

    /** Pure decision, unit-tested: re-assert only while the MCU reads back off the USB lane. */
    static boolean shouldReassert(boolean active, int decodedSource, long now, long lastAssertAt, int reasserts) {
        return active && decodedSource >= 0 && decodedSource != SOURCE_USB
                && now - lastAssertAt >= REASSERT_MIN_INTERVAL_MS && reasserts < MAX_REASSERT_PER_CALL;
    }

    private void assertUsbLane(final String phase) {
        final IBinder mm = multimedia, ss = serial;
        if (mm == null || ss == null) { log("usb_assert_skipped_service_unavailable " + phase); return; }
        try {
            COMMANDS.execute(new Runnable() { public void run() {
                synchronized (BydCallUsbAssert.this) { if (!callActive || closed.get()) return; }
                try {
                    serialSend(ss, frame(sourceMessage(SOURCE_USB)));
                    log("usb_source_request_returned source=10 phase=" + phase);
                    synchronized (BydCallUsbAssert.this) { if (!callActive || closed.get()) return; }
                    byte[] volumes = readDatabaseBytes(mm, OEM_VOLUME_KEYS);
                    if (volumes.length < 4) throw new IOException("OEM volume database returned too few values");
                    int mediaVolume = volumes[3] & 0xff;
                    serialSend(ss, frame(modeVolumeMessage(mediaVolume)));
                    log("usb_mode_volume_request_returned volume4Mx9=" + mediaVolume + " phase=" + phase);
                    synchronized (BydCallUsbAssert.this) { if (!callActive || closed.get()) return; }
                    // Official MuteControl(4) emits MCU message {4,4,1,1} and clears
                    // the OEM all-mute state; no source or UI switch.
                    control(mm, (byte) 3, (byte) 4, null);
                    log("usb_unmute_request_returned phase=" + phase);
                } catch (Exception e) {
                    log("usb_assert_failed=" + e.getClass().getSimpleName() + " phase=" + phase);
                }
            }});
        } catch (RejectedExecutionException e) { log("usb_assert_queue_busy " + phase); }
    }

    static byte[] sourceMessage(byte source) { return new byte[] { 1, 2, source, 0, 0, 0 }; }

    /** OEM McuControl.sendModeVolume layout: [4,4,0,volume,source]. */
    static byte[] modeVolumeMessage(int volume4Mx9) {
        return new byte[] { 4, 4, 0, (byte) volume4Mx9, SOURCE_USB };
    }

    static byte[] frame(byte[] payload) {
        if (payload == null || payload.length > 250) throw new IllegalArgumentException("Invalid MCU payload");
        byte[] out = new byte[payload.length + 5];
        out[0] = (byte) 0xaa; out[1] = 0x55; out[2] = (byte) out.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        // SerialService assigns the sequence byte and parity exactly as for CSerialPortProxy.
        return out;
    }

    static void serialSend(IBinder target, byte[] frame) throws Exception {
        if (target == null || !SERIAL_DESCRIPTOR.equals(target.getInterfaceDescriptor()))
            throw new IOException("Unexpected BYD serial interface");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERIAL_DESCRIPTOR);
            data.writeByteArray(frame);
            if (!target.transact(1, data, reply, 0)) throw new IOException("Serial send transaction rejected");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    static void control(IBinder target, byte group, byte subId, byte[] list) throws Exception {
        if (target == null || !MULTIMEDIA_DESCRIPTOR.equals(target.getInterfaceDescriptor()))
            throw new IOException("Unexpected BYD multimedia interface");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MULTIMEDIA_DESCRIPTOR);
            data.writeByte(group);
            data.writeByte(subId);
            data.writeStringArray(null);
            data.writeByteArray(list);
            data.writeStringArray(null);
            if (!target.transact(1, data, reply, 0)) throw new IOException("Control transaction rejected");
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    static byte[] readDatabaseBytes(IBinder target, String[] keys) throws Exception {
        if (target == null || !MULTIMEDIA_DESCRIPTOR.equals(target.getInterfaceDescriptor()))
            throw new IOException("Unexpected BYD multimedia interface");
        if (keys == null || keys.length == 0 || keys.length > 32)
            throw new IllegalArgumentException("Invalid OEM database keys");
        byte[] values = new byte[keys.length];
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MULTIMEDIA_DESCRIPTOR);
            data.writeByte((byte) 1); // DataBaseControl
            data.writeByte((byte) 2); // Get
            data.writeStringArray(keys);
            data.writeByteArray(values);
            data.writeStringArray(null);
            if (!target.transact(1, data, reply, 0)) throw new IOException("OEM database transaction rejected");
            reply.readException();
            byte[] returned = reply.createByteArray();
            reply.createStringArray(); // Consume the paired StringList output in the OEM AIDL contract.
            if (returned == null || returned.length != keys.length)
                throw new IOException("OEM database returned an invalid byte array");
            return returned;
        } finally { data.recycle(); reply.recycle(); }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (closed.get()) return;
        try {
            String actual = binder.getInterfaceDescriptor();
            if (MULTIMEDIA_DESCRIPTOR.equals(actual) && MULTIMEDIA_PACKAGE.equals(name.getPackageName())) multimedia = binder;
            else if (SERIAL_DESCRIPTOR.equals(actual) && SERIAL_PACKAGE.equals(name.getPackageName())) serial = binder;
            else { log("descriptor_mismatch=" + actual); close(); return; }
            log("service_connected package=" + name.getPackageName() + " descriptor_verified=true");
        } catch (Exception e) { log("service_connect_failed=" + e.getClass().getSimpleName()); close(); }
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        if (MULTIMEDIA_PACKAGE.equals(name.getPackageName())) multimedia = null;
        if (SERIAL_PACKAGE.equals(name.getPackageName())) serial = null;
        log("service_disconnected package=" + name.getPackageName());
    }

    private void log(String detail) {
        try { MainActivity.record(context, "BYD_CALL_USB_ASSERT", new JSONObject().put("detail", detail)
                .put("evidence", "serial_and_binder_acceptance_are_not_physical_speaker_confirmation")); }
        catch (Exception ignored) { }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (this) { callActive = false; }
        OemAudioObserver.setMcuSourceListener(null);
        main.post(new Runnable() { public void run() {
            multimedia = null;
            serial = null;
            if (multimediaBound) { multimediaBound = false; try { context.unbindService(BydCallUsbAssert.this); } catch (Exception ignored) { } }
            if (serialBound) { serialBound = false; try { context.unbindService(BydCallUsbAssert.this); } catch (Exception ignored) { } }
        }});
    }
}
