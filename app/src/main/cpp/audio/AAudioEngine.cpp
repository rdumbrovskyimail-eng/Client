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

#define LOGI(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    client::logging::NativeLogQueue::getInstance().pushRt(4, LOG_TAG, _buf); \
} while (0)

#define LOGW(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    client::logging::NativeLogQueue::getInstance().pushRt(5, LOG_TAG, _buf); \
} while (0)

#define LOGE(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    client::logging::NativeLogQueue::getInstance().pushRt(6, LOG_TAG, _buf); \
} while (0)

namespace client::audio {

void BiquadTdf2::reset() {
    s1 = 0.0;
    s2 = 0.0;
}

void BiquadTdf2::makeLowShelf(double fc, double gainDb, double fs, double S) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double omega = 2.0 * 3.141592653589793 * fc / fs;
    const double sn = std::sin(omega);
    const double cs = std::cos(omega);
    const double alpha = sn / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
    const double beta = 2.0 * std::sqrt(A) * alpha;

    const double a0 = (A + 1.0) + (A - 1.0) * cs + beta;
    b0 = (A * ((A + 1.0) - (A - 1.0) * cs + beta)) / a0;
    b1 = (2.0 * A * ((A - 1.0) - (A + 1.0) * cs)) / a0;
    b2 = (A * ((A + 1.0) - (A - 1.0) * cs - beta)) / a0;
    a1 = (-2.0 * ((A - 1.0) + (A + 1.0) * cs)) / a0;
    a2 = ((A + 1.0) + (A - 1.0) * cs - beta) / a0;
}

void BiquadTdf2::makeHighShelf(double fc, double gainDb, double fs, double S) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double omega = 2.0 * 3.141592653589793 * fc / fs;
    const double sn = std::sin(omega);
    const double cs = std::cos(omega);
    const double alpha = sn / 2.0 * std::sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0);
    const double beta = 2.0 * std::sqrt(A) * alpha;

    const double a0 = (A + 1.0) - (A - 1.0) * cs + beta;
    b0 = (A * ((A + 1.0) + (A - 1.0) * cs + beta)) / a0;
    b1 = (-2.0 * A * ((A - 1.0) + (A + 1.0) * cs)) / a0;
    b2 = (A * ((A + 1.0) - (A - 1.0) * cs - beta)) / a0;
    a1 = (2.0 * ((A - 1.0) - (A + 1.0) * cs)) / a0;
    a2 = ((A + 1.0) - (A - 1.0) * cs - beta) / a0;
}

void BiquadTdf2::makeHighPass(double fc, double q, double fs) {
    const double omega = 2.0 * 3.141592653589793 * fc / fs;
    const double cs = std::cos(omega);
    const double alpha = std::sin(omega) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = ((1.0 + cs) / 2.0) / a0;
    b1 = (-(1.0 + cs)) / a0;
    b2 = ((1.0 + cs) / 2.0) / a0;
    a1 = (-2.0 * cs) / a0;
    a2 = (1.0 - alpha) / a0;
}

void BiquadTdf2::makePeaking(double fc, double gainDb, double q, double fs) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double omega = 2.0 * 3.141592653589793 * fc / fs;
    const double cs = std::cos(omega);
    const double alpha = std::sin(omega) / (2.0 * q);
    const double a0 = 1.0 + alpha / A;
    b0 = (1.0 + alpha * A) / a0;
    b1 = (-2.0 * cs) / a0;
    b2 = (1.0 - alpha * A) / a0;
    a1 = (-2.0 * cs) / a0;
    a2 = (1.0 - alpha / A) / a0;
}

void BiquadTdf2::makeIdentity() {
    b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0;
}

void LookaheadPeakLimiter::reset(double fs) {
    lookahead_ = std::clamp<size_t>(static_cast<size_t>(std::lround(0.0015 * fs)), 2, MAX_LOOKAHEAD);
    pos_ = 0;
    gain_ = 1.0;
    for (size_t i = 0; i < MAX_LOOKAHEAD; ++i) {
        delay_[i] = 0.0;
        required_[i] = 1.0;
    }
    // Атака успевает за окно предпросмотра (≈5 постоянных времени), отпускание 80 мс без «пампинга»
    attackCoeff_ = std::exp(-5.0 / static_cast<double>(lookahead_ - 1));
    releaseCoeff_ = std::exp(-1.0 / (0.080 * fs));
}

AnalogVoiceEnhancer::AnalogVoiceEnhancer() {
    configure(48000, OUTPUT_EQ_SPEAKER);
}

void AnalogVoiceEnhancer::reset(int32_t sampleRate) {
    configure(sampleRate, profile_);
}

void AnalogVoiceEnhancer::configure(int32_t sampleRate, int32_t profile) {
    currentRate_ = sampleRate > 0 ? sampleRate : 48000;
    profile_ = std::clamp(profile, OUTPUT_EQ_SPEAKER, OUTPUT_EQ_HEADPHONES);
    const double fs = static_cast<double>(currentRate_);
    const double nyquistGuard = fs * 0.45;

    highPass_.reset();
    lowBand_.reset();
    midBand_.reset();
    highBand_.reset();

    switch (profile_) {
        case OUTPUT_EQ_HEADSET_VOICE:
            highPass_.makeHighPass(70.0, 0.7071, fs);
            lowBand_.makeLowShelf(200.0, 2.0, fs, 0.8);
            midBand_.makePeaking(std::min(2800.0, nyquistGuard), 1.2, 1.0, fs);
            highBand_.makeIdentity();
            preGain_ = std::pow(10.0, -1.0 / 20.0);
            break;
        case OUTPUT_EQ_HEADPHONES:
            highPass_.makeHighPass(25.0, 0.7071, fs);
            lowBand_.makeLowShelf(160.0, 2.5, fs, 0.8);
            midBand_.makeIdentity();
            highBand_.makeHighShelf(std::min(9000.0, nyquistGuard), 1.0, fs, 0.8);
            preGain_ = std::pow(10.0, -1.5 / 20.0);
            break;
        case OUTPUT_EQ_SPEAKER:
        default:
            highPass_.makeHighPass(120.0, 0.7071, fs);
            lowBand_.makeLowShelf(320.0, 1.5, fs, 0.8);
            midBand_.makeIdentity();
            highBand_.makeHighShelf(std::min(7000.0, nyquistGuard), -1.0, fs, 0.8);
            preGain_ = std::pow(10.0, -0.5 / 20.0);
            break;
    }

    limiter_.reset(fs);
}

