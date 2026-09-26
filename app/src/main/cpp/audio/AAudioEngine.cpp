#include "AAudioEngine.h"
#include "NativeLogQueue.h"
#include "dsp/NeonDspUtils.h"
#include <dlfcn.h>

#include <android/log.h>
#include <pthread.h>

#include <cstdio>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <chrono>
#include <thread>
#include <exception>

#define LOG_TAG "NativeAudioEngine"

#undef LOGI
#undef LOGW
#undef LOGE
#define LOGI(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(4, LOG_TAG, _buf); \
} while (0)

#define LOGW(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(5, LOG_TAG, _buf); \
} while (0)

#define LOGE(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(6, LOG_TAG, _buf); \
} while (0)

namespace client::audio {

void Biquad::reset() {
    w1 = 0.0f;
    w2 = 0.0f;
}

void Biquad::makeLowShelf(float fc, float gainDb, float fs) {
    const float A = std::pow(10.0f, gainDb / 40.0f);
    const float omega = 2.0f * 3.14159265f * fc / fs;
    const float sn = std::sin(omega);
    const float cs = std::cos(omega);
    const float alpha = sn / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 0.9f - 1.0f) + 2.0f);
    const float beta = 2.0f * std::sqrt(A) * alpha;
    const float a0 = (A + 1.0f) + (A - 1.0f) * cs + beta;
    b0 = (A * ((A + 1.0f) - (A - 1.0f) * cs + beta)) / a0;
    b1 = (2.0f * A * ((A - 1.0f) - (A + 1.0f) * cs)) / a0;
    b2 = (A * ((A + 1.0f) - (A - 1.0f) * cs - beta)) / a0;
    a1 = (-2.0f * ((A - 1.0f) + (A + 1.0f) * cs)) / a0;
    a2 = ((A + 1.0f) - (A - 1.0f) * cs - beta) / a0;
}

void Biquad::makeHighShelf(float fc, float gainDb, float fs) {
    const float A = std::pow(10.0f, gainDb / 40.0f);
    const float omega = 2.0f * 3.14159265f * fc / fs;
    const float sn = std::sin(omega);
    const float cs = std::cos(omega);
    const float alpha = sn / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 0.9f - 1.0f) + 2.0f);
    const float beta = 2.0f * std::sqrt(A) * alpha;
    const float a0 = (A + 1.0f) - (A - 1.0f) * cs + beta;
    b0 = (A * ((A + 1.0f) + (A - 1.0f) * cs + beta)) / a0;
    b1 = (-2.0f * A * ((A - 1.0f) + (A + 1.0f) * cs)) / a0;
    b2 = (A * ((A + 1.0f) - (A - 1.0f) * cs - beta)) / a0;
    a1 = (2.0f * ((A - 1.0f) + (A + 1.0f) * cs)) / a0;
    a2 = ((A + 1.0f) - (A - 1.0f) * cs - beta) / a0;
}

AnalogVoiceEnhancer::AnalogVoiceEnhancer() {
    reset(48000);
}

void AnalogVoiceEnhancer::reset(int32_t sampleRate) {
    currentRate_ = sampleRate > 0 ? sampleRate : 48000;
    const float fs = static_cast<float>(currentRate_);
    lowShelf_.reset();
    highShelf_.reset();
    lowShelf_.makeLowShelf(180.0f, 1.8f, fs);
    highShelf_.makeHighShelf(std::min(4500.0f, fs * 0.44f), 1.2f, fs);
}

void AnalogVoiceEnhancer::process(int16_t* samples, size_t numFrames, int32_t sampleRate) {
    if (samples == nullptr || numFrames == 0) return;
    if (sampleRate > 0 && sampleRate != currentRate_) reset(sampleRate);

    for (size_t i = 0; i < numFrames; ++i) {
        const float inSample = static_cast<float>(samples[i]);
        const float lowPass = lowShelf_.process(inSample);
        const float enhanced = highShelf_.process(lowPass);
        samples[i] = static_cast<int16_t>(std::clamp<int32_t>(
            static_cast<int32_t>(std::round(enhanced)), -32768, 32767
        ));
    }
}

