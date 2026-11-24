package local.headunit.diplay;

import java.io.Closeable;
import java.util.Arrays;

/** Independent, per-stream encoder for 48 kHz mono PCM16LE in 20 ms frames. */
final class NativeUplinkOpus implements Closeable {
    private static final int FRAME_BYTES = 1920;
    private static final int MAX_PACKET_BYTES = 1275;
    private static final boolean AVAILABLE = load();

    private final byte[] packet = new byte[MAX_PACKET_BYTES];
    private long handle;

    private NativeUplinkOpus(long handle) {
        this.handle = handle;
    }

    private static boolean load() {
        try {
            System.loadLibrary("diplay_mic_opus");
            return true;
        } catch (LinkageError | SecurityException unavailable) {
            return false;
        }
    }

    static NativeUplinkOpus open(int bitrate, int complexity) {
        if (!AVAILABLE) return null;
        try {
            long encoder = nativeCreate(bitrate, complexity);
            return encoder == 0 ? null : new NativeUplinkOpus(encoder);
        } catch (LinkageError unavailable) {
            return null;
        }
    }

    synchronized byte[] encode(byte[] pcm) {
        if (handle == 0 || pcm == null || pcm.length != FRAME_BYTES) return null;
        int length = nativeEncode(handle, pcm, packet);
        return length > 0 && length <= packet.length ? Arrays.copyOf(packet, length) : null;
    }

    synchronized boolean setComplexity(int complexity) {
        return handle != 0 && nativeSetComplexity(handle, complexity) == 0;
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        long encoder = handle;
        handle = 0;
        nativeDestroy(encoder);
    }

    private static native long nativeCreate(int bitrate, int complexity);
    private static native int nativeEncode(long handle, byte[] pcm, byte[] packet);
    private static native int nativeSetComplexity(long handle, int complexity);
    private static native void nativeDestroy(long handle);
}
