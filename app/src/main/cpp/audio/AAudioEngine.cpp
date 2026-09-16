// >>> FILE: app/src/main/cpp/audio/AAudioEngine.cpp
#include "AAudioEngine.h"
#include "NativeLogQueue.h"
#include "dsp/NeonDspUtils.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>
#include <chrono>

#define LOG_TAG "NativeAudioEngine"

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

namespace {

struct Biquad {
    float b0{1.0f}, b1{0.0f}, b2{0.0f};
    float a1{0.0f}, a2{0.0f};
    float w1{0.0f}, w2{0.0f};

    void reset() {
        w1 = 0.0f;
        w2 = 0.0f;
    }

    inline float process(float in) {
        float w0 = in - a1 * w1 - a2 * w2;
        float out = b0 * w0 + b1 * w1 + b2 * w2;
        w2 = w1;
        w1 = w0;
        return out;
    }

    void makeLowShelf(float fc, float gainDb, float fs) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float omega = 2.0f * 3.14159265f * fc / fs;
        float sn = std::sin(omega);
        float cs = std::cos(omega);
        float alpha = sn / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 0.9f - 1.0f) + 2.0f);
        float beta = 2.0f * std::sqrt(A) * alpha;

        float a0 = (A + 1.0f) + (A - 1.0f) * cs + beta;
        b0 = (A * ((A + 1.0f) - (A - 1.0f) * cs + beta)) / a0;
        b1 = (2.0f * A * ((A - 1.0f) - (A + 1.0f) * cs)) / a0;
        b2 = (A * ((A + 1.0f) - (A - 1.0f) * cs - beta)) / a0;
        a1 = (-2.0f * ((A - 1.0f) + (A + 1.0f) * cs)) / a0;
        a2 = ((A + 1.0f) + (A - 1.0f) * cs - beta) / a0;
    }

    void makeHighShelf(float fc, float gainDb, float fs) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float omega = 2.0f * 3.14159265f * fc / fs;
        float sn = std::sin(omega);
        float cs = std::cos(omega);
        float alpha = sn / 2.0f * std::sqrt((A + 1.0f / A) * (1.0f / 0.9f - 1.0f) + 2.0f);
        float beta = 2.0f * std::sqrt(A) * alpha;

        float a0 = (A + 1.0f) - (A - 1.0f) * cs + beta;
        b0 = (A * ((A + 1.0f) + (A - 1.0f) * cs + beta)) / a0;
        b1 = (-2.0f * A * ((A - 1.0f) + (A + 1.0f) * cs)) / a0;
        b2 = (A * ((A + 1.0f) - (A - 1.0f) * cs - beta)) / a0;
        a1 = (2.0f * ((A - 1.0f) - (A + 1.0f) * cs)) / a0;
        a2 = ((A + 1.0f) - (A - 1.0f) * cs - beta) / a0;
    }
};

class AnalogVoiceEnhancer {
public:
    AnalogVoiceEnhancer() {
        reset(48000);
    }

    void reset(int32_t sampleRate) {
        currentRate_ = sampleRate;
        float fs = static_cast<float>(sampleRate);

        lowShelf_.reset();
        highShelf_.reset();

        lowShelf_.makeLowShelf(160.0f, 3.5f, fs);

        float highFc = std::min(5000.0f, fs * 0.44f);
        highShelf_.makeHighShelf(highFc, 3.2f, fs);
    }

