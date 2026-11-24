package local.headunit.diplay;

import java.util.Arrays;
import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.concentus.OpusSignal;

/** The wireless streams use Opus even on Android releases without platform Opus codecs. */
public final class WirelessOpus {
    private static final int CAPTURE_RATE = 48000;
    private static final int FRAME_SAMPLES = 960;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int ENCODER_MAX_PACKET_BYTES = 1275;
    private static final int MAX_DECODE_DURATION_MS = 120;
    private static final int MAX_DATAGRAM_BYTES = 65535;

    private WirelessOpus() {}

    /** One instance per audio stream. Produces PCM16 little-endian for AudioTrack. */
    public static final class Decoder {
        private final OpusDecoder decoder;
        private final int channels;
        private final int maximumSamplesPerChannel;
        private final short[] samples;
        private byte[] pcm = new byte[0];
        private boolean usable = true;

        public Decoder(int sampleRate, int channels) {
            if ((sampleRate != 8000 && sampleRate != 12000 && sampleRate != 16000
                    && sampleRate != 24000 && sampleRate != 48000) || (channels != 1 && channels != 2)) {
                throw new IllegalArgumentException("Unsupported Opus decode format");
            }
            this.channels = channels;
            maximumSamplesPerChannel = sampleRate * MAX_DECODE_DURATION_MS / 1000;
            samples = new short[maximumSamplesPerChannel * channels];
            try {
                decoder = new OpusDecoder(sampleRate, channels);
            } catch (OpusException error) {
                throw new IllegalStateException("Could not initialize software Opus decoder", error);
            }
        }

        /** Returns PCM valid until the next decode on this worker, or null on failure. */
        public byte[] decode(byte[] packet, int offset, int length) {
            if (!usable || packet == null || offset < 0 || length <= 0 || length > MAX_DATAGRAM_BYTES
                    || offset > packet.length || length > packet.length - offset) {
                return null;
            }
            final int decoded;
            try {
                decoded = decoder.decode(packet, offset, length, samples, 0, maximumSamplesPerChannel, false);
            } catch (OpusException | RuntimeException | AssertionError error) {
                reset();
                return null;
            }
            if (decoded <= 0 || decoded > maximumSamplesPerChannel) return null;
            int count = decoded * channels;
            if (pcm.length != count * 2) pcm = new byte[count * 2];
            for (int index = 0, output = 0; index < count; index++) {
                int sample = samples[index];
                pcm[output++] = (byte) sample;
                pcm[output++] = (byte) (sample >> 8);
            }
            return pcm;
        }

        public void reset() {
            try {
                decoder.resetState();
                usable = true;
            } catch (RuntimeException | AssertionError resetFailed) {
                usable = false;
            }
        }
    }

    /** One instance per microphone stream, with 20 ms PCM16 little-endian input at 48 kHz mono. */
    public static final class Encoder implements java.io.Closeable {
        private final int bitrate;
        private final int javaComplexity;
        private NativeUplinkOpus nativeEncoder;
        private OpusEncoder javaEncoder;
        private short[] javaSamples;
        private byte[] javaPacket;
        private boolean usable = true;
        private boolean closed;
        private volatile int complexity;
        private volatile String implementation = "java_concentus";

        public Encoder(int bitrate, int complexity) {
            this(bitrate, complexity, false);
        }

        public Encoder(int bitrate, int complexity, boolean preferNative) {
            if (bitrate <= 0) throw new IllegalArgumentException("Opus bitrate must be positive");
            this.bitrate = bitrate;
            javaComplexity = preferNative ? 0 : complexity;
            if (preferNative) nativeEncoder = NativeUplinkOpus.open(bitrate, complexity);
            if (nativeEncoder != null) {
                this.complexity = complexity;
                implementation = "native_opus";
            } else {
                initJavaEncoder();
            }
        }

        private void initJavaEncoder() {
            try {
                javaEncoder = new OpusEncoder(CAPTURE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                javaEncoder.setBitrate(bitrate);
                javaEncoder.setComplexity(javaComplexity);
                javaEncoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
                javaSamples = new short[FRAME_SAMPLES];
                javaPacket = new byte[ENCODER_MAX_PACKET_BYTES];
                complexity = javaComplexity;
                implementation = "java_concentus";
            } catch (OpusException error) {
                throw new IllegalStateException("Could not initialize software Opus encoder", error);
            }
        }

        public int getComplexity() { return complexity; }

        public String getImplementation() { return implementation; }

        /** Reduce native encoder effort when the capture queue falls behind. */
        public void reduceComplexityForBacklog() {
            if (closed || complexity == 0 || nativeEncoder == null) return;
            try {
                if (nativeEncoder.setComplexity(0)) {
                    complexity = 0;
                    return;
                }
            } catch (LinkageError | RuntimeException unavailable) {
                // The Java path handles the next frame if the native encoder fails.
            }
            useJavaFallback();
        }

        /** Returns a fresh Opus payload, or null if this PCM frame could not be encoded. */
        public byte[] encode(byte[] pcm) {
            if (closed || !usable || pcm == null || pcm.length != FRAME_BYTES) return null;
            if (nativeEncoder != null) {
                try {
                    byte[] payload = nativeEncoder.encode(pcm);
                    if (payload != null) return payload;
                } catch (LinkageError | RuntimeException unavailable) {
                    // A missing or failed JNI entry point should leave the stream operational.
                }
                useJavaFallback();
            }
            for (int index = 0, input = 0; index < javaSamples.length; index++, input += 2) {
                javaSamples[index] = (short) ((pcm[input] & 0xff) | (pcm[input + 1] << 8));
            }
            try {
                int length = javaEncoder.encode(javaSamples, 0, FRAME_SAMPLES, javaPacket, 0, javaPacket.length);
                return length > 0 && length <= javaPacket.length ? Arrays.copyOf(javaPacket, length) : null;
            } catch (OpusException | RuntimeException | AssertionError error) {
                try {
                    javaEncoder.resetState();
                } catch (RuntimeException | AssertionError resetFailed) {
                    usable = false;
                }
                return null;
            }
        }

        private void useJavaFallback() {
            releaseNative();
            initJavaEncoder();
        }

        private void releaseNative() {
            NativeUplinkOpus previous = nativeEncoder;
            nativeEncoder = null;
            if (previous != null) {
                try {
                    previous.close();
                } catch (LinkageError | RuntimeException ignored) {
                    // The stream must still be able to release its capture worker.
                }
            }
        }

        @Override public void close() {
            closed = true;
            releaseNative();
        }
    }
}
