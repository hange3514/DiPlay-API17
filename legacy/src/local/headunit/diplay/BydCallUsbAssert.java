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
    private long callGeneration;
    private long callStartElapsed;
    private long lastAssertElapsed;
    private boolean lastAssertWasReactive;
    private int reassertCount;
    private int lastMcuSource;
    private long lastMcuSourceElapsed;
    private boolean sequenceCompleted;
    private boolean assertionPending;
    private boolean delayedReassertPending;

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
        final long generation = ++callGeneration;
        callStartElapsed = SystemClock.elapsedRealtime();
        lastAssertElapsed = -1;
        lastAssertWasReactive = false;
        reassertCount = 0;
        lastMcuSource = -1;
        lastMcuSourceElapsed = -1;
        sequenceCompleted = false;
        assertionPending = false;
        delayedReassertPending = false;
        OemAudioObserver.beginWholeCallCapture();
        OemAudioObserver.setMcuSourceListener(new OemAudioObserver.McuSourceListener() {
            @Override public void onMcuSource(int source, long elapsedMs) {
                onMcuSourceState(generation, source, elapsedMs);
            }
        });
        log("call_active; timed_usb_asserts=" + TIMED_ASSERT_MS.length);
        for (int index = 0; index < TIMED_ASSERT_MS.length; index++) {
            final String phase = "timed_" + index;
            main.postDelayed(new Runnable() { public void run() {
                assertUsbLane(generation, phase, false);
            }}, TIMED_ASSERT_MS[index]);
        }
    }

    public synchronized void callEnded() {
        if (!callActive) return;
        callActive = false;
        callGeneration++;
        OemAudioObserver.setMcuSourceListener(null);
        OemAudioObserver.beginCallRouteExitCapture();
        log("call_ended; usb_lane_confirmed=" + (lastMcuSource == SOURCE_USB) + " reasserts=" + reassertCount);
    }

    private synchronized boolean isCurrentCall(long generation) {
        return callActive && !closed.get() && generation == callGeneration;
    }

    private void onMcuSourceState(long generation, int source, long elapsedMs) {
        final boolean changed;
        synchronized (this) {
            if (!isCurrentCall(generation) || source < 0 || elapsedMs < callStartElapsed
                    || elapsedMs < lastMcuSourceElapsed) return;
            changed = lastMcuSource != source;
            lastMcuSource = source;
            lastMcuSourceElapsed = elapsedMs;
        }
        if (changed) log("mcu_source_state_during_call source=" + source);
        if (source != SOURCE_USB) assertUsbLane(generation, "reactive_source_" + source, true);
    }

    /** Pure decision, unit-tested: re-assert only while the MCU reads back off the USB lane. */
    static boolean shouldReassert(boolean active, int decodedSource, long now, long lastAssertAt, int reasserts) {
        return active && needsReactiveAssertion(decodedSource, reasserts) && reassertDelayMs(now, lastAssertAt) == 0;
    }

    private static boolean needsReactiveAssertion(int source, int reasserts) {
        return source >= 0 && source != SOURCE_USB && reasserts < MAX_REASSERT_PER_CALL;
    }

    static long reassertDelayMs(long now, long lastAssertAt) {
        return lastAssertAt < 0 ? 0 : Math.max(0, REASSERT_MIN_INTERVAL_MS - (now - lastAssertAt));
    }

    static boolean canSkipTimedAssertion(int source, boolean completed) {
        // A source readback does not report the volume or mute state. Keep one full sequence.
        return completed && source == SOURCE_USB;
    }

    private void assertUsbLane(final long generation, final String phase, final boolean reactive) {
        final IBinder mm = multimedia, ss = serial;
        synchronized (this) {
            if (!isCurrentCall(generation) || assertionPending) return;
            if (canSkipTimedAssertion(lastMcuSource, sequenceCompleted)) {
                if (!reactive) log("usb_assert_skipped_already_usb phase=" + phase);
                return;
            }
            if (reactive && !needsReactiveAssertion(lastMcuSource, reassertCount)) return;
            long delay = reassertDelayMs(SystemClock.elapsedRealtime(), lastAssertElapsed);
            if (delay > 0 && (reactive || lastAssertWasReactive)) {
                if (reactive && !delayedReassertPending) {
                    delayedReassertPending = true;
                    main.postDelayed(new Runnable() { public void run() {
                        synchronized (BydCallUsbAssert.this) {
                            if (!isCurrentCall(generation)) return;
                            delayedReassertPending = false;
                        }
                        assertUsbLane(generation, "deferred_source_reassert", true);
                    }}, delay);
                }
                return;
            }
            if (mm == null || ss == null) { log("usb_assert_skipped_service_unavailable " + phase); return; }
            assertionPending = true;
        }
        try {
            COMMANDS.execute(new Runnable() { public void run() {
                boolean started = false;
                try {
                    synchronized (BydCallUsbAssert.this) {
                        if (!isCurrentCall(generation) || canSkipTimedAssertion(lastMcuSource, sequenceCompleted)) return;
                        long now = SystemClock.elapsedRealtime();
                        if (reactive && !shouldReassert(true, lastMcuSource, now, lastAssertElapsed, reassertCount)) return;
                        lastAssertElapsed = now;
                        lastAssertWasReactive = reactive;
                        if (reactive) reassertCount++;
                        started = true;
                    }
                    serialSend(ss, frame(sourceMessage(SOURCE_USB)));
                    log("usb_source_request_returned source=10 phase=" + phase);
                    if (!isCurrentCall(generation)) return;
                    byte[] volumes = readDatabaseBytes(mm, OEM_VOLUME_KEYS);
                    if (volumes.length < 4) throw new IOException("OEM volume database returned too few values");
                    int mediaVolume = volumes[3] & 0xff;
                    if (!isCurrentCall(generation)) return;
                    serialSend(ss, frame(modeVolumeMessage(mediaVolume)));
                    log("usb_mode_volume_request_returned volume4Mx9=" + mediaVolume + " phase=" + phase);
                    if (!isCurrentCall(generation)) return;
                    // Official MuteControl(4) emits MCU message {4,4,1,1} and clears
                    // the OEM all-mute state; no source or UI switch.
                    control(mm, (byte) 3, (byte) 4, null);
                    log("usb_unmute_request_returned phase=" + phase);
                    synchronized (BydCallUsbAssert.this) {
                        if (isCurrentCall(generation)) sequenceCompleted = true;
                    }
                } catch (Exception e) {
                    log("usb_assert_failed=" + e.getClass().getSimpleName() + " phase=" + phase);
                } finally {
                    finishAssertion(generation, started);
                }
            }});
        } catch (RejectedExecutionException e) {
            finishAssertion(generation, false);
            log("usb_assert_queue_busy " + phase);
        }
    }

    private void finishAssertion(long generation, boolean checkReadback) {
        final boolean stillOffUsb;
        synchronized (this) {
            if (!isCurrentCall(generation)) return;
            assertionPending = false;
            stillOffUsb = checkReadback && lastMcuSourceElapsed > lastAssertElapsed
                    && needsReactiveAssertion(lastMcuSource, reassertCount);
        }
        if (stillOffUsb) assertUsbLane(generation, "source_readback_during_recovery", true);
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
        synchronized (this) { callActive = false; callGeneration++; }
        OemAudioObserver.setMcuSourceListener(null);
        main.post(new Runnable() { public void run() {
            multimedia = null;
            serial = null;
            if (multimediaBound) { multimediaBound = false; try { context.unbindService(BydCallUsbAssert.this); } catch (Exception ignored) { } }
            if (serialBound) { serialBound = false; try { context.unbindService(BydCallUsbAssert.this); } catch (Exception ignored) { } }
        }});
    }
}
