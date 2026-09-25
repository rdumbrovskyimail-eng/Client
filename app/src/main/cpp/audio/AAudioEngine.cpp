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
    a2 = ((A + 1.0f) + (A - 1.0f) * cs - beta) / a0;
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
    a1 = (2.0f * ((A - 1.0f) - (A + 1.0f) * cs)) / a0;
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
    lowShelf_.makeLowShelf(160.0f, 0.0f, fs);
    highShelf_.makeHighShelf(std::min(5000.0f, fs * 0.44f), 0.0f, fs);
}

void AnalogVoiceEnhancer::process(int16_t* samples, size_t numFrames, int32_t sampleRate) {
    if (samples == nullptr || numFrames == 0) return;
    if (sampleRate > 0 && sampleRate != currentRate_) reset(sampleRate);
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
      fftBuffer_(FFT_SIZE * 2, 0.0f),
      fftPos_(0),
      captureRawScratchBuffer_(CAPTURE_RAW_SCRATCH_FRAMES, 0),
      captureDecimateBuffer_(CAPTURE_DECIMATE_CAPACITY, 0),
      captureInputScratchBuffer_(CAPTURE_DECIMATE_CAPACITY, 0) {
    dsp::enableHardwareFtz();
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

    // ИСПРАВЛЕНИЕ ДЕФЕКТА: Явно запрошенное устройство обязано совпадать с открытым.
    if (inputDeviceId > 0 && actualInDeviceId != inputDeviceId) {
        LOGE("AAudio capture device mismatch rejected: requested=%d, actual=%d",
             inputDeviceId, actualInDeviceId);
        closeCaptureStreamLocked();
        return false;
    }

    actualCaptureSampleRate_.store(actualInRate, std::memory_order_release);
    actualCaptureChannels_.store(actualInChannels, std::memory_order_release);
    actualInputDeviceId_.store(actualInDeviceId, std::memory_order_release);

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

    isDisconnected_.store(false, std::memory_order_release);
    activeCaptureStream_.store(nullptr, std::memory_order_release);
    activePlaybackStream_.store(nullptr, std::memory_order_release);

    {
        std::scoped_lock lock(playbackControlMutex_, playbackJniWriteMutex_);
        resampler24To16_.reset();
        halfbandResampler24To48_.reset();
        genericResampler_.reset();
        captureDecimator48To16_.reset();
        captureDecimator32To16_.reset();
        captureResampler24To16_.reset();
        captureResampler44100To16000_.reset();
        captureUpsampler8To16_.reset();
        resetEarcon();
        voiceEnhancer_.reset(targetPlaybackSampleRate);
        fftPos_ = 0;
        inputIngressBlocked_.store(false, std::memory_order_release);
    }

    captureDroppedFrames_.store(0, std::memory_order_relaxed);
    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    playbackDspInputBuffer_.resetQuiesced();
    playbackBuffer_.resetQuiesced();

    // Шлюз входящих сэмплов закрыт по умолчанию
    captureIngressBlocked_.store(true, std::memory_order_release);

    actualCaptureSampleRate_.store(0, std::memory_order_release);
    actualCaptureChannels_.store(0, std::memory_order_release);
    actualInputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
    actualPlaybackBurst_.store(0, std::memory_order_release);
    isBluetoothMode_.store(isBluetoothMode, std::memory_order_relaxed);
    playbackSampleRate_.store(targetPlaybackSampleRate, std::memory_order_relaxed);

    const int32_t effectiveInputDeviceId = isBluetoothMode ? AAUDIO_UNSPECIFIED : inputDeviceId;

    requestedInputDeviceId_.store(effectiveInputDeviceId, std::memory_order_release);
    requestedOutputDeviceId_.store(outputDeviceId, std::memory_order_release);

    LOGI("AAudioEngine::initLocked: BT=%d, targetRate=%d, inDevId=%d, outDevId=%d",
         (int)isBluetoothMode, targetPlaybackSampleRate, inputDeviceId, outputDeviceId);

    const aaudio_result_t res = openPlaybackStreamWithFallback(
        targetPlaybackSampleRate, outputDeviceId, isBluetoothMode);

    if (res != AAUDIO_OK || playbackStream_ == nullptr) {
        LOGE("Failed to open playback stream: %d (%s)", res, AAudio_convertResultToText(res));
        closePlaybackStreamLocked();
        return false;
    }

    if (!validateAndPublishPlaybackConfigLocked(outputDeviceId)) {
        return false;
    }

    const int32_t verifiedPlaybackRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);
    voiceEnhancer_.reset(verifiedPlaybackRate);
    genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, verifiedPlaybackRate);

    const int32_t playBurst = AAudioStream_getFramesPerBurst(playbackStream_);
    const int32_t playCapacity = AAudioStream_getBufferCapacityInFrames(playbackStream_);
    if (playBurst > 0 && playCapacity > 0) {
        const int32_t targetBufSize = std::clamp(playBurst * 2, playBurst, playCapacity);
        const int32_t appliedBufSize = AAudioStream_setBufferSizeInFrames(playbackStream_, targetBufSize);
        LOGI("Playback buffer size tuned: requested=%d, applied=%d (burst=%d, capacity=%d)",
             targetBufSize, appliedBufSize, playBurst, playCapacity);
    }

    return true;
}

