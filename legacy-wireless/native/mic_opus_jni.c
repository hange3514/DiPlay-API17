#include <jni.h>
#include <stdint.h>

#include "opus.h"

enum {
    MIC_PCM_RATE = 48000,
    MIC_FRAME_SAMPLES = 960,
    MIC_FRAME_BYTES = MIC_FRAME_SAMPLES * 2,
    MIC_MAX_PACKET_BYTES = 1275
};

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "Native microphone input requires a little-endian target"
#endif

static OpusEncoder *from_handle(jlong handle) {
    return (OpusEncoder *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL Java_local_headunit_diplay_NativeUplinkOpus_nativeCreate(
        JNIEnv *env, jclass type, jint bitrate, jint complexity) {
    (void)env;
    (void)type;
    if (bitrate < 500 || bitrate > 512000 || complexity < 0 || complexity > 10)
        return 0;

    int status;
    OpusEncoder *encoder = opus_encoder_create(MIC_PCM_RATE, 1, OPUS_APPLICATION_VOIP, &status);
    if (!encoder) return 0;
    if (status != OPUS_OK
            || opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK
            || opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity)) != OPUS_OK
            || opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE)) != OPUS_OK
            || opus_encoder_ctl(encoder, OPUS_SET_DTX(0)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUplinkOpus_nativeEncode(
        JNIEnv *env, jclass type, jlong handle, jbyteArray pcm, jbyteArray packet) {
    (void)type;
    OpusEncoder *encoder = from_handle(handle);
    if (!encoder || !pcm || !packet || (*env)->GetArrayLength(env, pcm) != MIC_FRAME_BYTES
            || (*env)->GetArrayLength(env, packet) < MIC_MAX_PACKET_BYTES)
        return OPUS_BAD_ARG;

    opus_int16 samples[MIC_FRAME_SAMPLES];
    unsigned char output[MIC_MAX_PACKET_BYTES];
    (*env)->GetByteArrayRegion(env, pcm, 0, MIC_FRAME_BYTES, (jbyte *)samples);
    if ((*env)->ExceptionCheck(env)) return OPUS_INTERNAL_ERROR;
    int encoded = opus_encode(encoder, samples, MIC_FRAME_SAMPLES, output, sizeof(output));
    if (encoded > 0) (*env)->SetByteArrayRegion(env, packet, 0, encoded, (jbyte *)output);
    return encoded;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUplinkOpus_nativeSetComplexity(
        JNIEnv *env, jclass type, jlong handle, jint complexity) {
    (void)env;
    (void)type;
    return handle ? opus_encoder_ctl(from_handle(handle), OPUS_SET_COMPLEXITY(complexity)) : OPUS_BAD_ARG;
}

JNIEXPORT void JNICALL Java_local_headunit_diplay_NativeUplinkOpus_nativeDestroy(
        JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    if (handle) opus_encoder_destroy(from_handle(handle));
}