void AnalogVoiceEnhancer::process(int16_t* samples, size_t numFrames, int32_t sampleRate) {
    if (samples == nullptr || numFrames == 0) return;
    if (sampleRate > 0 && sampleRate != currentRate_) reset(sampleRate);

    for (size_t i = 0; i < numFrames; ++i) {
        double x = static_cast<double>(samples[i]) * (1.0 / 32768.0) * preGain_;
        x = highPass_.process(x);
        x = lowBand_.process(x);
        x = midBand_.process(x);
        x = highBand_.process(x);
        x = limiter_.process(x);
        samples[i] = static_cast<int16_t>(std::clamp<int32_t>(
            static_cast<int32_t>(std::lrint(x * 32767.0)), -32768, 32767));
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
    captureSessionId_.store(0, std::memory_order_relaxed);
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
    // 16 кГц нативно: VoIP-профиль HAL отдаёт сигнал после AEC/NS без нашей децимации
    AAudioStreamBuilder_setSampleRate(inBuilder, SAMPLE_RATE_GEMINI_IN);
    AAudioStreamBuilder_setChannelCount(inBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(inBuilder, AAUDIO_FORMAT_PCM_I16);

    // Маршрут микрофона задаёт setCommunicationDevice(). Явная привязка к порту BUILTIN_MIC
    // выбирала один микрофон и отключала многомикрофонное шумоподавление/AEC VoIP-тракта.
    (void)inputDeviceId;

    AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    // Сессия => legacy-путь (не MMAP): к VOICE_COMMUNICATION гарантированно цепляются AEC/NS/AGC
    AAudioStreamBuilder_setSessionId(inBuilder, AAUDIO_SESSION_ID_ALLOCATE);
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
    captureSessionId_.store(AAudioStream_getSessionId(captureStream_), std::memory_order_relaxed);

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

    micPipelineAdmitted_.store(false, std::memory_order_release);

    actualCaptureSampleRate_.store(0, std::memory_order_release);
    actualCaptureChannels_.store(0, std::memory_order_release);
    actualInputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
    actualPlaybackBurst_.store(0, std::memory_order_release);
    lastTunedXRunCount_.store(0, std::memory_order_release);
    isBluetoothMode_.store(isBluetoothMode, std::memory_order_relaxed);
    playbackSampleRate_.store(targetPlaybackSampleRate, std::memory_order_relaxed);

    const int32_t effectiveInputDeviceId = isBluetoothMode ? AAUDIO_UNSPECIFIED : inputDeviceId;

    requestedInputDeviceId_.store(effectiveInputDeviceId, std::memory_order_release);
    requestedOutputDeviceId_.store(outputDeviceId, std::memory_order_release);

    const aaudio_result_t res = openPlaybackStreamWithFallback(
        targetPlaybackSampleRate, outputDeviceId, isBluetoothMode);

    if (res != AAUDIO_OK || playbackStream_ == nullptr) {
        LOGE("Failed to open playback stream: %d (%s)", res, AAudio_convertResultToText(res));
        closePlaybackStreamLocked();
        engineState_.store(EngineState::IDLE, std::memory_order_release);
        return false;
    }

    if (!validateAndPublishPlaybackConfigLocked(outputDeviceId)) {
        engineState_.store(EngineState::IDLE, std::memory_order_release);
        return false;
    }

    const int32_t verifiedPlaybackRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);
    voiceEnhancer_.reset(verifiedPlaybackRate);
    genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, verifiedPlaybackRate);

    // УСТРАНЕНИЕ XRUN / ТРЕСКА: Безопасный размер буфера в 4 бёрста (~16-24 мс).
    // Полностью устраняет аппаратные опустошения буфера при микропаузах планировщика Linux CFS.
    const int32_t playBurst = AAudioStream_getFramesPerBurst(playbackStream_);
    const int32_t playCapacity = AAudioStream_getBufferCapacityInFrames(playbackStream_);
    if (playBurst > 0 && playCapacity > 0) {
        const int32_t targetBufSize = std::clamp(playBurst * 4, playBurst * 2, playCapacity);
        AAudioStream_setBufferSizeInFrames(playbackStream_, targetBufSize);
    }

    engineState_.store(EngineState::IDLE, std::memory_order_release);
    return true;
}

aaudio_result_t AAudioEngine::openPlaybackStreamWithFallback(
    int32_t targetPlaybackSampleRate,
    int32_t outputDeviceId,
    bool isBluetooth) {

    // УСТРАНЕНИЕ САМОПЕРЕБИВАНИЯ (AEC): Режим SHARED обязателен для голосовой связи.
    // Режим EXCLUSIVE MMAP обходит AudioFlinger и лишает Qualcomm Hexagon DSP опорного
    // сигнала петли ЦАП (Echo Reference), полностью отключая аппаратный эхоподавитель Fluence.
    const aaudio_sharing_mode_t desiredSharing = AAUDIO_SHARING_MODE_SHARED;

    // Маршрут вывода задаёт setCommunicationDevice(); привязка к порту не нужна
    (void)outputDeviceId;

    auto buildAndOpen = [this, targetPlaybackSampleRate, isBluetooth](aaudio_sharing_mode_t sharingMode) -> aaudio_result_t {
        AAudioStreamBuilder* outBuilder = nullptr;
        if (AAudio_createStreamBuilder(&outBuilder) != AAUDIO_OK) return AAUDIO_ERROR_INTERNAL;

        AAudioStreamBuilder_setDirection(outBuilder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(outBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setChannelCount(outBuilder, CHANNEL_COUNT_MONO);
        AAudioStreamBuilder_setFormat(outBuilder, AAUDIO_FORMAT_PCM_I16);

        if (isBluetooth && targetPlaybackSampleRate <= 0) {
            AAudioStreamBuilder_setSampleRate(outBuilder, AAUDIO_UNSPECIFIED);
        } else {
            AAudioStreamBuilder_setSampleRate(outBuilder, targetPlaybackSampleRate);
        }

        AAudioStreamBuilder_setSharingMode(outBuilder, sharingMode);
        // Режим связи → голосовой канал (AEC-эталон, HFP); Hi-Fi Bluetooth → медиа (A2DP)
        AAudioStreamBuilder_setUsage(outBuilder, mediaPlaybackUsage_.load(std::memory_order_acquire)
            ? AAUDIO_USAGE_MEDIA
            : AAUDIO_USAGE_VOICE_COMMUNICATION);
        AAudioStreamBuilder_setContentType(outBuilder, AAUDIO_CONTENT_TYPE_SPEECH);
        // Legacy-путь (VOIP_RX в режиме связи): эталон эха для аппаратного AEC гарантирован
        AAudioStreamBuilder_setSessionId(outBuilder, AAUDIO_SESSION_ID_ALLOCATE);
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

    engineState_.store(EngineState::STARTING, std::memory_order_release);

    if (!playbackStream_) {
        const aaudio_result_t reopen = openPlaybackStreamWithFallback(
            playbackSampleRate_.load(std::memory_order_acquire),
            requestedOutputDeviceId_.load(std::memory_order_acquire),
            isBluetoothMode_.load(std::memory_order_acquire));
        if (reopen != AAUDIO_OK || playbackStream_ == nullptr ||
            !validateAndPublishPlaybackConfigLocked(requestedOutputDeviceId_.load(std::memory_order_acquire))) {
            closePlaybackStreamLocked();
            engineState_.store(EngineState::IDLE, std::memory_order_release);
            return false;
        }
        const int32_t reopenedRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);
        voiceEnhancer_.reset(reopenedRate);
        genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, reopenedRate);
    }

    {
        std::scoped_lock lock(playbackControlMutex_);
        halfbandResampler24To48_.reset();
        resampler24To16_.reset();
        resampler24To32_.reset();
        genericResampler_.reset();
        smoothedRateFactor_ = 1.0f;
    }

    {
        std::lock_guard<std::mutex> producerLock(playbackProducerMutex_);
        playbackDspInputBuffer_.resetQuiesced();
        playbackBuffer_.resetQuiesced();
        fftTapBuffer_.resetQuiesced();
        playbackFlushInputMark_.store(0, std::memory_order_relaxed);
    }
    playbackPaused_.store(false, std::memory_order_release);
    playbackPrimeRequested_.store(true, std::memory_order_release);
    outputEqDirty_.store(true, std::memory_order_release);

    const aaudio_result_t result = AAudioStream_requestStart(playbackStream_);
    if (result != AAUDIO_OK || !waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_STARTED, 2000)) {
        closePlaybackStreamLocked();
        pushErrorEvent(AAUDIO_DIRECTION_OUTPUT, result != AAUDIO_OK ? result : AAUDIO_ERROR_TIMEOUT);
        engineState_.store(EngineState::RECOVERING, std::memory_order_release);
        return false;
    }

    joinPlaybackDspThreadLocked();
    joinFftTapThreadLocked();

    playbackDspRunning_.store(true, std::memory_order_release);
    fftTapRunning_.store(true, std::memory_order_release);

    try {
        playbackDspThread_ = std::thread(&AAudioEngine::playbackDspThreadLoop, this);
        fftTapThread_ = std::thread(&AAudioEngine::fftTapThreadLoop, this);
    } catch (...) {
        playbackDspRunning_.store(false, std::memory_order_release);
        fftTapRunning_.store(false, std::memory_order_release);
        playbackDspCv_.notify_all();
        fftTapCv_.notify_all();
        joinPlaybackDspThreadLocked();
        joinFftTapThreadLocked();
        if (playbackStream_) closePlaybackStreamLocked();
        pushErrorEvent(AAUDIO_DIRECTION_OUTPUT, AAUDIO_ERROR_INTERNAL);
        engineState_.store(EngineState::RECOVERING, std::memory_order_release);
        return false;
    }

    engineState_.store(EngineState::RUNNING, std::memory_order_release);
    return true;
}

