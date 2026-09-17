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

class AAudioEngine {
public:
    static AAudioEngine& getInstance();

    bool init(bool isBluetoothMode = false, 
              int32_t targetPlaybackSampleRate = SAMPLE_RATE_GEMINI_OUT,
              int32_t inputDeviceId = AAUDIO_UNSPECIFIED,
              int32_t outputDeviceId = AAUDIO_UNSPECIFIED);

    bool start();
    void stop();

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
    bool isMmapActive() const { return isMmapExclusiveActive_.load(std::memory_order_relaxed); }
    bool isDisconnected() const { return isDisconnected_.load(std::memory_order_relaxed); }

    int32_t getActualCaptureSampleRate() const { return actualCaptureSampleRate_.load(std::memory_order_relaxed); }
    int32_t getActualCaptureChannels() const { return actualCaptureChannels_.load(std::memory_order_relaxed); }
    int32_t getActualPlaybackSampleRate() const { return actualPlaybackSampleRate_.load(std::memory_order_relaxed); }

    int32_t getActiveInputDeviceId() const { return actualInputDeviceId_.load(std::memory_order_relaxed); }
    int32_t getActiveOutputDeviceId() const { return actualOutputDeviceId_.load(std::memory_order_relaxed); }

    // AUD-004: Метрика отброшенных входных аудио-фреймов
    uint64_t getCaptureDroppedFrames() const { return captureDroppedFrames_.load(std::memory_order_relaxed); }

    void getSpectrumData(dsp::SpectrumSnapshot& outSnapshot);

private:
    AAudioEngine();
    ~AAudioEngine();

    bool initLocked(bool isBluetoothMode, int32_t targetPlaybackSampleRate,
                    int32_t inputDeviceId, int32_t outputDeviceId);

    void stopLocked();

    aaudio_result_t openPlaybackStreamWithFallback(int32_t targetPlaybackSampleRate,
                                                   int32_t outputDeviceId,
                                                   bool isBluetooth);

    void playbackDspThreadLoop();
    void captureDspThreadLoop();

    static aaudio_data_callback_result_t captureCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static aaudio_data_callback_result_t playbackCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);

    static void errorCallback(
        AAudioStream* stream, void* userData, aaudio_result_t error);

    AAudioStream* captureStream_{nullptr};
    AAudioStream* playbackStream_{nullptr};

    // AUD-003: SPSC очереди захвата
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureRawBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_CAPTURE> captureBuffer_;

    // AUD-001: SPSC очереди воспроизведения
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackDspInputBuffer_;
    LockFreeRingBuffer<int16_t, RING_BUFFER_CAPACITY_PLAYBACK> playbackBuffer_;

    std::mutex lifecycleMutex_;

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isBluetoothMode_{false};
    std::atomic<bool> isMmapExclusiveActive_{false};
    std::atomic<bool> isDisconnected_{false};
    std::atomic<int32_t> playbackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    std::atomic<int32_t> actualCaptureSampleRate_{SAMPLE_RATE_GEMINI_IN};
    std::atomic<int32_t> actualCaptureChannels_{CHANNEL_COUNT_MONO};
    std::atomic<int32_t> actualPlaybackSampleRate_{SAMPLE_RATE_GEMINI_OUT};

    std::atomic<int32_t> actualInputDeviceId_{AAUDIO_UNSPECIFIED};
    std::atomic<int32_t> actualOutputDeviceId_{AAUDIO_UNSPECIFIED};

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

    PolyphaseResampler24To16 resampler24To16_;
    HermiteResampler24To48 resampler24To48_;
    Decimator48To16 captureDecimator48To16_;
    PolyphaseResampler24To16 captureResampler24To16_;
};

} // namespace client::audio