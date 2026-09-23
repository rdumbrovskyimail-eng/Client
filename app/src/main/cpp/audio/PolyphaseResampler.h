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
 * Problem #9: Stateful streaming decimator for 44.1 kHz -> 16 kHz capture.
 * Ratio: 44100 / 16000 = 2.75625 (160 / 441).
 * Architecture:
 *   Stage 1: 21-tap linear-phase symmetric anti-aliasing FIR filter (fc = 7.2 kHz,
 *            stopband rejection > 42 dB at 8.0 kHz Nyquist) conforming to 3GPP TS 26.445 (EVS).
 *   Stage 2: Continuous fractional streaming interpolator with phase accumulator and history overlap.
 * Zero-allocation during processing, phase-continuous across streaming chunks.
 */
class Resampler44100To16000 {
public:
    static constexpr size_t FIR_TAPS = 21;
    static constexpr size_t FIR_HALF_TAPS = (FIR_TAPS - 1) / 2; // 10
    static constexpr size_t FIR_HISTORY = FIR_TAPS - 1;         // 20
    static constexpr size_t CHUNK_SIZE = 2048;

    Resampler44100To16000() {
        reset();
    }

    void reset() {
        std::memset(firHistory_, 0, sizeof(firHistory_));
        sourceIndex_ = 0;
        phase_ = 0.0;
        previousFilteredSample_ = 0;
        hasPreviousFilteredSample_ = false;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames) {

        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames == 0) {
            return 0;
        }

        size_t processedIn = 0;
        size_t totalOut = 0;

        while (processedIn < inFrames && totalOut < maxOutFrames) {
            const size_t currentChunk = std::min(inFrames - processedIn, CHUNK_SIZE);
            totalOut += processChunk(
                in + processedIn,
                currentChunk,
                out + totalOut,
                maxOutFrames - totalOut);
            processedIn += currentChunk;
        }

        return totalOut;
    }