aaudio_result_t AAudioEngine::openPlaybackStreamWithFallback(
    int32_t targetPlaybackSampleRate,
    int32_t outputDeviceId,
    bool isBluetooth) {

    const aaudio_sharing_mode_t desiredSharing =
        isBluetooth ? AAUDIO_SHARING_MODE_SHARED : AAUDIO_SHARING_MODE_EXCLUSIVE;

    auto buildAndOpen = [this, targetPlaybackSampleRate, outputDeviceId](aaudio_sharing_mode_t sharingMode) -> aaudio_result_t {
        AAudioStreamBuilder* outBuilder = nullptr;
        if (AAudio_createStreamBuilder(&outBuilder) != AAUDIO_OK) return AAUDIO_ERROR_INTERNAL;

        AAudioStreamBuilder_setDirection(outBuilder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(outBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setChannelCount(outBuilder, CHANNEL_COUNT_MONO);
        AAudioStreamBuilder_setFormat(outBuilder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSampleRate(outBuilder, targetPlaybackSampleRate);

        if (outputDeviceId > 0) AAudioStreamBuilder_setDeviceId(outBuilder, outputDeviceId);
        AAudioStreamBuilder_setSharingMode(outBuilder, sharingMode);
        AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setDataCallback(outBuilder, playbackCallback, this);
        AAudioStreamBuilder_setErrorCallback(outBuilder, errorCallback, this);

        closePlaybackStreamLocked();

        aaudio_result_t openRes = AAudioStreamBuilder_openStream(outBuilder, &playbackStream_);
        AAudioStreamBuilder_delete(outBuilder);

        if (openRes == AAUDIO_OK && playbackStream_ != nullptr) {
            activePlaybackStream_.store(playbackStream_, std::memory_order_release);
            const int32_t openedRate = AAudioStream_getSampleRate(playbackStream_);
            const int32_t openedChannels = AAudioStream_getChannelCount(playbackStream_);
            const aaudio_format_t openedFormat = AAudioStream_getFormat(playbackStream_);

            if (AAudioStream_getDirection(playbackStream_) != AAUDIO_DIRECTION_OUTPUT ||
                openedRate <= 0 || openedChannels != CHANNEL_COUNT_MONO || openedFormat != AAUDIO_FORMAT_PCM_I16) {
                closePlaybackStreamLocked();
                openRes = AAUDIO_ERROR_INVALID_FORMAT;
            }
        }

        if (openRes != AAUDIO_OK && playbackStream_ != nullptr) closePlaybackStreamLocked();
        return openRes;
    };

    aaudio_result_t res = buildAndOpen(desiredSharing);
    if (res != AAUDIO_OK && desiredSharing == AAUDIO_SHARING_MODE_EXCLUSIVE) {
        LOGI("Playback EXCLUSIVE open failed (%d). Fallback to SHARED...", res);
        res = buildAndOpen(AAUDIO_SHARING_MODE_SHARED);
    }
    return res;
}

bool AAudioEngine::validateAndPublishPlaybackConfigLocked(int32_t requestedOutputDeviceId) {
    if (playbackStream_ == nullptr) return false;

    const int32_t actualRate = AAudioStream_getSampleRate(playbackStream_);
    const int32_t actualChannels = AAudioStream_getChannelCount(playbackStream_);
    const aaudio_format_t actualFormat = AAudioStream_getFormat(playbackStream_);
    const int32_t actualDeviceId = AAudioStream_getDeviceId(playbackStream_);
    const int32_t actualBurst = AAudioStream_getFramesPerBurst(playbackStream_);

    if (AAudioStream_getDirection(playbackStream_) != AAUDIO_DIRECTION_OUTPUT ||
        actualRate <= 0 || actualChannels != CHANNEL_COUNT_MONO || actualFormat != AAUDIO_FORMAT_PCM_I16) {
        closePlaybackStreamLocked();
        return false;
    }

    actualPlaybackSampleRate_.store(actualRate, std::memory_order_release);
    actualPlaybackChannels_.store(actualChannels, std::memory_order_release);
    actualPlaybackFormat_.store(static_cast<int32_t>(actualFormat), std::memory_order_release);
    actualPlaybackBurst_.store(actualBurst > 0 ? actualBurst : 0, std::memory_order_release);
    actualOutputDeviceId_.store(actualDeviceId, std::memory_order_release);
    isExclusiveSharingActive_.store(
        AAudioStream_getSharingMode(playbackStream_) == AAUDIO_SHARING_MODE_EXCLUSIVE,
        std::memory_order_release);

    bool mmapUsed = false;
    typedef bool (*isMMapUsedFn_t)(AAudioStream*);
    static auto fn_isMMapUsed = reinterpret_cast<isMMapUsedFn_t>(dlsym(RTLD_DEFAULT, "AAudioStream_isMMapUsed"));
    if (fn_isMMapUsed != nullptr && playbackStream_ != nullptr) {
        mmapUsed = fn_isMMapUsed(playbackStream_);
    }
    isMmapActive_.store(mmapUsed, std::memory_order_release);
    return true;
}

bool AAudioEngine::waitForStreamState(AAudioStream* stream, aaudio_stream_state_t desired, int timeoutMs) {
    if (stream == nullptr) return false;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(timeoutMs, 0));
    aaudio_stream_state_t state = AAudioStream_getState(stream);

    while (state != desired) {
        if (state == AAUDIO_STREAM_STATE_CLOSED || state == AAUDIO_STREAM_STATE_DISCONNECTED) return false;
        const auto now = std::chrono::steady_clock::now();
        if (now >= deadline) return false;
        const auto remainingNs = std::chrono::duration_cast<std::chrono::nanoseconds>(deadline - now).count();
        aaudio_stream_state_t nextState = state;
        const int64_t waitNs = std::min<int64_t>(remainingNs, 20'000'000LL);
        const aaudio_result_t result = AAudioStream_waitForStateChange(stream, state, &nextState, waitNs);

        if (result == AAUDIO_OK) state = nextState;
        else if (result == AAUDIO_ERROR_TIMEOUT) state = AAudioStream_getState(stream);
        else return false;
    }
    return true;
}

bool AAudioEngine::startPlayback() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (playbackDspRunning_.load(std::memory_order_acquire)) return true;

    if (!playbackStream_) {
        const aaudio_result_t reopen = openPlaybackStreamWithFallback(
            playbackSampleRate_.load(std::memory_order_acquire),
            requestedOutputDeviceId_.load(std::memory_order_acquire),
            isBluetoothMode_.load(std::memory_order_acquire));
        if (reopen != AAUDIO_OK || playbackStream_ == nullptr ||
            !validateAndPublishPlaybackConfigLocked(requestedOutputDeviceId_.load(std::memory_order_acquire))) {
            closePlaybackStreamLocked();
            return false;
        }
        const int32_t reopenedRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);
        voiceEnhancer_.reset(reopenedRate);
        genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, reopenedRate);
    }

    blockPlaybackCallbackAndWait();

    {
        std::scoped_lock lock(playbackControlMutex_, playbackJniWriteMutex_);
        halfbandResampler24To48_.reset();
        resampler24To16_.reset();
        genericResampler_.reset();
    }

    playbackDspInputBuffer_.resetQuiesced();
    playbackBuffer_.resetQuiesced();

    const aaudio_result_t result = AAudioStream_requestStart(playbackStream_);
    if (result != AAUDIO_OK) {
        closePlaybackStreamLocked();
        isDisconnected_.store(true, std::memory_order_release);
        unblockPlaybackCallback();
        return false;
    }
    if (!waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_STARTED, 2000)) {
        closePlaybackStreamLocked();
        isDisconnected_.store(true, std::memory_order_release);
        unblockPlaybackCallback();
        return false;
    }

    joinPlaybackDspThreadLocked();
    playbackDspRunning_.store(true, std::memory_order_release);
    try {
        playbackDspThread_ = std::thread(&AAudioEngine::playbackDspThreadLoop, this);
    } catch (...) {
        playbackDspRunning_.store(false, std::memory_order_release);
        playbackDspCv_.notify_all();
        if (playbackStream_) closePlaybackStreamLocked();
        isDisconnected_.store(true, std::memory_order_release);
        unblockPlaybackCallback();
        return false;
    }

    isRunning_.store(true, std::memory_order_release);
    unblockPlaybackCallback();
    return true;
}

