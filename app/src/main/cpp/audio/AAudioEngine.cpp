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

// Неблокирующее логирование: отправка в Android Logcat + NativeLogQueue
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

namespace client::audio {

AAudioEngine& AAudioEngine::getInstance() {
    static AAudioEngine instance;
    return instance;
}

AAudioEngine::AAudioEngine()
    : fftProcessor_(std::make_unique<dsp::FastFft>()),
      fftAccumulator_(FFT_SIZE * 2, 0.0f),
      resampleScratchBuffer_(RESAMPLE_SCRATCH_CAPACITY, 0),
      captureDecimateBuffer_(CAPTURE_DECIMATE_CAPACITY, 0) {
    dsp::enableHardwareFtz();
}

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::init(bool isBluetoothMode, int32_t targetPlaybackSampleRate,
                        int32_t inputDeviceId, int32_t outputDeviceId) {
    stop();

    // Пауза 20 мс для детерминированного освобождения аппаратных дескрипторов ядра
    std::this_thread::sleep_for(std::chrono::milliseconds(20));

    {
        std::lock_guard<std::mutex> lock(playbackWriteMutex_);
        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        resetEarcon();
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    isBluetoothMode_.store(isBluetoothMode, std::memory_order_relaxed);
    playbackSampleRate_.store(targetPlaybackSampleRate, std::memory_order_relaxed);

    LOGI("AAudioEngine::init: BT=%d, targetRate=%d, inDevId=%d, outDevId=%d", 
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

    // Явная аппаратная привязка к микрофону CMF Buds 2 или спикера
    if (inputDeviceId > 0) {
        AAudioStreamBuilder_setDeviceId(inBuilder, inputDeviceId);
    }

    // Режим SHARED с пресетом VOICE_COMMUNICATION гарантирует стабильный захват без отказов HAL
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

    actualCaptureSampleRate_.store(AAudioStream_getSampleRate(captureStream_), std::memory_order_release);
    actualInputDeviceId_.store(AAudioStream_getDeviceId(captureStream_), std::memory_order_release);

    // ─────────────────────────────────────────────────────────────
    // 2. КОНФИГУРАЦИЯ ПОТОКА ВОСПРОИЗВЕДЕНИЯ (ДИНАМИК / НАУШНИКИ)
    // ─────────────────────────────────────────────────────────────
    AAudioStreamBuilder* outBuilder = nullptr;
    if (AAudio_createStreamBuilder(&outBuilder) != AAUDIO_OK) {
        LOGE("Failed to create playback stream builder");
        AAudioStream_close(captureStream_);
        captureStream_ = nullptr;
        return false;
    }

    AAudioStreamBuilder_setDirection(outBuilder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(outBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setChannelCount(outBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(outBuilder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(outBuilder, targetPlaybackSampleRate);

    // Явная привязка к ID динамика/наушника
    if (outputDeviceId > 0) {
        AAudioStreamBuilder_setDeviceId(outBuilder, outputDeviceId);
    }

    if (isBluetoothMode) {
        AAudioStreamBuilder_setSharingMode(outBuilder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    } else {
        // Для динамиков используем USAGE_VOICE_COMMUNICATION для работы аппаратного эхоподавления
        AAudioStreamBuilder_setSharingMode(outBuilder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    }

    AAudioStreamBuilder_setDataCallback(outBuilder, playbackCallback, this);
    AAudioStreamBuilder_setErrorCallback(outBuilder, errorCallback, this);

    res = AAudioStreamBuilder_openStream(outBuilder, &playbackStream_);
    AAudioStreamBuilder_delete(outBuilder);

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

    LOGI("AAudio Initialized: CapRate=%d (DevId=%d), PlayRate=%d (DevId=%d), MMAP=%d",
         actualCaptureSampleRate_.load(), actualInputDeviceId_.load(),
         actualPlaybackSampleRate_.load(), actualOutputDeviceId_.load(),
         isMmapExclusiveActive_.load());
    return true;
}

bool AAudioEngine::start() {
    if (isRunning_.load()) return true;
    if (!captureStream_ || !playbackStream_) {
        LOGI("start() called with null streams, reinitializing...");
        if (!init(isBluetoothMode_.load(), playbackSampleRate_.load(),
                  actualInputDeviceId_.load(), actualOutputDeviceId_.load())) return false;
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    aaudio_result_t res = AAudioStream_requestStart(captureStream_);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart(capture) failed: %d (%s)", res, AAudio_convertResultToText(res));
        stop();
        return false;
    }

    res = AAudioStream_requestStart(playbackStream_);
    if (res != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart(playback) failed: %d (%s)", res, AAudio_convertResultToText(res));
        stop();
        return false;
    }

    isRunning_.store(true);
    LOGI("AAudioEngine started successfully");
    return true;
}

void AAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(stateMutex_);
    if (!isRunning_.exchange(false)) {
        return;
    }

    LOGI("Stopping AAudioEngine...");

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
        resetEarcon();
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    micRms_.store(0.0f);
    outRms_.store(0.0f);
    isMmapExclusiveActive_.store(false);
    fftAccumulatorPos_ = 0;

    LOGI("AAudioEngine stopped");
}

/**
 * Запись входящих сэмплов от Gemini Live.
 * 
 * ФУНДАМЕНТАЛЬНОЕ ПРАВИЛО МУЛЬТИРЕЙТИНГА (Источники 151, 161, 200):
 * Метод возвращает количество потребленных ВХОДНЫХ сэмплов (frames),
 * благодаря чему вызывающий слой в Kotlin сразу закрывает пакет за один проход
 * и не пересылает хвост звука повторно, устраняя эффект наложения нескольких моделей!
 */
size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames) {
    if (frames == 0 || pcm == nullptr) return 0;

    std::lock_guard<std::mutex> lock(playbackWriteMutex_);

    int32_t actualRate = actualPlaybackSampleRate_.load(std::memory_order_acquire);
    if (actualRate <= 0) actualRate = SAMPLE_RATE_GEMINI_OUT;

    // Автоматическое масштабирование скретчпада под размер любого входящего сетевого пакета
    const size_t neededCapacity = static_cast<size_t>(frames * 3);
    if (neededCapacity > resampleScratchBuffer_.size()) {
        resampleScratchBuffer_.resize(neededCapacity * 2);
    }

    // 1. Bluetooth HFP: 24 кГц -> 16 кГц (L=2, M=3)
    if (actualRate == SAMPLE_RATE_BT_HFP) {
        size_t resampledFrames = resampler24To16_.process(
            pcm, frames, resampleScratchBuffer_.data()
        );
        size_t writtenOut = playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
        
        // Возвращаем количество поглощенных ВХОДНЫХ сэмплов frames, ликвидируя дублирование
        if (writtenOut >= resampledFrames) {
            return frames; // Весь входной пакет 24 кГц записан за 1 проход
        } else {
            return (resampledFrames > 0) ? (writtenOut * frames / resampledFrames) : 0;
        }
    }

    // 2. Встроенный ЦАП S23 Ultra / A2DP: 24 кГц -> 48 кГц через кубический сплайн Эрмита
    if (actualRate == SAMPLE_RATE_NATIVE_SPEAKER || actualRate == SAMPLE_RATE_BT_A2DP) {
        size_t resampledFrames = resampler24To48_.process(
            pcm, frames, resampleScratchBuffer_.data()
        );
        size_t writtenOut = playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
        if (writtenOut >= resampledFrames) {
            return frames;
        } else {
            return (resampledFrames > 0) ? (writtenOut * frames / resampledFrames) : 0;
        }
    }

    // 3. Нативная частота Gemini Live (24 кГц - LE Audio LC3)
    if (actualRate == SAMPLE_RATE_GEMINI_OUT) {
        return playbackBuffer_.write(pcm, frames);
    }

    // 4. Дробная интерполяция для нестандартных частот
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

    size_t writtenOut = playbackBuffer_.write(dst, targetFrames);
    if (writtenOut >= targetFrames) {
        return frames;
    } else {
        return (targetFrames > 0) ? (writtenOut * frames / targetFrames) : 0;
    }
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    if (pcm == nullptr || maxFrames == 0) return 0;
    return captureBuffer_.read(pcm, maxFrames);
}

void AAudioEngine::flushPlayback() {
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

    thread_local bool ftzSet = false;
    if (!ftzSet) {
        dsp::enableHardwareFtz();
        ftzSet = true;
    }

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);

    const float gain = engine->micGain_.load(std::memory_order_relaxed);
    if (std::abs(gain - 1.0f) > 0.001f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t amplified = static_cast<int32_t>(std::round(samples[i] * gain));
            samples[i] = static_cast<int16_t>(std::clamp(amplified, -32768, 32767));
        }
    }

    const int32_t capRate = engine->actualCaptureSampleRate_.load(std::memory_order_relaxed);
    if (capRate == 48000) {
        size_t decCap = engine->captureDecimateBuffer_.size();
        if (static_cast<size_t>(numFrames) > decCap) {
            engine->captureDecimateBuffer_.resize(numFrames * 2);
            decCap = engine->captureDecimateBuffer_.size();
        }
        int16_t* decBuf = engine->captureDecimateBuffer_.data();
        size_t processed = engine->captureDecimator48To16_.process(samples, numFrames, decBuf, decCap);
        if (processed > 0) {
            engine->captureBuffer_.write(decBuf, processed);
            engine->micRms_.store(dsp::calculateRms(decBuf, processed), std::memory_order_relaxed);
        }
    } else {
        engine->captureBuffer_.write(samples, numFrames);
        engine->micRms_.store(dsp::calculateRms(samples, numFrames), std::memory_order_relaxed);
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
    LOGE("AAudio stream error callback: code %d (%s)", error, AAudio_convertResultToText(error));

    if (error == AAUDIO_ERROR_DISCONNECTED) {
        auto* engine = static_cast<AAudioEngine*>(userData);
        std::thread([engine]() {
            LOGI("Asynchronously stopping AAudioEngine after AAUDIO_ERROR_DISCONNECTED.");
            engine->stop();
        }).detach();
    }
}

} // namespace client::audio