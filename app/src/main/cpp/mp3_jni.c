#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include "mp3core.h"

#define JNI_FN(name) Java_com_lethargicrunner_mediacapture_Mp3Encoder_##name

JNIEXPORT jlong JNICALL JNI_FN(nativeCreate)(
    JNIEnv *env, jobject thiz, jint sampleRate, jint channels, jint bitrateKbps) {
    (void) env;
    (void) thiz;
    return (jlong) (intptr_t) mp3enc_create(sampleRate, channels, bitrateKbps);
}

JNIEXPORT jint JNICALL JNI_FN(nativeEncode)(
    JNIEnv *env, jobject thiz, jlong handle,
    jshortArray pcm, jint frames, jbyteArray out) {
    (void) thiz;
    Mp3Enc *enc = (Mp3Enc *) (intptr_t) handle;
    if (enc == NULL) return -3;

    jshort *pcmPtr = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (pcmPtr == NULL) return -2;
    jbyte *outPtr = (*env)->GetByteArrayElements(env, out, NULL);
    if (outPtr == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
        return -2;
    }
    jsize outSize = (*env)->GetArrayLength(env, out);

    int written = mp3enc_encode(
        enc, (const short *) pcmPtr, frames, (unsigned char *) outPtr, outSize);

    (*env)->ReleaseShortArrayElements(env, pcm, pcmPtr, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outPtr, written > 0 ? 0 : JNI_ABORT);
    return written;
}

JNIEXPORT jint JNICALL JNI_FN(nativeFlush)(
    JNIEnv *env, jobject thiz, jlong handle, jbyteArray out) {
    (void) thiz;
    Mp3Enc *enc = (Mp3Enc *) (intptr_t) handle;
    if (enc == NULL) return -3;

    jbyte *outPtr = (*env)->GetByteArrayElements(env, out, NULL);
    if (outPtr == NULL) return -2;
    jsize outSize = (*env)->GetArrayLength(env, out);

    int written = mp3enc_flush(enc, (unsigned char *) outPtr, outSize);

    (*env)->ReleaseByteArrayElements(env, out, outPtr, written > 0 ? 0 : JNI_ABORT);
    return written;
}

JNIEXPORT void JNICALL JNI_FN(nativeDestroy)(
    JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    mp3enc_destroy((Mp3Enc *) (intptr_t) handle);
}