// -----------------------------------------------------------------------------
// ФАЗА 1: Физический старт потока. PCM НЕ допускается в буферы.
// -----------------------------------------------------------------------------
bool AAudioEngine::startCapture() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);

    if (captureDspRunning_.load(std::memory_order_acquire)) {
        return true;
    }

    isDisconnected_.store(false, std::memory_order_release);
    captureIngressBlocked_.store(true, std::memory_order_release);

    joinCaptureDspThreadLocked();

    if (captureStream_) {
        const aaudio_stream_state_t state = AAudioStream_getState(captureStream_);
        if (state != AAUDIO_STREAM_STATE_OPEN && state != AAUDIO_STREAM_STATE_STOPPED) {
            const aaudio_result_t stopResult = AAudioStream_requestStop(captureStream_);
            if (stopResult != AAUDIO_OK ||
                !waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STOPPED, 500)) {
                LOGW("startCapture: failed to stop stale capture stream");
            }
        }
        closeCaptureStreamLocked();
    }

    if (!openCaptureStreamLocked(requestedInputDeviceId_.load(std::memory_order_acquire))) {
        LOGE("startCapture: failed to open capture stream");
        captureIngressBlocked_.store(true, std::memory_order_release);
        return false;
    }

    captureDroppedFrames_.store(0, std::memory_order_relaxed);
    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();

    captureDecimator48To16_.reset();
    captureDecimator32To16_.reset();
    captureResampler24To16_.reset();
    captureResampler44100To16000_.reset();
    captureUpsampler8To16_.reset();

    const aaudio_result_t result = AAudioStream_requestStart(captureStream_);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart(capture) failed: %d (%s)", result, AAudio_convertResultToText(result));
        closeCaptureStreamLocked();
        captureIngressBlocked_.store(true, std::memory_order_release);
        isDisconnected_.store(true, std::memory_order_release);
        return false;
    }

    if (!waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STARTED, 2000)) {
        LOGE("Capture stream did not reach STARTED within 2000 ms");
        AAudioStream_requestStop(captureStream_);
        waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STOPPED, 500);
        closeCaptureStreamLocked();
        captureIngressBlocked_.store(true, std::memory_order_release);
        isDisconnected_.store(true, std::memory_order_release);
        return false;
    }

    // Обновляем реальный ID устройства ПОСЛЕ физического запуска
    const int32_t actualDeviceId = AAudioStream_getDeviceId(captureStream_);
    actualInputDeviceId_.store(actualDeviceId, std::memory_order_release);

    isRunning_.store(true, std::memory_order_release);
    return true;
}

// -----------------------------------------------------------------------------
// ФАЗА 2: Запуск DSP-потребителя. PCM по-прежнему заблокирован.
// -----------------------------------------------------------------------------
bool AAudioEngine::activateCaptureDsp() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);

    if (captureStream_ == nullptr) {
        LOGE("activateCaptureDsp: capture stream is null");
        return false;
    }

    const aaudio_stream_state_t state = AAudioStream_getState(captureStream_);
    if (state != AAUDIO_STREAM_STATE_STARTED) {
        LOGE("activateCaptureDsp: stream state=%d, expected STARTED", static_cast<int>(state));
        return false;
    }

    const int32_t actualDeviceId = AAudioStream_getDeviceId(captureStream_);
    actualInputDeviceId_.store(actualDeviceId, std::memory_order_release);

    if (captureDspRunning_.load(std::memory_order_acquire)) {
        return true;
    }

    joinCaptureDspThreadLocked();
    captureDspRunning_.store(true, std::memory_order_release);

    try {
        captureDspThread_ = std::thread(&AAudioEngine::captureDspThreadLoop, this);
    } catch (...) {
        captureDspRunning_.store(false, std::memory_order_release);
        captureDspCv_.notify_all();
        LOGE("activateCaptureDsp: failed to create DSP thread");

        AAudioStream_requestStop(captureStream_);
        waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STOPPED, 500);
        closeCaptureStreamLocked();

        captureIngressBlocked_.store(true, std::memory_order_release);
        isDisconnected_.store(true, std::memory_order_release);
        return false;
    }

    captureDspCv_.notify_all();
    isRunning_.store(true, std::memory_order_release);
    return true;
}

