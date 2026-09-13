// >>> FILE: app/src/main/cpp/jni/NativeBridge.cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include "audio/AAudioEngine.h"
#include "audio/NativeLogQueue.h"

#define LOG_TAG "NativeCoreBridge"

// Перехват логов: вывод в logcat + фиксация в Lock-Free кольцевой очереди логов
#undef LOGI
#undef LOGE
#define LOGI(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(4, LOG_TAG, _buf); \
} while(0)

#define LOGE(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(6, LOG_TAG, _buf); \
} while(0)

#ifndef TCP_NOTSENT_LOWAT
#define TCP_NOTSENT_LOWAT 25
#endif

using namespace client::audio;

extern "C" JNIEXPORT jstring JNICALL
Java_com_client_app_audio_NativeAudioBridge_getHardwareCoreInfo(JNIEnv *env, jobject /* this */) {
    bool isMmap = AAudioEngine::getInstance().isMmapActive();
    std::string info = isMmap
        ? "Qualcomm SD8 Gen2 - AAudio MMAP Exclusive [4.2ms Direct]"
        : "Qualcomm SD8 Gen2 - AAudio Low-Latency Shared [BT Active]";
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_initAudioRoute(
    JNIEnv * /* env */, jobject /* this */, jboolean isBluetooth, jint sampleRate) {
    LOGI("initAudioRoute called: isBluetooth=%d, sampleRate=%d", (int)isBluetooth, (int)sampleRate);
    return static_cast<jboolean>(AAudioEngine::getInstance().init(isBluetooth, sampleRate));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("startAudio called");
    return static_cast<jboolean>(AAudioEngine::getInstance().start());
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("stopAudio called");
    AAudioEngine::getInstance().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setVolume(
    JNIEnv * /* env */, jobject /* this */, jfloat volume) {
    AAudioEngine::getInstance().setVolume(static_cast<float>(volume));
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setMicGain(
    JNIEnv * /* env */, jobject /* this */, jfloat gain) {
    AAudioEngine::getInstance().setMicGain(static_cast<float>(gain));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackByteArray(
    JNIEnv *env, jobject /* this */, jbyteArray byteArray, jint offset, jint length) {

    if (!byteArray || offset < 0 || length <= 0) return 0;
    jsize arrayLen = env->GetArrayLength(byteArray);
    if (offset + length > arrayLen) return 0;

    size_t frames = length / sizeof(int16_t);

    thread_local std::vector<int16_t> playbackJniBuffer;
    if (playbackJniBuffer.size() < frames) {
        playbackJniBuffer.resize(frames);
    }

    env->GetByteArrayRegion(
        byteArray, offset, length, reinterpret_cast<jbyte*>(playbackJniBuffer.data())
    );

    size_t written = AAudioEngine::getInstance().writePlaybackPcm(playbackJniBuffer.data(), frames);
    return static_cast<jint>(written * sizeof(int16_t));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackDirect(
    JNIEnv *env, jobject /* this */, jobject byteBuffer, jint offsetBytes, jint lengthBytes) {

    if (!byteBuffer || offsetBytes < 0 || lengthBytes <= 0) return 0;

    jlong capacity = env->GetDirectBufferCapacity(byteBuffer);
    if (capacity < 0 || (offsetBytes + lengthBytes) > capacity) {
        LOGE("writePlaybackDirect OOB: Cap=%lld, Req=%d", (long long)capacity, offsetBytes + lengthBytes);
        return 0;
    }

    auto *bufferPtr = static_cast<int16_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!bufferPtr) return 0;

    auto *startPtr = reinterpret_cast<int16_t*>(reinterpret_cast<char*>(bufferPtr) + offsetBytes);
    size_t frames = lengthBytes / sizeof(int16_t);

    return static_cast<jint>(AAudioEngine::getInstance().writePlaybackPcm(startPtr, frames) * sizeof(int16_t));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_readCaptureDirect(
    JNIEnv *env, jobject /* this */, jobject byteBuffer, jint capacityBytes) {

    if (!byteBuffer || capacityBytes <= 0) return 0;

    jlong realCap = env->GetDirectBufferCapacity(byteBuffer);
    if (realCap < capacityBytes) {
        capacityBytes = static_cast<jint>(realCap);
    }

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
    int res1 = setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(flag));

    int lowat = 16384;
    int res2 = setsockopt(fd, IPPROTO_TCP, TCP_NOTSENT_LOWAT, &lowat, sizeof(lowat));

    LOGI("tuneNativeSocket applied for fd=%d: TCP_NODELAY res=%d, TCP_NOTSENT_LOWAT res=%d", fd, res1, res2);
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_getSpectrumData(JNIEnv *env, jobject /* this */, jfloatArray outArray) {
    if (!outArray) return;
    jsize len = env->GetArrayLength(outArray);
    if (len < 7) return;

    client::dsp::SpectrumSnapshot snapshot;
    AAudioEngine::getInstance().getSpectrumData(snapshot);

    float data[7];
    for (int i = 0; i < 5; ++i) data[i] = snapshot.bands[i];
    data[5] = snapshot.micRms;
    data[6] = snapshot.outRms;

    env->SetFloatArrayRegion(outArray, 0, 7, data);
}

/**
 * E-51: Вычитка логов из C++ Lock-Free очереди NativeLogQueue в Java.
 * Защита от переполнения JNI Local References Table (лимит 512):
 * строгий вызов DeleteLocalRef на каждой итерации и ограничение пачки до 64 записей.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_client_app_audio_NativeAudioBridge_drainNativeLogs(JNIEnv *env, jobject /* this */) {
    std::vector<client::logging::NativeLogItem> drained;
    drained.reserve(64);

    client::logging::NativeLogItem item;
    while (drained.size() < 64 && client::logging::NativeLogQueue::getInstance().pop(item)) {
        drained.push_back(item);
    }

    if (drained.empty()) {
        return nullptr;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;

    const jsize totalElements = static_cast<jsize>(drained.size() * 3);
    jobjectArray resultArray = env->NewObjectArray(totalElements, stringClass, nullptr);
    if (!resultArray) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }

    for (size_t i = 0; i < drained.size(); ++i) {
        jstring jLevel = env->NewStringUTF(std::to_string(drained[i].level).c_str());
        jstring jTag = env->NewStringUTF(drained[i].tag);
        jstring jMsg = env->NewStringUTF(drained[i].message);

        jsize baseIdx = static_cast<jsize>(i * 3);
        env->SetObjectArrayElement(resultArray, baseIdx + 0, jLevel);
        env->SetObjectArrayElement(resultArray, baseIdx + 1, jTag);
        env->SetObjectArrayElement(resultArray, baseIdx + 2, jMsg);

        // Освобождаем локальные ссылки для предотвращения JNI Local Reference Table Overflow
        env->DeleteLocalRef(jLevel);
        env->DeleteLocalRef(jTag);
        env->DeleteLocalRef(jMsg);
    }

    env->DeleteLocalRef(stringClass);
    return resultArray;
}