// >>> FILE: app/src/main/cpp/audio/AAudioEngine.cpp
#include "AAudioEngine.h"
#include "dsp/NeonDspUtils.h"
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#define LOG_TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace client::audio {

AAudioEngine& AAudioEngine::getInstance() {
    static AAudioEngine instance;
    return instance;
}

AAudioEngine::AAudioEngine()
    : fftProcessor_(std::make_unique<dsp::FastFft>()),
      fftAccumulator_(FFT_SIZE, 0.0f),
      resampleScratchBuffer_(16384, 0), // ERR-07: Однократное предвыделение 16К сэмплов (32 КБ)
      captureDecimateBuffer_(4096, 0) {  // ERR-04: Однократное предвыделение 4К сэмплов (8 КБ)
    dsp::enableHardwareFtz();
}

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::init(bool isBluetoothMode, int32_t targetPlaybackSampleRate) {
    stop(); // E-12: Всегда полностью закрываем предыдущие потоки

    {
        // ERR-07: Потокобезопасный сброс ресемплеров
        std::lock_guard<std::mutex> lock(playbackWriteMutex_);
        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        resetEarcon();
    }

    // ERR-08: Синхронная очистка буферов в состоянии покоя
    captureBuffer_.clear();
    playbackBuffer_.clear();

    isBluetoothMode_.store(isBluetoothMode, std::memory_order_relaxed);
    playbackSampleRate_.store(targetPlaybackSampleRate, std::memory_order_relaxed);

    // 1. Конфигурация потока захвата
    AAudioStreamBuilder* inBuilder = nullptr;
    if (AAudio_createStreamBuilder(&inBuilder) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setDirection(inBuilder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(inBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSampleRate(inBuilder, SAMPLE_RATE_GEMINI_IN);
    AAudioStreamBuilder_setChannelCount(inBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(inBuilder, AAUDIO_FORMAT_PCM_I16);

    if (isBluetoothMode) {
        AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    } else {
        AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_UNPROCESSED);
    }

    AAudioStreamBuilder_setDataCallback(inBuilder, captureCallback, this);

    aaudio_result_t res = AAudioStreamBuilder_openStream(inBuilder, &captureStream_);
    AAudioStreamBuilder_delete(inBuilder);

    if (res != AAUDIO_OK) {
        LOGE("Failed to open capture stream: %d", res);
        return false;
    }

    // E-07: Фиксируем реальную частоту микрофона от HAL
    actualCaptureSampleRate_.store(AAudioStream_getSampleRate(captureStream_), std::memory_order_release);

    // 2. Конфигурация потока воспроизведения
    AAudioStreamBuilder* outBuilder = nullptr;
    if (AAudio_createStreamBuilder(&outBuilder) != AAUDIO_OK) {
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
        return false;
    }

    AAudioStreamBuilder_setDirection(outBuilder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(outBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setChannelCount(outBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(outBuilder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(outBuilder, targetPlaybackSampleRate);

    if (isBluetoothMode) {
        AAudioStreamBuilder_setSharingMode(outBuilder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    } else {
        AAudioStreamBuilder_setSharingMode(outBuilder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_MEDIA);
    }

    AAudioStreamBuilder_setDataCallback(outBuilder, playbackCallback, this);

    res = AAudioStreamBuilder_openStream(outBuilder, &playbackStream_);
    AAudioStreamBuilder_delete(outBuilder);

    if (res != AAUDIO_OK) {
        LOGE("Failed to open playback stream: %d", res);
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
        return false;
    }

    actualPlaybackSampleRate_.store(AAudioStream_getSampleRate(playbackStream_), std::memory_order_release);

    isMmapExclusiveActive_.store(
        !isBluetoothMode && (AAudioStream_getSharingMode(playbackStream_) == AAUDIO_SHARING_MODE_EXCLUSIVE),
        std::memory_order_relaxed
    );

    LOGI("AAudio initialized successfully. Capture Rate: %d, Playback Rate: %d, MMAP: %d",
         actualCaptureSampleRate_.load(), actualPlaybackSampleRate_.load(), isMmapExclusiveActive_.load());
    return true;
}

bool AAudioEngine::start() {
    if (isRunning_.load()) return true;
    if (!captureStream_ || !playbackStream_) {
        if (!init(isBluetoothMode_.load(), playbackSampleRate_.load())) return false;
    }

    // ERR-08: Детерминированный сброс буферов перед запуском потоков вместо отложенного requestFlush
    captureBuffer_.clear();
    playbackBuffer_.clear();

    if (AAudioStream_requestStart(captureStream_) != AAUDIO_OK) {
        stop();
        return false;
    }

    if (AAudioStream_requestStart(playbackStream_) != AAUDIO_OK) {
        stop();
        return false;
    }

    isRunning_.store(true);
    LOGI("AAudio engine started");
    return true;
}

// E-12: Гарантированное закрытие дескрипторов при любом сценарии
void AAudioEngine::stop() {
    isRunning_.store(false);

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
        // ERR-07: Изолируем сброс ресемплеров от параллельных сетевых записей
        std::lock_guard<std::mutex> lock(playbackWriteMutex_);
        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        resetEarcon();
    }

    // ERR-08: Синхронное очищение очередей при закрытых потоках исключает Stale Flush Trap
    captureBuffer_.clear();
    playbackBuffer_.clear();

    micRms_.store(0.0f);
    outRms_.store(0.0f);
    isMmapExclusiveActive_.store(false);
    fftAccumulatorPos_ = 0;
}

size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames) {
    if (frames == 0) return 0;

    // ERR-07: Потокобезопасная блокировка вызова без динамических аллокаций памяти
    std::lock_guard<std::mutex> lock(playbackWriteMutex_);

    int32_t actualRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);

    // Bluetooth 16 кГц: 24 кГц -> 16 кГц
    if (actualRate == SAMPLE_RATE_BT_HFP) {
        const size_t neededCapacity = frames * 2;
        if (neededCapacity > resampleScratchBuffer_.size()) {
            LOGE("writePlaybackPcm: frames %zu exceeds scratch buffer capacity", frames);
            return 0;
        }
        size_t resampledFrames = resampler24To16_.process(
            pcm, frames, resampleScratchBuffer_.data()
        );
        return playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
    }

    // Bluetooth 48 кГц: 24 кГц -> 48 кГц
    if (actualRate == SAMPLE_RATE_BT_A2DP) {
        const size_t neededCapacity = frames * 2;
        if (neededCapacity > resampleScratchBuffer_.size()) {
            LOGE("writePlaybackPcm: frames %zu exceeds scratch buffer capacity", frames);
            return 0;
        }
        size_t resampledFrames = resampler24To48_.process(
            pcm, frames, resampleScratchBuffer_.data()
        );
        return playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
    }

    // Нативный 24 кГц
    return playbackBuffer_.write(pcm, frames);
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    return captureBuffer_.read(pcm, maxFrames);
}

// ERR-08: Динамический сброс при перебивании (Barge-In) на лету
void AAudioEngine::flushPlayback() {
    playbackBuffer_.requestFlush();
    outRms_.store(0.0f);
}

void AAudioEngine::triggerBargeInEarcon() {
    earconPhase_.store(0, std::memory_order_release);
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

    // E-26: Активация FTZ непосредственно в потоке захвата
    thread_local bool ftzSet = false;
    if (!ftzSet) {
        dsp::enableHardwareFtz();
        ftzSet = true;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);

    // Применение усиления
    float gain = engine->micGain_.load(std::memory_order_relaxed);
    if (std::abs(gain - 1.0f) > 0.001f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t amplified = static_cast<int32_t>(std::round(samples[i] * gain));
            samples[i] = static_cast<int16_t>(std::clamp(amplified, -32768, 32767));
        }
    }

    // ERR-04: Если HAL захватывает на 48 кГц — безопасно децимируем 3:1 в предвыделенный буфер
    int32_t capRate = engine->actualCaptureSampleRate_.load(std::memory_order_relaxed);
    if (capRate == 48000) {
        int16_t* decBuf = engine->captureDecimateBuffer_.data();
        size_t decCap = engine->captureDecimateBuffer_.size();
        size_t processed = engine->captureDecimator48To16_.process(samples, numFrames, decBuf, decCap);
        engine->captureBuffer_.write(decBuf, processed);