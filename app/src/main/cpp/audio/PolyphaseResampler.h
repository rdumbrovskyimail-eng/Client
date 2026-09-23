// >>> FILE: app/src/main/cpp/audio/PolyphaseResampler.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <cstring>
#include <cmath>

namespace client::audio {

/**
 * 12-точечный полифазный FIR-ресемплер 24 кГц -> 16 кГц (L=2, M=3).
 * Полностью исключает аллокации в heap (Zero-Allocation Chunked Loop).
 */
class PolyphaseResampler24To16 {
public:
    static constexpr size_t FILTER_ORDER = 5;
    static constexpr size_t CHUNK_SIZE = 480;

    PolyphaseResampler24To16() {
        reset();
    }

    void reset() {
        std::memset(historyBuf_, 0, sizeof(historyBuf_));
        phase_ = 0;
        offset_ = 0;
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out) {
        if (in == nullptr || out == nullptr || inFrames == 0) return 0;

        size_t processedIn = 0;
        size_t totalOut = 0;

        while (processedIn < inFrames) {
            size_t currentChunk = std::min(inFrames - processedIn, CHUNK_SIZE);
            totalOut += processChunk(in + processedIn, currentChunk, out + totalOut);
            processedIn += currentChunk;
        }

        return totalOut;
    }

private:
    size_t processChunk(const int16_t* in, size_t inFrames, int16_t* out) {
        static const int32_t H0[6] = { -151, -1039, 11689, 20454,  2522, -707 };
        static const int32_t H1[6] = { -707,  2522, 20454, 11689, -1039, -151 };

        int16_t workBuf[CHUNK_SIZE + FILTER_ORDER];
        std::memcpy(workBuf, historyBuf_, FILTER_ORDER * sizeof(int16_t));
        std::memcpy(workBuf + FILTER_ORDER, in, inFrames * sizeof(int16_t));
        const size_t totalFrames = FILTER_ORDER + inFrames;

        size_t outFrames = 0;
        size_t inputIndex = FILTER_ORDER + static_cast<size_t>(offset_);

        while (inputIndex < totalFrames) {
            const int32_t* H = (phase_ == 0) ? H0 : H1;

            const int64_t acc = static_cast<int64_t>(H[0]) * workBuf[inputIndex]     +
                                static_cast<int64_t>(H[1]) * workBuf[inputIndex - 1] +
                                static_cast<int64_t>(H[2]) * workBuf[inputIndex - 2] +
                                static_cast<int64_t>(H[3]) * workBuf[inputIndex - 3] +
                                static_cast<int64_t>(H[4]) * workBuf[inputIndex - 4] +
                                static_cast<int64_t>(H[5]) * workBuf[inputIndex - 5];

            out[outFrames++] = static_cast<int16_t>(std::clamp<int32_t>(static_cast<int32_t>(acc >> 15), -32768, 32767));

            const int32_t step = phase_ + 3;
            inputIndex += static_cast<size_t>(step / 2);
            phase_ = step % 2;
        }

        offset_ = static_cast<int32_t>(inputIndex - totalFrames);
        std::memcpy(historyBuf_, workBuf + totalFrames - FILTER_ORDER, FILTER_ORDER * sizeof(int16_t));
        return outFrames;
    }

    alignas(16) int16_t historyBuf_[FILTER_ORDER]{0};
    int32_t phase_{0};
    int32_t offset_{0};
};

/**
 * Дециматор 3:1 (48 кГц -> 16 кГц) для микрофонного тракта.
 * Исключает замирание микрофона: кольцевой аккумулятор phase_ строго в пределах [0..2].
 * Устраняет поднормальное переполнение size_t и гарантирует непрерывную отдачу фреймов в VAD.
 */
class Decimator48To16 {
public:
    static constexpr size_t TAPS = 12;

    Decimator48To16() {
        reset();
    }

    void reset() {
        std::memset(history_, 0, sizeof(history_));
        phase_ = 0;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames) {

        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames == 0) {
            return 0;
        }

        size_t requiredOut = 0;
        switch (phase_) {
            case 0:
                requiredOut = (inFrames / 3u) +
                              ((inFrames % 3u) != 0u ? 1u : 0u);
                break;
            case 1:
                requiredOut = inFrames / 3u;
                break;
            default: // phase_ == 2
                requiredOut = (inFrames / 3u) +
                              ((inFrames % 3u) >= 2u ? 1u : 0u);
                break;
        }