// -----------------------------------------------------------------------------
// ФАЗА 3: Финальный коммит. Открытие шлюза для поступления PCM в пайплайн.
// -----------------------------------------------------------------------------
bool AAudioEngine::commitCaptureAdmission() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);

    if (captureStream_ == nullptr) {
        LOGE("commitCaptureAdmission: capture stream is null");
        return false;
    }

    const aaudio_stream_state_t state = AAudioStream_getState(captureStream_);
    if (state != AAUDIO_STREAM_STATE_STARTED) {
        LOGE("commitCaptureAdmission: stream is not STARTED");
        return false;
    }

    if (!captureDspRunning_.load(std::memory_order_acquire)) {
        LOGE("commitCaptureAdmission: DSP is not running");
        return false;
    }

    captureIngressBlocked_.store(false, std::memory_order_release);
    captureDspCv_.notify_all();
    return true;
}

bool AAudioEngine::start() {
    if (!startPlayback()) return false;
    if (!startCapture()) { stop(); return false; }
    if (!activateCaptureDsp()) { stop(); return false; }
    if (!commitCaptureAdmission()) { stop(); return false; }
    return true;
}

void AAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopLocked();
}

bool AAudioEngine::flushOutputStreamLocked(bool resumeAfterFlush) {
    if (playbackStream_ == nullptr) return true;
    aaudio_stream_state_t state = AAudioStream_getState(playbackStream_);
    if (state == AAUDIO_STREAM_STATE_CLOSED || state == AAUDIO_STREAM_STATE_DISCONNECTED) return false;

    const bool wasStarted =
        state == AAUDIO_STREAM_STATE_STARTED ||
        state == AAUDIO_STREAM_STATE_STARTING ||
        state == AAUDIO_STREAM_STATE_PAUSING;

    if (state == AAUDIO_STREAM_STATE_STARTING) {
        if (!waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_STARTED, 500)) return false;
        state = AAUDIO_STREAM_STATE_STARTED;
    }
    if (state == AAUDIO_STREAM_STATE_STARTED) {
        if (AAudioStream_requestPause(playbackStream_) != AAUDIO_OK) return false;
        if (!waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_PAUSED, 500)) return false;
        state = AAUDIO_STREAM_STATE_PAUSED;
    } else if (state == AAUDIO_STREAM_STATE_PAUSING) {
        if (!waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_PAUSED, 500)) return false;
        state = AAUDIO_STREAM_STATE_PAUSED;
    }

    if (state == AAUDIO_STREAM_STATE_PAUSED ||
        state == AAUDIO_STREAM_STATE_OPEN ||
        state == AAUDIO_STREAM_STATE_STOPPED ||
        state == AAUDIO_STREAM_STATE_FLUSHED) {
        if (state != AAUDIO_STREAM_STATE_FLUSHED) {
            if (AAudioStream_requestFlush(playbackStream_) != AAUDIO_OK) return false;
            if (!waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_FLUSHED, 500)) return false;
        }
    } else {
        return false;
    }

    if (resumeAfterFlush && wasStarted) {
        if (AAudioStream_requestStart(playbackStream_) != AAUDIO_OK) return false;
        if (!waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_STARTED, 500)) return false;
    }
    return true;
}

void AAudioEngine::stopCaptureLocked() {
    captureIngressBlocked_.store(true, std::memory_order_release);
    captureDspRunning_.store(false, std::memory_order_release);
    captureDspCv_.notify_all();

    joinCaptureDspThreadLocked();

    if (captureStream_) {
        const aaudio_result_t res = AAudioStream_requestStop(captureStream_);
        if (res != AAUDIO_OK) {
            LOGW("AAudioStream_requestStop(capture) returned %d (%s)", res, AAudio_convertResultToText(res));
        }
        waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STOPPED, 500);
        closeCaptureStreamLocked();
    }

    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    captureUpsampler8To16_.reset();

    micRms_.store(0.0f, std::memory_order_relaxed);
    actualInputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
}

void AAudioEngine::stopPlaybackLocked() {
    blockPlaybackCallbackAndWait();
    playbackDspRunning_.store(false, std::memory_order_release);
    playbackDspCv_.notify_all();
    playbackIngressCv_.notify_all();
    joinPlaybackDspThreadLocked();

    if (playbackStream_) {
        if (!flushOutputStreamLocked(false)) {
            LOGW("Playback physical flush failed during stop; closing stream");
        }
        closePlaybackStreamLocked();
    }

    playbackDspInputBuffer_.resetQuiesced();
    playbackBuffer_.resetQuiesced();
    outRms_.store(0.0f, std::memory_order_relaxed);
}

