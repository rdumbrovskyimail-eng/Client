// >>> FILE: app/src/main/cpp/audio/AAudioEngine.cpp
#include "AAudioEngine.h"
#include "NativeLogQueue.h"
#include "dsp/NeonDspUtils.h"

#include <android/log.h>
#include <pthread.h>

#include <cstdio>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <chrono>
#include <thread>

#define LOG_TAG "NativeAudioEngine"

#undef LOGI
#undef LOGW
#undef LOGE

#define LOGI(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(4, LOG_TAG, _buf); \
} while (0)

#define LOGW(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(5, LOG_TAG, _buf); \
} while (0)

#define LOGE(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(6, LOG_TAG, _buf); \
} while (0)

namespace {

struct Biquad {
    float b0{1.0f};
    float b1{0.0f};
    float b2{0.0f};

    float a1{0.0f};
    float a2{0.0f};

    float w1{0.0f};
    float w2{0.0f};

    void reset() {
        w1 = 0.0f;
        w2 = 0.0f;
    }

    inline float process(float in) {
        float w0 = in - a1 * w1 - a2 * w2;

        float out =
            b0 * w0 +
            b1 * w1 +
            b2 * w2;

        w2 = w1;
        w1 = w0;

        return out;
    }

    void makeLowShelf(float fc, float gainDb, float fs) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float omega = 2.0f * 3.14159265f * fc / fs;
        float sn = std::sin(omega);
        float cs = std::cos(omega);

        float alpha =
            sn / 2.0f *
            std::sqrt(
                (A + 1.0f / A) *
                (1.0f / 0.9f - 1.0f) +
                2.0f
            );

        float beta =
            2.0f * std::sqrt(A) * alpha;

        float a0 =
            (A + 1.0f) +
            (A - 1.0f) * cs +
            beta;

        b0 =
            (A *
             ((A + 1.0f) -
              (A - 1.0f) * cs +
              beta)) / a0;

        b1 =
            (2.0f * A *
             ((A - 1.0f) -
              (A + 1.0f) * cs)) / a0;

        b2 =
            (A *
             ((A + 1.0f) -
              (A - 1.0f) * cs -
              beta)) / a0;

        a1 =
            (-2.0f *
             ((A - 1.0f) +
              (A + 1.0f) * cs)) / a0;

        a2 =
            ((A + 1.0f) +
             (A - 1.0f) * cs -
             beta) / a0;
    }

    void makeHighShelf(float fc, float gainDb, float fs) {
        float A = std::pow(10.0f, gainDb / 40.0f);
        float omega = 2.0f * 3.14159265f * fc / fs;
        float sn = std::sin(omega);
        float cs = std::cos(omega);

        float alpha =
            sn / 2.0f *
            std::sqrt(
                (A + 1.0f / A) *
                (1.0f / 0.9f - 1.0f) +
                2.0f
            );

        float beta =
            2.0f * std::sqrt(A) * alpha;

        float a0 =
            (A + 1.0f) -
            (A - 1.0f) * cs +
            beta;

        b0 =
            (A *
             ((A + 1.0f) +
              (A - 1.0f) * cs +
              beta)) / a0;

        b1 =
            (-2.0f * A *
             ((A - 1.0f) +
              (A + 1.0f) * cs)) / a0;

        b2 =
            (A *
             ((A + 1.0f) -
              (A - 1.0f) * cs -
              beta)) / a0;

        a1 =
            (2.0f *
             ((A - 1.0f) -
              (A + 1.0f) * cs)) / a0;

        a2 =
            ((A + 1.0f) -
             (A - 1.0f) * cs -
             beta) / a0;
    }
};

class AnalogVoiceEnhancer {
public:
    AnalogVoiceEnhancer() {
        reset(48000);
    }

    void reset(int32_t sampleRate) {
        currentRate_ = sampleRate;

        float fs =
            static_cast<float>(
                sampleRate > 0
                    ? sampleRate
                    : 48000
            );

        lowShelf_.reset();
        highShelf_.reset();

        lowShelf_.makeLowShelf(
            160.0f,
            3.5f,
            fs
        );

        float highFc =
            std::min(
                5000.0f,
                fs * 0.44f
            );

        highShelf_.makeHighShelf(
            highFc,
            3.2f,
            fs
        );
    }

    void process(
        int16_t* samples,
        size_t numFrames,
        int32_t sampleRate) {

        if (samples == nullptr || numFrames == 0) {
            return;
        }

        if (sampleRate != currentRate_ &&
            sampleRate > 0) {
            reset(sampleRate);
        }

        constexpr float PRE_DRIVE = 1.48f;
        constexpr float INV_32768 = 1.0f / 32768.0f;

        for (size_t i = 0;
             i < numFrames;
             ++i) {

            float x =
                static_cast<float>(samples[i]) *
                INV_32768 *
                PRE_DRIVE;

            x = lowShelf_.process(x);
            x = highShelf_.process(x);

            x = x + 0.12f * (x * x);

            float absX = std::abs(x);

            float y =
                (absX < 1.0f)
                ? (x - 0.22f * x * x * x)
                : (
                    (x > 0.0f ? 1.0f : -1.0f) *
                    (
                        0.78f +
                        0.22f *
                        (
                            1.0f -
                            std::exp(
                                -2.0f *
                                (absX - 1.0f)
                            )
                        )
                    )
                  );

            int32_t outSample =
                static_cast<int32_t>(
                    y * 32767.0f
                );

            samples[i] =
                static_cast<int16_t>(
                    std::clamp(
                        outSample,
                        -32768,
                        32767
                    )
                );
        }
    }

private:
    int32_t currentRate_{48000};

    Biquad lowShelf_;
    Biquad highShelf_;
};

static AnalogVoiceEnhancer s_voiceEnhancer;

} // namespace

