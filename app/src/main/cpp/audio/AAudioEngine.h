// >>> FILE: app/src/main/cpp/audio/AAudioEngine.h
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
#include "AudioConstants.h"
#include "LockFreeRingBuffer.h"
#include "PolyphaseResampler.h"
#include "dsp/FastFft.h"

namespace client::audio {

constexpr size_t PLAYBACK_DSP_INPUT_CHUNK_FRAMES = 1024;
constexpr size_t PLAYBACK_DSP_MAX_OUTPUT_FRAMES = 8192;
constexpr size_t EARCON_SCRATCH_MAX_FRAMES = 2048;
constexpr size_t CAPTURE_RAW_SCRATCH_FRAMES = 2048;

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
    bool startCapture();
    void stopCapture();

    // AUD-005: generation строго обязателен
    size_t writePlaybackPcm(const int16_t* pcm, size_t frames, uint64_t generation);
    size_t readCapturePcm(int16_t* pcm, size_t maxFrames);

    // AUD-005: generation строго обязателен
    void flushPlayback(uint64_t generation);
    void triggerBargeInEarcon();
    void resetEarcon();

    void setVolume(float vol);
    void setMicGain(float gain);

    float getMicRms() const { return micRms_.load(std::memory_order_relaxed); }
    float getOutRms() const { return outRms_.load(std::memory_order_relaxed); }
    bool isMmapActive() const { return isMmapActive_.load(std::memory_order_relaxed); }
    bool isExclusiveSharingActive() const { return isExclusiveSharingActive_.load(std::memory_order_relaxed); }
    bool isDisconnected() const { return isDisconnected_.load(std::memory_order_relaxed); }

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

    // AUD-004: Метрика отброшенных входных аудио-фреймов
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
    bool flushOutputStreamLocked(bool resumeAfterFlush);

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

    // AUD-063:
    // High bit blocks new callback entries.
    // Low 31 bits count callbacks already inside the critical section.
    //
    // This lets lifecycle code quiesce the playback consumer without
    // modifying the SPSC consumer index while the callback is active.
    static constexpr uint32_t PLAYBACK_CALLBACK_BLOCKED = 0x80000000u;
    static constexpr uint32_t PLAYBACK_CALLBACK_COUNT_MASK = 0x7fffffffu;

    bool tryEnterPlaybackCallback();
    void leavePlaybackCallback();
    void blockPlaybackCallbackAndWait();
    void unblockPlaybackCallback();

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    // Atomic identity barriers let the error callback distinguish an old
    // stream closing in the background from the currently active stream.
    // The callback only publishes a recovery signal; lifecycle code owns close/reopen.
    std::atomic<AAudioStream*> activeCaptureStream_{nullptr};
    std::atomic<AAudioStream*> activePlaybackStream_{nullptr};

    // AUD-003: SPSC queues захвата
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureRawBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;

    // AUD-001: SPSC queues воспроизведения
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackDspInputBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;

    std::mutex lifecycleMutex_;

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapActive_{false};
    std::atomic<bool> isExclusiveSharingActive_{false};
    std::atomic<bool> isDisconnected_{false};

    std::atomic<int32_t> playbackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    std::atomic<int32_t> actualCaptureSampleRate_{SAMPLE_RATE_GEMINI_IN};
    std::atomic<int32_t> actualCaptureChannels_{CHANNEL_COUNT_MONO};
    std::atomic<int32_t> actualPlaybackSampleRate_{SAMPLE_RATE_GEMINI_OUT};
    std::atomic<int32_t> actualPlaybackChannels_{CHANNEL_COUNT_MONO};
    std::atomic<int32_t> actualPlaybackFormat_{static_cast<int32_t>(AAUDIO_FORMAT_PCM_I16)};
    std::atomic<int32_t> actualPlaybackBurst_{0};

    std::atomic<int32_t> actualInputDeviceId_{AAUDIO_UNSPECIFIED};
    std::atomic<int32_t> actualOutputDeviceId_{AAUDIO_UNSPECIFIED};

    // Requested route identifiers are kept separately from actual opened IDs.
    // This is required for independent stream reopen after a disconnect.
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

    alignas(64) std::atomic<uint64_t> playbackEpoch_{0};

    // Generation reset acknowledgement from the sole playback DSP consumer.
    // The input SPSC ring may only be discarded by its consumer thread.
    alignas(64) std::atomic<uint64_t> playbackDspResetAcknowledgedEpoch_{0};

    // AUD-063: realtime callback admission/quiescence state.
    alignas(64)
    std::atomic<uint32_t> playbackCallbackState_{PLAYBACK_CALLBACK_BLOCKED};

    std::mutex playbackControlMutex_;
    std::mutex playbackJniWriteMutex_;

    std::mutex playbackIngressMutex_;
    std::condition_variable playbackIngressCv_;
    std::atomic<bool> inputIngressBlocked_{false};

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

    // Stateful playback DSP belongs to this engine instance. Lifecycle code
    // only resets it while the playback worker is quiescent; the worker itself
    // owns normal-time processing. This removes the global DSP state race.
    AnalogVoiceEnhancer voiceEnhancer_;

    PolyphaseResampler24To16 resampler24To16_;
    HalfbandResampler24To48 halfbandResampler24To48_;
    StreamingLinearResampler genericResampler_;
    Decimator48To16 captureDecimator48To16_;
    Decimator32To16 captureDecimator32To16_;
    PolyphaseResampler24To16 captureResampler24To16_;

    // Stateful causal 8 kHz -> 16 kHz interpolation across capture chunks.
    int16_t lastCaptureSample8k_{0};
    bool hasLastCaptureSample8k_{false};
};

} // namespace client::audio