void AAudioEngine::stopLocked() {
    blockPlaybackCallbackAndWait();
    const bool hadState =
        isRunning_.exchange(false, std::memory_order_acq_rel) ||
        captureStream_ != nullptr ||
        playbackStream_ != nullptr ||
        captureDspRunning_.load(std::memory_order_acquire) ||
        playbackDspRunning_.load(std::memory_order_acquire) ||
        captureDspThread_.joinable() ||
        playbackDspThread_.joinable();

    if (!hadState) {
        unblockPlaybackCallback();
        return;
    }

    stopCaptureLocked();
    stopPlaybackLocked();

    {
        std::scoped_lock lock(playbackControlMutex_, playbackJniWriteMutex_);
        resampler24To16_.reset();
        halfbandResampler24To48_.reset();
        genericResampler_.reset();
        captureDecimator48To16_.reset();
        captureDecimator32To16_.reset();
        captureResampler24To16_.reset();
        captureResampler44100To16000_.reset();
        captureUpsampler8To16_.reset();
        resetEarcon();
        voiceEnhancer_.reset(48000);
        fftPos_ = 0;
        inputIngressBlocked_.store(false, std::memory_order_release);
    }

    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    playbackDspInputBuffer_.resetQuiesced();
    playbackBuffer_.resetQuiesced();
    micRms_.store(0.0f, std::memory_order_relaxed);
    outRms_.store(0.0f, std::memory_order_relaxed);
    isMmapActive_.store(false, std::memory_order_relaxed);
    isExclusiveSharingActive_.store(false, std::memory_order_relaxed);
    actualPlaybackChannels_.store(0, std::memory_order_relaxed);
    actualPlaybackFormat_.store(0, std::memory_order_relaxed);
    actualPlaybackSampleRate_.store(0, std::memory_order_relaxed);
    actualPlaybackBurst_.store(0, std::memory_order_relaxed);
    actualOutputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_relaxed);
    unblockPlaybackCallback();
}

void AAudioEngine::stopCapture() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopCaptureLocked();
    if (!playbackDspRunning_.load(std::memory_order_acquire)) {
        isRunning_.store(false, std::memory_order_release);
    }
}

void AAudioEngine::captureDspThreadLoop() {
    pthread_setname_np(pthread_self(), "AudioCapWorker");
    dsp::enableHardwareFtz();

    try {
        while (captureDspRunning_.load(std::memory_order_acquire)) {
            const int32_t channels = actualCaptureChannels_.load(std::memory_order_relaxed);
            const size_t ch = static_cast<size_t>(channels > 0 ? channels : 1);

            size_t availableSamples = captureRawBuffer_.availableRead();
            availableSamples -= availableSamples % ch;

            if (availableSamples == 0) {
                std::unique_lock<std::mutex> lock(captureDspWaitMutex_);
                constexpr auto kCaptureDspWaitTimeout = std::chrono::milliseconds(100);
                captureDspCv_.wait_for(lock, kCaptureDspWaitTimeout, [this]() {
                    return !captureDspRunning_.load(std::memory_order_acquire) ||
                        captureRawBuffer_.availableRead() > 0;
                });
                continue;
            }

            size_t maxScratchSamples = captureRawScratchBuffer_.size();
            maxScratchSamples -= maxScratchSamples % ch;

            const size_t samplesToRead = std::min(availableSamples, maxScratchSamples);
            const size_t readSamples = captureRawBuffer_.read(captureRawScratchBuffer_.data(), samplesToRead);
            if (readSamples == 0) continue;

            const size_t chunkFrames = readSamples / ch;
            const int16_t* inPtr = captureRawScratchBuffer_.data();
            int16_t* monoBuf = captureInputScratchBuffer_.data();
            const float gain = micGain_.load(std::memory_order_relaxed);
            const int32_t capRate = actualCaptureSampleRate_.load(std::memory_order_relaxed);

            if (channels == 2) {
                dsp::stereoToMonoWithGain(inPtr, monoBuf, chunkFrames, gain);
            } else {
                dsp::applyGainInPlace(inPtr, monoBuf, chunkFrames, gain);
            }

            const size_t decimateScratchCap = captureDecimateBuffer_.size();
            int16_t* finalPcm = monoBuf;
            size_t finalFrames = chunkFrames;

            if (capRate == 48000) {
                int16_t* decBuf = captureDecimateBuffer_.data();
                finalFrames = captureDecimator48To16_.process(monoBuf, chunkFrames, decBuf, decimateScratchCap);
                finalPcm = decBuf;
            } else if (capRate == 44100) {
                int16_t* decBuf = captureDecimateBuffer_.data();
                finalFrames = captureResampler44100To16000_.process(monoBuf, chunkFrames, decBuf, decimateScratchCap);
                finalPcm = decBuf;
            } else if (capRate == 24000) {
                int16_t* decBuf = captureDecimateBuffer_.data();
                finalFrames = captureResampler24To16_.process(monoBuf, chunkFrames, decBuf);
                finalPcm = decBuf;
            } else if (capRate == 32000) {
                int16_t* decBuf = captureDecimateBuffer_.data();
                finalFrames = captureDecimator32To16_.process(monoBuf, chunkFrames, decBuf, decimateScratchCap);
                finalPcm = decBuf;
            } else if (capRate == 8000) {
                int16_t* upBuf = captureDecimateBuffer_.data();
                finalFrames = captureUpsampler8To16_.process(monoBuf, chunkFrames, upBuf, decimateScratchCap);
                finalPcm = upBuf;
            }

            if (finalFrames > 0) {
                micRms_.store(dsp::calculateRms(finalPcm, finalFrames), std::memory_order_relaxed);
                const size_t writtenFrames = captureBuffer_.write(finalPcm, finalFrames);
                if (writtenFrames < finalFrames) {
                    captureDroppedFrames_.fetch_add(finalFrames - writtenFrames, std::memory_order_relaxed);
                }
            }
        }
    } catch (const std::exception& e) {
        LOGE("AAudioEngine: capture DSP worker exception: %s", e.what());
        isDisconnected_.store(true, std::memory_order_release);
    } catch (...) {
        LOGE("AAudioEngine: capture DSP worker unknown exception");
        isDisconnected_.store(true, std::memory_order_release);
    }

    captureDspRunning_.store(false, std::memory_order_release);
    captureDspCv_.notify_all();
}

