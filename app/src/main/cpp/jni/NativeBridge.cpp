#include <jni.h>
#include <cstdio>
#include <string>
#include <vector>
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include "audio/AAudioEngine.h"
#include "audio/NativeLogQueue.h"

#define LOG_TAG "NativeCoreBridge"

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
    auto& engine = AAudioEngine::getInstance();
    const bool isMmap = engine.isMmapActive();
    const bool isExclusive = engine.isExclusiveSharingActive();
    const int32_t inRate = engine.getActualCaptureSampleRate();
    const int32_t outRate = engine.getActualPlaybackSampleRate();
    const int32_t inDevId = engine.getActiveInputDeviceId();
    const int32_t outDevId = engine.getActiveOutputDeviceId();

    char infoBuf[256];
    snprintf(infoBuf, sizeof(infoBuf),
             "Qualcomm SD8 Gen2 - [In:%dHz/ID:%d -> Out:%dHz/ID:%d, Exclusive:%s, MMAP:%s]",
             inRate, inDevId, outRate, outDevId,
             isExclusive ? "yes" : "no",
             isMmap ? "yes" : "no");

    return env->NewStringUTF(infoBuf);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_initAudioRoute(
    JNIEnv * /* env */, jobject /* this */,
    jboolean isBluetooth, jint sampleRate, jint inputDeviceId, jint outputDeviceId) {

    LOGI("initAudioRoute called: isBluetooth=%d, sampleRate=%d, inDevId=%d, outDevId=%d",
         (int)isBluetooth, (int)sampleRate, (int)inputDeviceId, (int)outputDeviceId);

    return static_cast<jboolean>(AAudioEngine::getInstance().init(
        isBluetooth, sampleRate, inputDeviceId, outputDeviceId
    ));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("startAudio called");
    return static_cast<jboolean>(AAudioEngine::getInstance().start());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startPlaybackAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("startPlaybackAudio called");
    return static_cast<jboolean>(AAudioEngine::getInstance().startPlayback());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startCaptureAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("startCaptureAudio called");
    return static_cast<jboolean>(AAudioEngine::getInstance().startCapture());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_activateCaptureDspAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("activateCaptureDspAudio called");
    return static_cast<jboolean>(AAudioEngine::getInstance().activateCaptureDsp());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_commitCaptureAdmission(JNIEnv * /* env */, jobject /* this */) {
    LOGI("commitCaptureAdmission called");
    return static_cast<jboolean>(AAudioEngine::getInstance().commitCaptureAdmission());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackSampleRate(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActualPlaybackSampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackChannels(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActualPlaybackChannels();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackFormat(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActualPlaybackFormat();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualCaptureSampleRate(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActualCaptureSampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualCaptureChannels(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActualCaptureChannels();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActiveInputDeviceId(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActiveInputDeviceId();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActiveOutputDeviceId(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActiveOutputDeviceId();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPendingPlaybackFrames(
    JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(
        AAudioEngine::getInstance().getPendingPlaybackFrames()
    );
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_client_app_audio_NativeAudioBridge_getOutRms(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getOutRms();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_client_app_audio_NativeAudioBridge_getMicRms(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getMicRms();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isMmapActive(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().isMmapActive());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isExclusiveSharingActive(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().isExclusiveSharingActive());
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopCaptureAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("stopCaptureAudio called");
    AAudioEngine::getInstance().stopCapture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopAudio(JNIEnv * /* env */, jobject /* this */) {
    LOGI("stopAudio called");
    AAudioEngine::getInstance().stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isAudioDisconnected(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().isDisconnected());
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
    JNIEnv *env, jobject /* this */, jbyteArray byteArray, jint offset, jint length, jlong generation) {

    if (!byteArray || offset < 0 || length <= 0) return 0;
    if (generation <= 0) return 0;
    if ((length & 1) != 0) return 0;
    const jsize arrayLen = env->GetArrayLength(byteArray);
    if (arrayLen < 0) return 0;

    const jlong endOffset =
        static_cast<jlong>(offset) +
        static_cast<jlong>(length);
    if (endOffset > static_cast<jlong>(arrayLen)) {
        return 0;
    }

    const size_t frames =
        static_cast<size_t>(length) / sizeof(int16_t);

    constexpr size_t MAX_PERSISTENT_FRAMES = 8192;
    thread_local std::vector<int16_t> playbackJniBuffer;
    if (playbackJniBuffer.size() < frames) {
        playbackJniBuffer.resize(frames);
    }
    if (playbackJniBuffer.capacity() > MAX_PERSISTENT_FRAMES && frames <= MAX_PERSISTENT_FRAMES) {
        playbackJniBuffer.shrink_to_fit();
    }

    env->GetByteArrayRegion(
        byteArray, offset, length, reinterpret_cast<jbyte*>(playbackJniBuffer.data())
    );

    const size_t writtenFrames = AAudioEngine::getInstance().writePlaybackPcm(
        playbackJniBuffer.data(), frames, static_cast<uint64_t>(generation)
    );
    return static_cast<jint>(writtenFrames * sizeof(int16_t));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackDirect(
    JNIEnv *env, jobject /* this */, jobject byteBuffer, jint offsetBytes, jint lengthBytes, jlong generation) {

    if (!byteBuffer || offsetBytes < 0 || lengthBytes <= 0) return 0;
    if (generation <= 0) return 0;
    if ((lengthBytes & 1) != 0) return 0;
    if ((offsetBytes & 1) != 0) return 0;

    const jlong capacity =
        env->GetDirectBufferCapacity(byteBuffer);

    const jlong endOffset =
        static_cast<jlong>(offsetBytes) +
        static_cast<jlong>(lengthBytes);

    if (capacity < 0 || endOffset > capacity) {
        LOGE(
            "writePlaybackDirect OOB: Cap=%lld, Req=%lld",
            static_cast<long long>(capacity),
            static_cast<long long>(endOffset)
        );
        return 0;
    }

    auto *bufferPtr = static_cast<int16_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (!bufferPtr) return 0;

    auto *startPtr = reinterpret_cast<int16_t*>(reinterpret_cast<char*>(bufferPtr) + offsetBytes);
    const size_t frames = static_cast<size_t>(lengthBytes) / sizeof(int16_t);

    const size_t writtenFrames = AAudioEngine::getInstance().writePlaybackPcm(
        startPtr, frames, static_cast<uint64_t>(generation)
    );
    return static_cast<jint>(writtenFrames * sizeof(int16_t));
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

    const size_t maxFrames = static_cast<size_t>(capacityBytes) / sizeof(int16_t);
    const size_t readFrames = AAudioEngine::getInstance().readCapturePcm(bufferPtr, maxFrames);
    return static_cast<jint>(readFrames * sizeof(int16_t));
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_flushPlayback(
    JNIEnv * /* env */, jobject /* this */, jlong generation) {

    if (generation <= 0) {
        LOGE(
            "flushPlayback rejected invalid generation=%lld",
            static_cast<long long>(generation)
        );
        return;
    }

    AAudioEngine::getInstance().flushPlayback(
        static_cast<uint64_t>(generation)
    );
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
    const jsize len = env->GetArrayLength(outArray);
    if (len < 7) return;

    client::dsp::SpectrumSnapshot snapshot;
    AAudioEngine::getInstance().getSpectrumData(snapshot);

    float data[7];
    for (int i = 0; i < 5; ++i) data[i] = snapshot.bands[i];
    data[5] = snapshot.micRms;
    data[6] = snapshot.outRms;

    env->SetFloatArrayRegion(outArray, 0, 7, data);
}

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

    const jsize totalElements = static_cast<jsize>(drained.size() * 4);
    jobjectArray resultArray = env->NewObjectArray(totalElements, stringClass, nullptr);
    if (!resultArray) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }

    for (size_t i = 0; i < drained.size(); ++i) {
        jstring jLevel = env->NewStringUTF(std::to_string(drained[i].level).c_str());
        jstring jTag = env->NewStringUTF(drained[i].tag);
        jstring jMsg = env->NewStringUTF(drained[i].message);
        jstring jTime = env->NewStringUTF(std::to_string(drained[i].timestampNs).c_str());

        const jsize baseIdx = static_cast<jsize>(i * 4);
        env->SetObjectArrayElement(resultArray, baseIdx + 0, jLevel);
        env->SetObjectArrayElement(resultArray, baseIdx + 1, jTag);
        env->SetObjectArrayElement(resultArray, baseIdx + 2, jMsg);
        env->SetObjectArrayElement(resultArray, baseIdx + 3, jTime);

        env->DeleteLocalRef(jLevel);
        env->DeleteLocalRef(jTag);
        env->DeleteLocalRef(jMsg);
        env->DeleteLocalRef(jTime);
    }

    env->DeleteLocalRef(stringClass);
    return resultArray;
}

Файл 4: app/src/main/java/com/client/app/audio/NativeAudioBridge.kt

package com.client.app.audio

import javax.inject.Inject
import javax.inject.Singleton
import java.nio.ByteBuffer

@Singleton
class NativeAudioBridge @Inject constructor() {
    companion object {
        init {
            System.loadLibrary("client_core")
        }
    }

    external fun getHardwareCoreInfo(): String

    external fun initAudioRoute(
        isBluetooth: Boolean,
        sampleRate: Int,
        inputDeviceId: Int = 0,
        outputDeviceId: Int = 0
    ): Boolean

    external fun startAudio(): Boolean
    external fun startPlaybackAudio(): Boolean
    external fun startCaptureAudio(): Boolean
    external fun activateCaptureDspAudio(): Boolean
    external fun commitCaptureAdmission(): Boolean
    external fun stopCaptureAudio()
    external fun stopAudio()
    external fun isAudioDisconnected(): Boolean
    external fun getActualPlaybackSampleRate(): Int
    external fun getActualPlaybackChannels(): Int
    external fun getActualPlaybackFormat(): Int
    external fun getActualCaptureSampleRate(): Int
    external fun getActualCaptureChannels(): Int
    external fun getActiveInputDeviceId(): Int
    external fun getActiveOutputDeviceId(): Int
    external fun getPendingPlaybackFrames(): Long

    external fun getOutRms(): Float
    external fun getMicRms(): Float

    external fun isMmapActive(): Boolean
    external fun isExclusiveSharingActive(): Boolean
    external fun setVolume(volume: Float)
    external fun setMicGain(gain: Float)

    external fun writePlaybackByteArray(
        pcmArray: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
        generation: Long
    ): Int

    external fun writePlaybackDirect(
        byteBuffer: ByteBuffer,
        offsetBytes: Int,
        lengthBytes: Int,
        generation: Long
    ): Int

    external fun readCaptureDirect(byteBuffer: ByteBuffer, capacityBytes: Int): Int

    external fun flushPlayback(generation: Long)
    external fun triggerBargeInEarcon()
    external fun tuneNativeSocket(fd: Int)
    external fun getSpectrumData(outArray: FloatArray)
    external fun drainNativeLogs(): Array<String>?
}