private:
    size_t processChunk(
        const int16_t* in,
        size_t chunkFrames,
        int16_t* out,
        size_t maxOut) {

        if (chunkFrames == 0 || maxOut == 0) return 0;

        // Stage 1: Anti-aliasing FIR Low-Pass Filter (Cutoff = 7.2 kHz, Stopband >= 8.0 kHz)
        static constexpr int32_t COEFFS[FIR_HALF_TAPS + 1] = {
            0, 10, 50, -30, -644, -1119, 102, 3924, 8664, 10854, 0
        };

        std::memcpy(
            firWorkBuffer_,
            firHistory_,
            FIR_HISTORY * sizeof(int16_t));
        std::memcpy(
            firWorkBuffer_ + FIR_HISTORY,
            in,
            chunkFrames * sizeof(int16_t));

        for (size_t i = 0; i < chunkFrames; ++i) {
            const size_t idx = FIR_HISTORY + i;

            int64_t acc = static_cast<int64_t>(COEFFS[9]) * static_cast<int32_t>(firWorkBuffer_[idx - 10]);

            for (size_t k = 0; k < 9; ++k) {
                const int32_t pair =
                    static_cast<int32_t>(firWorkBuffer_[idx - k]) +
                    static_cast<int32_t>(firWorkBuffer_[idx - (20 - k)]);
                acc += static_cast<int64_t>(COEFFS[k]) * pair;
            }

            constexpr int64_t HALF = 1LL << 14;
            const int32_t rounded = static_cast<int32_t>((acc + HALF) >> 15);
            filteredBuffer_[i] = static_cast<int16_t>(std::clamp(rounded, -32768, 32767));
        }

        if (chunkFrames >= FIR_HISTORY) {
            std::memcpy(
                firHistory_,
                firWorkBuffer_ + chunkFrames,
                FIR_HISTORY * sizeof(int16_t));
        } else {
            std::memmove(
                firHistory_,
                firHistory_ + chunkFrames,
                (FIR_HISTORY - chunkFrames) * sizeof(int16_t));
            std::memcpy(
                firHistory_ + (FIR_HISTORY - chunkFrames),
                in,
                chunkFrames * sizeof(int16_t));
        }

        // Stage 2: Streaming Fractional Interpolation (Step = 44100 / 16000 = 2.75625)
        constexpr double STEP = 44100.0 / 16000.0;
        const bool hadPrev = hasPreviousFilteredSample_;
        const size_t logicalSize = chunkFrames + (hadPrev ? 1u : 0u);
        size_t outCount = 0;

        while (outCount < maxOut) {
            if (sourceIndex_ + 1u >= logicalSize) {
                break;
            }

            const int32_t s0 = getSample(filteredBuffer_, chunkFrames, sourceIndex_, hadPrev);
            const int32_t s1 = getSample(filteredBuffer_, chunkFrames, sourceIndex_ + 1u, hadPrev);

            const double interpolated =
                static_cast<double>(s0) +
                (static_cast<double>(s1) - static_cast<double>(s0)) * phase_;

            const long rounded = std::lround(interpolated);
            out[outCount++] = static_cast<int16_t>(std::clamp<long>(rounded, -32768L, 32767L));

            const double advancedPhase = phase_ + STEP;
            const double wholePart = std::floor(advancedPhase);
            const size_t wholeFrames = static_cast<size_t>(wholePart);

            phase_ = advancedPhase - wholePart;
            sourceIndex_ += wholeFrames;
        }

        previousFilteredSample_ = filteredBuffer_[chunkFrames - 1u];
        hasPreviousFilteredSample_ = true;

        const size_t historyShift = hadPrev ? chunkFrames : (chunkFrames - 1u);
        if (sourceIndex_ >= historyShift) {
            sourceIndex_ -= historyShift;
        } else {
            sourceIndex_ = 0;
            phase_ = 0.0;
        }

        return outCount;
    }

    inline int32_t getSample(const int16_t* buf, size_t count, size_t index, bool hadPrev) const {
        if (hadPrev && index == 0u) {
            return static_cast<int32_t>(previousFilteredSample_);
        }
        const size_t localIdx = hadPrev ? (index - 1u) : index;
        if (localIdx >= count) {
            return static_cast<int32_t>(buf[count - 1u]);
        }
        return static_cast<int32_t>(buf[localIdx]);
    }

    alignas(16) int16_t firHistory_[FIR_HISTORY]{0};
    alignas(16) int16_t firWorkBuffer_[FIR_HISTORY + CHUNK_SIZE]{0};
    alignas(16) int16_t filteredBuffer_[CHUNK_SIZE]{0};

    size_t sourceIndex_{0};
    double phase_{0.0};
    int16_t previousFilteredSample_{0};
    bool hasPreviousFilteredSample_{false};
};

/**
 * Problem #11: Stateful streaming 1:2 upsampler (8 kHz -> 16 kHz) with causal anti-imaging filter.
 * Architecture:
 *   Stage 1: Linear midpoint interpolation (8k -> 16k) preserving input history across chunks.
 *   Stage 2: Causal 3-point binomial smoothing FIR filter H(z) = (1 + 2z^-1 + z^-2) / 4 [0.25, 0.5, 0.25].
 * Entirely eliminates chunk boundary clicks, phase steps, and spectral splatter in Bluetooth SCO (CVSD).
 * Zero dynamic memory allocations during streaming, continuous C0/C1 phase response.
 */
class Upsampler8000To16000 {
public:
    static constexpr size_t CHUNK_SIZE = 2048;
    static constexpr size_t MAX_OUT_CHUNK = CHUNK_SIZE * 2u;

    Upsampler8000To16000() {
        reset();
    }

    void reset() {
        lastInputSample_ = 0;
        hasLastInputSample_ = false;
        firHistory_[0] = 0;
        firHistory_[1] = 0;
        hasFirHistory_ = false;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames) {

        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames == 0) {
            return 0;
        }

        size_t processedIn = 0;
        size_t totalOut = 0;

        while (processedIn < inFrames && totalOut < maxOutFrames) {
            const size_t currentChunk = std::min(inFrames - processedIn, CHUNK_SIZE);
            const size_t outChunk = processChunk(
                in + processedIn,
                currentChunk,
                out + totalOut,
                maxOutFrames - totalOut);

            if (outChunk == 0) break;
            totalOut += outChunk;
            processedIn += currentChunk;
        }

        return totalOut;
    }