void AAudioEngine::playbackDspThreadLoop() {
    pthread_setname_np(pthread_self(), "AudioDspWorker");
    dsp::enableHardwareFtz();

    try {
        int16_t* input = playbackDspInputScratch_.data();
        int16_t* output = playbackDspOutputScratch_.data();
        int16_t* earconBuf = earconScratch_.data();
        uint64_t workerDspEpoch = playbackEpoch_.load(std::memory_order_acquire);

        while (playbackDspRunning_.load(std::memory_order_acquire)) {
            const uint64_t activeEpoch = playbackEpoch_.load(std::memory_order_acquire);
            if (activeEpoch != workerDspEpoch) {
                const int32_t currentRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_acquire));
                voiceEnhancer_.reset(currentRate);
                halfbandResampler24To48_.reset();
                resampler24To16_.reset();
                genericResampler_.reset();
                genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, currentRate);
                fftPos_ = 0;
                std::fill(fftBuffer_.begin(), fftBuffer_.end(), 0.0f);

                {
                    std::lock_guard<std::mutex> ingressLock(playbackJniWriteMutex_);
                    playbackDspInputBuffer_.discardAllQuiesced();
                    workerDspEpoch = activeEpoch;
                }

                playbackDspResetAcknowledgedEpoch_.store(activeEpoch, std::memory_order_release);
                playbackDspCv_.notify_all();
            }

            const int32_t actualRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_acquire));
            const int32_t actualBurst = actualPlaybackBurst_.load(std::memory_order_acquire);

            const size_t timeTargetFrames = static_cast<size_t>(
                static_cast<uint64_t>(actualRate) * PLAYBACK_TARGET_BUFFER_MS / 1000ULL);
            const size_t burstTargetFrames = (actualBurst > 0)
                ? static_cast<size_t>(actualBurst) * PLAYBACK_BURST_MIN_MULTIPLIER : 0U;
            const size_t targetBufferFrames = std::max<size_t>(1U, std::max(timeTargetFrames, burstTargetFrames));

            if (earconRequested_.load(std::memory_order_acquire)) {
                const size_t earconFrames = std::min<size_t>(
                    static_cast<size_t>(actualRate * (EARCON_DURATION_MS / 1000.0f)), earconScratch_.size());

                for (size_t i = 0; i < earconFrames; ++i) {
                    const float t = static_cast<float>(i) / static_cast<float>(actualRate);
                    const float env = std::cos((3.14159265f * static_cast<float>(i)) / (2.0f * static_cast<float>(earconFrames)));
                    const float sample = std::sin(2.0f * 3.14159265f * EARCON_FREQ_HZ * t) * env * env * 12000.0f;
                    earconBuf[i] = static_cast<int16_t>(std::clamp(sample, -32768.0f, 32767.0f));
                }

                std::lock_guard<std::mutex> commitLock(playbackControlMutex_);
                if (playbackEpoch_.load(std::memory_order_acquire) == activeEpoch &&
                    playbackBuffer_.availableWrite() >= earconFrames) {
                    const size_t written = playbackBuffer_.write(earconBuf, earconFrames);
                    if (written == earconFrames) earconRequested_.store(false, std::memory_order_release);
                }
            }

            const size_t maxOutputFrames = static_cast<size_t>(
                std::ceil(static_cast<double>(PLAYBACK_DSP_INPUT_CHUNK_FRAMES) *
                          static_cast<double>(actualRate) / static_cast<double>(SAMPLE_RATE_GEMINI_OUT)));

            const size_t buffered = playbackBuffer_.availableRead();
            const size_t freeSpace = playbackBuffer_.availableWrite();

            if (buffered >= targetBufferFrames || freeSpace < maxOutputFrames) {
                std::unique_lock<std::mutex> waitLock(playbackDspWaitMutex_);
                playbackDspCv_.wait_for(waitLock, std::chrono::milliseconds(5), [this, activeEpoch, targetBufferFrames, maxOutputFrames]() {
                    if (!playbackDspRunning_.load(std::memory_order_acquire)) return true;
                    if (playbackEpoch_.load(std::memory_order_acquire) != activeEpoch) return true;
                    return playbackBuffer_.availableRead() < targetBufferFrames && playbackBuffer_.availableWrite() >= maxOutputFrames;
                });
                continue;
            }

            size_t inputFrames = playbackDspInputBuffer_.read(input, PLAYBACK_DSP_INPUT_CHUNK_FRAMES);
            if (inputFrames == 0) {
                std::unique_lock<std::mutex> waitLock(playbackDspWaitMutex_);
                playbackDspCv_.wait_for(waitLock, std::chrono::milliseconds(10), [this, activeEpoch]() {
                    return !playbackDspRunning_.load(std::memory_order_acquire) ||
                        playbackEpoch_.load(std::memory_order_acquire) != activeEpoch ||
                        playbackDspInputBuffer_.availableRead() > 0;
                });
                continue;
            }

            if (activeEpoch != playbackEpoch_.load(std::memory_order_acquire)) continue;

            size_t outputFrames = 0;
            if (actualRate == SAMPLE_RATE_GEMINI_OUT) {
                outputFrames = inputFrames;
                std::memcpy(output, input, outputFrames * sizeof(int16_t));
            } else if (actualRate == SAMPLE_RATE_NATIVE_SPEAKER || actualRate == SAMPLE_RATE_BT_A2DP) {
                outputFrames = halfbandResampler24To48_.process(input, inputFrames, output);
            } else if (actualRate == SAMPLE_RATE_BT_HFP) {
                outputFrames = resampler24To16_.process(input, inputFrames, output);
            } else {
                genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, actualRate);
                outputFrames = genericResampler_.process(input, inputFrames, output, playbackDspOutputScratch_.size());
            }

            if (outputFrames == 0) continue;

            voiceEnhancer_.process(output, outputFrames, actualRate);
            const float volume = playbackVolume_.load(std::memory_order_relaxed);
            if (volume != 1.0f) {
                for (size_t i = 0; i < outputFrames; ++i) {
                    const float v = static_cast<float>(output[i]) * volume;
                    output[i] = static_cast<int16_t>(std::clamp(v, -32768.0f, 32767.0f));
                }
            }

            const float outRms = dsp::calculateRms(output, outputFrames);
            const float micRms = micRms_.load(std::memory_order_relaxed);
            const size_t requiredAccum = (actualRate >= 44100) ? (FFT_SIZE * 2) : FFT_SIZE;
            const size_t hopSize = (actualRate >= 44100) ? (FFT_HOP_SIZE * 2) : FFT_HOP_SIZE;

            for (size_t i = 0; i < outputFrames; ++i) {
                if (fftPos_ < fftBuffer_.size()) {
                    fftBuffer_[fftPos_++] = static_cast<float>(output[i]) * (1.0f / 32768.0f);
                }
                if (fftPos_ >= requiredAccum) {
                    if (fftProcessor_) {
                        fftProcessor_->process(fftBuffer_.data(), requiredAccum, micRms, outRms, actualRate);
                    }
                    std::memmove(fftBuffer_.data(), fftBuffer_.data() + hopSize, (requiredAccum - hopSize) * sizeof(float));
                    fftPos_ = requiredAccum - hopSize;
                }
            }

            {
                std::lock_guard<std::mutex> commitLock(playbackControlMutex_);
                if (playbackDspRunning_.load(std::memory_order_acquire) &&
                    playbackEpoch_.load(std::memory_order_acquire) == activeEpoch) {
                    if (outputFrames > playbackBuffer_.availableWrite()) continue;
                    playbackBuffer_.write(output, outputFrames);
                }
            }
        }
    } catch (const std::exception& e) {
        LOGE("AAudioEngine: playback DSP worker exception: %s", e.what());
        isDisconnected_.store(true, std::memory_order_release);
    } catch (...) {
        LOGE("AAudioEngine: playback DSP worker unknown exception");
        isDisconnected_.store(true, std::memory_order_release);
    }

    playbackDspRunning_.store(false, std::memory_order_release);
    playbackDspCv_.notify_all();
}