AAudioEngine& AAudioEngine::getInstance() {
    static AAudioEngine instance;
    return instance;
}

AAudioEngine::AAudioEngine()
    : fftProcessor_(std::make_unique<dsp::FastFft>()),
      playbackDspInputScratch_(PLAYBACK_DSP_INPUT_CHUNK_FRAMES, 0),
      playbackDspOutputScratch_(PLAYBACK_DSP_MAX_OUTPUT_FRAMES, 0),
      earconScratch_(EARCON_SCRATCH_MAX_FRAMES, 0),
      captureRawScratchBuffer_(CAPTURE_RAW_SCRATCH_FRAMES, 0),
      captureDecimateBuffer_(CAPTURE_DECIMATE_CAPACITY, 0),
      captureInputScratchBuffer_(CAPTURE_DECIMATE_CAPACITY, 0) {
    for (auto& bucket : errorHistogram_) {
        bucket.store(0, std::memory_order_relaxed);
    }
}

AAudioEngine::~AAudioEngine() {
    stop();
}

void AAudioEngine::joinPlaybackDspThreadLocked() {
    if (playbackDspThread_.joinable()) {
        playbackDspThread_.join();
    }
}

void AAudioEngine::joinCaptureDspThreadLocked() {
    if (captureDspThread_.joinable()) {
        captureDspThread_.join();
    }
}

void AAudioEngine::joinFftTapThreadLocked() {
    if (fftTapThread_.joinable()) {
        fftTapThread_.join();
    }
}

bool AAudioEngine::init(
    bool isBluetoothMode,
    int32_t targetPlaybackSampleRate,
    int32_t inputDeviceId,
    int32_t outputDeviceId) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    return initLocked(isBluetoothMode, targetPlaybackSampleRate, inputDeviceId, outputDeviceId);
}

void AAudioEngine::closeCaptureStreamLocked() {
    activeCaptureStream_.store(nullptr, std::memory_order_release);
    if (captureStream_ != nullptr) {
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
    }
}

void AAudioEngine::closePlaybackStreamLocked() {
    activePlaybackStream_.store(nullptr, std::memory_order_release);
    if (playbackStream_ != nullptr) {
        AAudioStream_close(playbackStream_);
        playbackStream_ = nullptr;
    }
}

