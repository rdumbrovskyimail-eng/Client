#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <memory>
#include <vector>
#include "AudioConstants.h"
#include "LockFreeRingBuffer.h"
#include "PolyphaseResampler.h"
#include "dsp/FastFft.h"

namespace client::audio {

class AAudioEngine {
public:
    static AAudioEngine& getInstance();

    // Инициализация тракта: isBluetoothMode переключает Exclusive MMAP на Shared Low-Latency
    bool init(bool isBluetoothMode = false, int32_t targetPlaybackSampleRate = SAMPLE_RATE_GEMINI_OUT);
    bool start();
    void stop();

    // Запись звука из сети в динамик
    size_t writePlaybackPcm(const int16_t* pcm, size_t frames);

    // Считывание звука микрофона для отправки в Gemini
    size_t readCapturePcm(int16_t* pcm, size_t maxFrames);

    // Мгновенный lock-free сброс буфера при перебивании (Barge-In)
    void flushPlayback();

    // Синтез звукового микро-клика (Earcon) для CMF Buds 2 при перебивании
    void triggerBargeInEarcon();

    void setVolume(float vol);
    void setMicGain(float gain);
    void setRouteMode(bool isBluetooth, int32_t targetSampleRate);

    float getMicRms() const { return micRms_.load(std::memory_order_relaxed); }
    float getOutRms() const { return outRms_.load(std::memory_order_relaxed); }
    bool isMmapActive() const { return isMmapExclusiveActive_.load(std::memory_order_relaxed); }

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
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapExclusiveActive_{false};
    std::atomic<int32_t> playbackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    std::atomic<float> playbackVolume_{1.0f};
    std::atomic<float> micGain_{1.0f};

    std::atomic<float> micRms_{0.0f};
    std::atomic<float> outRms_{0.0f};

    // Состояние генератора Earcon
    std::atomic<size_t> earconPhase_{EARCON_DURATION_FRAMES_24K};

    std::unique_ptr<dsp::FastFft> fftProcessor_;
    std::vector<float> pcmFloatBuffer_;
    std::vector<int16_t> resampleScratchBuffer_;
};

} // namespace client::audio