        if (requiredOut > maxOutFrames) {
            return 0;
        }

        static constexpr int32_t COEFFS[TAPS] = {
            -180, -320, 450, 2400, 5800, 8234, 8234, 5800, 2400, 450, -320, -180
        };

        size_t outCount = 0;

        for (size_t i = 0; i < inFrames; ++i) {
            if (phase_ == 0) {
                if (outCount >= maxOutFrames) break;

                int64_t acc = 0;
                for (size_t t = 0; t < TAPS; ++t) {
                    const int32_t sample = (i >= t)
                        ? static_cast<int32_t>(in[i - t])
                        : static_cast<int32_t>(history_[TAPS + (static_cast<int32_t>(i) - static_cast<int32_t>(t))]);
                    acc += static_cast<int64_t>(COEFFS[t]) * sample;
                }
                out[outCount++] = static_cast<int16_t>(std::clamp<int32_t>(static_cast<int32_t>(acc >> 15), -32768, 32767));
            }
            phase_ = (phase_ + 1) % 3;
        }

        if (inFrames >= TAPS) {
            std::memcpy(history_, in + inFrames - TAPS, TAPS * sizeof(int16_t));
        } else {
            std::memmove(history_, history_ + inFrames, (TAPS - inFrames) * sizeof(int16_t));
            std::memcpy(history_ + (TAPS - inFrames), in, inFrames * sizeof(int16_t));
        }

        return outCount;
    }

private:
    alignas(16) int16_t history_[TAPS]{0};
    int32_t phase_{0};
};

/**
 * Stateful 2:1 FIR decimator (32 kHz -> 16 kHz) for microphone capture.
 *
 * The filter is a 95-tap linear-phase low-pass designed for a 7 kHz
 * passband and 8.5 kHz stopband at the 32 kHz input rate. Coefficients are
 * stored in Q30 with unity DC gain. The implementation uses filter
 * symmetry and a contiguous history/work buffer. State survives process()
 * chunk boundaries.
 */
class Decimator32To16 {
public:
    static constexpr size_t TAPS = 95;
    static constexpr size_t HISTORY = TAPS - 1;
    static constexpr size_t CHUNK_SIZE = 2048;
    static constexpr size_t HALF_TAPS = (TAPS - 1) / 2;

    Decimator32To16() {
        reset();
    }

