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
#include <array>
#include <cmath>
#include "AudioConstants.h"
#include "LockFreeRingBuffer.h"
#include "PolyphaseResampler.h"
#include "dsp/FastFft.h"

namespace client::audio {

constexpr size_t PLAYBACK_DSP_MAX_OUTPUT_FRAMES = 8192;
constexpr size_t EARCON_SCRATCH_MAX_FRAMES = 2048;
constexpr size_t CAPTURE_RAW_SCRATCH_FRAMES = 2048;
constexpr size_t FFT_TAP_BUFFER_CAPACITY = 4096;

/**
 * Строгий конечный автомат жизненного цикла движка.
 */
enum class EngineState : int32_t {
    IDLE = 0,
    STARTING = 1,
    RUNNING = 2,
    RECOVERING = 3,
    STOPPING = 4
};

/**
 * Таксономия аппаратных сбоев AAudio.
 */
enum class StreamFaultType : int32_t {
    NONE = 0,
    SOFT_TIMEOUT = 1,
    HARD_DISCONNECTED = 2,
    INVALID_STATE = 3,
    SYSTEM_ERROR = 4
};

/**
 * Структурированное событие аппаратного сбоя.
 */
struct StreamErrorEvent {
    int32_t direction{0}; // 1 = Input (Capture), 2 = Output (Playback)
    int32_t errorCode{0}; // Код AAUDIO_ERROR_*
    StreamFaultType faultType{StreamFaultType::NONE};
    uint64_t timestampNs{0};
};

/**
 * Разделение физических возможностей оборудования и активной конфигурации.
 */
struct HardwareAudioCapabilities {
    bool supports16k{false};
    bool supports24k{false};
    bool supports32k{false};
    bool supports48k{false};
    int32_t minBurstFrames{0};
    int32_t maxBurstFrames{0};
};

struct HardwareAudioActiveConfig {
    int32_t sampleRate{0};
    int32_t channelCount{0};
    int32_t format{0};
    int32_t burstFrames{0};
    int32_t bufferSizeFrames{0};
    int32_t bufferCapacityFrames{0};
    int32_t deviceId{0};
    bool isMmap{false};
    bool isExclusive{false};
};

/**
 * Сквозная телеметрия баланса фреймов и качества тракта (E2E Accounting).
 */
struct AudioPipelineDiagnostics {
    uint64_t totalHardwareCapturedFrames{0};
    uint64_t totalDspProcessedFrames{0};
    uint64_t captureDroppedFrames{0};
    uint64_t totalHardwarePlaybackFrames{0};
    uint64_t playbackUnderrunFrames{0};
    uint64_t playbackUnderrunCount{0};
    uint64_t playbackDroppedFrames{0};
    uint32_t streamDisconnectCount{0};
    uint32_t captureErrorCount{0};
    uint32_t playbackErrorCount{0};
    int32_t lastXRunCount{0};
};

/**
 * Однополюсный фильтр подавления постоянной составляющей (DC-Blocker ~15 Гц).
 * Устраняет малейший дрейф нуля перед эквализацией.
 */
struct DcBlocker {
    double x1{0.0};
    double y1{0.0};
    double R{0.998};

    void reset(double fs = 48000.0) {
        x1 = 0.0;
        y1 = 0.0;
        const double omega = 2.0 * 3.141592653589793 * 15.0 / fs;
        R = std::clamp(1.0 - omega, 0.990, 0.999);
    }

    inline double process(double x) {
        if (!std::isfinite(x)) x = 0.0;
        if (!std::isfinite(y1)) y1 = 0.0;
        const double y = x - x1 + R * y1;
        x1 = x;
        y1 = y;
        return y;
    }
};

/**
 * Эталонный цифровой биквадрат в транспонированной прямой форме II (Transposed Direct Form II).
 * 1. Состояния s1 и s2 имеют масштаб входного сигнала (внутренний гейн равен 1, а не 1000).
 * 2. Вычисления выполняются с двойной точностью (double, 53 бита мантиссы).
 * 3. Встроенная защита от заражения NaN: битый отсчет мгновенно гасится без срыва фильтра.
 */
struct BiquadTdf2 {
    double b0{1.0};
    double b1{0.0};
    double b2{0.0};
    double a1{0.0};
    double a2{0.0};
    double s1{0.0};
    double s2{0.0};

    void reset();
    inline double process(double in) {
        if (!std::isfinite(in)) in = 0.0;
        if (!std::isfinite(s1)) s1 = 0.0;
        if (!std::isfinite(s2)) s2 = 0.0;

        const double out = b0 * in + s1;
        s1 = s2 + b1 * in - a1 * out;
        s2 = b2 * in - a2 * out;
        return out;
    }