    void process(int16_t* samples, size_t numFrames, int32_t sampleRate) {
        if (samples == nullptr || numFrames == 0) return;

        if (sampleRate != currentRate_ && sampleRate > 0) {
            reset(sampleRate);
        }

        constexpr float PRE_DRIVE = 1.48f;
        constexpr float INV_32768 = 1.0f / 32768.0f;

        for (size_t i = 0; i < numFrames; ++i) {
            float x = static_cast<float>(samples[i]) * INV_32768 * PRE_DRIVE;

            x = lowShelf_.process(x);
            x = highShelf_.process(x);

            x = x + 0.12f * (x * x);

            float absX = std::abs(x);
            float y = (absX < 1.0f)
                ? (x - 0.22f * x * x * x)
                : ((x > 0.0f ? 1.0f : -1.0f) * (0.78f + 0.22f * (1.0f - std::exp(-2.0f * (absX - 1.0f)))));

            int32_t outSample = static_cast<int32_t>(y * 32767.0f);
            samples[i] = static_cast<int16_t>(std::clamp(outSample, -32768, 32767));
        }
    }

private:
    int32_t currentRate_{48000};
    Biquad lowShelf_;
    Biquad highShelf_;
};

static AnalogVoiceEnhancer s_voiceEnhancer;

} // namespace

