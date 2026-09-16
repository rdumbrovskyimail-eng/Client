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

    /**
     * Инициализация аудиопотоков с поддержкой явных системных ID устройств ввода/вывода (Bluetooth SCO/BLE/Speaker).
     * Сериализована под защитой lifecycleMutex_.
     */
    bool init(bool isBluetoothMode = false, 
              int32_t targetPlaybackSampleRate = SAMPLE_RATE_GEMINI_OUT,
              int32_t inputDeviceId = AAUDIO_UNSPECIFIED,
              int32_t outputDeviceId = AAUDIO_UNSPECIFIED);

    bool start();
    void stop();

    // Потокобезопасная запись сэмплов без динамических аллокаций
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
    bool isDisconnected() const { return isDisconnected_.load(std::memory_order_relaxed); }

    int32_t getActualCaptureSampleRate() const { return actualCaptureSampleRate_.load(std::memory_order_relaxed); }
    int32_t getActualPlaybackSampleRate() const { return actualPlaybackSampleRate_.load(std::memory_order_relaxed); }

    int32_t getActiveInputDeviceId() const { return actualInputDeviceId_.load(std::memory_order_relaxed); }
    int32_t getActiveOutputDeviceId() const { return actualOutputDeviceId_.load(std::memory_order_relaxed); }

    // Возврат когерентного среза спектра через Wait-Free алгоритм Андерсона
    void getSpectrumData(dsp::SpectrumSnapshot& outSnapshot);

private:
    AAudioEngine();
    ~AAudioEngine();

    // Внутренняя сериализованная инициализация под уже захваченным lifecycleMutex_
    bool initLocked(bool isBluetoothMode, int32_t targetPlaybackSampleRate,
                    int32_t inputDeviceId, int32_t outputDeviceId);

    // Внутренняя сериализованная остановка под уже захваченным lifecycleMutex_
    void stopLocked();

    // Открытие потока воспроизведения с безопасным fallback EXCLUSIVE -> SHARED
    aaudio_result_t openPlaybackStreamWithFallback(int32_t targetPlaybackSampleRate,
                                                   int32_t outputDeviceId,
                                                   bool isBluetooth);

    static aaudio_data_callback_result_t captureCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static aaudio_data_callback_result_t playbackCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static void errorCallback(
        AAudioStream* stream, void* userData, aaudio_result_t error);

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    // Расширенные безблокировочные кольцевые буферы (32K сэмплов захват / 256K сэмплов вывод)
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;

    // Единый нерекурсивный мьютекс управления жизненным циклом
    std::mutex lifecycleMutex_;

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapExclusiveActive_{false};
    std::atomic<bool> isDisconnected_{false};
    std::atomic<int32_t> playbackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    // Подтвержденные HAL частоты дискретизации микрофона и динамика
    std::atomic<int32_t> actualCaptureSampleRate_{SAMPLE_RATE_GEMINI_IN};
    std::atomic<int32_t> actualPlaybackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    // Подтвержденные аппаратные ID устройств ввода и вывода
    std::atomic<int32_t> actualInputDeviceId_{AAUDIO_UNSPECIFIED};
    std::atomic<int32_t> actualOutputDeviceId_{AAUDIO_UNSPECIFIED};

    std::atomic<float> playbackVolume_{1.0f};
    std::atomic<float> micGain_{1.0f};

    std::atomic<float> micRms_{0.0f};
    std::atomic<float> outRms_{0.0f};

    // Фазовый аккумулятор генератора Earcon
    std::atomic<size_t> earconPhase_{EARCON_INACTIVE_PHASE};

    std::unique_ptr<dsp::FastFft> fftProcessor_;

    // Накопитель с перекрытием для БПФ
    std::vector<float> fftAccumulator_;
    size_t fftAccumulatorPos_{0};

    // Мьютекс для защиты скретчпада писателя в JNI-потоке (НЕ блокирует RT playbackCallback!)
    std::mutex playbackWriteMutex_;

    // Статически предвыделенные буферы ресемплинга и децимации
    std::vector<int16_t> resampleScratchBuffer_;
    std::vector<int16_t> captureDecimateBuffer_;
    std::vector<int16_t> captureInputScratchBuffer_;

    // Буфер отложенного сброса ресемплированного вывода при противодавлении ring buffer
    std::vector<int16_t> resamplePendingBuffer_;
    size_t resamplePendingOffset_{0};
    size_t resamplePendingCount_{0};

    PolyphaseResampler24To16 resampler24To16_;
    HermiteResampler24To48 resampler24To48_;
    Decimator48To16 captureDecimator48To16_;
};

} // namespace client::audio