    void reset() {
        std::memset(history_, 0, sizeof(history_));
        phase_ = 0;
        primed_ = false;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames) {

        if (in == nullptr || out == nullptr || inFrames == 0 ||
            maxOutFrames == 0) {
            return 0;
        }

        const size_t requiredOut =
            (inFrames / 2) +
            ((phase_ == 0 && (inFrames & 1u) != 0u) ? 1u : 0u);

        if (requiredOut > maxOutFrames) {
            return 0;
        }

        if (!primed_) {
            std::fill(
                history_,
                history_ + HISTORY,
                in[0]);
            primed_ = true;
        }

        static constexpr int32_t COEFFS[TAPS] = {
              9277,   -142232,   -248570,    -19077,    307385,
             85865,   -454217,   -231270,    624952,    466772,
            -803193,   -813531,    966911,   1293141,  -1085886,
          -1924923,   1120958,   2724099,  -1023827,  -3699732,
            736628,   4853231,   -191699,  -6176465,   -688534,
            7652181,   1995049,  -9251823,  -3834797,  10938368,
           6342900, -12665461,  -9701107,  14380258,  14179417,
         -16026032, -20225409,  17544313,  28673636, -18878210,
         -41317058,  19975994,  62851763, -20793951,-110595642,
          21298709, 340809654, 515664194, 340809654,  21298709,
        -110595642,-20793951,  62851763,  19975994,-41317058,
         -18878210,  28673636,  17544313,-20225409,-16026032,
          14179417,  14380258,  -9701107,-12665461,   6342900,
          10938368,  -3834797,  -9251823,   1995049,   7652181,
           -688534,  -6176465,   -191699,   4853231,    736628,
          -3699732,  -1023827,   2724099,   1120958,  -1924923,
          -1085886,   1293141,    966911,   -813531,   -803193,
            466772,    624952,   -231270,   -454217,     85865,
            307385,    -19077,   -248570,   -142232,      9277
        };

        size_t totalOut = 0;
        size_t processed = 0;

        while (processed < inFrames) {
            const size_t chunk =
                std::min(inFrames - processed, CHUNK_SIZE);

            const int16_t* chunkIn = in + processed;
            std::memcpy(
                workBuffer_,
                history_,
                HISTORY * sizeof(int16_t));
            std::memcpy(
                workBuffer_ + HISTORY,
                chunkIn,
                chunk * sizeof(int16_t));

            const size_t base = HISTORY;

            for (size_t i = 0; i < chunk; ++i) {
                const size_t idx = base + i;

                if (phase_ == 0) {
                    int64_t acc = 0;

                    for (size_t k = 0; k < HALF_TAPS; ++k) {
                        const int32_t pair =
                            static_cast<int32_t>(workBuffer_[idx - k]) +
                            static_cast<int32_t>(workBuffer_[idx - (TAPS - 1 - k)]);
                        acc +=
                            static_cast<int64_t>(COEFFS[k]) * pair;
                    }

                    acc +=
                        static_cast<int64_t>(COEFFS[HALF_TAPS]) *
                        static_cast<int32_t>(workBuffer_[idx - HALF_TAPS]);

                    constexpr int64_t HALF = 1LL << 29;
                    const int64_t rounded =
                        acc >= 0
                            ? (acc + HALF) >> 30
                            : -(((-acc) + HALF) >> 30);

                    out[totalOut++] =
                        static_cast<int16_t>(
                            std::clamp<int64_t>(
                                rounded,
                                -32768,
                                32767));
                }

                phase_ ^= 1u;
            }

            if (chunk >= HISTORY) {
                std::memcpy(
                    history_,
                    workBuffer_ + HISTORY + chunk - HISTORY,
                    HISTORY * sizeof(int16_t));
            } else {
                std::memmove(
                    history_,
                    history_ + chunk,
                    (HISTORY - chunk) * sizeof(int16_t));
                std::memcpy(
                    history_ + (HISTORY - chunk),
                    chunkIn,
                    chunk * sizeof(int16_t));
            }

            processed += chunk;
        }

        return totalOut;
    }

private:
    alignas(16) int16_t history_[HISTORY]{0};
    alignas(16) int16_t workBuffer_[HISTORY + CHUNK_SIZE]{0};
    uint32_t phase_{0};
    bool primed_{false};
};

/**
 * 2x half-band FIR interpolator, 24 kHz -> 48 kHz.
 *
 * Linear-phase half-band filter providing deterministic 2:1 sample production
 * and image rejection above the 12 kHz Nyquist boundary.
 *
 * The polyphase representation splits the 127 taps into:
 *   - Even branch: 64 symmetric taps acting on input samples (delay 31.5 input samples)
 *   - Odd branch: single center tap h[63] = 1.0 Q30 (delayed by 31 input samples)
 * Both branches yield identical group delay of 63 output samples.
 */
class HalfbandResampler24To48 {
public:
    static constexpr size_t TAPS = 127;
    static constexpr size_t HISTORY = 63;
    static constexpr size_t CHUNK_SIZE = 2048;
    static constexpr size_t EVEN_TAPS = 64;
    static constexpr size_t EVEN_PAIRS = 32;

    HalfbandResampler24To48() {
        reset();
    }