size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames, uint64_t generation) {
    if (pcm == nullptr || frames == 0 || generation == 0) return 0;
    if (!playbackDspRunning_.load(std::memory_order_acquire)) return 0;

    while (true) {
        {
            std::lock_guard<std::mutex> lock(playbackJniWriteMutex_);
            if (generation < playbackEpoch_.load(std::memory_order_acquire)) return frames;
            if (!inputIngressBlocked_.load(std::memory_order_acquire)) {
                const size_t written = playbackDspInputBuffer_.write(pcm, frames);
                if (written > 0) playbackDspCv_.notify_one();
                return written;
            }
        }

        {
            std::unique_lock<std::mutex> lock(playbackIngressMutex_);
            if (!inputIngressBlocked_.load(std::memory_order_acquire)) continue;
            playbackIngressCv_.wait_for(lock, std::chrono::milliseconds(4), [this]() {
                return !inputIngressBlocked_.load(std::memory_order_acquire) ||
                    !playbackDspRunning_.load(std::memory_order_acquire);
            });
        }

        if (!playbackDspRunning_.load(std::memory_order_acquire)) return 0;
    }
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    if (pcm == nullptr || maxFrames == 0) return 0;
    return captureBuffer_.read(pcm, maxFrames);
}

void AAudioEngine::flushPlayback(uint64_t generation) {
    if (generation == 0) return;
    std::unique_lock<std::mutex> lifecycleLock(lifecycleMutex_);

    const uint64_t currentEpoch = playbackEpoch_.load(std::memory_order_acquire);
    if (generation <= currentEpoch) return;

    blockPlaybackCallbackAndWait();
    const bool wasPlaybackActive = playbackDspRunning_.load(std::memory_order_acquire);

    {
        std::scoped_lock lock(playbackControlMutex_, playbackJniWriteMutex_);
        inputIngressBlocked_.store(true, std::memory_order_release);
        playbackBuffer_.discardAllQuiesced();
        playbackEpoch_.store(generation, std::memory_order_release);
        earconRequested_.store(false, std::memory_order_release);
        outRms_.store(0.0f, std::memory_order_relaxed);
    }

    playbackDspCv_.notify_all();

    bool resetReady = true;
    if (wasPlaybackActive) {
        const auto kDspResetTimeout = std::chrono::milliseconds(PLAYBACK_DSP_RESET_TIMEOUT_MS);
        std::unique_lock<std::mutex> waitLock(playbackDspWaitMutex_);
        resetReady = playbackDspCv_.wait_for(waitLock, kDspResetTimeout, [this, generation]() {
            return playbackDspResetAcknowledgedEpoch_.load(std::memory_order_acquire) >= generation ||
                !playbackDspRunning_.load(std::memory_order_acquire);
        });
        resetReady = resetReady && (playbackDspResetAcknowledgedEpoch_.load(std::memory_order_acquire) >= generation);
    }

    if (!resetReady) {
        playbackDspRunning_.store(false, std::memory_order_release);
        playbackDspCv_.notify_all();
        playbackIngressCv_.notify_all();
        if (playbackDspThread_.joinable()) playbackDspThread_.join();

        playbackDspInputBuffer_.resetQuiesced();
        playbackBuffer_.discardAllQuiesced();
        if (playbackStream_) closePlaybackStreamLocked();

        isMmapActive_.store(false, std::memory_order_release);
        isExclusiveSharingActive_.store(false, std::memory_order_release);
        isDisconnected_.store(true, std::memory_order_release);
        inputIngressBlocked_.store(false, std::memory_order_release);
        unblockPlaybackCallback();
        return;
    }

    inputIngressBlocked_.store(false, std::memory_order_release);
    playbackIngressCv_.notify_all();

    bool physicalOk = true;
    if (playbackStream_) physicalOk = flushOutputStreamLocked(wasPlaybackActive);

    if (!physicalOk) {
        playbackDspRunning_.store(false, std::memory_order_release);
        playbackDspCv_.notify_all();
        playbackIngressCv_.notify_all();
        if (playbackDspThread_.joinable()) playbackDspThread_.join();

        playbackDspInputBuffer_.resetQuiesced();
        playbackBuffer_.discardAllQuiesced();
        if (playbackStream_) closePlaybackStreamLocked();

        isMmapActive_.store(false, std::memory_order_release);
        isExclusiveSharingActive_.store(false, std::memory_order_release);
        isDisconnected_.store(true, std::memory_order_release);
    }

    playbackDspCv_.notify_all();
    playbackIngressCv_.notify_all();
    unblockPlaybackCallback();
}

