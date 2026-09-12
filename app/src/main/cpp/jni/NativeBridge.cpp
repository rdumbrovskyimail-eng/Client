// >>> FILE: app/src/main/cpp/jni/NativeBridge.cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include "audio/AAudioEngine.h"

#define LOG_TAG "NativeCoreBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

using namespace client::audio;

extern "C" JNIEXPORT jstring JNICALL
Java_com_client_app_audio_NativeAudioBridge_getHardwareCoreInfo(JNIEnv *env, jobject /* this */) {
    std::string info = "Qualcomm Snapdragon 8 Gen 2 (kalama) Native Engine [C++20/ARMv9] - AAudio Pipeline Active";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_probeMmapSupport(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().init());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startAudio(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().start());
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopAudio(JNIEnv * /* env */, jobject /* this */) {
    AAudioEngine::getInstance().stop();
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
Java_com_client_app_audio_NativeAudioBridge_setVolume(JNIEnv * /* env */, jobject /* this */, jfloat volume) {
    AAudioEngine::getInstance().setVolume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setMicGain(JNIEnv * /* env */, jobject /* this */, jfloat gain) {
    AAudioEngine::getInstance().setMicGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_getSpectrumData(JNIEnv *env, jobject /* this */, jfloatArray outArray) {
    float bands[7] = {0.0f};
    AAudioEngine::getInstance().getSpectrumUniforms(bands);
    bands[5] = AAudioEngine::getInstance().getMicRms();
    bands[6] = AAudioEngine::getInstance().getOutRms();

    env->SetFloatArrayRegion(outArray, 0, 7, bands);
}