bool AAudioEngine::startCapture() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);

    if (captureDspRunning_.load(std::memory_order_acquire)) {
        return true;
    }

    isDisconnectedExplicit_.store(false, std::memory_order_release);
    micPipelineAdmitted_.store(false, std::memory_order_release);

    joinCaptureDspThreadLocked();

    if (captureStream_) {
        const aaudio_stream_state_t state = AAudioStream_getState(captureStream_);
        if (state != AAUDIO_STREAM_STATE_OPEN && state != AAUDIO_STREAM_STATE_STOPPED) {
            AAudioStream_requestStop(captureStream_);
            waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STOPPED, 500);
        }
        closeCaptureStreamLocked();
    }

    if (!openCaptureStreamLocked(requestedInputDeviceId_.load(std::memory_order_acquire))) {
        LOGE("startCapture: failed to open capture stream");
        micPipelineAdmitted_.store(false, std::memory_order_release);
        return false;
    }

    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    unifiedCaptureResampler_.reset();

    const aaudio_result_t result = AAudioStream_requestStart(captureStream_);
    if (result != AAUDIO_OK || !waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STARTED, 2000)) {
        closeCaptureStreamLocked();
        micPipelineAdmitted_.store(false, std::memory_order_release);
        pushErrorEvent(AAUDIO_DIRECTION_INPUT, result != AAUDIO_OK ? result : AAUDIO_ERROR_TIMEOUT);
        return false;
    }

    const int32_t actualDeviceId = AAudioStream_getDeviceId(captureStream_);
    actualInputDeviceId_.store(actualDeviceId, std::memory_order_release);

    engineState_.store(EngineState::RUNNING, std::memory_order_release);
    return true;
}

bool AAudioEngine::activateCaptureDsp() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);

    if (captureStream_ == nullptr || AAudioStream_getState(captureStream_) != AAUDIO_STREAM_STATE_STARTED) {
        return false;
    }

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
        closeCaptureStreamLocked();
        micPipelineAdmitted_.store(false, std::memory_order_release);
        pushErrorEvent(AAUDIO_DIRECTION_INPUT, AAUDIO_ERROR_INTERNAL);
        return false;
    }

    captureDspCv_.notify_all();
    return true;
}

bool AAudioEngine::commitCaptureAdmission() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    if (captureStream_ == nullptr || AAudioStream_getState(captureStream_) != AAUDIO_STREAM_STATE_STARTED) {
        return false;
    }
    if (!captureDspRunning_.load(std::memory_order_acquire)) {
        return false;
    }

    micPipelineAdmitted_.store(true, std::memory_order_release);
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

bool AAudioEngine::restartCaptureStream() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopCaptureLocked();

    if (!openCaptureStreamLocked(requestedInputDeviceId_.load(std::memory_order_acquire))) {
        return false;
    }

    const aaudio_result_t result = AAudioStream_requestStart(captureStream_);
    if (result != AAUDIO_OK || !waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STARTED, 2000)) {
        closeCaptureStreamLocked();
        return false;
    }

    captureDspRunning_.store(true, std::memory_order_release);
    try {
        captureDspThread_ = std::thread(&AAudioEngine::captureDspThreadLoop, this);
    } catch (...) {
        captureDspRunning_.store(false, std::memory_order_release);
        closeCaptureStreamLocked();
        return false;
    }

    micPipelineAdmitted_.store(true, std::memory_order_release);
    captureDspCv_.notify_all();
    engineState_.store(EngineState::RUNNING, std::memory_order_release);
    return true;
}

bool AAudioEngine::restartPlaybackStream() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopPlaybackLocked();

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
    playbackPaused_.store(false, std::memory_order_release);
    playbackPrimeRequested_.store(true, std::memory_order_release);
    outputEqDirty_.store(true, std::memory_order_release);

    const aaudio_result_t result = AAudioStream_requestStart(playbackStream_);
    if (result != AAUDIO_OK || !waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_STARTED, 2000)) {
        closePlaybackStreamLocked();
        return false;
    }

    playbackDspRunning_.store(true, std::memory_order_release);
    fftTapRunning_.store(true, std::memory_order_release);
    try {
        playbackDspThread_ = std::thread(&AAudioEngine::playbackDspThreadLoop, this);
        fftTapThread_ = std::thread(&AAudioEngine::fftTapThreadLoop, this);
    } catch (...) {
        playbackDspRunning_.store(false, std::memory_order_release);
        fftTapRunning_.store(false, std::memory_order_release);
        playbackDspCv_.notify_all();
        fftTapCv_.notify_all();
        joinPlaybackDspThreadLocked();
        joinFftTapThreadLocked();
        closePlaybackStreamLocked();
        return false;
    }

    engineState_.store(EngineState::RUNNING, std::memory_order_release);
    return true;
}

void AAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopLocked();
}

void AAudioEngine::stopCaptureLocked() {
    micPipelineAdmitted_.store(false, std::memory_order_release);
    captureDspRunning_.store(false, std::memory_order_release);
    captureDspCv_.notify_all();
    captureReadyCv_.notify_all();

    joinCaptureDspThreadLocked();

    if (captureStream_) {
        AAudioStream_requestStop(captureStream_);
        waitForStreamState(captureStream_, AAUDIO_STREAM_STATE_STOPPED, 500);
        closeCaptureStreamLocked();
    }

    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    unifiedCaptureResampler_.reset();

    micRms_.store(0.0f, std::memory_order_relaxed);
    actualInputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_release);
}

void AAudioEngine::stopPlaybackLocked() {
    playbackDspRunning_.store(false, std::memory_order_release);
    fftTapRunning_.store(false, std::memory_order_release);
    playbackDspCv_.notify_all();
    fftTapCv_.notify_all();

    joinPlaybackDspThreadLocked();
    joinFftTapThreadLocked();

    if (playbackStream_) {
        AAudioStream_requestStop(playbackStream_);
        waitForStreamState(playbackStream_, AAUDIO_STREAM_STATE_STOPPED, 500);
        closePlaybackStreamLocked();
    }

    {
        std::lock_guard<std::mutex> producerLock(playbackProducerMutex_);
        playbackDspInputBuffer_.resetQuiesced();
        playbackBuffer_.resetQuiesced();
        fftTapBuffer_.resetQuiesced();
        playbackFlushInputMark_.store(0, std::memory_order_relaxed);
    }
    lastHwFramePosition_.store(0, std::memory_order_relaxed);
    lastHwFramesWritten_.store(0, std::memory_order_relaxed);
    outRms_.store(0.0f, std::memory_order_relaxed);
    isPlaybackRenderingActive_.store(false, std::memory_order_release);
    playbackPaused_.store(false, std::memory_order_release);
    playbackPrimeRequested_.store(true, std::memory_order_release);
}

