// >>> FILE: app/src/main/cpp/audio/AAudioEngine.cpp
#include "AAudioEngine.h"
#include "dsp/NeonDspUtils.h"
#include <android/log.h>
#include <algorithm>
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
      pcmFloatBuffer_(FFT_SIZE, 0.0f) {}

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::init() {
    if (isRunning_.load()) return true;

    // 1. Конфигурация потока захвата (Микрофон 16 кГц)
    AAudioStreamBuilder* inBuilder = nullptr;
    if (AAudio_createStreamBuilder(&inBuilder) != AAUDIO_OK) return false;

    AAudioStreamBuilder_setDirection(inBuilder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setPerformanceMode(inBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(inBuilder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setSampleRate(inBuilder, SAMPLE_RATE_IN);
    AAudioStreamBuilder_setChannelCount(inBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(inBuilder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setInputPreset(inBuilder, AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setDataCallback(inBuilder, captureCallback, this);

    aaudio_result_t res = AAudioStreamBuilder_openStream(inBuilder, &captureStream_);
    AAudioStreamBuilder_delete(inBuilder);

    if (res != AAUDIO_OK) {
        LOGE("Failed to open AAudio capture stream: %d", res);
        return false;
    }

    // 2. Конфигурация потока воспроизведения (Динамик 24 кГц)
    AAudioStreamBuilder* outBuilder = nullptr;
    if (AAudio_createStreamBuilder(&outBuilder) != AAUDIO_OK) {
        AAudioStream_close(captureStream_);
        return false;
    }

    AAudioStreamBuilder_setDirection(outBuilder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(outBuilder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(outBuilder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setSampleRate(outBuilder, SAMPLE_RATE_OUT);
    AAudioStreamBuilder_setChannelCount(outBuilder, CHANNEL_COUNT_MONO);
    AAudioStreamBuilder_setFormat(outBuilder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setUsage(outBuilder, AAUDIO_USAGE_VOICE_COMMUNICATION);
    AAudioStreamBuilder_setDataCallback(outBuilder, playbackCallback, this);

    res = AAudioStreamBuilder_openStream(outBuilder, &playbackStream_);
    AAudioStreamBuilder_delete(outBuilder);

    if (res != AAUDIO_OK) {
        LOGE("Failed to open AAudio playback stream: %d", res);
        AAudioStream_close(captureStream_);
        return false;
    }

    LOGI("AAudio streams successfully initialized (MMAP Exclusive Mode)");
    return true;
}

bool AAudioEngine::start() {
    if (isRunning_.load()) return true;
    if (!captureStream_ || !playbackStream_) {
        if (!init()) return false;
    }

    captureBuffer_.clear();
    playbackBuffer_.clear();

    if (AAudioStream_requestStart(captureStream_) != AAUDIO_OK) return false;
    if (AAudioStream_requestStart(playbackStream_) != AAUDIO_OK) {
        AAudioStream_requestStop(captureStream_);
        return false;
    }

    isRunning_.store(true);
    LOGI("AAudio native engine started");
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

    captureBuffer_.clear();
    playbackBuffer_.clear();
    micRms_.store(0.0f);
    outRms_.store(0.0f);
    LOGI("AAudio native engine stopped");
}

size_t AAudioEngine::writePlaybackPcm(const int16_t* pcm, size_t frames) {
    return playbackBuffer_.write(pcm, frames);
}

size_t AAudioEngine::readCapturePcm(int16_t* pcm, size_t maxFrames) {
    return captureBuffer_.read(pcm, maxFrames);
}

void AAudioEngine::flushPlayback() {
    playbackBuffer_.clear();
    outRms_.store(0.0f);

    if (playbackStream_ && isRunning_.load()) {
        AAudioStream_requestPause(playbackStream_);
        AAudioStream_requestFlush(playbackStream_);
        AAudioStream_requestStart(playbackStream_);
    }
}

void AAudioEngine::setVolume(float vol) {
    playbackVolume_.store(std::clamp(vol, 0.0f, 1.0f), std::memory_order_relaxed);
}

void AAudioEngine::setMicGain(float gain) {
    micGain_.store(std::clamp(gain, 0.5f, 2.0f), std::memory_order_relaxed);
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

    // Дополнение нулями (тишина) при опустошении буфера
    if (read < static_cast<size_t>(numFrames)) {
        std::memset(samples + read, 0, (numFrames - read) * sizeof(int16_t));
    }

    float vol = engine->playbackVolume_.load(std::memory_order_relaxed);
    if (vol < 0.999f) {
        for (int32_t i = 0; i < numFrames; ++i) {
            samples[i] = static_cast<int16_t>(samples[i] * vol);
        }
    }

    float rms = dsp::calculateRms(samples, numFrames);
    engine->outRms_.store(rms, std::memory_order_relaxed);

    // Подготовка Float данных для FFT анализа
    if (numFrames >= static_cast<int32_t>(FFT_SIZE)) {
        dsp::pcm16ToFloat(samples, engine->pcmFloatBuffer_.data(), FFT_SIZE);
        engine->fftProcessor_->process(engine->pcmFloatBuffer_.data(), FFT_SIZE);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

} // namespace client::audio