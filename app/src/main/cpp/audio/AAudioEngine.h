#pragma once

#include <aaudio/AAudio.h>
#include <atomic>
#include <memory>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <queue>
#include "AudioConstants.h"
#include "LockFreeRingBuffer.h"
#include "PolyphaseResampler.h"
#include "dsp/FastFft.h"

namespace client::audio {

constexpr size_t PLAYBACK_DSP_INPUT_CHUNK_FRAMES = 1024;
constexpr size_t PLAYBACK_DSP_MAX_OUTPUT_FRAMES = 8192;
constexpr size_t EARCON_SCRATCH_MAX_FRAMES = 2048;
constexpr size_t CAPTURE_RAW_SCRATCH_FRAMES = 2048;

/**
 * УСТРАНЕНИЕ ДЕФЕКТА 45: Строгий детерминированный конечный автомат движка.
 */
enum class EngineState : int32_t {
    IDLE = 0,
    STARTING = 1,
    RUNNING = 2,
    RECOVERING = 3,
    STOPPING = 4
};

/**
 * УСТРАНЕНИЕ ДЕФЕКТОВ 41 и 43: Таксономия аппаратных сбоев и событий.
 */
enum class StreamFaultType : int32_t {
    NONE = 0,
    SOFT_TIMEOUT = 1,
    HARD_DISCONNECTED = 2,
    SYSTEM_ERROR = 3
};

struct StreamErrorEvent {
    int32_t direction{0}; // 1 = Input (Capture), 2 = Output (Playback)
    int32_t errorCode{0}; // Код AAUDIO_ERROR_*
    StreamFaultType faultType{StreamFaultType::NONE};
    uint64_t timestampNs{0};
};

struct Biquad {
    float b0{1.0f};
    float b1{0.0f};
    float b2{0.0f};
    float a1{0.0f};
    float a2{0.0f};
    float w1{0.0f};
    float w2{0.0f};

    void reset();
    inline float process(float in) {
        const float w0 = in - a1 * w1 - a2 * w2;
        const float out = b0 * w0 + b1 * w1 + b2 * w2;
        w2 = w1;
        w1 = w0;
        return out;
    }

    void makeLowShelf(float fc, float gainDb, float fs);
    void makeHighShelf(float fc, float gainDb, float fs);
};

class AnalogVoiceEnhancer {
public:
    AnalogVoiceEnhancer();
    void reset(int32_t sampleRate);
    void process(int16_t* samples, size_t numFrames, int32_t sampleRate);

private:
    int32_t currentRate_{48000};
    Biquad lowShelf_;
    Biquad highShelf_;
};

class AAudioEngine {
public:
    static AAudioEngine& getInstance();

    bool init(bool isBluetoothMode = false,
              int32_t targetPlaybackSampleRate = SAMPLE_RATE_GEMINI_OUT,
              int32_t inputDeviceId = AAUDIO_UNSPECIFIED,
              int32_t outputDeviceId = AAUDIO_UNSPECIFIED);

    bool start();
    void stop();

    bool startPlayback();

    // Трёхфазный барьер захвата
    bool startCapture();
    bool activateCaptureDsp();
    bool commitCaptureAdmission();
    void stopCapture();

    // УСТРАНЕНИЕ ДЕФЕКТОВ 41 и 44: Изолированный целевой перезапуск конкретных стримов
    bool restartCaptureStream();
    bool restartPlaybackStream();

    size_t writePlaybackPcm(const int16_t* pcm, size_t frames, uint64_t generation);
    size_t readCapturePcm(int16_t* pcm, size_t maxFrames);

    // УСТРАНЕНИЕ ДЕФЕКТОВ 21 и 22: Мгновенный неблокирующий сброс без остановки ЦАП
    void flushPlayback(uint64_t generation);
    void triggerBargeInEarcon();
    void resetEarcon();

    void setVolume(float vol);
    void setMicGain(float gain);

    float getMicRms() const { return micRms_.load(std::memory_order_relaxed); }
    float getOutRms() const { return outRms_.load(std::memory_order_relaxed); }
    bool isMmapActive() const { return isMmapActive_.load(std::memory_order_relaxed); }
    bool isExclusiveSharingActive() const { return isExclusiveSharingActive_.load(std::memory_order_relaxed); }