void AAudioEngine::stopLocked() {
    engineState_.store(EngineState::STOPPING, std::memory_order_release);

    stopCaptureLocked();
    stopPlaybackLocked();

    {
        std::scoped_lock lock(playbackControlMutex_);
        resampler24To16_.reset();
        resampler24To32_.reset();
        halfbandResampler24To48_.reset();
        genericResampler_.reset();
        unifiedCaptureResampler_.reset();
        resetEarcon();
        voiceEnhancer_.reset(48000);
        smoothedRateFactor_ = 1.0f;
    }

    captureRawBuffer_.resetQuiesced();
    captureBuffer_.resetQuiesced();
    playbackDspInputBuffer_.resetQuiesced();
    playbackBuffer_.resetQuiesced();
    fftTapBuffer_.resetQuiesced();

    micRms_.store(0.0f, std::memory_order_relaxed);
    outRms_.store(0.0f, std::memory_order_relaxed);
    isMmapActive_.store(false, std::memory_order_relaxed);
    isExclusiveSharingActive_.store(false, std::memory_order_relaxed);
    isDisconnectedExplicit_.store(false, std::memory_order_relaxed);
    actualPlaybackChannels_.store(0, std::memory_order_relaxed);
    actualPlaybackFormat_.store(0, std::memory_order_relaxed);
    actualPlaybackSampleRate_.store(0, std::memory_order_relaxed);
    actualPlaybackBurst_.store(0, std::memory_order_relaxed);
    actualOutputDeviceId_.store(AAUDIO_UNSPECIFIED, std::memory_order_relaxed);
    lastPlaybackWriteNs_.store(0, std::memory_order_relaxed);
    interArrivalJitterNs_.store(0, std::memory_order_relaxed);
    playbackTargetBufferMs_.store(PLAYBACK_TARGET_BUFFER_MS, std::memory_order_relaxed);
    isPlaybackRenderingActive_.store(false, std::memory_order_release);

    lastTunedXRunCount_.store(0, std::memory_order_relaxed);

    engineState_.store(EngineState::IDLE, std::memory_order_release);
}

void AAudioEngine::stopCapture() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopCaptureLocked();
    if (!playbackDspRunning_.load(std::memory_order_acquire)) {
        engineState_.store(EngineState::IDLE, std::memory_order_release);
    }
}

StreamFaultType AAudioEngine::classifyAaudioError(aaudio_result_t errorCode) {
    switch (errorCode) {
        case AAUDIO_ERROR_DISCONNECTED:
        case AAUDIO_ERROR_NO_SERVICE:
            return StreamFaultType::HARD_DISCONNECTED;
        case AAUDIO_ERROR_TIMEOUT:
            return StreamFaultType::SOFT_TIMEOUT;
        case AAUDIO_ERROR_INVALID_STATE:
            return StreamFaultType::INVALID_STATE;
        default:
            return StreamFaultType::SYSTEM_ERROR;
    }
}

void AAudioEngine::recordErrorHistogram(aaudio_result_t errorCode) {
    size_t bucketIdx = 0;
    switch (errorCode) {
        case AAUDIO_ERROR_DISCONNECTED: bucketIdx = 1; break;
        case AAUDIO_ERROR_NO_SERVICE: bucketIdx = 2; break;
        case AAUDIO_ERROR_TIMEOUT: bucketIdx = 3; break;
        case AAUDIO_ERROR_INVALID_STATE: bucketIdx = 4; break;
        case AAUDIO_ERROR_INTERNAL: bucketIdx = 5; break;
        case AAUDIO_ERROR_UNAVAILABLE: bucketIdx = 6; break;
        case AAUDIO_ERROR_UNIMPLEMENTED: bucketIdx = 7; break;
        case AAUDIO_ERROR_OUT_OF_RANGE: bucketIdx = 8; break;
        case AAUDIO_ERROR_INVALID_HANDLE: bucketIdx = 9; break;
        default: bucketIdx = 10; break;
    }
    if (bucketIdx < ERROR_HISTOGRAM_BUCKETS) {
        errorHistogram_[bucketIdx].fetch_add(1, std::memory_order_relaxed);
    }
}

void AAudioEngine::pushErrorEvent(int32_t direction, aaudio_result_t errorCode) {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    const uint64_t nowNs = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);

    const StreamFaultType faultType = classifyAaudioError(errorCode);
    recordErrorHistogram(errorCode);

    if (direction == AAUDIO_DIRECTION_INPUT) {
        captureErrorCount_.fetch_add(1, std::memory_order_relaxed);
    } else if (direction == AAUDIO_DIRECTION_OUTPUT) {
        playbackErrorCount_.fetch_add(1, std::memory_order_relaxed);
    }

    if (faultType == StreamFaultType::HARD_DISCONNECTED) {
        streamDisconnectCount_.fetch_add(1, std::memory_order_relaxed);
    }

    StreamErrorEvent evt{
        // Контракт с Kotlin (drainAndDispatchNativeErrors): 1 = захват, 2 = вывод
        .direction = (direction == AAUDIO_DIRECTION_INPUT) ? 1 : 2,
        .errorCode = errorCode,
        .faultType = faultType,
        .timestampNs = nowNs
    };

    {
        std::lock_guard<std::mutex> lock(errorQueueMutex_);
        if (errorEventQueue_.size() >= 32) {
            errorEventQueue_.pop();
        }
        errorEventQueue_.push(evt);
        errorEventPending_.store(true, std::memory_order_release);
    }

    isDisconnectedExplicit_.store(true, std::memory_order_release);
    engineState_.store(EngineState::RECOVERING, std::memory_order_release);
}

bool AAudioEngine::pollErrorEvent(StreamErrorEvent& outEvent) {
    std::lock_guard<std::mutex> lock(errorQueueMutex_);
    if (errorEventQueue_.empty()) {
        errorEventPending_.store(false, std::memory_order_release);
        return false;
    }

    outEvent = errorEventQueue_.front();
    errorEventQueue_.pop();
    errorEventPending_.store(!errorEventQueue_.empty(), std::memory_order_release);
    return true;
}

void AAudioEngine::getAudioDiagnostics(AudioPipelineDiagnostics& outDiagnostics) {
    outDiagnostics.totalHardwareCapturedFrames = totalHardwareCapturedFrames_.load(std::memory_order_relaxed);
    outDiagnostics.totalDspProcessedFrames = totalDspProcessedFrames_.load(std::memory_order_relaxed);
    outDiagnostics.captureDroppedFrames = captureDroppedFrames_.load(std::memory_order_relaxed);
    outDiagnostics.totalHardwarePlaybackFrames = totalHardwarePlaybackFrames_.load(std::memory_order_relaxed);
    outDiagnostics.playbackUnderrunFrames = playbackUnderrunFrames_.load(std::memory_order_relaxed);
    outDiagnostics.playbackUnderrunCount = playbackUnderrunCount_.load(std::memory_order_relaxed);
    outDiagnostics.playbackDroppedFrames = playbackDroppedFrames_.load(std::memory_order_relaxed);
    outDiagnostics.streamDisconnectCount = streamDisconnectCount_.load(std::memory_order_relaxed);
    outDiagnostics.captureErrorCount = captureErrorCount_.load(std::memory_order_relaxed);
    outDiagnostics.playbackErrorCount = playbackErrorCount_.load(std::memory_order_relaxed);
    outDiagnostics.lastXRunCount = lastXRunCount_.load(std::memory_order_relaxed);
}

void AAudioEngine::getErrorHistogram(uint32_t* outArray, size_t arraySize) {
    if (outArray == nullptr || arraySize == 0) return;
    const size_t count = std::min(arraySize, ERROR_HISTOGRAM_BUCKETS);
    for (size_t i = 0; i < count; ++i) {
        outArray[i] = errorHistogram_[i].load(std::memory_order_relaxed);
    }
}

void AAudioEngine::setPlaybackActiveState(bool isActive) {
    isPlaybackRenderingActive_.store(isActive, std::memory_order_release);
}

size_t AAudioEngine::getPendingPlaybackInputFrames() const {
    return playbackDspInputBuffer_.availableRead();
}

size_t AAudioEngine::getPendingPlaybackOutputFrames() const {
    return playbackBuffer_.availableRead();
}

size_t AAudioEngine::getPendingPlaybackFrames() const {
    const int32_t outRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_relaxed));
    const size_t inFrames = playbackDspInputBuffer_.availableRead();
    const size_t outFrames = playbackBuffer_.availableRead();

    const size_t normalizedInFrames = static_cast<size_t>(
        static_cast<double>(inFrames) * static_cast<double>(outRate) / static_cast<double>(SAMPLE_RATE_GEMINI_OUT)
    );
    return normalizedInFrames + outFrames;
}