    void reset() {
        std::memset(history_, 0, sizeof(history_));
        primed_ = false;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out) {

        if (in == nullptr || out == nullptr || inFrames == 0) {
            return 0;
        }

        size_t totalOut = 0;
        size_t processed = 0;

        if (!primed_) {
            std::fill(
                history_,
                history_ + HISTORY,
                in[0]);
            primed_ = true;
        }

        static constexpr int32_t EVEN_COEFFS[EVEN_TAPS] = {
            -12983, 37855, -76308, 135307,
            -221491, 342740, -508266, 728702,
            -1016174, 1384398, -1848783, 2426564,
            -3137012, 4001717, -5045041, 6294779,
            -7783166, 9548399, -11636940, 14107054,
            -17034317, 20520423, -24707672, 29803820,
            -36126917, 44191608, -54889615, 69910812,
            -92885992, 133271935, -225775909, 682871385,
            682871385, -225775909, 133271935, -92885992,
            69910812, -54889615, 44191608, -36126917,
            29803820, -24707672, 20520423, -17034317,
            14107054, -11636940, 9548399, -7783166,
            6294779, -5045041, 4001717, -3137012,
            2426564, -1848783, 1384398, -1016174,
            728702, -508266, 342740, -221491,
            135307, -76308, 37855, -12983
        };

        while (processed < inFrames) {
            const size_t chunk =
                std::min(inFrames - processed, CHUNK_SIZE);

            const int16_t* chunkIn = in + processed;
            std::memcpy(
                workBuffer_,
                history_,
                HISTORY * sizeof(int16_t));
            std::memcpy(
                workBuffer_ + HISTORY,
                chunkIn,
                chunk * sizeof(int16_t));

            const size_t base = HISTORY;

            for (size_t i = 0; i < chunk; ++i) {
                const size_t idx = base + i;

                int64_t evenAcc = 0;
                for (size_t k = 0; k < EVEN_PAIRS; ++k) {
                    const int32_t pair =
                        static_cast<int32_t>(workBuffer_[idx - k]) +
                        static_cast<int32_t>(workBuffer_[idx - (HISTORY - k)]);
                    evenAcc +=
                        static_cast<int64_t>(EVEN_COEFFS[k]) * pair;
                }

                const int64_t evenRounded =
                    evenAcc >= 0
                        ? (evenAcc + (1LL << 29)) >> 30
                        : -(((-evenAcc) + (1LL << 29)) >> 30);

                const int16_t oddSample =
                    workBuffer_[idx - (HISTORY / 2)];

                out[totalOut++] =
                    static_cast<int16_t>(
                        std::clamp<int64_t>(
                            evenRounded,
                            -32768,
                            32767));
                out[totalOut++] = oddSample;
            }

            if (chunk >= HISTORY) {
                std::memcpy(
                    history_,
                    workBuffer_ + HISTORY + chunk - HISTORY,
                    HISTORY * sizeof(int16_t));
            } else {
                std::memmove(
                    history_,
                    history_ + chunk,
                    (HISTORY - chunk) * sizeof(int16_t));
                std::memcpy(
                    history_ + (HISTORY - chunk),
                    chunkIn,
                    chunk * sizeof(int16_t));
            }

            processed += chunk;
        }

        return totalOut;
    }

private:
    alignas(16) int16_t history_[HISTORY]{0};
    alignas(16) int16_t workBuffer_[HISTORY + CHUNK_SIZE]{0};
    bool primed_{false};
};

/**
 * Stateful linear streaming resampler for generic playback paths.
 *
 * Fractional clock accumulator maintains phase across chunk boundaries without allocation.
 */
class StreamingLinearResampler {
public:
    StreamingLinearResampler() = default;

    void configure(int32_t inputRate, int32_t outputRate) {
        if (inputRate <= 0 || outputRate <= 0) {
            reset();
            inputRate_ = 0;
            outputRate_ = 0;
            return;
        }

        if (inputRate_ != inputRate || outputRate_ != outputRate) {
            inputRate_ = inputRate;
            outputRate_ = outputRate;
            reset();
        }
    }

    void reset() {
        sourceIndex_ = 0;
        phase_ = 0.0;
        previousSample_ = 0;
        hasPreviousSample_ = false;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames) {

        if (in == nullptr || out == nullptr ||
            inFrames == 0 || maxOutFrames == 0 ||
            inputRate_ <= 0 || outputRate_ <= 0) {
            return 0;
        }

        const double step =
            static_cast<double>(inputRate_) /
            static_cast<double>(outputRate_);

        const bool hadPreviousSample = hasPreviousSample_;
        const size_t logicalSize =
            inFrames + (hadPreviousSample ? 1u : 0u);

        size_t outCount = 0;

        auto sampleAt = [&](size_t logicalIndex) -> int32_t {
            if (hadPreviousSample && logicalIndex == 0u) {
                return static_cast<int32_t>(previousSample_);
            }

            const size_t localIndex =
                hadPreviousSample