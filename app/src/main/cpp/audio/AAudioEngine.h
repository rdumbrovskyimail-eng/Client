// >>> FILE: app/src/main/cpp/audio/AAudioEngine.h
#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <memory>
#include <vector>
#include <mutex>
#include "AudioConstants.h"
#include "LockFreeRingBuffer.h"
#include "PolyphaseResampler.h"
#include "dsp/FastFft.h"

namespace client::audio {

class AAudioEngine {
public:
    static AAudioEngine& getInstance();

    bool init(bool isBluetoothMode = false, int32_t targetPlaybackSampleRate = SAMPLE_RATE_GEMINI_OUT);
    bool start();
    void stop();

    // E-09, ERR-07: Потокобезопасная запись звука без выделения DirectByteBuffer и динамических реаллокаций
    size_t writePlaybackPcm(const int16_t* pcm, size_t frames);
    size_t readCapturePcm(int16_t* pcm, size_t maxFrames);

    void flushPlayback();
    void triggerBargeInEarcon();
    void resetEarcon();

    void setVolume(float vol);
    void setMicGain(float gain);

    float getMicRms() const { return micRms_.load(std::memory_order_relaxed); }
    float getOutRms() const { return outRms_.load(std::memory_order_relaxed); }
    bool isMmapActive() const { return isMmapExclusiveActive_.load(std::memory_order_relaxed); }

    int32_t getActualCaptureSampleRate() const { return actualCaptureSampleRate_.load(std::memory_order_relaxed); }
    int32_t getActualPlaybackSampleRate() const { return actualPlaybackSampleRate_.load(std::memory_order_relaxed); }

    // E-03, ERR-05: Возврат согласованного снимка спектра через Wait-Free алгоритм Андерсона
    void getSpectrumData(dsp::SpectrumSnapshot& outSnapshot);

private:
    AAudioEngine();
    ~AAudioEngine();

    static aaudio_data_callback_result_t captureCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static aaudio_data_callback_result_t playbackCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    // Обработчик системных ошибок стрима и потери Bluetooth-маршрута (AAUDIO_ERROR_DISCONNECTED)
    static void errorCallback(
        AAudioStream* stream, void* userData, aaudio_result_t error);

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapExclusiveActive_{false};
    std::atomic<int32_t> playbackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    // E-07: Подтверждённая HAL частота микрофона и динамика
    std::atomic<int32_t> actualCaptureSampleRate_{SAMPLE_RATE_GEMINI_IN};
    std::atomic<int32_t> actualPlaybackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    std::atomic<float> playbackVolume_{1.0f};
    std::atomic<float> micGain_{1.0f};

    std::atomic<float> micRms_{0.0f};
    std::atomic<float> outRms_{0.0f};

    // E-10: Фаза генератора Earcon
    std::atomic<size_t> earconPhase_{EARCON_INACTIVE_PHASE};

    std::unique_ptr<dsp::FastFft> fftProcessor_;

    // E-02: Накопитель с перекрытием для БПФ
    std::vector<float> fftAccumulator_;
    size_t fftAccumulatorPos_{0};

    // ERR-07: Мьютекс для защиты ресемплеров и скретчпада при смене маршрутов
    std::mutex playbackWriteMutex_;

    // ERR-07: Предвыделенный статический буфер ресемплинга на 16384 сэмпла (Zero-Allocation)
    std::vector<int16_t> resampleScratchBuffer_;

    // ERR-04: Предвыделенный буфер децимации для исключения Stack Buffer Overflow
    std::vector<int16_t> captureDecimateBuffer_;

    PolyphaseResampler24To16 resampler24To16_;
    LinearResampler24To48 resampler24To48_;
    Decimator48To16 captureDecimator48To16_;
};

} // namespace client::audio