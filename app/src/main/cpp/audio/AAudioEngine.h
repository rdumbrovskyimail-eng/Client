// >>> FILE: app/src/main/cpp/audio/AAudioEngine.h
#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <memory>
#include <vector>
#include "AudioConstants.h"
#include "LockFreeRingBuffer.h"
#include "dsp/FastFft.h"

namespace client::audio {

class AAudioEngine {
public:
    static AAudioEngine& getInstance();

    bool init();
    bool start();
    void stop();

    // Запись звука из сети (Gemini Live -> Динамик)
    size_t writePlaybackPcm(const int16_t* pcm, size_t frames);

    // Считывание звука микрофона (Микрофон -> Gemini Live)
    size_t readCapturePcm(int16_t* pcm, size_t maxFrames);

    // Мгновенный сброс буфера динамика при перебивании (Barge-In)
    void flushPlayback();

    void setVolume(float vol);
    void setMicGain(float gain);

    float getMicRms() const { return micRms_.load(std::memory_order_relaxed); }
    float getOutRms() const { return outRms_.load(std::memory_order_relaxed); }

    void getSpectrumUniforms(float* out5Bands);

private:
    AAudioEngine();
    ~AAudioEngine();

    static aaudio_data_callback_result_t captureCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static aaudio_data_callback_result_t playbackCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;

    std::atomic<bool> isRunning_{false};
    std::atomic<float> playbackVolume_{1.0f};
    std::atomic<float> micGain_{1.0f};

    std::atomic<float> micRms_{0.0f};
    std::atomic<float> outRms_{0.0f};

    std::unique_ptr<dsp::FastFft> fftProcessor_;
    std::vector<float> pcmFloatBuffer_;
};

} // namespace client::audio