namespace client::audio {

AAudioEngine& AAudioEngine::getInstance() {
    static AAudioEngine instance;
    return instance;
}

AAudioEngine::AAudioEngine()
    : fftProcessor_(std::make_unique<dsp::FastFft>()),
      playbackDspInputScratch_(
          PLAYBACK_DSP_INPUT_CHUNK_FRAMES,
          0),
      playbackDspOutputScratch_(
          PLAYBACK_DSP_MAX_OUTPUT_FRAMES,
          0),
      earconScratch_(
          EARCON_SCRATCH_MAX_FRAMES,
          0),
      fftBuffer_(
          FFT_SIZE * 2,
          0.0f),
      fftPos_(0),
      captureRawScratchBuffer_(
          CAPTURE_RAW_SCRATCH_FRAMES,
          0),
      captureDecimateBuffer_(
          CAPTURE_DECIMATE_CAPACITY,
          0),
      captureInputScratchBuffer_(
          CAPTURE_DECIMATE_CAPACITY,
          0) {

    dsp::enableHardwareFtz();
}

AAudioEngine::~AAudioEngine() {
    stop();
}

bool AAudioEngine::init(
    bool isBluetoothMode,
    int32_t targetPlaybackSampleRate,
    int32_t inputDeviceId,
    int32_t outputDeviceId) {

    std::lock_guard<std::mutex> lock(
        lifecycleMutex_);

    return initLocked(
        isBluetoothMode,
        targetPlaybackSampleRate,
        inputDeviceId,
        outputDeviceId);
}

bool AAudioEngine::initLocked(
    bool isBluetoothMode,
    int32_t targetPlaybackSampleRate,
    int32_t inputDeviceId,
    int32_t outputDeviceId) {

    stopLocked();

    isDisconnected_.store(
        false,
        std::memory_order_release);

    {
        std::scoped_lock lock(
            playbackControlMutex_,
            playbackJniWriteMutex_);

        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        captureResampler24To16_.reset();

        resetEarcon();

        s_voiceEnhancer.reset(
            targetPlaybackSampleRate);

        fftPos_ = 0;

        playbackEpoch_.store(
            0,
            std::memory_order_release);

        inputIngressBlocked_.store(
            false,
            std::memory_order_release);
    }

    captureDroppedFrames_.store(
        0,
        std::memory_order_relaxed);

    captureRawBuffer_.clear();
    captureBuffer_.clear();
    playbackDspInputBuffer_.clear();
    playbackBuffer_.clear();

    isBluetoothMode_.store(
        isBluetoothMode,
        std::memory_order_relaxed);

    playbackSampleRate_.store(
        targetPlaybackSampleRate,
        std::memory_order_relaxed);

    LOGI(
        "AAudioEngine::initLocked: BT=%d, targetRate=%d, inDevId=%d, outDevId=%d",
        (int)isBluetoothMode,
        targetPlaybackSampleRate,
        inputDeviceId,
        outputDeviceId);

    AAudioStreamBuilder* inBuilder = nullptr;

    if (AAudio_createStreamBuilder(
            &inBuilder) != AAUDIO_OK) {

        LOGE(
            "Failed to create capture stream builder");

        return false;
    }

    AAudioStreamBuilder_setDirection(
        inBuilder,
        AAUDIO_DIRECTION_INPUT);

    AAudioStreamBuilder_setPerformanceMode(
        inBuilder,
        AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

    AAudioStreamBuilder_setSampleRate(
        inBuilder,
        SAMPLE_RATE_GEMINI_IN);

    AAudioStreamBuilder_setChannelCount(
        inBuilder,
        CHANNEL_COUNT_MONO);

    AAudioStreamBuilder_setFormat(
        inBuilder,
        AAUDIO_FORMAT_PCM_I16);

    if (inputDeviceId > 0) {
        AAudioStreamBuilder_setDeviceId(
            inBuilder,
            inputDeviceId);
    }

    AAudioStreamBuilder_setSharingMode(
        inBuilder,
        AAUDIO_SHARING_MODE_SHARED);

    AAudioStreamBuilder_setInputPreset(
        inBuilder,
        AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION);

    AAudioStreamBuilder_setDataCallback(
        inBuilder,
        captureCallback,
        this);

    AAudioStreamBuilder_setErrorCallback(
        inBuilder,
        errorCallback,
        this);

    aaudio_result_t res =
        AAudioStreamBuilder_openStream(
            inBuilder,
            &captureStream_);

    AAudioStreamBuilder_delete(
        inBuilder);

    if (res != AAUDIO_OK) {
        LOGE(
            "Failed to open capture stream: %d (%s)",
            res,
            AAudio_convertResultToText(res));

        return false;
    }

    const int32_t actualInRate =
        AAudioStream_getSampleRate(
            captureStream_);

    const int32_t actualInChannels =
        AAudioStream_getChannelCount(
            captureStream_);

    const aaudio_format_t actualInFormat =
        AAudioStream_getFormat(
            captureStream_);

    if (actualInFormat != AAUDIO_FORMAT_PCM_I16 ||
        (actualInChannels != 1 &&
         actualInChannels != 2) ||
        (actualInRate != 8000 &&
         actualInRate != 16000 &&
         actualInRate != 24000 &&
         actualInRate != 48000)) {

        LOGE(
            "AAudio capture unsupported format: rate=%d, channels=%d, format=%d",
            actualInRate,
            actualInChannels,
            (int)actualInFormat);

        AAudioStream_close(
            captureStream_);

        captureStream_ = nullptr;

        return false;
    }

    actualCaptureSampleRate_.store(
        actualInRate,
        std::memory_order_release);

    actualCaptureChannels_.store(
        actualInChannels,
        std::memory_order_release);

    actualInputDeviceId_.store(
        AAudioStream_getDeviceId(
            captureStream_),
        std::memory_order_release);

    int32_t inFramesPerCallback =
        AAudioStream_getFramesPerDataCallback(
            captureStream_);

    int32_t inCapacity =
        AAudioStream_getBufferCapacityInFrames(
            captureStream_);

    int32_t inBurst =
        AAudioStream_getFramesPerBurst(
            captureStream_);

    size_t neededCaptureScratch =
        CAPTURE_DECIMATE_CAPACITY;

    if (inCapacity > 0 &&
        static_cast<size_t>(inCapacity * 4) >
            neededCaptureScratch) {

        neededCaptureScratch =
            static_cast<size_t>(
                inCapacity * 4);
    }

    if (inFramesPerCallback > 0 &&
        static_cast<size_t>(inFramesPerCallback * 4) >
            neededCaptureScratch) {

        neededCaptureScratch =
            static_cast<size_t>(
                inFramesPerCallback * 4);
    }

    if (inBurst > 0 &&
        static_cast<size_t>(inBurst * 8) >
            neededCaptureScratch) {

        neededCaptureScratch =
            static_cast<size_t>(
                inBurst * 8);
    }

    if (captureInputScratchBuffer_.size() <
        neededCaptureScratch) {

        captureInputScratchBuffer_.resize(
            neededCaptureScratch,
            0);
    }

    if (captureDecimateBuffer_.size() <
        neededCaptureScratch) {

        captureDecimateBuffer_.resize(
            neededCaptureScratch,
            0);
    }

    if (captureRawScratchBuffer_.size() <
        CAPTURE_RAW_SCRATCH_FRAMES) {

        captureRawScratchBuffer_.resize(
            CAPTURE_RAW_SCRATCH_FRAMES,
            0);
    }

    res =
        openPlaybackStreamWithFallback(
            targetPlaybackSampleRate,
            outputDeviceId,
            isBluetoothMode);

    if (res != AAUDIO_OK) {
        LOGE(
            "Failed to open playback stream: %d (%s)",
            res,
            AAudio_convertResultToText(res));

        AAudioStream_close(
            captureStream_);

        captureStream_ = nullptr;

        return false;
    }

    actualPlaybackSampleRate_.store(
        AAudioStream_getSampleRate(
            playbackStream_),
        std::memory_order_release);

    actualOutputDeviceId_.store(
        AAudioStream_getDeviceId(
            playbackStream_),
        std::memory_order_release);

    isMmapExclusiveActive_.store(
        !isBluetoothMode &&
        (
            AAudioStream_getSharingMode(
                playbackStream_) ==
            AAUDIO_SHARING_MODE_EXCLUSIVE
        ),
        std::memory_order_relaxed);

    const int32_t playBurst =
        AAudioStream_getFramesPerBurst(
            playbackStream_);

    const int32_t playCapacity =
        AAudioStream_getBufferCapacityInFrames(
            playbackStream_);

    if (playBurst > 0 &&
        playCapacity > 0) {

        const int32_t targetBufSize =
            std::clamp(
                playBurst * 2,
                playBurst,
                playCapacity);

        const int32_t appliedBufSize =
            AAudioStream_setBufferSizeInFrames(
                playbackStream_,
                targetBufSize);

        LOGI(
            "Playback buffer size tuned: requested=%d, applied=%d (burst=%d, capacity=%d)",
            targetBufSize,
            appliedBufSize,
            playBurst,
            playCapacity);
    }

    if (playbackDspInputScratch_.size() <
        PLAYBACK_DSP_INPUT_CHUNK_FRAMES) {

        playbackDspInputScratch_.resize(
            PLAYBACK_DSP_INPUT_CHUNK_FRAMES,
            0);
    }

    if (playbackDspOutputScratch_.size() <
        PLAYBACK_DSP_MAX_OUTPUT_FRAMES) {

        playbackDspOutputScratch_.resize(
            PLAYBACK_DSP_MAX_OUTPUT_FRAMES,
            0);
    }

    if (earconScratch_.size() <
        EARCON_SCRATCH_MAX_FRAMES) {

        earconScratch_.resize(
            EARCON_SCRATCH_MAX_FRAMES,
            0);
    }

    LOGI(
        "AAudio Initialized: CapRate=%d (Ch=%d, DevId=%d), PlayRate=%d (DevId=%d), MMAP=%d",
        actualCaptureSampleRate_.load(),
        actualCaptureChannels_.load(),
        actualInputDeviceId_.load(),
        actualPlaybackSampleRate_.load(),
        actualOutputDeviceId_.load(),
        isMmapExclusiveActive_.load());

    return true;
}

aaudio_result_t
AAudioEngine::openPlaybackStreamWithFallback(
    int32_t targetPlaybackSampleRate,
    int32_t outputDeviceId,
    bool isBluetooth) {

    const aaudio_sharing_mode_t desiredSharing =
        isBluetooth
            ? AAUDIO_SHARING_MODE_SHARED
            : AAUDIO_SHARING_MODE_EXCLUSIVE;

    auto buildAndOpen =
        [this,
         targetPlaybackSampleRate,
         outputDeviceId]
        (
            aaudio_sharing_mode_t sharingMode)
        -> aaudio_result_t {

        AAudioStreamBuilder* outBuilder = nullptr;

        if (AAudio_createStreamBuilder(
                &outBuilder) != AAUDIO_OK) {

            return AAUDIO_ERROR_INTERNAL;
        }

        AAudioStreamBuilder_setDirection(
            outBuilder,
            AAUDIO_DIRECTION_OUTPUT);

        AAudioStreamBuilder_setPerformanceMode(
            outBuilder,
            AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);

        AAudioStreamBuilder_setChannelCount(
            outBuilder,
            CHANNEL_COUNT_MONO);

        AAudioStreamBuilder_setFormat(
            outBuilder,
            AAUDIO_FORMAT_PCM_I16);

        AAudioStreamBuilder_setSampleRate(
            outBuilder,
            targetPlaybackSampleRate);

        if (outputDeviceId > 0) {
            AAudioStreamBuilder_setDeviceId(
                outBuilder,
                outputDeviceId);
        }

        AAudioStreamBuilder_setSharingMode(
            outBuilder,
            sharingMode);

        AAudioStreamBuilder_setUsage(
            outBuilder,
            AAUDIO_USAGE_VOICE_COMMUNICATION);

        AAudioStreamBuilder_setDataCallback(
            outBuilder,
            playbackCallback,
            this);

        AAudioStreamBuilder_setErrorCallback(
            outBuilder,
            errorCallback,
            this);

        if (playbackStream_ != nullptr) {
            AAudioStream_close(
                playbackStream_);

            playbackStream_ = nullptr;
        }

        aaudio_result_t openRes =
            AAudioStreamBuilder_openStream(
                outBuilder,
                &playbackStream_);

        AAudioStreamBuilder_delete(
            outBuilder);

        if (openRes != AAUDIO_OK &&
            playbackStream_ != nullptr) {

            AAudioStream_close(
                playbackStream_);

            playbackStream_ = nullptr;
        }

        return openRes;
    };

    aaudio_result_t res =
        buildAndOpen(desiredSharing);

    if (res != AAUDIO_OK &&
        desiredSharing ==
            AAUDIO_SHARING_MODE_EXCLUSIVE) {

        LOGI(
            "Playback EXCLUSIVE open failed (%d: %s). Conservative fallback to SHARED...",
            res,
            AAudio_convertResultToText(res));

        res =
            buildAndOpen(
                AAUDIO_SHARING_MODE_SHARED);
    }

    return res;
}

bool AAudioEngine::start() {
    std::lock_guard<std::mutex> lock(
        lifecycleMutex_);

    if (isRunning_.load()) {
        return true;
    }

    if (!captureStream_ ||
        !playbackStream_) {

        LOGI(
            "start() called with null streams, reinitializing...");

        if (!initLocked(
                isBluetoothMode_.load(),
                playbackSampleRate_.load(),
                actualInputDeviceId_.load(),
                actualOutputDeviceId_.load())) {

            return false;
        }
    }

    // AUD-063:
    // Keep the realtime playback callback quiesced while lifecycle
    // code clears queues and starts the stream.
    blockPlaybackCallbackAndWait();

    captureDroppedFrames_.store(
        0,
        std::memory_order_relaxed);

    captureRawBuffer_.clear();
    captureBuffer_.clear();
    playbackDspInputBuffer_.clear();
    playbackBuffer_.clear();

    aaudio_result_t res =
        AAudioStream_requestStart(
            captureStream_);

    if (res != AAUDIO_OK) {
        LOGE(
            "AAudioStream_requestStart(capture) failed: %d (%s)",
            res,
            AAudio_convertResultToText(res));

        stopLocked();
        return false;
    }

    res =
        AAudioStream_requestStart(
            playbackStream_);

    if (res != AAUDIO_OK) {
        LOGE(
            "AAudioStream_requestStart(playback) failed: %d (%s)",
            res,
            AAudio_convertResultToText(res));

        AAudioStream_requestStop(
            captureStream_);

        stopLocked();
        return false;
    }

    playbackDspRunning_.store(
        true,
        std::memory_order_release);

    playbackDspThread_ =
        std::thread(
            &AAudioEngine::playbackDspThreadLoop,
            this);

    captureDspRunning_.store(
        true,
        std::memory_order_release);

    captureDspThread_ =
        std::thread(
            &AAudioEngine::captureDspThreadLoop,
            this);

    isRunning_.store(
        true,
        std::memory_order_release);

    unblockPlaybackCallback();

    LOGI(
        "AAudioEngine, Playback DSP worker and Capture DSP worker started successfully");

    return true;
}

void AAudioEngine::stop() {
    std::lock_guard<std::mutex> lock(
        lifecycleMutex_);

    stopLocked();
}

void AAudioEngine::stopLocked() {
    // AUD-063:
    // Quiesce playback callback before touching stream lifecycle or
    // consumer-owned playbackBuffer_.
    blockPlaybackCallbackAndWait();

    bool wasRunning =
        isRunning_.exchange(false);

    if (!wasRunning &&
        !captureStream_ &&
        !playbackStream_ &&
        !playbackDspRunning_.load() &&
        !captureDspRunning_.load()) {

        return;
    }

    LOGI(
        "Stopping AAudioEngine (stopLocked)...");

    if (captureDspRunning_.load(
            std::memory_order_acquire)) {

        captureDspRunning_.store(
            false,
            std::memory_order_release);

        captureDspCv_.notify_all();

        if (captureDspThread_.joinable()) {
            captureDspThread_.join();
        }
    }

    if (playbackDspRunning_.load(
            std::memory_order_acquire)) {

        playbackDspRunning_.store(
            false,
            std::memory_order_release);

        playbackDspCv_.notify_all();
        playbackIngressCv_.notify_all();

        if (playbackDspThread_.joinable()) {
            playbackDspThread_.join();
        }
    }

    if (captureStream_) {
        AAudioStream_requestStop(
            captureStream_);

        AAudioStream_close(
            captureStream_);

        captureStream_ = nullptr;
    }

    if (playbackStream_) {
        AAudioStream_requestStop(
            playbackStream_);

        AAudioStream_close(
            playbackStream_);

        playbackStream_ = nullptr;
    }

    {
        std::scoped_lock lock(
            playbackControlMutex_,
            playbackJniWriteMutex_);

        resampler24To16_.reset();
        resampler24To48_.reset();
        captureDecimator48To16_.reset();
        captureResampler24To16_.reset();

        resetEarcon();

        s_voiceEnhancer.reset(48000);

        fftPos_ = 0;

        inputIngressBlocked_.store(
            false,
            std::memory_order_release);
    }

    captureRawBuffer_.clear();
    captureBuffer_.clear();
    playbackDspInputBuffer_.clear();
    playbackBuffer_.clear();

    micRms_.store(
        0.0f,
        std::memory_order_relaxed);

    outRms_.store(
        0.0f,
        std::memory_order_relaxed);

    isMmapExclusiveActive_.store(
        false,
        std::memory_order_relaxed);

    LOGI(
        "AAudioEngine stopped cleanly");
}

// AUD-003:
void AAudioEngine::captureDspThreadLoop() {
    pthread_setname_np(
        pthread_self(),
        "AudioCapWorker");

    dsp::enableHardwareFtz();

    while (
        captureDspRunning_.load(
            std::memory_order_acquire)) {

        const int32_t channels =
            actualCaptureChannels_.load(
                std::memory_order_relaxed);

        const size_t ch =
            static_cast<size_t>(
                channels > 0
                    ? channels
                    : 1);

        size_t availableSamples =
            captureRawBuffer_.availableRead();

        availableSamples -=
            availableSamples % ch;

        if (availableSamples == 0) {

            std::unique_lock<std::mutex> lock(
                captureDspWaitMutex_);

            captureDspCv_.wait_for(
                lock,
                std::chrono::milliseconds(2),
                [this]() {
                    return !captureDspRunning_.load(
                               std::memory_order_acquire)
                        ||
                        captureRawBuffer_.availableRead() > 0;
                });

            continue;
        }

        size_t maxScratchSamples =
            captureRawScratchBuffer_.size();

        maxScratchSamples -=
            maxScratchSamples % ch;

        const size_t samplesToRead =
            std::min(
                availableSamples,
                maxScratchSamples);

        const size_t readSamples =
            captureRawBuffer_.read(
                captureRawScratchBuffer_.data(),
                samplesToRead);

        if (readSamples == 0) {
            continue;
        }

        const size_t chunkFrames =
            readSamples / ch;

        const int16_t* inPtr =
            captureRawScratchBuffer_.data();

        int16_t* monoBuf =
            captureInputScratchBuffer_.data();

        const float gain =
            micGain_.load(
                std::memory_order_relaxed);

        const bool applyGain =
            std::abs(gain - 1.0f) > 0.001f;

        const int32_t capRate =
            actualCaptureSampleRate_.load(
                std::memory_order_relaxed);

        if (channels == 2) {

            for (size_t i = 0;
                 i < chunkFrames;
                 ++i) {

                int32_t mixed =
                    (
                        static_cast<int32_t>(
                            inPtr[i * 2]) +
                        static_cast<int32_t>(
                            inPtr[i * 2 + 1])
                    ) / 2;

                if (applyGain) {
                    mixed =
                        static_cast<int32_t>(
                            std::round(
                                mixed * gain));
                }

                monoBuf[i] =
                    static_cast<int16_t>(
                        std::clamp(
                            mixed,
                            -32768,
                            32767));
            }

        } else {

            if (applyGain) {

                for (size_t i = 0;
                     i < chunkFrames;
                     ++i) {

                    int32_t amplified =
                        static_cast<int32_t>(
                            std::round(
                                inPtr[i] * gain));

                    monoBuf[i] =
                        static_cast<int16_t>(
                            std::clamp(
                                amplified,
                                -32768,
                                32767));
                }

            } else {

                std::memcpy(
                    monoBuf,
                    inPtr,
                    chunkFrames *
                    sizeof(int16_t));
            }
        }

        const size_t decimateScratchCap =
            captureDecimateBuffer_.size();

        int16_t* finalPcm =
            monoBuf;

        size_t finalFrames =
            chunkFrames;

        if (capRate == 48000) {

            int16_t* decBuf =
                captureDecimateBuffer_.data();

            size_t processed =
                captureDecimator48To16_.process(
                    monoBuf,
                    chunkFrames,
                    decBuf,
                    decimateScratchCap);

            finalPcm = decBuf;
            finalFrames = processed;

        } else if (capRate == 24000) {

            int16_t* decBuf =
                captureDecimateBuffer_.data();

            size_t processed =
                captureResampler24To16_.process(
                    monoBuf,
                    chunkFrames,
                    decBuf);

            finalPcm = decBuf;
            finalFrames = processed;

        } else if (capRate == 8000) {

            int16_t* upBuf =
                captureDecimateBuffer_.data();

            size_t outIdx = 0;

            for (
                size_t i = 0;
                i < chunkFrames &&
                (outIdx + 1) < decimateScratchCap;
                ++i) {

                int16_t current =
                    monoBuf[i];

                int16_t next =
                    (i + 1 < chunkFrames)
                        ? monoBuf[i + 1]
                        : current;

                upBuf[outIdx++] =
                    current;

                upBuf[outIdx++] =
                    static_cast<int16_t>(
                        (
                            static_cast<int32_t>(
                                current) +
                            static_cast<int32_t>(
                                next)
                        ) / 2);
            }

            finalPcm = upBuf;
            finalFrames = outIdx;
        }

        if (finalFrames > 0) {

            micRms_.store(
                dsp::calculateRms(
                    finalPcm,
                    finalFrames),
                std::memory_order_relaxed);

            captureBuffer_.write(
                finalPcm,
                finalFrames);
        }
    }
}

// AUD-062:
void AAudioEngine::playbackDspThreadLoop() {
    pthread_setname_np(
        pthread_self(),
        "AudioDspWorker");

    dsp::enableHardwareFtz();

    constexpr size_t TARGET_BUFFER_MS = 40;

    int16_t* input =
        playbackDspInputScratch_.data();

    int16_t* output =
        playbackDspOutputScratch_.data();

    int16_t* earconBuf =
        earconScratch_.data();

    uint64_t workerDspEpoch =
        playbackEpoch_.load(
            std::memory_order_acquire);

    while (
        playbackDspRunning_.load(
            std::memory_order_acquire)) {

        const uint64_t activeEpoch =
            playbackEpoch_.load(
                std::memory_order_acquire);

        if (activeEpoch != workerDspEpoch) {

            const int32_t currentRate =
                std::max(
                    1,
                    actualPlaybackSampleRate_.load(
                        std::memory_order_acquire));

            s_voiceEnhancer.reset(
                currentRate);

            resampler24To48_.reset();
            resampler24To16_.reset();

            fftPos_ = 0;

            std::fill(
                fftBuffer_.begin(),
                fftBuffer_.end(),
                0.0f);

            // playbackDspInputBuffer_ consumer ownership belongs to
            // this worker, so it is safe to discard it here after
            // observing the generation transition.
            playbackDspInputBuffer_.discardAll();

            workerDspEpoch = activeEpoch;

            inputIngressBlocked_.store(
                false,
                std::memory_order_release);

            playbackIngressCv_.notify_all();
        }

        const int32_t actualRate =
            std::max(
                1,
                actualPlaybackSampleRate_.load(
                    std::memory_order_acquire));

        const size_t targetBufferFrames =
            std::max<size_t>(
                1,
                static_cast<size_t>(
                    actualRate *
                    TARGET_BUFFER_MS /
                    1000));

        if (earconRequested_.load(
                std::memory_order_acquire)) {

            const size_t earconFrames =
                std::min<size_t>(
                    static_cast<size_t>(
                        actualRate *
                        (EARCON_DURATION_MS /
                         1000.0f)),
                    earconScratch_.size());

            if (playbackBuffer_.availableWrite() >=
                earconFrames) {

                for (size_t i = 0;
                     i < earconFrames;
                     ++i) {

                    const float t =
                        static_cast<float>(i) /
                        static_cast<float>(
                            actualRate);

                    const float env =
                        std::cos(
                            (
                                3.14159265f *
                                static_cast<float>(i)
                            ) /
                            (
                                2.0f *
                                static_cast<float>(
                                    earconFrames)
                            ));

                    const float sample =
                        std::sin(
                            2.0f *
                            3.14159265f *
                            EARCON_FREQ_HZ *
                            t
                        ) *
                        env *
                        env *
                        12000.0f;

                    earconBuf[i] =
                        static_cast<int16_t>(
                            std::clamp(
                                sample,
                                -32768.0f,
                                32767.0f));
                }

                {
                    std::lock_guard<std::mutex>
                        commitLock(
                            playbackControlMutex_);

                    if (playbackEpoch_.load(
                            std::memory_order_acquire)
                        == activeEpoch) {

                        playbackBuffer_.write(
                            earconBuf,
                            earconFrames);

                        earconRequested_.store(
                            false,
                            std::memory_order_release);
                    }
                }
            }
        }

        const size_t maxOutputFrames =
            static_cast<size_t>(
                std::ceil(
                    static_cast<double>(
                        PLAYBACK_DSP_INPUT_CHUNK_FRAMES) *
                    static_cast<double>(
                        actualRate) /
                    static_cast<double>(
                        SAMPLE_RATE_GEMINI_OUT)));

        const size_t buffered =
            playbackBuffer_.availableRead();

        const size_t freeSpace =
            playbackBuffer_.availableWrite();

        // AUD-062:
        // The worker sleeps once the hardware-facing playback buffer
        // reaches its target watermark.
        //
        // The previous predicate tested only freeSpace, which is nearly
        // always true for a large ring buffer even when buffered data is
        // already above the target, causing immediate wait_for() wakeups.
        if (buffered >= targetBufferFrames ||
            freeSpace < maxOutputFrames) {

            std::unique_lock<std::mutex>
                waitLock(playbackDspWaitMutex_);

            playbackDspCv_.wait_for(
                waitLock,
                std::chrono::milliseconds(5),
                [this,
                 activeEpoch,
                 targetBufferFrames,
                 maxOutputFrames]() {

                    if (!playbackDspRunning_.load(
                            std::memory_order_acquire)) {
                        return true;
                    }

                    if (playbackEpoch_.load(
                            std::memory_order_acquire)
                        != activeEpoch) {
                        return true;
                    }

                    const size_t currentBuffered =
                        playbackBuffer_.availableRead();

                    const size_t currentFree =
                        playbackBuffer_.availableWrite();

                    return
                        currentBuffered <
                            targetBufferFrames
                        &&
                        currentFree >=
                            maxOutputFrames;
                });

            continue;
        }

        size_t inputFrames =
            playbackDspInputBuffer_.read(
                input,
                PLAYBACK_DSP_INPUT_CHUNK_FRAMES);

        if (inputFrames == 0) {

            std::unique_lock<std::mutex>
                waitLock(playbackDspWaitMutex_);

            playbackDspCv_.wait_for(
                waitLock,
                std::chrono::milliseconds(10),
                [this, activeEpoch]() {

                    return
                        !playbackDspRunning_.load(
                            std::memory_order_acquire)
                        ||
                        playbackEpoch_.load(
                            std::memory_order_acquire)
                            != activeEpoch
                        ||
                        playbackDspInputBuffer_
                            .availableRead() > 0;
                });

            continue;
        }

        if (activeEpoch !=
            playbackEpoch_.load(
                std::memory_order_acquire)) {

            continue;
        }

        size_t outputFrames = 0;

        if (actualRate ==
            SAMPLE_RATE_GEMINI_OUT) {

            outputFrames =
                inputFrames;

            std::memcpy(
                output,
                input,
                outputFrames *
                sizeof(int16_t));

        } else if (
            actualRate ==
                SAMPLE_RATE_NATIVE_SPEAKER ||
            actualRate ==
                SAMPLE_RATE_BT_A2DP) {

            outputFrames =
                resampler24To48_.process(
                    input,
                    inputFrames,
                    output);

        } else if (
            actualRate ==
                SAMPLE_RATE_BT_HFP) {

            outputFrames =
                resampler24To16_.process(
                    input,
                    inputFrames,
                    output);

        } else {

            const double ratio =
                static_cast<double>(
                    actualRate) /
                static_cast<double>(
                    SAMPLE_RATE_GEMINI_OUT);

            const size_t wanted =
                std::min(
                    playbackDspOutputScratch_.size(),
                    static_cast<size_t>(
                        inputFrames * ratio));

            for (size_t i = 0;
                 i < wanted;
                 ++i) {

                const double src =
                    static_cast<double>(i) /
                    ratio;

                const size_t idx0 =
                    std::min(
                        static_cast<size_t>(
                            src),
                        inputFrames - 1);

                const size_t idx1 =
                    std::min(
                        idx0 + 1,
                        inputFrames - 1);

                const double frac =
                    src -
                    static_cast<double>(
                        idx0);

                const double s =
                    static_cast<double>(
                        input[idx0]) +
                    frac *
                    (
                        static_cast<double>(
                            input[idx1]) -
                        static_cast<double>(
                            input[idx0])
                    );

                output[i] =
                    static_cast<int16_t>(
                        std::clamp(
                            s,
                            -32768.0,
                            32767.0));
            }

            outputFrames =
                wanted;
        }

        if (outputFrames == 0) {
            continue;
        }

        s_voiceEnhancer.process(
            output,
            outputFrames,
            actualRate);

        const float volume =
            playbackVolume_.load(
                std::memory_order_relaxed);

        if (volume != 1.0f) {

            for (size_t i = 0;
                 i < outputFrames;
                 ++i) {

                const float v =
                    static_cast<float>(
                        output[i]) *
                    volume;

                output[i] =
                    static_cast<int16_t>(
                        std::clamp(
                            v,
                            -32768.0f,
                            32767.0f));
            }
        }

        const float outRms =
            dsp::calculateRms(
                output,
                outputFrames);

        outRms_.store(
            outRms,
            std::memory_order_relaxed);

        const float micRms =
            micRms_.load(
                std::memory_order_relaxed);

        const size_t requiredAccum =
            (actualRate >= 44100)
                ? (FFT_SIZE * 2)
                : FFT_SIZE;

        const size_t hopSize =
            (actualRate >= 44100)
                ? (FFT_HOP_SIZE * 2)
                : FFT_HOP_SIZE;

        for (size_t i = 0;
             i < outputFrames;
             ++i) {

            if (fftPos_ <
                fftBuffer_.size()) {

                fftBuffer_[fftPos_++] =
                    static_cast<float>(
                        output[i]) *
                    (1.0f / 32768.0f);
            }

            if (fftPos_ >= requiredAccum) {

                if (fftProcessor_) {
                    fftProcessor_->process(
                        fftBuffer_.data(),
                        requiredAccum,
                        micRms,
                        outRms,
                        actualRate);
                }

                std::memmove(
                    fftBuffer_.data(),
                    fftBuffer_.data() +
                        hopSize,
                    (
                        requiredAccum -
                        hopSize
                    ) * sizeof(float));

                fftPos_ =
                    requiredAccum -
                    hopSize;
            }
        }

        {
            std::lock_guard<std::mutex>
                commitLock(
                    playbackControlMutex_);

            if (playbackEpoch_.load(
                    std::memory_order_acquire)
                == activeEpoch) {

                if (outputFrames >
                    playbackBuffer_.availableWrite()) {

                    LOGE(
                        "Playback DSP invariant violation: outputFrames=%zu free=%zu",
                        outputFrames,
                        playbackBuffer_.availableWrite());

                    continue;
                }

                const size_t written =
                    playbackBuffer_.write(
                        output,
                        outputFrames);

                if (written != outputFrames) {

                    LOGE(
                        "Playback DSP invariant violation: written=%zu expected=%zu",
                        written,
                        outputFrames);
                }
            }
        }
    }
}

size_t AAudioEngine::writePlaybackPcm(
    const int16_t* pcm,
    size_t frames,
    uint64_t generation) {

    if (pcm == nullptr || frames == 0) {
        return 0;
    }

    if (generation == 0) {
        LOGE(
            "writePlaybackPcm called without authoritative generation");
        return 0;
    }

    if (!playbackDspRunning_.load(
            std::memory_order_acquire)) {
        return 0;
    }

    while (true) {

        {
            std::lock_guard<std::mutex>
                lock(playbackJniWriteMutex_);

            if (generation <
                playbackEpoch_.load(
                    std::memory_order_acquire)) {

                // Stale audio is intentionally discarded.
                // Report it as consumed.
                return frames;
            }

            if (!inputIngressBlocked_.load(
                    std::memory_order_acquire)) {

                const size_t written =
                    playbackDspInputBuffer_.write(
                        pcm,
                        frames);

                if (written > 0) {
                    playbackDspCv_.notify_one();
                }

                return written;
            }
        }

        {
            std::unique_lock<std::mutex>
                lock(playbackIngressMutex_);

            if (!inputIngressBlocked_.load(
                    std::memory_order_acquire)) {

                continue;
            }

            playbackIngressCv_.wait_for(
                lock,
                std::chrono::milliseconds(4),
                [this]() {
                    return
                        !inputIngressBlocked_.load(
                            std::memory_order_acquire)
                        ||
                        !playbackDspRunning_.load(
                            std::memory_order_acquire);
                });
        }

        if (!playbackDspRunning_.load(
                std::memory_order_acquire)) {

            return 0;
        }
    }
}

size_t AAudioEngine::readCapturePcm(
    int16_t* pcm,
    size_t maxFrames) {

    if (pcm == nullptr || maxFrames == 0) {
        return 0;
    }

    return captureBuffer_.read(
        pcm,
        maxFrames);
}

// AUD-063:
//
// Never wait for a callback-generated flush ACK.
//
// Instead:
//   1. block new playback callbacks;
//   2. wait for already admitted callbacks to finish;
//   3. reset playbackBuffer_ only while quiescent;
//   4. publish new epoch;
//   5. unblock callbacks.
//
// This preserves SPSC head ownership.
void AAudioEngine::flushPlayback(
    uint64_t generation) {

    if (generation == 0) {

        LOGE(
            "flushPlayback called without authoritative generation");

        return;
    }

    std::scoped_lock lock(
        playbackControlMutex_,
        playbackJniWriteMutex_);

    const uint64_t currentEpoch =
        playbackEpoch_.load(
            std::memory_order_acquire);

    if (generation <= currentEpoch) {

        LOGW(
            "flushPlayback called with non-monotonic generation=%llu <= currentEpoch=%llu",
            static_cast<unsigned long long>(
                generation),
            static_cast<unsigned long long>(
                currentEpoch));

        return;
    }

    inputIngressBlocked_.store(
        true,
        std::memory_order_release);

    blockPlaybackCallbackAndWait();

    // SPSC consumer ownership is now quiescent.
    playbackBuffer_.discardAllQuiesced();

    // playbackDspInputBuffer_ is intentionally NOT reset here:
    // its consumer is playbackDspThreadLoop itself.
    // That worker sees the new epoch and discards stale input itself.
    playbackEpoch_.store(
        generation,
        std::memory_order_release);

    earconRequested_.store(
        false,
        std::memory_order_release);

    outRms_.store(
        0.0f,
        std::memory_order_relaxed);

    playbackDspCv_.notify_all();
    playbackIngressCv_.notify_all();

    unblockPlaybackCallback();

    LOGI(
        "AAudioEngine: flush committed. Authoritative Epoch=%llu",
        static_cast<unsigned long long>(
            generation));
}

void AAudioEngine::triggerBargeInEarcon() {
    earconRequested_.store(
        true,
        std::memory_order_release);

    playbackDspCv_.notify_all();

    LOGI(
        "AAudioEngine: triggerBargeInEarcon");
}

void AAudioEngine::resetEarcon() {
    earconRequested_.store(
        false,
        std::memory_order_release);
}

void AAudioEngine::setVolume(float vol) {
    playbackVolume_.store(
        std::clamp(
            vol,
            0.0f,
            1.0f),
        std::memory_order_relaxed);
}

void AAudioEngine::setMicGain(float gain) {
    micGain_.store(
        std::clamp(
            gain,
            0.5f,
            2.0f),
        std::memory_order_relaxed);
}

bool AAudioEngine::tryEnterPlaybackCallback() {
    uint32_t state =
        playbackCallbackState_.load(
            std::memory_order_acquire);

    for (;;) {

        if ((state &
             PLAYBACK_CALLBACK_BLOCKED) != 0) {

            return false;
        }

        const uint32_t count =
            state &
            PLAYBACK_CALLBACK_COUNT_MASK;

        if (count ==
            PLAYBACK_CALLBACK_COUNT_MASK) {

            return false;
        }

        const uint32_t desired =
            state + 1u;

        if (playbackCallbackState_
                .compare_exchange_weak(
                    state,
                    desired,
                    std::memory_order_acq_rel,
                    std::memory_order_acquire)) {

            return true;
        }
    }
}

void AAudioEngine::leavePlaybackCallback() {
    playbackCallbackState_.fetch_sub(
        1u,
        std::memory_order_acq_rel);
}

void AAudioEngine::blockPlaybackCallbackAndWait() {
    playbackCallbackState_.fetch_or(
        PLAYBACK_CALLBACK_BLOCKED,
        std::memory_order_acq_rel);

    while (
        (
            playbackCallbackState_.load(
                std::memory_order_acquire)
            &
            PLAYBACK_CALLBACK_COUNT_MASK
        ) != 0) {

        // Control/lifecycle thread only.
        std::this_thread::yield();
    }
}

void AAudioEngine::unblockPlaybackCallback() {
    playbackCallbackState_.fetch_and(
        PLAYBACK_CALLBACK_COUNT_MASK,
        std::memory_order_release);
}

void AAudioEngine::getSpectrumData(
    dsp::SpectrumSnapshot& outSnapshot) {

    fftProcessor_->getLatestSnapshot(
        outSnapshot);
}

// Capture callback must remain realtime-safe.
aaudio_data_callback_result_t
AAudioEngine::captureCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {

    if (userData == nullptr ||
        audioData == nullptr ||
        numFrames <= 0) {

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    auto* engine =
        static_cast<AAudioEngine*>(
            userData);

    const auto* inSamples =
        static_cast<const int16_t*>(
            audioData);

    const int32_t channels =
        engine->actualCaptureChannels_.load(
            std::memory_order_relaxed);

    const size_t ch =
        static_cast<size_t>(
            channels > 0
                ? channels
                : 1);

    const size_t availableFrames =
        engine->captureRawBuffer_.availableWrite()
        / ch;

    const size_t framesToWrite =
        std::min(
            static_cast<size_t>(numFrames),
            availableFrames);

    const size_t samplesToWrite =
        framesToWrite * ch;

    const size_t written =
        engine->captureRawBuffer_.write(
            inSamples,
            samplesToWrite);

    const size_t writtenFrames =
        written / ch;

    if (writtenFrames <
        static_cast<size_t>(numFrames)) {

        engine->captureDroppedFrames_.fetch_add(
            static_cast<size_t>(
                numFrames) -
            writtenFrames,
            std::memory_order_relaxed);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

// AUD-063:
//
// No mutex.
// No condition variable.
// No logging.
// No allocation.
// No stream control.
//
// Only:
//   atomic admission
//   SPSC read
//   zero-fill
//   atomic leave.
aaudio_data_callback_result_t
AAudioEngine::playbackCallback(
    AAudioStream* /*stream*/,
    void* userData,
    void* audioData,
    int32_t numFrames) {

    if (userData == nullptr ||
        audioData == nullptr ||
        numFrames <= 0) {

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    auto* engine =
        static_cast<AAudioEngine*>(
            userData);

    auto* samples =
        static_cast<int16_t*>(
            audioData);

    const size_t frames =
        static_cast<size_t>(
            numFrames);

    if (!engine->tryEnterPlaybackCallback()) {

        std::memset(
            samples,
            0,
            frames * sizeof(int16_t));

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    const size_t read =
        engine->playbackBuffer_.read(
            samples,
            frames);

    if (read < frames) {

        std::memset(
            samples + read,
            0,
            (frames - read) *
            sizeof(int16_t));
    }

    engine->leavePlaybackCallback();

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioEngine::errorCallback(
    AAudioStream* /*stream*/,
    void* userData,
    aaudio_result_t error) {

    char errBuf[64];

    snprintf(
        errBuf,
        sizeof(errBuf),
        "AAudio stream error: %d",
        error);

    client::logging::NativeLogQueue::getInstance()
        .push(
            5,
            LOG_TAG,
            errBuf);

    if (error ==
        AAUDIO_ERROR_DISCONNECTED) {

        auto* engine =
            static_cast<AAudioEngine*>(
                userData);

        engine->isDisconnected_.store(
            true,
            std::memory_order_release);
    }
}

} // namespace client::audio