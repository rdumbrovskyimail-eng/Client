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
      pcmFloatBuffer_(FFT_SIZE, 0.0f),
      resampleScratchBuffer_(BURST_10MS_24K * 4, 0) {
    dsp::enableHardwareFtzDaz();
}

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::init(bool isBluetoothMode, int32_t targetPlaybackSampleRate) {
    if (isRunning_.load()) stop();

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
        // Режим CMF Buds 2: Shared Mode (AudioFlinger BT stack)
        AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    } else {
        // Режим S23 Ultra: MMAP Exclusive
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

    // Проверка реального режима MMAP
    aaudio_sharing_mode_t actualCaptureMode = AAudioStream_getSharingMode(captureStream_);
    if (!isBluetoothMode && actualCaptureMode != AAUDIO_SHARING_MODE_EXCLUSIVE) {
        LOGI("Capture stream fell back to shared mode. Reopening with fallback...");
    }

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

    isMmapExclusiveActive_.store(
        !isBluetoothMode && (AAudioStream_getSharingMode(playbackStream_) == AAUDIO_SHARING_MODE_EXCLUSIVE),
        std::memory_order_relaxed
    );

    LOGI("AAudio initialized successfully. BT Mode: %d, MMAP: %d", isBluetoothMode, isMmapExclusiveActive_.load());
    return true;
}

bool AAudioEngine::start() {
    if (isRunning_.load()) return true;
    if (!captureStream_ || !playbackStream_) {
        if (!init(isBluetoothMode_.load(), playbackSampleRate_.load())) return false;
    }

    captureBuffer_.requestFlush();
    playbackBuffer_.requestFlush();

    if (AAudioStream_requestStart(captureStream_) != AAUDIO_OK) return false;
    if (AAudioStream_requestStart(playbackStream_) != AAUDIO_OK) {
        AAudioStream_requestStop(captureStream_);
        return false;
    }

    isRunning_.store(true);
    LOGI("AAudio engine started");
    return true;
}

void AAudioEngine::stop() {
    if (!isRunning_.exchange(false)) return;

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

    captureBuffer_.requestFlush();
    playbackBuffer_.requestFlush();
    micRms_.store(0.0f);
    outRms_.store(0.0f);
    isMmapExclusiveActive_.store(false);
    LOGI("AAudio engine stopped");
}

size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames) {
    int32_t targetRate = playbackSampleRate_.load(std::memory_order_relaxed);

    // Адаптивный ресемплинг при выводе на CMF Buds 2 в режиме HFP (24k -> 16k)
    if (targetRate == SAMPLE_RATE_BT_HFP) {
        size_t resampledFrames = PolyphaseResampler::resample24To16(
            pcm, frames, resampleScratchBuffer_.data()
        );
        return playbackBuffer_.write(resampleScratchBuffer_.data(), resampledFrames);
    }

    return playbackBuffer_.write(pcm, frames);
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    return captureBuffer_.read(pcm, maxFrames);
}

void AAudioEngine::flushPlayback() {
    playbackBuffer_.requestFlush();
    outRms_.store(0.0f);
}

void AAudioEngine::triggerBargeInEarcon() {
    earconPhase_.store(0, std::memory_order_release);
}

void AAudioEngine::setVolume(float vol) {
    playbackVolume_.store(std::clamp(vol, 0.0f, 1.0f), std::memory_order_relaxed);
}

void AAudioEngine::setMicGain(float gain) {
    micGain_.store(std::clamp(gain, 0.5f, 2.0f), std::memory_order_relaxed);
}

void AAudioEngine::setRouteMode(bool isBluetooth, int32_t targetSampleRate) {
    if (isBluetoothMode_.load() != isBluetooth || playbackSampleRate_.load() != targetSampleRate) {
        init(isBluetooth, targetSampleRate);
        start();
    }
}

void AAudioEngine::getSpectrumUniforms(float* out5Bands) {
    auto bands = fftProcessor_->getBands();
    for (size_t i = 0; i < 5; ++i) {
        out5Bands[i] = bands[i];
    }
}

aaudio_data_callback_result_t AAudioEngine::captureCallback(
    AAudioStream* /* stream */, void* userData, void* audioData, int32_t numFrames) {

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);

    // Применение программного усиления микрофона с аппаратным насыщением (clamping)
    float gain = engine->micGain_.load(std::memory_order_relaxed);
    if (std::abs(gain - 1.0f) > 0.001f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            int32_t amplified = static_cast<int32_t>(std::round(samples[i] * gain));
            samples[i] = static_cast<int16_t>(std::clamp(amplified, -32768, 32767));
        }
    }

    engine->captureBuffer_.write(samples, numFrames);
    float rms = dsp::calculateRms(samples, numFrames);
    engine->micRms_.store(rms, std::memory_order_relaxed);

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t AAudioEngine::playbackCallback(
    AAudioStream* /* stream */, void* userData, void* audioData, int32_t numFrames) {

    auto* engine = static_cast<AAudioEngine*>(userData);
    auto* samples = static_cast<int16_t*>(audioData);

    size_t read = engine->playbackBuffer_.read(samples, numFrames);

    if (read < static_cast<size_t>(numFrames)) {
        std::memset(samples + read, 0, (numFrames - read) * sizeof(int16_t));
    }

    // Синтез звукового микро-клика (Earcon Pip 750 Гц) при перебивании
    size_t phase = engine->earconPhase_.load(std::memory_order_acquire);
    if (phase < EARCON_DURATION_FRAMES_24K) {
        for (int32_t i = 0; i < numFrames && phase < EARCON_DURATION_FRAMES_24K; ++i, ++phase) {
            float t = static_cast<float>(phase) / static_cast<float>(SAMPLE_RATE_GEMINI_OUT);
            float env = std::cos((3.14159265f * phase) / (2.0f * EARCON_DURATION_FRAMES_24K));
            env *= env;
            int16_t pip = static_cast<int16_t>(std::sin(2.0f * 3.14159265f * EARCON_FREQ_HZ * t) * env * 12000.0f);
            samples[i] = std::clamp(samples[i] + pip, -32768, 32767);
        }
        engine->earconPhase_.store(phase, std::memory_order_release);
    }

    float vol = engine->playbackVolume_.load(std::memory_order_relaxed);
    if (vol < 0.999f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            samples[i] = static_cast<int16_t>(samples[i] * vol);
        }
    }

    float rms = dsp::calculateRms(samples, numFrames);
    engine->outRms_.store(rms, std::memory_order_relaxed);

    if (numFrames >= static_cast<int32_t>(FFT_SIZE)) {
        dsp::pcm16ToFloat(samples, engine->pcmFloatBuffer_.data(), FFT_SIZE);
        engine->fftProcessor_->process(engine->pcmFloatBuffer_.data(), FFT_SIZE);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

} // namespace client::audio