    void makeLowShelf(double fc, double gainDb, double fs, double S = 0.85);
    void makeHighShelf(double fc, double gainDb, double fs, double S = 0.90);
};

/**
 * Процессор голоса вещательного уровня:
 * - DC Blocker (15 Гц)
 * - Полка теплоты и глубокого грудного баса (Low-Shelf 165 Гц, +3.2 дБ)
 * - Полка прозрачности и артикуляции (High-Shelf 5200 Гц, +2.4 дБ)
 * - Аналоговый кубический сатуратор (Soft Saturator) для плотного громкого звука без клиппинга
 */
class AnalogVoiceEnhancer {
public:
    AnalogVoiceEnhancer();
    void reset(int32_t sampleRate);
    void process(int16_t* samples, size_t numFrames, int32_t sampleRate);

private:
    int32_t currentRate_{48000};
    DcBlocker dcBlocker_;
    BiquadTdf2 lowShelf_;
    BiquadTdf2 highShelf_;
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

    // Трёхфазный барьер безопасного старта микрофона
    bool startCapture();
    bool activateCaptureDsp();
    bool commitCaptureAdmission();
    void stopCapture();

    // Изолированный целевой перезапуск конкретных стримов
    bool restartCaptureStream();
    bool restartPlaybackStream();

    // Неблокирующий Try-Write контракт
    size_t writePlaybackPcm(const int16_t* pcm, size_t frames, uint64_t generation);
    size_t readCapturePcm(int16_t* pcm, size_t maxFrames);

    // Аппаратный Soft-Flush без остановки потока ЦАП
    void flushPlayback(uint64_t generation);
    void triggerBargeInEarcon();
    void resetEarcon();

    void setVolume(float vol);
    void setMicGain(float gain);

    float getMicRms() const { return micRms_.load(std::memory_order_relaxed); }
    float getOutRms() const { return outRms_.load(std::memory_order_relaxed); }
    float getMicNoiseFloorRms() const { return micNoiseFloorRms_.load(std::memory_order_relaxed); }
    bool isMmapActive() const { return isMmapActive_.load(std::memory_order_relaxed); }
    bool isExclusiveSharingActive() const { return isExclusiveSharingActive_.load(std::memory_order_relaxed); }

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

    bool pollErrorEvent(StreamErrorEvent& outEvent);
    bool hasPendingError() const {
        return errorEventPending_.load(std::memory_order_acquire);
    }

    // Метрология аппаратных таймстемпов и последовательности
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

    // Раздельные очереди и физическая задержка в миллисекундах
    size_t getPendingPlaybackInputFrames() const;
    size_t getPendingPlaybackOutputFrames() const;
    size_t getPendingPlaybackFrames() const;
    float getPendingPlaybackDurationMs() const;
    float getTotalEstimatedPlaybackLatencyMs() const;

    // Метрики потерь и качества звукового тракта
    uint64_t getCaptureDroppedFrames() const {
        return captureDroppedFrames_.load(std::memory_order_relaxed);
    }
    uint64_t getPlaybackUnderrunFrames() const {
        return playbackUnderrunFrames_.load(std::memory_order_relaxed);
    }
    uint64_t getPlaybackUnderrunCount() const {
        return playbackUnderrunCount_.load(std::memory_order_relaxed);
    }
    uint64_t getPlaybackDroppedFrames() const {
        return playbackDroppedFrames_.load(std::memory_order_relaxed);
    }
    uint32_t getStreamDisconnectCount() const {
        return streamDisconnectCount_.load(std::memory_order_relaxed);
    }
    int32_t getLastXRunCount() const {
        return lastXRunCount_.load(std::memory_order_relaxed);
    }

    void getAudioDiagnostics(AudioPipelineDiagnostics& outDiagnostics);
    void getErrorHistogram(uint32_t* outArray, size_t arraySize);

    void getSpectrumData(dsp::SpectrumSnapshot& outSnapshot);
    void setPlaybackActiveState(bool isActive);

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
    void joinFftTapThreadLocked();
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
    void fftTapThreadLoop();

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
    StreamFaultType classifyAaudioError(aaudio_result_t errorCode);
    void recordErrorHistogram(aaudio_result_t errorCode);

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    std::atomic<AAudioStream*> activeCaptureStream_{nullptr};
    std::atomic<AAudioStream*> activePlaybackStream_{nullptr};

    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureRawBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;

    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackDspInputBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;
    LockFreeRingBuffer<int16_t, FFT_TAP_BUFFER_CAPACITY> fftTapBuffer_;