bool AAudioEngine::openCaptureStreamLocked(int32_t inputDeviceId) {
    closeCaptureStreamLocked();

    AAudioStreamBuilder* inBuilder = nullptr;
    if (AAudio_createStreamBuilder(&inBuilder) != AAUDIO_OK) {
        LOGE("Failed to create capture stream builder");
        return false;
    }

    AAudioStreamBuilder_setDirection(inBuilder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(inBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(inBuilder, AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setChannelCount(inBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(inBuilder, AAUDIO_FORMAT_PCM_I16);

    const bool isBt = isBluetoothMode_.load(std::memory_order_relaxed);

    if (!isBt && inputDeviceId > 0) {
        AAudioStreamBuilder_setDeviceId(inBuilder, inputDeviceId);
    }

    AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setDataCallback(inBuilder, captureCallback, this);
    AAudioStreamBuilder_setErrorCallback(inBuilder, errorCallback, this);

    const aaudio_result_t res = AAudioStreamBuilder_openStream(inBuilder, &captureStream_);
    AAudioStreamBuilder_delete(inBuilder);

    if (res != AAUDIO_OK || captureStream_ == nullptr) {
        LOGE("Failed to open capture stream: %d (%s)", res, AAudio_convertResultToText(res));
        captureStream_ = nullptr;
        return false;
    }

    activeCaptureStream_.store(captureStream_, std::memory_order_release);

    const int32_t actualInRate = AAudioStream_getSampleRate(captureStream_);
    const int32_t actualInChannels = AAudioStream_getChannelCount(captureStream_);
    const aaudio_format_t actualInFormat = AAudioStream_getFormat(captureStream_);
    const int32_t actualInDeviceId = AAudioStream_getDeviceId(captureStream_);

    if (AAudioStream_getDirection(captureStream_) != AAUDIO_DIRECTION_INPUT ||
        actualInFormat != AAUDIO_FORMAT_PCM_I16 ||
        (actualInChannels != 1 && actualInChannels != 2) ||
        (actualInRate != 8000 && actualInRate != 16000 && actualInRate != 24000 &&
         actualInRate != 32000 && actualInRate != 44100 && actualInRate != 48000)) {
        LOGE("AAudio capture unsupported actual config: rate=%d, channels=%d, format=%d, device=%d",
             actualInRate, actualInChannels, static_cast<int>(actualInFormat), actualInDeviceId);
        closeCaptureStreamLocked();
        return false;
    }

    actualCaptureSampleRate_.store(actualInRate, std::memory_order_release);
    actualCaptureChannels_.store(actualInChannels, std::memory_order_release);
    actualInputDeviceId_.store(actualInDeviceId, std::memory_order_release);

    unifiedCaptureResampler_.configure(actualInRate, SAMPLE_RATE_GEMINI_IN);

    const int32_t inFramesPerCallback = AAudioStream_getFramesPerDataCallback(captureStream_);
    const int32_t inCapacity = AAudioStream_getBufferCapacityInFrames(captureStream_);
    const int32_t inBurst = AAudioStream_getFramesPerBurst(captureStream_);

    size_t neededCaptureScratch = CAPTURE_DECIMATE_CAPACITY;
    if (inCapacity > 0) neededCaptureScratch = std::max(neededCaptureScratch, static_cast<size_t>(inCapacity) * 4u);
    if (inFramesPerCallback > 0) neededCaptureScratch = std::max(neededCaptureScratch, static_cast<size_t>(inFramesPerCallback) * 4u);
    if (inBurst > 0) neededCaptureScratch = std::max(neededCaptureScratch, static_cast<size_t>(inBurst) * 8u);

    if (captureInputScratchBuffer_.size() < neededCaptureScratch) captureInputScratchBuffer_.resize(neededCaptureScratch, 0);
    if (captureDecimateBuffer_.size() < neededCaptureScratch) captureDecimateBuffer_.resize(neededCaptureScratch, 0);
    if (captureRawScratchBuffer_.size() < CAPTURE_RAW_SCRATCH_FRAMES) captureRawScratchBuffer_.resize(CAPTURE_RAW_SCRATCH_FRAMES, 0);

    return true;
}

bool AAudioEngine::initLocked(
    bool isBluetoothMode,
    int32_t targetPlaybackSampleRate,
    int32_t inputDeviceId,
    int32_t outputDeviceId) {

    stopLocked();

    engineState_.store(EngineState::STARTING, std::memory_order_release);
    isDisconnectedExplicit_.store(false, std::memory_order_release);
    activeCaptureStream_.store(nullptr, std::memory_order_release);
    activePlaybackStream_.store(nullptr, std::memory_order_release);

    {
        std::scoped_lock lock(playbackControlMutex_);
        resampler24To16_.reset();
        resampler24To32_.reset();
        halfbandResampler24To48_.reset();
        genericResampler_.reset();
        unifiedCaptureResampler_.reset();
        resetEarcon();
        voiceEnhancer_.reset(targetPlaybackSampleRate);
        smoothedRateFactor_ = 1.0f;
    }

    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    playbackDspInputBuffer_.resetQuiesced();
    playbackBuffer_.resetQuiesced();
    fftTapBuffer_.resetQuiesced();

    // УСТРАНЕНИЕ ДЕФЕКТА 223: Закрытие шлюза приема микрофона при старте
    micPipelineAdmitted_.store(false, std::memory_order_release);

    actualCaptureSampleRate_.store(0, std::memory_order_release);
    actualCaptureChannels_.store(0, std::memory_order_release);
    actualInputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
    actualPlaybackBurst_.