namespace client::audio {

AAudioEngine& AAudioEngine::getInstance() {
    static AAudioEngine instance;
    return instance;
}

AAudioEngine::AAudioEngine()
    : fftProcessor_(std::make_unique<dsp::FastFft>()),
      fftAccumulator_(FFT_SIZE * 2, 0.0f),
      resampleScratchBuffer_(RESAMPLE_SCRATCH_CAPACITY, 0),
      captureDecimateBuffer_(CAPTURE_DECIMATE_CAPACITY, 0),
      captureInputScratchBuffer_(CAPTURE_DECIMATE_CAPACITY, 0),
      resamplePendingBuffer_(RESAMPLE_SCRATCH_CAPACITY, 0),
      resamplePendingOffset_(0),
      resamplePendingCount_(0) {
    dsp::enableHardwareFtz();
}

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::init(bool isBluetoothMode, int32_t targetPlaybackSampleRate,
                        int32_t inputDeviceId, int32_t outputDeviceId) {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    return initLocked(isBluetoothMode, targetPlaybackSampleRate, inputDeviceId, outputDeviceId);
}

bool AAudioEngine::initLocked(bool isBluetoothMode, int32_t targetPlaybackSampleRate,
                              int32_t inputDeviceId, int32_t outputDeviceId) {
    stopLocked();
    isDisconnected_.store(false, std::memory_order_release);

    {
        std::lock_guard<std::mutex> pLock(playbackWriteMutex_);
        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        resamplePendingOffset_ = 0;
        resamplePendingCount_ = 0;
        resetEarcon();
        s_voiceEnhancer.reset(targetPlaybackSampleRate);
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    isBluetoothMode_.store(isBluetoothMode, std::memory_order_relaxed);
    playbackSampleRate_.store(targetPlaybackSampleRate, std::memory_order_relaxed);

    LOGI("AAudioEngine::initLocked: BT=%d, targetRate=%d, inDevId=%d, outDevId=%d", 
         (int)isBluetoothMode, targetPlaybackSampleRate, inputDeviceId, outputDeviceId);

    // ─────────────────────────────────────────────────────────────
    // 1. КОНФИГУРАЦИЯ ПОТОКА ЗАХВАТА (МИКРОФОН)
    // ─────────────────────────────────────────────────────────────
    AAudioStreamBuilder* inBuilder = nullptr;
    if (AAudio_createStreamBuilder(&inBuilder) != AAUDIO_OK) {
        LOGE("Failed to create capture stream builder");
        return false;
    }

    AAudioStreamBuilder_setDirection(inBuilder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(inBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(inBuilder, SAMPLE_RATE_GEMINI_IN);
    AAudioStreamBuilder_setChannelCount(inBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(inBuilder, AAUDIO_FORMAT_PCM_I16);

    if (inputDeviceId > 0) {
        AAudioStreamBuilder_setDeviceId(inBuilder, inputDeviceId);
    }

    AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);

    AAudioStreamBuilder_setDataCallback(inBuilder, captureCallback, this);
    AAudioStreamBuilder_setErrorCallback(inBuilder, errorCallback, this);

    aaudio_result_t res = AAudioStreamBuilder_openStream(inBuilder, &captureStream_);
    AAudioStreamBuilder_delete(inBuilder);

    if (res != AAUDIO_OK) {
        LOGE("Failed to open capture stream: %d (%s)", res, AAudio_convertResultToText(res));
        return false;
    }

    // P1-04: Валидация фактических характеристик потока захвата
    const int32_t actualInRate = AAudioStream_getSampleRate(captureStream_);
    const int32_t actualInChannels = AAudioStream_getChannelCount(captureStream_);
    const aaudio_format_t actualInFormat = AAudioStream_getFormat(captureStream_);

    if (actualInFormat != AAUDIO_FORMAT_PCM_I16 || actualInChannels != CHANNEL_COUNT_MONO ||
        (actualInRate != 16000 && actualInRate != 48000)) {
        LOGE("AAudio capture unsupported format: rate=%d, channels=%d, format=%d",
             actualInRate, actualInChannels, (int)actualInFormat);
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
        return false;
    }

    actualCaptureSampleRate_.store(actualInRate, std::memory_order_release);
    actualInputDeviceId_.store(AAudioStream_getDeviceId(captureStream_), std::memory_order_release);

    int32_t inFramesPerCallback = AAudioStream_getFramesPerDataCallback(captureStream_);
    int32_t inCapacity = AAudioStream_getBufferCapacityInFrames(captureStream_);
    int32_t inBurst = AAudioStream_getFramesPerBurst(captureStream_);

    size_t neededCaptureScratch = CAPTURE_DECIMATE_CAPACITY;
    if (inCapacity > 0 && static_cast<size_t>(inCapacity * 4) > neededCaptureScratch) {
        neededCaptureScratch = static_cast<size_t>(inCapacity * 4);
    }
    if (inFramesPerCallback > 0 && static_cast<size_t>(inFramesPerCallback * 4) > neededCaptureScratch) {
        neededCaptureScratch = static_cast<size_t>(inFramesPerCallback * 4);
    }
    if (inBurst > 0 && static_cast<size_t>(inBurst * 8) > neededCaptureScratch) {
        neededCaptureScratch = static_cast<size_t>(inBurst * 8);
    }

    if (captureInputScratchBuffer_.size() < neededCaptureScratch) {
        captureInputScratchBuffer_.resize(neededCaptureScratch, 0);
    }
    if (captureDecimateBuffer_.size() < neededCaptureScratch) {
        captureDecimateBuffer_.resize(neededCaptureScratch, 0);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. КОНФИГУРАЦИЯ ПОТОКА ВОСПРОИЗВЕДЕНИЯ (P1-03: EXCLUSIVE -> SHARED FALLBACK)
    // ─────────────────────────────────────────────────────────────
    res = openPlaybackStreamWithFallback(targetPlaybackSampleRate, outputDeviceId, isBluetoothMode);
    if (res != AAUDIO_OK) {
        LOGE("Failed to open playback stream: %d (%s)", res, AAudio_convertResultToText(res));
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
        return false;
    }

    actualPlaybackSampleRate_.store(AAudioStream_getSampleRate(playbackStream_), std::memory_order_release);
    actualOutputDeviceId_.store(AAudioStream_getDeviceId(playbackStream_), std::memory_order_release);

    isMmapExclusiveActive_.store(
        !isBluetoothMode && (AAudioStream_getSharingMode(playbackStream_) == AAUDIO_SHARING_MODE_EXCLUSIVE),
        std::memory_order_relaxed
    );

    // P1-08: Безопасный тюнинг размера буфера (2 * burst)
    const int32_t playBurst = AAudioStream_getFramesPerBurst(playbackStream_);
    const int32_t playCapacity = AAudioStream_getBufferCapacityInFrames(playbackStream_);
    if (playBurst > 0 && playCapacity > 0) {
        const int32_t targetBufSize = std::clamp(playBurst * 2, playBurst, playCapacity);
        const int32_t appliedBufSize = AAudioStream_setBufferSizeInFrames(playbackStream_, targetBufSize);
        LOGI("Playback buffer size tuned: requested=%d, applied=%d (burst=%d, capacity=%d)",
             targetBufSize, appliedBufSize, playBurst, playCapacity);
    }

    LOGI("AAudio Initialized: CapRate=%d (DevId=%d), PlayRate=%d (DevId=%d), MMAP=%d",
         actualCaptureSampleRate_.load(), actualInputDeviceId_.load(),
         actualPlaybackSampleRate_.load(), actualOutputDeviceId_.load(),
         isMmapExclusiveActive_.load());
    return true;
}

aaudio_result_t AAudioEngine::openPlaybackStreamWithFallback(
    int32_t targetPlaybackSampleRate, int32_t outputDeviceId, bool isBluetooth) {

    const aaudio_sharing_mode_t desiredSharing = isBluetooth ? AAUDIO_SHARING_MODE_SHARED : AAUDIO_SHARING_MODE_EXCLUSIVE;

    auto buildAndOpen = [this, targetPlaybackSampleRate, outputDeviceId](aaudio_sharing_mode_t sharingMode) -> aaudio_result_t {
        AAudioStreamBuilder* outBuilder = nullptr;
        if (AAudio_createStreamBuilder(&outBuilder) != AAUDIO_OK) {
            return AAUDIO_ERROR_INTERNAL;
        }

        AAudioStreamBuilder_setDirection(outBuilder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(outBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setChannelCount(outBuilder, CHANNEL_COUNT_MONO);
        AAudioStreamBuilder_setFormat(outBuilder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSampleRate(outBuilder, targetPlaybackSampleRate);

        if (outputDeviceId > 0) {
            AAudioStreamBuilder_setDeviceId(outBuilder, outputDeviceId);
        }

        AAudioStreamBuilder_setSharingMode(outBuilder, sharingMode);
        AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_VOICE_COMMUNICATION);

        AAudioStreamBuilder_setDataCallback(outBuilder, playbackCallback, this);
        AAudioStreamBuilder_setErrorCallback(outBuilder, errorCallback, this);

        if (playbackStream_ != nullptr) {
            AAudioStream_close(playbackStream_);
            playbackStream_ = nullptr;
        }

        aaudio_result_t openRes = AAudioStreamBuilder_openStream(outBuilder, &playbackStream_);
        AAudioStreamBuilder_delete(outBuilder);

        if (openRes != AAUDIO_OK && playbackStream_ != nullptr) {
            AAudioStream_close(playbackStream_);
            playbackStream_ = nullptr;
        }
        return openRes;
    };

    aaudio_result_t res = buildAndOpen(desiredSharing);

    // P1-03: Контролируемый однократный откат с EXCLUSIVE на SHARED
    if (res != AAUDIO_OK && desiredSharing == AAUDIO_SHARING_MODE_EXCLUSIVE) {
        LOGI("Playback EXCLUSIVE open failed (%d: %s). Conservative fallback to SHARED...",
             res, AAudio_convertResultToText(res));
        res = buildAndOpen(AAUDIO_SHARING_MODE_SHARED);
    }

    return res;
}

bool AAudioEngine::start() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);

    if (isRunning_.load()) return true;

    // REMEDIATION #1: Вызываем initLocked без повторного захвата mutex
    if (!captureStream_ || !playbackStream_) {
        LOGI("start() called with null streams, reinitializing...");
        if (!initLocked(isBluetoothMode_.load(), playbackSampleRate_.load(),
                        actualInputDeviceId_.load(), actualOutputDeviceId_.load())) {
            return false;
        }
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    aaudio_result_t res = AAudioStream_requestStart(captureStream_);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart(capture) failed: %d (%s)", res, AAudio_convertResultToText(res));
        stopLocked();
        return false;
    }

    res = AAudioStream_requestStart(playbackStream_);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart(playback) failed: %d (%s)", res, AAudio_convertResultToText(res));
        AAudioStream_requestStop(captureStream_);
        stopLocked();
        return false;
    }

    isRunning_.store(true);
    LOGI("AAudioEngine started successfully");
    return true;
}

void AAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex_);
    stopLocked();
}

void AAudioEngine::stopLocked() {
    bool wasRunning = isRunning_.exchange(false);
    if (!wasRunning && !captureStream_ && !playbackStream_) {
        return;
    }

    LOGI("Stopping AAudioEngine (stopLocked)...");

    if (captureStream_) {
        AAudioStream_requestStop(captureStream_);
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
    }

    if (playbackStream_) {
        AAudioStream_requestStop(playbackStream_);
        AAudioStream_close(playbackStream_);
        playbackStream_ = nullptr;
    }

    {
        std::lock_guard<std::mutex> pLock(playbackWriteMutex_);
        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        resamplePendingOffset_ = 0;
        resamplePendingCount_ = 0;
        resetEarcon();
        s_voiceEnhancer.reset(48000);
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    micRms_.store(0.0f);
    outRms_.store(0.0f);
    isMmapExclusiveActive_.store(false);
    fftAccumulatorPos_ = 0;

    LOGI("AAudioEngine stopped cleanly");
}

size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames) {
    if (frames == 0 || pcm == nullptr) return 0;

    std::lock_guard<std::mutex> lock(playbackWriteMutex_);

    if (resamplePendingCount_ > 0) {
        size_t written = playbackBuffer_.write(
            resamplePendingBuffer_.data() + resamplePendingOffset_,
            resamplePendingCount_
        );
        resamplePendingOffset_ += written;
        resamplePendingCount_ -= written;

        if (resamplePendingCount_ > 0) {
            return 0;
        }
        resamplePendingOffset_ = 0;
    }

    int32_t actualRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);
    if (actualRate <= 0) actualRate = SAMPLE_RATE_GEMINI_OUT;

    const size_t neededCapacity = static_cast<size_t>(frames * 3);
    if (neededCapacity > resampleScratchBuffer_.size()) {
        resampleScratchBuffer_.resize(neededCapacity * 2);
    }
    if (neededCapacity > resamplePendingBuffer_.size()) {
        resamplePendingBuffer_.resize(neededCapacity * 2);
    }

    if (actualRate == SAMPLE_RATE_BT_HFP) {
        size_t resampledFrames = resampler24To16_.process(
            pcm, frames, resampleScratchBuffer_.data()
        );
        size_t written = playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
        if (written < resampledFrames) {
            size_t unwritten = resampledFrames - written;
            std::memcpy(resamplePendingBuffer_.data(),
                        resampleScratchBuffer_.data() + written,
                        unwritten * sizeof(int16_t));
            resamplePendingOffset_ = 0;
            resamplePendingCount_ = unwritten;
        }
        return frames;
    }

    if (actualRate == SAMPLE_RATE_NATIVE_SPEAKER || actualRate == SAMPLE_RATE_BT_A2DP) {
        size_t resampledFrames = resampler24To48_.process(
            pcm, frames, resampleScratchBuffer_.data()
        );
        size_t written = playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
        if (written < resampledFrames) {
            size_t unwritten = resampledFrames - written;
            std::memcpy(resamplePendingBuffer_.data(),
                        resampleScratchBuffer_.data() + written,
                        unwritten * sizeof(int16_t));
            resamplePendingOffset_ = 0;
            resamplePendingCount_ = unwritten;
        }
        return frames;
    }

    if (actualRate == SAMPLE_RATE_GEMINI_OUT) {
        return playbackBuffer_.write(pcm, frames);
    }

    const double rateRatio = static_cast<double>(actualRate) / static_cast<double>(SAMPLE_RATE_GEMINI_OUT);
    const size_t targetFrames = static_cast<size_t>(frames * rateRatio);

    int16_t* dst = resampleScratchBuffer_.data();
    for (size_t i = 0; i < targetFrames; ++i) {
        double srcIdx = i / rateRatio;
        size_t idx0 = static_cast<size_t>(srcIdx);
        size_t idx1 = std::min(idx0 + 1, frames - 1);
        double frac = srcIdx - idx0;

        int32_t val0 = pcm[idx0];
        int32_t val1 = pcm[idx1];
        int32_t interpolated = static_cast<int32_t>(val0 + frac * (val1 - val0));
        dst[i] = static_cast<int16_t>(std::clamp(interpolated, -32768, 32767));
    }

    size_t written = playbackBuffer_.write(dst, targetFrames);
    if (written >= targetFrames) {
        return frames;
    } else {
        return (targetFrames > 0) ? (written * frames / targetFrames) : 0;
    }
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    if (pcm == nullptr || maxFrames == 0) return 0;
    return captureBuffer_.read(pcm, maxFrames);
}

void AAudioEngine::flushPlayback() {
    {
        std::lock_guard<std::mutex> lock(playbackWriteMutex_);
        resamplePendingOffset_ = 0;
        resamplePendingCount_ = 0;
    }
    playbackBuffer_.requestFlush();
    outRms_.store(0.0f);
    LOGI("AAudioEngine: flushPlayback executed");
}

void AAudioEngine::triggerBargeInEarcon() {
    earconPhase_.store(0, std::memory_order_release);
    LOGI("AAudioEngine: triggerBargeInEarcon (750 Hz pip)");
}

void AAudioEngine::resetEarcon() {
    earconPhase_.store(EARCON_INACTIVE_PHASE, std::memory_order_release);
}

void AAudioEngine::setVolume(float vol) {
    playbackVolume_.store(std::clamp(vol, 0.0f, 1.0f), std::memory_order_relaxed);
}

void AAudioEngine::setMicGain(float gain) {
    micGain_.store(std::clamp(gain, 0.5f, 2.0f), std::memory_order_relaxed);
}

void AAudioEngine::getSpectrumData(dsp::SpectrumSnapshot& outSnapshot) {
    fftProcessor_->getLatestSnapshot(outSnapshot);
}

aaudio_data_callback_result_t AAudioEngine::captureCallback(
    AAudioStream* /* stream */, void* userData, void* audioData, int32_t numFrames) {

    if (audioData == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    thread_local bool ftzSet = false;
    if (!ftzSet) {
        dsp::enableHardwareFtz();
        ftzSet = true;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);
    const auto* inSamples = static_cast<const int16_t*>(audioData);

    const float gain = engine->micGain_.load(std::memory_order_relaxed);
    const bool applyGain = (std::abs(gain - 1.0f) > 0.001f);
    const int32_t capRate = engine->actualCaptureSampleRate_.load(std::memory_order_relaxed);

    const size_t inputScratchCap = engine->captureInputScratchBuffer_.size();
    const size_t decimateScratchCap = engine->captureDecimateBuffer_.size();

    size_t framesRemaining = static_cast<size_t>(numFrames);
    const int16_t* inPtr = inSamples;

    while (framesRemaining > 0) {
        const size_t chunkFrames = std::min(framesRemaining, inputScratchCap);
        int16_t* scratchBuf = engine->captureInputScratchBuffer_.data();

        if (applyGain) {
            for (size_t i = 0; i < chunkFrames; ++i) {
                int32_t amplified = static_cast<int32_t>(std::round(inPtr[i] * gain));
                scratchBuf[i] = static_cast<int16_t>(std::clamp(amplified, -32768, 32767));
            }
        } else {
            std::memcpy(scratchBuf, inPtr, chunkFrames * sizeof(int16_t));
        }

        if (capRate == 48000) {
            int16_t* decBuf = engine->captureDecimateBuffer_.data();
            size_t processed = engine->captureDecimator48To16_.process(
                scratchBuf, chunkFrames, decBuf, decimateScratchCap
            );
            if (processed > 0) {
                engine->captureBuffer_.write(decBuf, processed);
                engine->micRms_.store(dsp::calculateRms(decBuf, processed), std::memory_order_relaxed);
            }
        } else if (capRate == 16000) {
            engine->captureBuffer_.write(scratchBuf, chunkFrames);
            engine->micRms_.store(dsp::calculateRms(scratchBuf, chunkFrames), std::memory_order_relaxed);
        }

        framesRemaining -= chunkFrames;
        inPtr += chunkFrames;
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AAudioEngine::playbackCallback(
    AAudioStream* /* stream */, void* userData, void* audioData, int32_t numFrames) {

    thread_local bool ftzSet = false;
    if (!ftzSet) {
        dsp::enableHardwareFtz();
        ftzSet = true;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);

    size_t read = engine->playbackBuffer_.read(samples, numFrames);
    if (read < static_cast<size_t>(numFrames)) {
        std::memset(samples + read, 0, (numFrames - read) * sizeof(int16_t));
    }

    const int32_t actualRate = engine->actualPlaybackSampleRate_.load(std::memory_order_relaxed);
    const size_t earconLimitFrames = static_cast<size_t>(actualRate * (EARCON_DURATION_MS / 1000.0f));
    size_t phase = engine->earconPhase_.load(std::memory_order_acquire);

    if (phase < earconLimitFrames) {
        for (int32_t i = 0; i < numFrames && phase < earconLimitFrames; ++i, ++phase) {
            float t = static_cast<float>(phase) / static_cast<float>(actualRate);
            float env = std::cos((3.14159265f * phase) / (2.0f * earconLimitFrames));
            env *= env;
            int16_t pip = static_cast<int16_t>(std::sin(2.0f * 3.14159265f * EARCON_FREQ_HZ * t) * env * 12000.0f);
            samples[i] = std::clamp(samples[i] + pip, -32768, 32767);
        }
        engine->earconPhase_.store(phase, std::memory_order_release);
    }

    s_voiceEnhancer.process(samples, numFrames, actualRate);

    const float vol = engine->playbackVolume_.load(std::memory_order_relaxed);
    if (vol < 0.999f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            samples[i] = static_cast<int16_t>(samples[i] * vol);
        }
    }

    const float outRms = dsp::calculateRms(samples, numFrames);
    engine->outRms_.store(outRms, std::memory_order_relaxed);

    const float micRms = engine->micRms_.load(std::memory_order_relaxed);
    const size_t requiredAccum = (actualRate >= 44100) ? (FFT_SIZE * 2) : FFT_SIZE;
    const size_t hopSize = (actualRate >= 44100) ? (FFT_HOP_SIZE * 2) : FFT_HOP_SIZE;

    for (int32_t i = 0; i < numFrames; ++i) {
        if (engine->fftAccumulatorPos_ < engine->fftAccumulator_.size()) {
            engine->fftAccumulator_[engine->fftAccumulatorPos_++] = samples[i] * (1.0f / 32768.0f);
        }
        if (engine->fftAccumulatorPos_ >= requiredAccum) {
            engine->fftProcessor_->process(engine->fftAccumulator_.data(), requiredAccum, micRms, outRms, actualRate);
            std::memmove(&engine->fftAccumulator_[0], &engine->fftAccumulator_[hopSize], (requiredAccum - hopSize) * sizeof(float));
            engine->fftAccumulatorPos_ = requiredAccum - hopSize;
        }
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioEngine::errorCallback(AAudioStream* /* stream */, void* userData, aaudio_result_t error) {
    char errBuf[64];
    snprintf(errBuf, sizeof(errBuf), "AAudio stream error: %d", error);
    client::logging::NativeLogQueue::getInstance().push(5, LOG_TAG, errBuf);

    if (error == AAUDIO_ERROR_DISCONNECTED) {
        auto* engine = static_cast<AAudioEngine*>(userData);
        engine->isDisconnected_.store(true, std::memory_order_release);
    }
}

} // namespace client::audio