float AAudioEngine::getPendingPlaybackDurationMs() const {
    const int32_t outRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_relaxed));
    const size_t inFrames = playbackDspInputBuffer_.availableRead();
    const size_t outFrames = playbackBuffer_.availableRead();

    const float inMs = (static_cast<float>(inFrames) * 1000.0f) / static_cast<float>(SAMPLE_RATE_GEMINI_OUT);
    const float outMs = (static_cast<float>(outFrames) * 1000.0f) / static_cast<float>(outRate);
    return inMs + outMs;
}

float AAudioEngine::getTotalEstimatedPlaybackLatencyMs() const {
    const float userQueueMs = getPendingPlaybackDurationMs();
    float halLatencyMs = ESTIMATED_HAL_SPEAKER_LATENCY_MS;

    if (isBluetoothMode_.load(std::memory_order_relaxed)) {
        const int32_t outRate = actualPlaybackSampleRate_.load(std::memory_order_relaxed);
        if (outRate == SAMPLE_RATE_BT_HFP) {
            halLatencyMs = ESTIMATED_HAL_BT_SCO_LATENCY_MS;
        } else {
            halLatencyMs = ESTIMATED_HAL_BT_BLE_LATENCY_MS;
        }
    }

    // Только значения, опубликованные колбэком: никаких обращений к AAudioStream* вне RT-колбэка
    if (activePlaybackStream_.load(std::memory_order_acquire) != nullptr) {
        const int64_t framePosition = lastHwFramePosition_.load(std::memory_order_relaxed);
        const int64_t framesWritten = lastHwFramesWritten_.load(std::memory_order_relaxed);
        const int32_t rate = actualPlaybackSampleRate_.load(std::memory_order_relaxed);
        if (framePosition > 0 && framesWritten > framePosition && rate > 0) {
            const float dacBufferMs = static_cast<float>(framesWritten - framePosition) * 1000.0f / static_cast<float>(rate);
            return userQueueMs + std::max(dacBufferMs, halLatencyMs);
        }
    }

    return userQueueMs + halLatencyMs;
}

