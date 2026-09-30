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
// УСТРАНЕНИЕ ДЕФЕКТОВ 173, 174, 175: Неблокирующий pushRt для исключения зависаний при логировании
#define LOGI(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    client::logging::NativeLogQueue::getInstance().pushRt(4, LOG_TAG, _buf); \
} while(0)

#define LOGE(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    client::logging::NativeLogQueue::getInstance().pushRt(6, LOG_TAG, _buf); \
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
    const uint64_t underruns = engine.getPlaybackUnderrunCount();
    const uint64_t drops = engine.getPlaybackDroppedFrames();
    const float noiseFloor = engine.getMicNoiseFloorRms();

    char infoBuf[256];
    snprintf(infoBuf, sizeof(infoBuf),
             "Qualcomm SD8 Gen2 - [In:%dHz/ID:%d -> Out:%dHz/ID:%d, Excl:%s, MMAP:%s, XRuns:%llu, Drops:%llu, Noise:%.4f]",
             inRate, inDevId, outRate, outDevId,
             isExclusive ? "yes" : "no",
             isMmap ? "yes" : "no",
             static_cast<unsigned long long>(underruns),
             static_cast<unsigned long long>(drops),
             noiseFloor);

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_restartCaptureStream(JNIEnv * /* env */, jobject /* this */) {
    LOGI("restartCaptureStream called");
    return static_cast<jboolean>(AAudioEngine::getInstance().restartCaptureStream());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_restartPlaybackStream(JNIEnv * /* env */, jobject /* this */) {
    LOGI("restartPlaybackStream called");
    return static_cast<jboolean>(AAudioEngine::getInstance().restartPlaybackStream());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getEngineState(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jint>(AAudioEngine::getInstance().getEngineState());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_pollAudioError(
    JNIEnv *env, jobject /* this */, jlongArray outData) {

    if (!outData || env->GetArrayLength(outData) < 4) return JNI_FALSE;

    client::audio::StreamErrorEvent event{};
    if (!AAudioEngine::getInstance().pollErrorEvent(event)) {
        return JNI_FALSE;
    }

    jlong data[4] = {
        static_cast<jlong>(event.direction),
        static_cast<jlong>(event.errorCode),
        static_cast<jlong>(event.faultType),
        static_cast<jlong>(event.timestampNs)
    };

    env->SetLongArrayRegion(outData, 0, 4, data);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_hasPendingError(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().hasPendingError());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getCaptureSequenceNumber(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getCaptureSequenceNumber());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getCaptureTimestampNs(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getCaptureTimestampNs());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPlaybackSequenceNumber(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPlaybackSequenceNumber());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPlaybackPresentationTimestampNs(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPlaybackPresentationTimestampNs());
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
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackBurst(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getActualPlaybackBurst();
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

// УСТРАНЕНИЕ ДЕФЕКТОВ 72, 73, 74: Использование критического массива исключает копирование GetByteArrayRegion
extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackByteArray(
    JNIEnv *env, jobject /* this */, jbyteArray byteArray, jint offset, jint length, jlong generation) {

    if (!byteArray || offset < 0 || length <= 0) return 0;
    if (generation <= 0) return 0;
    if ((length & 1) != 0) return 0;
    const jsize arrayLen = env->GetArrayLength(byteArray);
    if (arrayLen < 0) return 0;

    const jlong endOffset = static_cast<jlong>(offset) + static_cast<jlong>(length);
    if (endOffset > static_cast<jlong>(arrayLen)) {
        return 0;
    }

    const size_t frames = static_cast<size_t>(length) / sizeof(int16_t);

    jboolean isCopy = JNI_FALSE;
    void* rawPtr = env->GetPrimitiveArrayCritical(byteArray, &isCopy);
    if (!rawPtr) {
        return 0;
    }

    const auto* startPtr = reinterpret_cast<const int16_t*>(
        static_cast<const char*>(rawPtr) + offset
    );

    const size_t writtenFrames = AAudioEngine::getInstance().writePlaybackPcm(
        startPtr, frames, static_cast<uint64_t>(generation)
    );

    env->ReleasePrimitiveArrayCritical(byteArray, rawPtr, JNI_ABORT);

    return static_cast<jint>(writtenFrames * sizeof(int16_t));
}

// УСТРАНЕНИЕ ДЕФЕКТОВ 75 и 221: Zero-Copy передача через прямой буфер ByteBuffer
extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackDirect(
    JNIEnv *env, jobject /* this */, jobject byteBuffer, jint offsetBytes, jint lengthBytes, jlong generation) {

    if (!byteBuffer || offsetBytes < 0 || lengthBytes <= 0) return 0;
    if (generation <= 0) return 0;
    if ((lengthBytes & 1) != 0) return 0;
    if ((offsetBytes & 1) != 0) return 0;

    const jlong capacity = env->GetDirectBufferCapacity(byteBuffer);
    const jlong endOffset = static_cast<jlong>(offsetBytes) + static_cast<jlong>(lengthBytes);

    if (capacity < 0 || endOffset > capacity) {
        LOGE("writePlaybackDirect OOB: Cap=%lld, Req=%lld",
             static_cast<long long>(capacity), static_cast<long long>(endOffset));
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
        LOGE("flushPlayback rejected invalid generation=%lld", static_cast<long long>(generation));
        return;
    }

    AAudioEngine::getInstance().flushPlayback(static_cast<uint64_t>(generation));
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_triggerBargeInEarcon(JNIEnv * /* env */, jobject /* this */) {
    AAudioEngine::getInstance().triggerBargeInEarcon();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setPlaybackPaused(JNIEnv * /* env */, jobject /* this */, jboolean paused) {
    AAudioEngine::getInstance().setPlaybackPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isPlaybackPaused(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jboolean>(AAudioEngine::getInstance().isPlaybackPaused());
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setOutputEqProfile(JNIEnv * /* env */, jobject /* this */, jint profile) {
    AAudioEngine::getInstance().setOutputEqProfile(static_cast<int32_t>(profile));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_waitForCaptureFrames(
    JNIEnv * /* env */, jobject /* this */, jint frames, jint timeoutMs) {
    if (frames <= 0) return JNI_TRUE;
    return static_cast<jboolean>(AAudioEngine::getInstance().waitForCaptureFrames(
        static_cast<size_t>(frames), static_cast<int32_t>(timeoutMs)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getCaptureSessionId(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jint>(AAudioEngine::getInstance().getCaptureSessionId());
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setMediaPlaybackUsage(JNIEnv * /* env */, jobject /* this */, jboolean enabled) {
    AAudioEngine::getInstance().setMediaPlaybackUsage(enabled == JNI_TRUE);
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

// --- Диагностические и метрологические точки входа телеметрии E2E ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getCaptureDroppedFrames(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getCaptureDroppedFrames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPlaybackUnderrunFrames(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPlaybackUnderrunFrames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPlaybackUnderrunCount(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPlaybackUnderrunCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPlaybackDroppedFrames(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPlaybackDroppedFrames());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getStreamDisconnectCount(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jint>(AAudioEngine::getInstance().getStreamDisconnectCount());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getLastXRunCount(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jint>(AAudioEngine::getInstance().getLastXRunCount());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPendingPlaybackInputFrames(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPendingPlaybackInputFrames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPendingPlaybackOutputFrames(JNIEnv * /* env */, jobject /* this */) {
    return static_cast<jlong>(AAudioEngine::getInstance().getPendingPlaybackOutputFrames());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPendingPlaybackDurationMs(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getPendingPlaybackDurationMs();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_client_app_audio_NativeAudioBridge_getTotalEstimatedPlaybackLatencyMs(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getTotalEstimatedPlaybackLatencyMs();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_client_app_audio_NativeAudioBridge_getMicNoiseFloorRms(JNIEnv * /* env */, jobject /* this */) {
    return AAudioEngine::getInstance().getMicNoiseFloorRms();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_setPlaybackActiveState(JNIEnv * /* env */, jobject /* this */, jboolean isActive) {
    AAudioEngine::getInstance().setPlaybackActiveState(isActive == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_getAudioPipelineDiagnostics(
    JNIEnv *env, jobject /* this */, jlongArray outArray) {

    if (!outArray || env->GetArrayLength(outArray) < 11) return JNI_FALSE;

    client::audio::AudioPipelineDiagnostics diag{};
    AAudioEngine::getInstance().getAudioDiagnostics(diag);

    jlong data[11] = {
        static_cast<jlong>(diag.totalHardwareCapturedFrames),
        static_cast<jlong>(diag.totalDspProcessedFrames),
        static_cast<jlong>(diag.captureDroppedFrames),
        static_cast<jlong>(diag.totalHardwarePlaybackFrames),
        static_cast<jlong>(diag.playbackUnderrunFrames),
        static_cast<jlong>(diag.playbackUnderrunCount),
        static_cast<jlong>(diag.playbackDroppedFrames),
        static_cast<jlong>(diag.streamDisconnectCount),
        static_cast<jlong>(diag.captureErrorCount),
        static_cast<jlong>(diag.playbackErrorCount),
        static_cast<jlong>(diag.lastXRunCount)
    };

    env->SetLongArrayRegion(outArray, 0, 11, data);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_getErrorHistogram(
    JNIEnv *env, jobject /* this */, jintArray outArray) {

    if (!outArray) return JNI_FALSE;
    const jsize len = env->GetArrayLength(outArray);
    if (len < static_cast<jsize>(client::audio::ERROR_HISTOGRAM_BUCKETS)) return JNI_FALSE;

    uint32_t buf[client::audio::ERROR_HISTOGRAM_BUCKETS] = {0};
    AAudioEngine::getInstance().getErrorHistogram(buf, client::audio::ERROR_HISTOGRAM_BUCKETS);

    jint jbuf[client::audio::ERROR_HISTOGRAM_BUCKETS];
    for (size_t i = 0; i < client::audio::ERROR_HISTOGRAM_BUCKETS; ++i) {
        jbuf[i] = static_cast<jint>(buf[i]);
    }

    env->SetIntArrayRegion(outArray, 0, client::audio::ERROR_HISTOGRAM_BUCKETS, jbuf);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_getHardwareActiveConfig(
    JNIEnv *env, jobject /* this */, jintArray outArray) {

    if (!outArray || env->GetArrayLength(outArray) < 7) return JNI_FALSE;

    auto& engine = AAudioEngine::getInstance();
    jint data[7] = {
        engine.getActualPlaybackSampleRate(),
        engine.getActualPlaybackChannels(),
        engine.getActualPlaybackFormat(),
        engine.getActualPlaybackBurst(),
        engine.getActiveOutputDeviceId(),
        engine.isMmapActive() ? 1 : 0,
        engine.isExclusiveSharingActive() ? 1 : 0
    };

    env->SetIntArrayRegion(outArray, 0, 7, data);
    return JNI_TRUE;
}