    std::mutex lifecycleMutex_;

    alignas(64) std::atomic<EngineState> engineState_{EngineState::IDLE};
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapActive_{false};
    std::atomic<bool> isExclusiveSharingActive_{false};
    std::atomic<bool> isDisconnectedExplicit_{false};

    // Очередь аппаратных сбоев
    std::mutex errorQueueMutex_;
    std::queue<StreamErrorEvent> errorEventQueue_;
    std::atomic<bool> errorEventPending_{false};

    // Метрология аппаратных счетчиков последовательности и таймстемпов
    alignas(64) std::atomic<uint64_t> captureSequenceNumber_{0};
    alignas(64) std::atomic<uint64_t> lastCaptureTimestampNs_{0};
    alignas(64) std::atomic<uint64_t> playbackSequenceNumber_{0};
    alignas(64) std::atomic<uint64_t> lastPlaybackPresentationTimestampNs_{0};

    // Адаптивный джиттер-буфер (AJB) и контроллер скорости вывода (TSM)
    alignas(64) std::atomic<size_t> playbackTargetBufferMs_{PLAYBACK_TARGET_BUFFER_MS};
    alignas(64) std::atomic<uint64_t> lastPlaybackWriteNs_{0};
    alignas(64) std::atomic<int64_t> interArrivalJitterNs_{0};
    float smoothedRateFactor_{1.0f};

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

    alignas(64) std::atomic<float> micNoiseFloorRms_{0.015f};
    std::atomic<bool> isPlaybackRenderingActive_{false};

    // Сквозные счетчики потерь данных
    alignas(64) std::atomic<uint64_t> totalHardwareCapturedFrames_{0};
    alignas(64) std::atomic<uint64_t> totalDspProcessedFrames_{0};
    alignas(64) std::atomic<uint64_t> captureDroppedFrames_{0};

    alignas(64) std::atomic<uint64_t> totalHardwarePlaybackFrames_{0};
    alignas(64) std::atomic<uint64_t> playbackUnderrunFrames_{0};
    alignas(64) std::atomic<uint64_t> playbackUnderrunCount_{0};
    alignas(64) std::atomic<uint64_t> playbackDroppedFrames_{0};

    alignas(64) std::atomic<uint32_t> streamDisconnectCount_{0};
    alignas(64) std::atomic<uint32_t> captureErrorCount_{0};
    alignas(64) std::atomic<uint32_t> playbackErrorCount_{0};
    alignas(64) std::atomic<int32_t> lastXRunCount_{0};

    alignas(64) std::atomic<int32_t> lastTunedXRunCount_{0};

    alignas(64) std::array<std::atomic<uint32_t>, ERROR_HISTOGRAM_BUCKETS> errorHistogram_{};

    std::thread playbackDspThread_;
    std::atomic<bool> playbackDspRunning_{false};
    std::mutex playbackDspWaitMutex_;
    std::condition_variable playbackDspCv_;

    std::thread captureDspThread_;
    std::atomic<bool> captureDspRunning_{false};
    std::mutex captureDspWaitMutex_;
    std::condition_variable captureDspCv_;

    std::thread fftTapThread_;
    std::atomic<bool> fftTapRunning_{false};
    std::mutex fftTapWaitMutex_;
    std::condition_variable fftTapCv_;

    std::atomic<bool> micPipelineAdmitted_{false};

    alignas(64) std::atomic<uint64_t> playbackEpoch_{0};
    alignas(64) std::atomic<uint64_t> playbackDspResetAcknowledgedEpoch_{0};

    std::mutex playbackControlMutex_;
    std::atomic<bool> earconRequested_{false};

    std::unique_ptr<dsp::FastFft> fftProcessor_;

    std::vector<int16_t> playbackDspInputScratch_;
    std::vector<int16_t> playbackDspOutputScratch_;
    std::vector<int16_t> earconScratch_;

    std::vector<int16_t> captureRawScratchBuffer_;
    std::vector<int16_t> captureDecimateBuffer_;
    std::vector<int16_t> captureInputScratchBuffer_;

    AnalogVoiceEnhancer voiceEnhancer_;

    PolyphaseResampler24To16 resampler24To16_;
    PolyphaseResampler24To32 resampler24To32_;
    HalfbandResampler24To48 halfbandResampler24To48_;
    StreamingLinearResampler genericResampler_;

    UnifiedCaptureResampler unifiedCaptureResampler_;
};

} // namespace client::audio