void AAudioEngine::captureDspThreadLoop() {
    pthread_setname_np(pthread_self(), "AudioCapWorker");
    dsp::FtzGuard ftzGuard;

    try {
        while (captureDspRunning_.load(std::memory_order_acquire)) {
            const int32_t channels = actualCaptureChannels_.load(std::memory_order_relaxed);
            const size_t ch = static_cast<size_t>(channels > 0 ? channels : 1);

            size_t availableSamples = captureRawBuffer_.availableRead();
            availableSamples -= availableSamples % ch;

            if (availableSamples == 0) {
                std::unique_lock<std::mutex> lock(captureDspWaitMutex_);
                captureDspCv_.wait(lock, [this]() {
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

            if (channels == 2) {
                dsp::stereoToMonoWithGain(inPtr, monoBuf, chunkFrames, gain);
            } else {
                dsp::applyGainInPlace(inPtr, monoBuf, chunkFrames, gain);
            }

            const size_t decimateScratchCap = captureDecimateBuffer_.size();
            int16_t* decBuf = captureDecimateBuffer_.data();

            const size_t finalFrames = unifiedCaptureResampler_.process(
                monoBuf, chunkFrames, decBuf, decimateScratchCap
            );
            const int16_t* finalPcm = decBuf;

            if (finalFrames > 0) {
                totalDspProcessedFrames_.fetch_add(finalFrames, std::memory_order_relaxed);
                const float currentMicRms = dsp::calculateRms(finalPcm, finalFrames);
                micRms_.store(currentMicRms, std::memory_order_relaxed);

                float currentFloor = micNoiseFloorRms_.load(std::memory_order_relaxed);
                if (currentMicRms < currentFloor) {
                    currentFloor = currentFloor * 0.95f + currentMicRms * 0.05f;
                } else {
                    currentFloor = currentFloor * NOISE_FLOOR_DECAY_COEFF + currentMicRms * NOISE_FLOOR_ATTACK_COEFF;
                }
                currentFloor = std::clamp(currentFloor, NOISE_FLOOR_MIN_RMS, NOISE_FLOOR_MAX_RMS);
                micNoiseFloorRms_.store(currentFloor, std::memory_order_relaxed);

                const size_t writtenFrames = captureBuffer_.writeAllOrNothing(finalPcm, finalFrames);
                if (writtenFrames == 0) {
                    captureDroppedFrames_.fetch_add(finalFrames, std::memory_order_relaxed);
                } else {
                    captureReadyCv_.notify_one();
                }
            }
        }
    } catch (const std::exception& e) {
        LOGE("AAudioEngine: capture DSP worker exception: %s", e.what());
        pushErrorEvent(AAUDIO_DIRECTION_INPUT, AAUDIO_ERROR_INTERNAL);
    } catch (...) {
        LOGE("AAudioEngine: capture DSP worker unknown exception");
        pushErrorEvent(AAUDIO_DIRECTION_INPUT, AAUDIO_ERROR_INTERNAL);
    }

    captureDspRunning_.store(false, std::memory_order_release);
    captureDspCv_.notify_all();
}

void AAudioEngine::fftTapThreadLoop() {
    pthread_setname_np(pthread_self(), "AudioFftTapWorker");
    dsp::FtzGuard ftzGuard;

    constexpr size_t ACCUM_SIZE = FFT_SIZE * 2;
    std::vector<float> accumBuf(ACCUM_SIZE, 0.0f);
    std::vector<int16_t> readScratch(512, 0);
    size_t accumPos = 0;

    try {
        while (fftTapRunning_.load(std::memory_order_acquire)) {
            const size_t available = fftTapBuffer_.availableRead();
            if (available == 0) {
                std::unique_lock<std::mutex> lock(fftTapWaitMutex_);
                fftTapCv_.wait(lock, [this]() {
                    return !fftTapRunning_.load(std::memory_order_acquire) ||
                           fftTapBuffer_.availableRead() > 0;
                });
                continue;
            }

            const size_t toRead = std::min(available, readScratch.size());
            const size_t read = fftTapBuffer_.read(readScratch.data(), toRead);
            if (read == 0) continue;

            const int32_t currentRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_relaxed));
            const float micRms = micRms_.load(std::memory_order_relaxed);
            const float outRmsVal = outRms_.load(std::memory_order_relaxed);
            const size_t requiredAccum = (currentRate >= 44100) ? (FFT_SIZE * 2) : FFT_SIZE;
            const size_t hopSize = (currentRate >= 44100) ? (FFT_HOP_SIZE * 2) : FFT_HOP_SIZE;

            for (size_t i = 0; i < read; ++i) {
                if (accumPos < accumBuf.size()) {
                    accumBuf[accumPos++] = static_cast<float>(readScratch[i]) * (1.0f / 32768.0f);
                }

                if (accumPos >= requiredAccum) {
                    if (fftProcessor_) {
                        fftProcessor_->process(accumBuf.data(), requiredAccum, micRms, outRmsVal, currentRate);
                    }
                    std::memmove(accumBuf.data(), accumBuf.data() + hopSize, (requiredAccum - hopSize) * sizeof(float));
                    accumPos = requiredAccum - hopSize;
                }
            }
        }
    } catch (...) {
        LOGE("AAudioEngine: FFT tap worker terminated with exception");
    }

    fftTapRunning_.store(false, std::memory_order_release);
}

void AAudioEngine::playbackDspThreadLoop() {
    pthread_setname_np(pthread_self(), "AudioDspWorker");
    dsp::FtzGuard ftzGuard;

    try {
        int16_t* input = playbackDspInputScratch_.data();
        int16_t* output = playbackDspOutputScratch_.data();
        int16_t* earconBuf = earconScratch_.data();
        uint64_t workerDspEpoch = playbackEpoch_.load(std::memory_order_acquire);

        while (playbackDspRunning_.load(std::memory_order_acquire)) {
            if (outputEqDirty_.exchange(false, std::memory_order_acq_rel)) {
                voiceEnhancer_.configure(
                    std::max(1, actualPlaybackSampleRate_.load(std::memory_order_acquire)),
                    outputEqProfile_.load(std::memory_order_acquire));
            }

            const uint64_t activeEpoch = playbackEpoch_.load(std::memory_order_acquire);
            if (activeEpoch != workerDspEpoch) {
                const int32_t currentRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_acquire));
                voiceEnhancer_.reset(currentRate);
                halfbandResampler24To48_.reset();
                resampler24To16_.reset();
                resampler24To32_.reset();
                genericResampler_.reset();
                genericResampler_.configure(SAMPLE_RATE_GEMINI_OUT, currentRate);
                smoothedRateFactor_ = 1.0f;

                // Только устаревшее (до метки flush); новый ответ, записанный после flush, сохраняется
                playbackDspInputBuffer_.discardUpTo(playbackFlushInputMark_.load(std::memory_order_acquire));
                fftTapBuffer_.discardAllQuiesced();
                workerDspEpoch = activeEpoch;

                playbackDspResetAcknowledgedEpoch_.store(activeEpoch, std::memory_order_release);
                playbackDspCv_.notify_all();
            }

            const int32_t actualRate = std::max(1, actualPlaybackSampleRate_.load(std::memory_order_acquire));
            const int32_t actualBurst = actualPlaybackBurst_.load(std::memory_order_acquire);

            AAudioStream* playStream = activePlaybackStream_.load(std::memory_order_acquire);
            if (playStream != nullptr) {
                const int32_t currentXRun = lastXRunCount_.load(std::memory_order_relaxed);
                const int32_t prevTuned = lastTunedXRunCount_.load(std::memory_order_relaxed);
                if (currentXRun > prevTuned && prevTuned >= 0) {
                    lastTunedXRunCount_.store(currentXRun, std::memory_order_relaxed);
                    const int32_t burst = AAudioStream_getFramesPerBurst(playStream);
                    const int32_t capacity = AAudioStream_getBufferCapacityInFrames(playStream);
                    const int32_t currentBufSize = AAudioStream_getBufferSizeInFrames(playStream);
                    if (burst > 0 && currentBufSize < capacity) {
                        const int32_t tunedSize = std::min(currentBufSize + burst, capacity);
                        AAudioStream_setBufferSizeInFrames(playStream, tunedSize);
                    }
                }
            }

            const size_t currentTargetMs = playbackTargetBufferMs_.load(std::memory_order_relaxed);
            const size_t timeTargetFrames = static_cast<size_t>(
                static_cast<uint64_t>(actualRate) * currentTargetMs / 1000ULL);
            const size_t burstTargetFrames = (actualBurst > 0)
                ? static_cast<size_t>(actualBurst) * PLAYBACK_BURST_MIN_MULTIPLIER : 0U;
            const size_t targetBufferFrames = std::max<size_t>(1U, std::max(timeTargetFrames, burstTargetFrames));

            // УСТРАНЕНИЕ ФАЗОВОГО ТРЕСКА: Расширенное окно гистерезиса 25 мс (1200 фреймов @ 48 кГц).
            // Исключает постоянные осцилляции вокруг 1.0f и паразитные переключения КИХ-фильтров.
            const size_t hysteresisFrames = static_cast<size_t>(actualRate * 25 / 1000);
            const size_t highWatermarkFrames = targetBufferFrames + hysteresisFrames;
            const size_t lowWatermarkFrames = (targetBufferFrames > hysteresisFrames)
                ? (targetBufferFrames - hysteresisFrames) : (targetBufferFrames / 2);

            if (earconRequested_.load(std::memory_order_acquire)) {
                const size_t earconFrames = std::min<size_t>(
                    static_cast<size_t>(actualRate * (EARCON_DURATION_MS / 1000.0f)), earconScratch_.size());

                for (size_t i = 0; i < earconFrames; ++i) {
                    const float t = static_cast<float>(i) / static_cast<float>(actualRate);
                    const float env = std::cos((3.14159265f * static_cast<float>(i)) / (2.0f * static_cast<float>(earconFrames)));
                    const float sample = std::sin(2.0f * 3.14159265f * EARCON_FREQ_HZ * t) * env * env * 12000.0f;
                    earconBuf[i] = static_cast<int16_t>(std::clamp(sample, -32768.0f, 32767.0f));
                }

                if (playbackEpoch_.load(std::memory_order_acquire) == activeEpoch) {
                    const size_t written = playbackBuffer_.writeAllOrNothing(earconBuf, earconFrames);
                    if (written == earconFrames) earconRequested_.store(false, std::memory_order_release);
                }
            }

            const size_t maxOutputFrames = static_cast<size_t>(
                std::ceil(static_cast<double>(PLAYBACK_DSP_INPUT_CHUNK_FRAMES) *
                          static_cast<double>(actualRate) / static_cast<double>(SAMPLE_RATE_GEMINI_OUT) * 1.05));

            const size_t buffered = playbackBuffer_.availableRead();
            const size_t freeSpace = playbackBuffer_.availableWrite();

            if (buffered >= highWatermarkFrames || freeSpace < maxOutputFrames) {
                std::unique_lock<std::mutex> waitLock(playbackDspWaitMutex_);
                playbackDspCv_.wait_for(waitLock, std::chrono::milliseconds(5), [this, activeEpoch, lowWatermarkFrames, maxOutputFrames]() {
                    if (!playbackDspRunning_.load(std::memory_order_acquire)) return true;
                    if (playbackEpoch_.load(std::memory_order_acquire) != activeEpoch) return true;
                    return playbackBuffer_.availableRead() <= lowWatermarkFrames && playbackBuffer_.availableWrite() >= maxOutputFrames;
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

            // Поток Gemini приходит быстрее реального времени: подстройка скорости не нужна.
            // Прежний TSM давал плавание тона и щелчки при смене ресемплеров.
            smoothedRateFactor_ = 1.0f;
            const bool isNominalRate = true;

            size_t outputFrames = 0;
            if (isNominalRate && actualRate == SAMPLE_RATE_BT_LC3_24K) {
                outputFrames = inputFrames;
                std::memcpy(output, input, outputFrames * sizeof(int16_t));
            } else if (isNominalRate && (actualRate == SAMPLE_RATE_NATIVE_SPEAKER || actualRate == SAMPLE_RATE_BT_A2DP || actualRate == SAMPLE_RATE_BT_LC3_48K)) {
                outputFrames = halfbandResampler24To48_.process(input, inputFrames, output, playbackDspOutputScratch_.size());
            } else if (isNominalRate && actualRate == SAMPLE_RATE_BT_HFP) {
                outputFrames = resampler24To16_.process(input, inputFrames, output);
            } else if (isNominalRate && actualRate == SAMPLE_RATE_BT_LC3_32K) {
                outputFrames = resampler24To32_.process(input, inputFrames, output);
            } else {
                const int32_t effectiveInputRate = static_cast<int32_t>(std::round(SAMPLE_RATE_GEMINI_OUT * smoothedRateFactor_));
                genericResampler_.configure(effectiveInputRate, actualRate);
                outputFrames = genericResampler_.process(input, inputFrames, output, playbackDspOutputScratch_.size());
            }

            if (outputFrames == 0) continue;

            // Студийный звуковой процессор речи: мягкий срез инфразвука + True-Peak лимитер без клиппинга
            voiceEnhancer_.process(output, outputFrames, actualRate);

            const float volume = playbackVolume_.load(std::memory_order_relaxed);
            if (volume != 1.0f) {
                for (size_t i = 0; i < outputFrames; ++i) {
                    const float v = static_cast<float>(output[i]) * volume;
                    output[i] = static_cast<int16_t>(std::clamp(v, -32768.0f, 32767.0f));
                }
            }

            if (playbackDspRunning_.load(std::memory_order_acquire) &&
                playbackEpoch_.load(std::memory_order_acquire) == activeEpoch) {
                const size_t written = playbackBuffer_.writeAllOrNothing(output, outputFrames);
                if (written == 0) {
                    playbackDroppedFrames_.fetch_add(outputFrames, std::memory_order_relaxed);
                } else {
                    fftTapBuffer_.write(output, outputFrames);
                    fftTapCv_.notify_one();
                }
            }
        }
    } catch (const std::exception& e) {
        LOGE("AAudioEngine: playback DSP worker exception: %s", e.what());
        pushErrorEvent(AAUDIO_DIRECTION_OUTPUT, AAUDIO_ERROR_INTERNAL);
    } catch (...) {
        LOGE("AAudioEngine: playback DSP worker unknown exception");
        pushErrorEvent(AAUDIO_DIRECTION_OUTPUT, AAUDIO_ERROR_INTERNAL);
    }

    playbackDspRunning_.store(false, std::memory_order_release);
    playbackDspCv_.notify_all();
}

size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames, uint64_t generation) {
    if (pcm == nullptr || frames == 0 || generation == 0) return 0;
    // Один продюсер за раз + проверка эпохи и запись атомарны относительно flushPlayback()
    std::lock_guard<std::mutex> producerLock(playbackProducerMutex_);
    if (!playbackDspRunning_.load(std::memory_order_acquire)) return 0;

    const uint64_t activeEpoch = playbackEpoch_.load(std::memory_order_acquire);
    if (generation != activeEpoch) {
        playbackDroppedFrames_.fetch_add(frames, std::memory_order_relaxed);
        return 0;
    }

    isPlaybackRenderingActive_.store(true, std::memory_order_release);

    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    const uint64_t nowNs = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
    const uint64_t lastWrite = lastPlaybackWriteNs_.exchange(nowNs, std::memory_order_relaxed);

    if (lastWrite > 0 && nowNs > lastWrite) {
        const int64_t deltaNs = static_cast<int64_t>(nowNs - lastWrite);
        const int64_t nominalNs = static_cast<int64_t>(frames) * 1000000000LL / SAMPLE_RATE_GEMINI_OUT;
        const int64_t diffNs = std::abs(deltaNs - nominalNs);

        int64_t jitter = interArrivalJitterNs_.load(std::memory_order_relaxed);
        jitter += (diffNs - jitter) / 16;
        interArrivalJitterNs_.store(jitter, std::memory_order_relaxed);

        const size_t dynamicTargetMs = static_cast<size_t>(
            std::clamp<int64_t>((jitter / 1000000LL) * 2 + 35,
                                static_cast<int64_t>(PLAYBACK_TARGET_BUFFER_SPEAKER_MIN_MS),
                                static_cast<int64_t>(PLAYBACK_TARGET_BUFFER_BT_MAX_MS))
        );
        playbackTargetBufferMs_.store(dynamicTargetMs, std::memory_order_relaxed);
    }

    const size_t written = playbackDspInputBuffer_.write(pcm, frames);
    if (written == 0) {
        playbackDroppedFrames_.fetch_add(frames, std::memory_order_relaxed);
    } else {
        playbackDspCv_.notify_one();
    }
    return written;
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    if (pcm == nullptr || maxFrames == 0) return 0;
    // Только целые кадры: частичное чтение + нулевой паддинг в Kotlin ломали VAD
    if (captureBuffer_.availableRead() < maxFrames) return 0;
    return captureBuffer_.read(pcm, maxFrames);
}

void AAudioEngine::flushPlayback(uint64_t generation) {
    if (generation == 0) return;
    std::lock_guard<std::mutex> producerLock(playbackProducerMutex_);

    const uint64_t currentEpoch = playbackEpoch_.load(std::memory_order_acquire);
    if (generation <= currentEpoch) return;

    const size_t pendingInput = playbackDspInputBuffer_.availableRead();
    const size_t pendingOutput = playbackBuffer_.availableRead();
    playbackDroppedFrames_.fetch_add(pendingInput + pendingOutput, std::memory_order_relaxed);

    playbackFlushInputMark_.store(playbackDspInputBuffer_.tailPosition(), std::memory_order_relaxed);
    playbackEpoch_.store(generation, std::memory_order_release);
    isPlaybackRenderingActive_.store(false, std::memory_order_release);
    playbackPaused_.store(false, std::memory_order_release);
    playbackPrimeRequested_.store(true, std::memory_order_release);
    earconRequested_.store(false, std::memory_order_release);
    outRms_.store(0.0f, std::memory_order_relaxed);
    lastPlaybackWriteNs_.store(0, std::memory_order_relaxed);
    interArrivalJitterNs_.store(0, std::memory_order_relaxed);
    playbackTargetBufferMs_.store(PLAYBACK_TARGET_BUFFER_MS, std::memory_order_relaxed);

    playbackDspInputBuffer_.discardAllQuiesced();
    playbackBuffer_.discardAllQuiesced();
    fftTapBuffer_.discardAllQuiesced();

    playbackDspCv_.notify_all();
    fftTapCv_.notify_all();
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

void AAudioEngine::setPlaybackPaused(bool paused) {
    playbackPaused_.store(paused, std::memory_order_release);
    playbackDspCv_.notify_all();
}

void AAudioEngine::setOutputEqProfile(int32_t profile) {
    const int32_t clamped = std::clamp(profile, OUTPUT_EQ_SPEAKER, OUTPUT_EQ_HEADPHONES);
    if (outputEqProfile_.exchange(clamped, std::memory_order_acq_rel) != clamped) {
        outputEqDirty_.store(true, std::memory_order_release);
        playbackDspCv_.notify_all();
    }
}

bool AAudioEngine::waitForCaptureFrames(size_t frames, int32_t timeoutMs) {
    if (frames == 0) return true;
    if (captureBuffer_.availableRead() >= frames) return true;
    std::unique_lock<std::mutex> lock(captureReadyMutex_);
    captureReadyCv_.wait_for(lock, std::chrono::milliseconds(std::max(0, timeoutMs)), [this, frames]() {
        return captureBuffer_.availableRead() >= frames ||
               !captureDspRunning_.load(std::memory_order_acquire);
    });
    return captureBuffer_.availableRead() >= frames;
}

void AAudioEngine::getSpectrumData(dsp::SpectrumSnapshot& outSnapshot) {
    fftProcessor_->getLatestSnapshot(outSnapshot);
}

aaudio_data_callback_result_t AAudioEngine::captureCallback(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames) {

    if (userData == nullptr || audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);

    if (stream != nullptr) {
        const aaudio_stream_state_t st = AAudioStream_getState(stream);
        if (st == AAUDIO_STREAM_STATE_DISCONNECTED || st == AAUDIO_STREAM_STATE_CLOSING || st == AAUDIO_STREAM_STATE_CLOSED) {
            engine->pushErrorEvent(AAUDIO_DIRECTION_INPUT, AAUDIO_ERROR_DISCONNECTED);
            return AAUDIO_CALLBACK_RESULT_STOP;
        }
    }

    engine->totalHardwareCapturedFrames_.fetch_add(static_cast<uint64_t>(numFrames), std::memory_order_relaxed);

    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    const uint64_t nowNs = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
    engine->lastCaptureTimestampNs_.store(nowNs, std::memory_order_relaxed);

    engine->captureSequenceNumber_.fetch_add(1, std::memory_order_relaxed);

    if (!engine->micPipelineAdmitted_.load(std::memory_order_acquire)) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const auto* inSamples = static_cast<const int16_t*>(audioData);
    const int32_t channels = engine->actualCaptureChannels_.load(std::memory_order_relaxed);
    const size_t ch = static_cast<size_t>(channels > 0 ? channels : 1);

    const size_t samplesToWrite = static_cast<size_t>(numFrames) * ch;
    const size_t written = engine->captureRawBuffer_.writeAllOrNothing(inSamples, samplesToWrite);
    const size_t writtenFrames = written / ch;

    if (writtenFrames == static_cast<size_t>(numFrames)) {
        engine->captureDspCv_.notify_one();
    } else {
        engine->captureDroppedFrames_.fetch_add(static_cast<size_t>(numFrames), std::memory_order_relaxed);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AAudioEngine::playbackCallback(
    AAudioStream* stream,
    void* userData,
    void* audioData,
    int32_t numFrames) {

    if (userData == nullptr || audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);
    const size_t frames = static_cast<size_t>(numFrames);

    if (stream != nullptr) {
        const aaudio_stream_state_t st = AAudioStream_getState(stream);
        if (st == AAUDIO_STREAM_STATE_DISCONNECTED || st == AAUDIO_STREAM_STATE_CLOSING || st == AAUDIO_STREAM_STATE_CLOSED) {
            engine->pushErrorEvent(AAUDIO_DIRECTION_OUTPUT, AAUDIO_ERROR_DISCONNECTED);
            return AAUDIO_CALLBACK_RESULT_STOP;
        }
    }

    engine->totalHardwarePlaybackFrames_.fetch_add(static_cast<uint64_t>(numFrames), std::memory_order_relaxed);

    // Точная аппаратная привязка времени презентации звука
    int64_t framePos = 0;
    int64_t hwTimestampNs = 0;
    if (stream != nullptr && AAudioStream_getTimestamp(stream, CLOCK_BOOTTIME, &framePos, &hwTimestampNs) == AAUDIO_OK) {
        engine->lastPlaybackPresentationTimestampNs_.store(static_cast<uint64_t>(hwTimestampNs), std::memory_order_relaxed);
        engine->lastHwFramePosition_.store(framePos, std::memory_order_relaxed);
        engine->lastHwFramesWritten_.store(AAudioStream_getFramesWritten(stream), std::memory_order_relaxed);
    } else {
        timespec ts{};
        clock_gettime(CLOCK_BOOTTIME, &ts);
        const uint64_t nowNs = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
        engine->lastPlaybackPresentationTimestampNs_.store(nowNs, std::memory_order_relaxed);
    }

    engine->playbackSequenceNumber_.fetch_add(1, std::memory_order_relaxed);

    if (stream != nullptr) {
        const int32_t currentXRun = AAudioStream_getXRunCount(stream);
        const int32_t prevXRun = engine->lastXRunCount_.exchange(currentXRun, std::memory_order_relaxed);
        if (currentXRun > prevXRun && prevXRun >= 0) {
            const int32_t deltaXRun = currentXRun - prevXRun;
            engine->playbackUnderrunCount_.fetch_add(static_cast<uint64_t>(deltaXRun), std::memory_order_relaxed);
        }
    }

    const int32_t rate = std::max(1, engine->actualPlaybackSampleRate_.load(std::memory_order_relaxed));
    const size_t fadeFrames = std::max<size_t>(1, static_cast<size_t>(rate) * PLAYBACK_FADE_MS / 1000);
    const float rampStep = 1.0f / static_cast<float>(fadeFrames);

    if (engine->playbackPrimeRequested_.exchange(false, std::memory_order_acq_rel)) {
        engine->playbackPriming_ = true;
        engine->playbackRampGain_ = 0.0f;
    }

    const bool paused = engine->playbackPaused_.load(std::memory_order_acquire);

    // 1) Полная пауза: тишина, позиция очереди сохраняется (ничего не теряется)
    if (paused && engine->playbackRampGain_ <= 0.0f) {
        std::memset(samples, 0, frames * sizeof(int16_t));
        engine->outRms_.store(0.0f, std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // 2) Предзаполнение перед стартом фразы: убирает заикание первого слога при сетевом джиттере
    if (engine->playbackPriming_ && !paused) {
        const size_t buffered = engine->playbackBuffer_.availableRead();
        const size_t primeFrames = static_cast<size_t>(rate) * PLAYBACK_PRIME_MS / 1000;
        bool startNow = buffered >= primeFrames;
        if (!startNow && buffered > 0 && engine->playbackDspInputBuffer_.availableRead() == 0) {
            const uint64_t lastWrite = engine->lastPlaybackWriteNs_.load(std::memory_order_relaxed);
            timespec tsPrime{};
            clock_gettime(CLOCK_BOOTTIME, &tsPrime);
            const uint64_t nowPrimeNs = static_cast<uint64_t>(tsPrime.tv_sec) * 1000000000ULL +
                                        static_cast<uint64_t>(tsPrime.tv_nsec);
            startNow = lastWrite != 0 && nowPrimeNs > lastWrite &&
                nowPrimeNs - lastWrite >= static_cast<uint64_t>(PLAYBACK_PRIME_IDLE_MS) * 1000000ULL;
        }
        if (!startNow) {
            std::memset(samples, 0, frames * sizeof(int16_t));
            engine->outRms_.store(0.0f, std::memory_order_relaxed);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        engine->playbackPriming_ = false;
        engine->playbackRampGain_ = 0.0f;
    }

    // 3) При уходе в паузу читаем ровно столько, сколько занимает затухание: остальное остаётся в очереди
    size_t want = frames;
    if (paused) {
        const size_t rampFrames = static_cast<size_t>(std::ceil(engine->playbackRampGain_ / rampStep));
        want = std::min(frames, std::max<size_t>(1, rampFrames));
    }

    const size_t read = engine->playbackBuffer_.read(samples, want);
    if (read > 0) {
        engine->playbackDspCv_.notify_one();
    }

    float ramp = engine->playbackRampGain_;
    if ((paused && ramp > 0.0f) || (!paused && ramp < 1.0f)) {
        for (size_t i = 0; i < read; ++i) {
            ramp = paused ? std::max(0.0f, ramp - rampStep) : std::min(1.0f, ramp + rampStep);
            samples[i] = static_cast<int16_t>(std::lrintf(static_cast<float>(samples[i]) * ramp));
        }
        engine->playbackRampGain_ = ramp;
    }

    if (read < frames) {
        std::memset(samples + read, 0, (frames - read) * sizeof(int16_t));
        if (!paused) {
            // Опустошение: гасим хвост без щелчка и готовим предзаполнение следующей фразы
            const size_t tail = std::min(read, fadeFrames);
            for (size_t i = 0; i < tail; ++i) {
                const float g = static_cast<float>(tail - i) / static_cast<float>(tail + 1);
                const size_t idx = read - tail + i;
                samples[idx] = static_cast<int16_t>(std::lrintf(static_cast<float>(samples[idx]) * g));
            }
            engine->playbackPriming_ = true;
            engine->playbackRampGain_ = 0.0f;
            if (engine->isPlaybackRenderingActive_.load(std::memory_order_relaxed)) {
                engine->playbackUnderrunFrames_.fetch_add(frames - read, std::memory_order_relaxed);
                engine->playbackUnderrunCount_.fetch_add(1, std::memory_order_relaxed);
            }
        }
    }

    // Эталон эха: RMS фактически выданного в ЦАП кадра (с учётом фейдов и тишины)
    engine->outRms_.store(read > 0 ? dsp::calculateRms(samples, frames) : 0.0f, std::memory_order_relaxed);

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

    // AAUDIO_DIRECTION_OUTPUT == 0: прежняя проверка «!= 0» теряла все ошибки вывода
    bool matched = false;
    int32_t direction = AAUDIO_DIRECTION_OUTPUT;
    if (stream == activeCapture) {
        direction = AAUDIO_DIRECTION_INPUT;
        matched = true;
    } else if (stream == activePlayback) {
        direction = AAUDIO_DIRECTION_OUTPUT;
        matched = true;
    }

    if (matched) {
        LOGE("AAudioEngine::errorCallback invoked: stream=%p, direction=%d, error=%d (%s)",
             stream, direction, error, AAudio_convertResultToText(error));
        engine->pushErrorEvent(direction, error);
    }
}

} // namespace client::audio