void AAudioEngine::triggerBargeInEarcon() {
    earconRequested_.store(true, std::memory_order_release);
    playbackDspCv_.notify_all();
}

void AAudioEngine::resetEarcon() {
    earconRequested_.store(false, std::memory_order_release);
}

void AAudioEngine::setVolume(float vol) {
    playbackVolume_.store(std::clamp(vol, 0.0f, 1.0f), std::memory_order_relaxed);
}

void AAudioEngine::setMicGain(float gain) {
    micGain_.store(std::clamp(gain, 0.5f, 2.0f), std::memory_order_relaxed);
}

bool AAudioEngine::tryEnterPlaybackCallback() {
    uint32_t state = playbackCallbackState_.load(std::memory_order_acquire);
    for (;;) {
        if ((state & PLAYBACK_CALLBACK_BLOCKED) != 0) return false;
        const uint32_t count = state & PLAYBACK_CALLBACK_COUNT_MASK;
        if (count == PLAYBACK_CALLBACK_COUNT_MASK) return false;
        const uint32_t desired = state + 1u;
        if (playbackCallbackState_.compare_exchange_weak(state, desired, std::memory_order_acq_rel, std::memory_order_acquire)) {
            return true;
        }
    }
}

void AAudioEngine::leavePlaybackCallback() {
    playbackCallbackState_.fetch_sub(1u, std::memory_order_acq_rel);
}

void AAudioEngine::blockPlaybackCallbackAndWait() {
    playbackCallbackState_.fetch_or(PLAYBACK_CALLBACK_BLOCKED, std::memory_order_acq_rel);
    while ((playbackCallbackState_.load(std::memory_order_acquire) & PLAYBACK_CALLBACK_COUNT_MASK) != 0) {
        std::this_thread::yield();
    }
}

void AAudioEngine::unblockPlaybackCallback() {
    playbackCallbackState_.fetch_and(PLAYBACK_CALLBACK_COUNT_MASK, std::memory_order_release);
}

size_t AAudioEngine::getPendingPlaybackFrames() const {
    return playbackDspInputBuffer_.availableRead() + playbackBuffer_.availableRead();
}

void AAudioEngine::getSpectrumData(dsp::SpectrumSnapshot& outSnapshot) {
    fftProcessor_->getLatestSnapshot(outSnapshot);
}

aaudio_data_callback_result_t AAudioEngine::captureCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {

    if (userData == nullptr || audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);

    // БАРЬЕР ДОПУСКА: пока маршрут не проверен и не подтверждён, данные отбрасываются
    if (engine->captureIngressBlocked_.load(std::memory_order_acquire)) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const auto* inSamples = static_cast<const int16_t*>(audioData);
    const int32_t channels = engine->actualCaptureChannels_.load(std::memory_order_relaxed);
    const size_t ch = static_cast<size_t>(channels > 0 ? channels : 1);

    const size_t availableFrames = engine->captureRawBuffer_.availableWrite() / ch;
    const size_t framesToWrite = std::min(static_cast<size_t>(numFrames), availableFrames);
    const size_t samplesToWrite = framesToWrite * ch;

    const size_t written = engine->captureRawBuffer_.write(inSamples, samplesToWrite);
    const size_t writtenFrames = written / ch;

    if (written > 0) {
        engine->captureDspCv_.notify_one();
    }

    if (writtenFrames < static_cast<size_t>(numFrames)) {
        engine->captureDroppedFrames_.fetch_add(
            static_cast<size_t>(numFrames) - writtenFrames, std::memory_order_relaxed);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AAudioEngine::playbackCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {

    if (userData == nullptr || audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);
    const size_t frames = static_cast<size_t>(numFrames);

    if (!engine->tryEnterPlaybackCallback()) {
        std::memset(samples, 0, frames * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const size_t read = engine->playbackBuffer_.read(samples, frames);
    if (read < frames) {
        std::memset(samples + read, 0, (frames - read) * sizeof(int16_t));
    }

    if (read > 0) {
        const float dacRms = dsp::calculateRms(samples, read);
        engine->outRms_.store(dacRms, std::memory_order_relaxed);
    } else {
        engine->outRms_.store(0.0f, std::memory_order_relaxed);
    }

    engine->leavePlaybackCallback();
    engine->playbackDspCv_.notify_one();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioEngine::errorCallback(
    AAudioStream* stream,
    void* userData,
    aaudio_result_t error) {

    auto* engine = static_cast<AAudioEngine*>(userData);
    if (engine == nullptr || error == AAUDIO_OK) return;

    const AAudioStream* activeCapture = engine->activeCaptureStream_.load(std::memory_order_acquire);
    const AAudioStream* activePlayback = engine->activePlaybackStream_.load(std::memory_order_acquire);

    if (stream == activeCapture || stream == activePlayback) {
        engine->isDisconnected_.store(true, std::memory_order_release);
    }
}

} // namespace client::audio