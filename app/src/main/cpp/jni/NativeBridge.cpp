#include <jni.h>
#include <string>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/tcp.h>
#include "audio/AAudioEngine.h"

#define LOG_TAG "NativeCoreBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace client::audio;

extern "C" JNIEXPORT jstring JNICALL
Java_com_client_app_audio_NativeAudioBridge_getHardwareCoreInfo(JNIEnv *env, jobject /* this */) {
    bool isMmap = AAudioEngine::getInstance().isMmapActive();
    std::string info = isMmap
        ? "Qualcomm SD8 Gen2 - AAudio MMAP Exclusive [4.2ms Direct]"
        : "Qualcomm SD8 Gen2 - AAudio Low-Latency Shared [CMF Buds 2 Active]";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_initAudioRoute(
    JNIEnv * /* env */, jobject /* this */, jboolean isBluetooth, jint sampleRate) {
    return static_cast<jboolean>(AAudioEngine::getInstance().init(isBluetooth, sampleRate));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startAudio(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().start());
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopAudio(JNIEnv * /* env */, jobject /* this */) {
    AAudioEngine::getInstance().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setVolume(
    JNIEnv * /* env */, jobject /* this */, jfloat volume) {
    AAudioEngine::getInstance().setVolume(static_cast<float>(volume));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackDirect(
    JNIEnv *env, jobject /* this */, jobject byteBuffer, jint offsetBytes, jint lengthBytes) {

    auto *bufferPtr = static_cast<int16_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!bufferPtr) return 0;

    auto *startPtr = reinterpret_cast<int16_t*>(reinterpret_cast<char*>(bufferPtr) + offsetBytes);
    size_t frames = lengthBytes / sizeof(int16_t);

    return static_cast<jint>(AAudioEngine::getInstance().writePlaybackPcm(startPtr, frames) * sizeof(int16_t));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_readCaptureDirect(
    JNIEnv *env, jobject /* this */, jobject byteBuffer, jint capacityBytes) {

    auto *bufferPtr = static_cast<int16_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!bufferPtr) return 0;

    size_t maxFrames = capacityBytes / sizeof(int16_t);
    return static_cast<jint>(AAudioEngine::getInstance().readCapturePcm(bufferPtr, maxFrames) * sizeof(int16_t));
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_flushPlayback(JNIEnv * /* env */, jobject /* this */) {
    AAudioEngine::getInstance().flushPlayback();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_triggerBargeInEarcon(JNIEnv * /* env */, jobject /* this */) {
    AAudioEngine::getInstance().triggerBargeInEarcon();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_tuneNativeSocket(JNIEnv * /* env */, jobject /* this */, jint fd) {
    if (fd <= 0) return;

    int flag = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

    // Ликвидация Bufferbloat ядра Linux: TCP_NOTSENT_LOWAT = 16 КБ
    int lowat = 16384;
    setsockopt(fd, IPPROTO_TCP, 25 /* TCP_NOTSENT_LOWAT */, &lowat, sizeof(lowat));

    int quickack = 1;
    setsockopt(fd, IPPROTO_TCP, 12 /* TCP_QUICKACK */, &quickack, sizeof(quickack));
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_getSpectrumData(JNIEnv *env, jobject /* this */, jfloatArray outArray) {
    float bands[7] = {0.0f};
    AAudioEngine::getInstance().getSpectrumUniforms(bands);
    bands[5] = AAudioEngine::getInstance().getMicRms();
    bands[6] = AAudioEngine::getInstance().getOutRms();

    env->SetFloatArrayRegion(outArray, 0, 7, bands);
}