    // УСТРАНЕНИЕ ДЕФЕКТА 46: Состояние вычисляется непосредственно из инвариантов FSM
    bool isRunning() const {
        return engineState_.load(std::memory_order_acquire) == EngineState::RUNNING;
    }
    bool isDisconnected() const {
        return engineState_.load(std::memory_order_acquire) == EngineState::RECOVERING ||
               isDisconnectedExplicit_.load(std::memory_order_acquire);
    }
    EngineState getEngineState() const {
        return engineState_.load(std::memory_order_acquire);
    }

    // УСТРАНЕНИЕ ДЕФЕКТОВ 41 и 42: Реактивное извлечение событий аппаратных сбоев
    bool pollErrorEvent(StreamErrorEvent& outEvent);
    bool hasPendingError() const {
        return errorEventPending_.load(std::memory_order_acquire);
    }

    // УСТРАНЕНИЕ ДЕФЕКТОВ 47, 48, 49: Метрология аппаратных таймстемпов и номеров пакетов
    uint64_t getCaptureSequenceNumber() const {
        return captureSequenceNumber_.load(std::memory_order_relaxed);
    }
    uint64_t getCaptureTimestampNs() const {
        return lastCaptureTimestampNs_.load(std::memory_order_relaxed);
    }
    uint64_t getPlaybackSequenceNumber() const {
        return playbackSequenceNumber_.load(std::memory_order_relaxed);
    }
    uint64_t getPlaybackPresentationTimestampNs() const {
        return lastPlaybackPresentationTimestampNs_.load(std::memory_order_relaxed);
    }

    int32_t getActualCaptureSampleRate() const {
        return actualCaptureSampleRate_.load(std::memory_order_relaxed);
    }
    int32_t getActualCaptureChannels() const {
        return actualCaptureChannels_.load(std::memory_order_relaxed);
    }
    int32_t getActualPlaybackSampleRate() const {
        return actualPlaybackSampleRate_.load(std::memory_order_relaxed);
    }
    int32_t getActualPlaybackChannels() const {
        return actualPlaybackChannels_.load(std::memory_order_relaxed);
    }
    int32_t getActualPlaybackFormat() const {
        return actualPlaybackFormat_.load(std::memory_order_relaxed);
    }
    int32_t getActualPlaybackBurst() const {
        return actualPlaybackBurst_.load(std::memory_order_relaxed);
    }
    int32_t getActiveInputDeviceId() const {
        return actualInputDeviceId_.load(std::memory_order_relaxed);
    }
    int32_t getActiveOutputDeviceId() const {
        return actualOutputDeviceId_.load(std::memory_order_relaxed);
    }

    size_t getPendingPlaybackFrames() const;

    uint64_t getCaptureDroppedFrames() const {
        return captureDroppedFrames_.load(std::memory_order_relaxed);
    }

    void getSpectrumData(dsp::SpectrumSnapshot& outSnapshot);

private:
    AAudioEngine();
    ~AAudioEngine();

    bool initLocked(bool isBluetoothMode,
                    int32_t targetPlaybackSampleRate,
                    int32_t inputDeviceId,
                    int32_t outputDeviceId);

    void stopLocked();
    void stopCaptureLocked();
    void stopPlaybackLocked();
    void joinPlaybackDspThreadLocked();
    void joinCaptureDspThreadLocked();
    void closeCaptureStreamLocked();
    void closePlaybackStreamLocked();
    bool openCaptureStreamLocked(int32_t inputDeviceId);

    bool waitForStreamState(AAudioStream* stream, aaudio_stream_state_t desired, int timeoutMs);
    bool validateAndPublishPlaybackConfigLocked(int32_t requestedOutputDeviceId);

    aaudio_result_t openPlaybackStreamWithFallback(
        int32_t targetPlaybackSampleRate,
        int32_t outputDeviceId,
        bool isBluetooth);

    void playbackDspThreadLoop();
    void captureDspThreadLoop();

    static aaudio_data_callback_result_t captureCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames);

    static aaudio_data_callback_result_t playbackCallback(
        AAudioStream* stream,
        void* userData,
        void* audioData,
        int32_t numFrames);

    static void errorCallback(
        AAudioStream* stream,
        void* userData,
        aaudio_result_t error);

    void pushErrorEvent(int32_t direction, aaudio_result_t errorCode);

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    std::atomic<AAudioStream*> activeCaptureStream_{nullptr};
    std::atomic<AAudioStream*> activePlaybackStream_{nullptr};

    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureRawBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;

    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackDspInputBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;

