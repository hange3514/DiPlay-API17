package local.headunit.diplay;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

/** Read-only Android media capability inventory for the in-vehicle diagnostic. */
final class Api17PlaybackCapability {
    private static final int MAX_CODECS = 256;
    private static final String FREESCALE_AVC = "OMX.Freescale.std.video_decoder.avc.v3.hw-based";

    static final class Result {
        final String summary;
        final String display;

        Result(String summary, String display) {
            this.summary = summary;
            this.display = display;
        }
    }

    private Api17PlaybackCapability() {}

    static Result inspect(Context context) {
        boolean avc = false;
        boolean freescaleAvc = false;
        boolean hevc = false;
        boolean aac = false;
        boolean complete = true;
        try {
            int count = MediaCodecList.getCodecCount();
            complete = count >= 0 && count <= MAX_CODECS;
            for (int index = 0; index < Math.min(count, MAX_CODECS); index++) {
                try {
                    MediaCodecInfo codec = MediaCodecList.getCodecInfoAt(index);
                    if (codec.isEncoder()) continue;
                    for (String mime : codec.getSupportedTypes()) {
                        if ("video/avc".equalsIgnoreCase(mime)) {
                            avc = true;
                            freescaleAvc |= FREESCALE_AVC.equalsIgnoreCase(codec.getName());
                        } else if ("video/hevc".equalsIgnoreCase(mime)) {
                            hevc = true;
                        } else if ("audio/mp4a-latm".equalsIgnoreCase(mime)) {
                            aac = true;
                        }
                    }
                } catch (RuntimeException | LinkageError error) {
                    complete = false;
                }
            }
        } catch (RuntimeException | LinkageError error) {
            complete = false;
        }

        AudioManager audio = null;
        try {
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        } catch (RuntimeException ignored) {
            // The channel query below can still report how the Android audio API responds.
        }
        int rate = readProperty(audio, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE, 8000, 192000);
        int frames = readProperty(audio, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER, 1, 65536);
        int queryRate = rate > 0 ? rate : 48000;
        String pcm51 = minimumBufferStatus(queryRate, AudioFormat.CHANNEL_OUT_5POINT1);
        String pcm71 = minimumBufferStatus(queryRate, AudioFormat.CHANNEL_OUT_7POINT1);

        String summary = "codecList=" + (complete ? "complete" : "partial")
                + " avcDecoder=" + codecState(avc, complete)
                + " avcFreescale=" + codecState(freescaleAvc, complete)
                + " hevcDecoder=" + codecState(hevc, complete)
                + " aacDecoder=" + codecState(aac, complete)
                + " outputSampleRate=" + numberOrUnknown(rate)
                + " outputFramesPerBuffer=" + numberOrUnknown(frames)
                + " pcmProbeRate=" + queryRate
                + " pcm51MinBuffer=" + pcm51 + " pcm71MinBuffer=" + pcm71
                + " physicalChannels=unknown";
        String display = "H.264 " + codecDisplay(avc, complete)
                + "，Freescale 硬解 " + codecDisplay(freescaleAvc, complete)
                + "\nHEVC " + codecDisplay(hevc, complete)
                + "，AAC " + codecDisplay(aac, complete)
                + "\n" + queryRate + " Hz 多声道缓冲查询  5.1 " + audioDisplay(pcm51)
                + "，7.1 " + audioDisplay(pcm71)
                + "\n解码器登记和缓冲查询只反映系统报告。实际解码与独立扬声器声道需播放验证。";
        return new Result(summary, display);
    }

    private static String minimumBufferStatus(int sampleRate, int channelMask) {
        try {
            int bytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
            if (bytes > 0) return "reported";
            return bytes == AudioTrack.ERROR_BAD_VALUE ? "rejected" : "unknown";
        } catch (RuntimeException | LinkageError error) {
            return "unknown";
        }
    }

    private static int readProperty(AudioManager audio, String property, int minimum, int maximum) {
        if (audio == null) return 0;
        try {
            int value = Integer.parseInt(audio.getProperty(property));
            return minimum <= value && value <= maximum ? value : 0;
        } catch (RuntimeException | LinkageError error) {
            return 0;
        }
    }

    private static String codecState(boolean found, boolean complete) {
        return found ? "present" : complete ? "absent" : "unknown";
    }

    private static String codecDisplay(boolean found, boolean complete) {
        return found ? "已登记" : complete ? "无登记" : "未确认";
    }

    private static String audioDisplay(String state) {
        if ("reported".equals(state)) return "系统返回缓冲大小";
        return "rejected".equals(state) ? "系统拒绝参数" : "未确认";
    }

    private static String numberOrUnknown(int value) {
        return value > 0 ? Integer.toString(value) : "unknown";
    }
}
