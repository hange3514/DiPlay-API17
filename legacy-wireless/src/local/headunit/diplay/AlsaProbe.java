package local.headunit.diplay;

/** Direct ALSA capture probe, bypassing AudioFlinger/HAL policy. */
public final class AlsaProbe {
    private static final boolean AVAILABLE;
    static {
        boolean ok;
        try { System.loadLibrary("diplayusb"); ok = true; }
        catch (UnsatisfiedLinkError e) { ok = false; }
        AVAILABLE = ok;
    }

    private AlsaProbe() { }

    public static boolean available() { return AVAILABLE; }

    /** Opens the pcm capture node, negotiates S16_LE, and reads for millis. Returns an ok/fail summary. */
    public static native String captureTest(String device, int rate, int channels, int millis);

    /** Dumps mixer controls, enables capture-ish ones, sweeps input muxes, and retests capture. */
    public static native String mixerTest(int card, int rate, int channels, int millis);

    /** Read-only mixer snapshot for diffing baseline vs recording state. */
    public static native String dumpControls(int card);

    /** Opens a persistent capture stream; outParams=[rate,channels], outStatus=[stage,errno,rateMin,rateMax,channelsMin,channelsMax,rate,channels]. */
    public static native long captureOpen(String device, int rate, int channels, int[] outParams, int[] outStatus);

    /** Reads interleaved S16_LE PCM into buf. Returns bytes read, 0 on timeout, negative on error. */
    public static native int captureRead(long handle, byte[] buf, int off, int len);

    /** Closes a capture handle from captureOpen. */
    public static native void captureClose(long handle);
}