    std::mutex lifecycleMutex_;

    // УСТРАНЕНИЕ ДЕФЕКТОВ 45 и 46: Консолидация жизненного цикла в конечный автомат FSM
    alignas(64) std::atomic<EngineState> engineState_{EngineState::IDLE};
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapActive_{false};
    std::atomic<bool> isExclusiveSharingActive_{false};
    std::atomic<bool> isDisconnectedExplicit_{false};

    // Очередь аппаратных сбоев
    std::mutex errorQueueMutex_;
    std::queue<StreamErrorEvent> errorEventQueue_;
    std::atomic<bool> errorEventPending_{false};

    // УСТРАНЕНИЕ ДЕФЕКТОВ 47, 48, 49: Аппаратные счетчики последовательности и таймстемпы
    alignas(64) std::atomic<uint64_t> captureSequenceNumber_{0};
    alignas(64) std::atomic<uint64_t> lastCaptureTimestampNs_{0};
    alignas(64) std::atomic<uint64_t> playbackSequenceNumber_{0};
    alignas(64) std::atomic<uint64_t> lastPlaybackPresentationTimestampNs_{0};

    std::atomic<int32_t> playbackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    std::atomic<int32_t> actualCaptureSampleRate_{SAMPLE_RATE_GEMINI_IN};
    std::atomic<int32_t> actualCaptureChannels_{CHANNEL_COUNT_MONO};
    std::atomic<int32_t> actualPlaybackSampleRate_{SAMPLE_RATE_GEMINI_OUT};
    std::atomic<int32_t> actualPlaybackChannels_{CHANNEL_COUNT_MONO};
    std::atomic<int32_t> actualPlaybackFormat_{static_cast<int32_t>(AAUDIO_FORMAT_PCM_I16)};
    std::atomic<int32_t> actualPlaybackBurst_{0};

    std::atomic<int32_t> actualInputDeviceId_{AAUDIO_UNSPECIFIED};
    std::atomic<int32_t> actualOutputDeviceId_{AAUDIO_UNSPECIFIED};

    std::atomic<int32_t> requestedInputDeviceId_{AAUDIO_UNSPECIFIED};
    std::atomic<int32_t> requestedOutputDeviceId_{AAUDIO_UNSPECIFIED};

    std::atomic<float> playbackVolume_{1.0f};
    std::atomic<float> micGain_{1.0f};

    std::atomic<float> micRms_{0.0f};
    std::atomic<float> outRms_{0.0f};

    alignas(64) std::atomic<uint64_t> captureDroppedFrames_{0};

    std::thread playbackDspThread_;
    std::atomic<bool> playbackDspRunning_{false};
    std::mutex playbackDspWaitMutex_;
    std::condition_variable playbackDspCv_;

    std::thread captureDspThread_;
    std::atomic<bool> captureDspRunning_{false};
    std::mutex captureDspWaitMutex_;
    std::condition_variable captureDspCv_;

    std::atomic<bool> captureIngressBlocked_{true};

    alignas(64) std::atomic<uint64_t> playbackEpoch_{0};
    alignas(64) std::atomic<uint64_t> playbackDspResetAcknowledgedEpoch_{0};

    std::mutex playbackControlMutex_;

    std::atomic<bool> earconRequested_{false};

    std::unique_ptr<dsp::FastFft> fftProcessor_;

    std::vector<int16_t> playbackDspInputScratch_;
    std::vector<int16_t> playbackDspOutputScratch_;
    std::vector<int16_t> earconScratch_;

    std::vector<float> fftBuffer_;
    size_t fftPos_{0};

    std::vector<int16_t> captureRawScratchBuffer_;
    std::vector<int16_t> captureDecimateBuffer_;
    std::vector<int16_t> captureInputScratchBuffer_;

    AnalogVoiceEnhancer voiceEnhancer_;

    PolyphaseResampler24To16 resampler24To16_;
    PolyphaseResampler24To32 resampler24To32_;
    HalfbandResampler24To48 halfbandResampler24To48_;
    StreamingLinearResampler genericResampler_;
    Decimator48To16 captureDecimator48To16_;
    Decimator32To16 captureDecimator32To16_;
    PolyphaseResampler24To16 captureResampler24To16_;
    Resampler44100To16000 captureResampler44100To16000_;
    Upsampler8000To16000 captureUpsampler8To16_;
};

} // namespace client::audio