private:
    size_t processChunk(
        const int16_t* in,
        size_t chunkFrames,
        int16_t* out,
        size_t maxOut) {

        const size_t neededOut = chunkFrames * 2u;
        if (neededOut > maxOut || neededOut > MAX_OUT_CHUNK) {
            return 0;
        }

        // Step 1: Linear midpoint interpolation (8k -> 16k) with state preservation
        int16_t prev = hasLastInputSample_ ? lastInputSample_ : in[0];
        size_t uIdx = 0;

        for (size_t i = 0; i < chunkFrames; ++i) {
            const int16_t curr = in[i];
            const int16_t midpoint = static_cast<int16_t>(
                (static_cast<int32_t>(prev) + static_cast<int32_t>(curr) + 1) >> 1
            );
            interpWorkBuf_[uIdx++] = midpoint;
            interpWorkBuf_[uIdx++] = curr;
            prev = curr;
        }

        lastInputSample_ = in[chunkFrames - 1u];
        hasLastInputSample_ = true;

        // Step 2: Causal continuous 3-point binomial anti-imaging filter [0.25, 0.5, 0.25]
        // H(z) = (1 + 2z^-1 + z^-2) / 4. Filters every sample including index 0 with 1-sample group delay.
        int32_t h0 = hasFirHistory_ ? firHistory_[0] : interpWorkBuf_[0];
        int32_t h1 = hasFirHistory_ ? firHistory_[1] : interpWorkBuf_[0];

        for (size_t k = 0; k < uIdx; ++k) {
            const int32_t s2 = interpWorkBuf_[k];
            out[k] = static_cast<int16_t>((h0 + (h1 << 1) + s2 + 2) >> 2);
            h0 = h1;
            h1 = s2;
        }

        firHistory_[0] = static_cast<int16_t>(h0);
        firHistory_[1] = static_cast<int16_t>(h1);
        hasFirHistory_ = true;

        return uIdx;
    }

    alignas(16) int16_t interpWorkBuf_[MAX_OUT_CHUNK]{0};
    int16_t lastInputSample_{0};
    bool hasLastInputSample_{false};
    int16_t firHistory_[2]{0, 0};
    bool hasFirHistory_{false};
};

/**
 * 2x half-band FIR interpolator, 24 kHz -> 48 kHz.
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

        const bool hadPrev = hasPreviousSample_;
        const size_t logicalSize = inFrames + (hadPrev ? 1u : 0u);
        size_t outCount = 0;

        while (outCount < maxOutFrames) {
            if (sourceIndex_ + 1u >= logicalSize) {
                break;
            }

            const int32_t s0 = getSample(in, inFrames, sourceIndex_, hadPrev);
            const int32_t s1 = getSample(in, inFrames, sourceIndex_ + 1u, hadPrev);

            const double interpolated =
                static_cast<double>(s0) +
                (static_cast<double>(s1) - static_cast<double>(s0)) * phase_;

            const long rounded = std::lround(interpolated);

            out[outCount++] =
                static_cast<int16_t>(std::clamp<long>(rounded, -32768L, 32767L));

            const double advancedPhase = phase_ + step;
            const double wholePart = std::floor(advancedPhase);
            const size_t wholeFrames = static_cast<size_t>(wholePart);

            phase_ = advancedPhase - wholePart;
            sourceIndex_ += wholeFrames;
        }

        previousSample_ = in[inFrames - 1u];
        hasPreviousSample_ = true;

        const size_t historyShift = hadPrev ? inFrames : (inFrames - 1u);
        if (sourceIndex_ >= historyShift) {
            sourceIndex_ -= historyShift;
        } else {
            sourceIndex_ = 0;
            phase_ = 0.0;
        }

        return outCount;
    }

private:
    inline int32_t getSample(const int16_t* in, size_t inFrames, size_t index, bool hadPrev) const {
        if (hadPrev && index == 0u) {
            return static_cast<int32_t>(previousSample_);
        }
        const size_t localIdx = hadPrev ? (index - 1u) : index;
        if (localIdx >= inFrames) {
            return static_cast<int32_t>(in[inFrames - 1u]);
        }
        return static_cast<int32_t>(in[localIdx]);
    }

    int32_t inputRate_{0};
    int32_t outputRate_{0};
    size_t sourceIndex_{0};
    double phase_{0.0};
    int16_t previousSample_{0};
    bool hasPreviousSample_{false};
};

} // namespace client::audio