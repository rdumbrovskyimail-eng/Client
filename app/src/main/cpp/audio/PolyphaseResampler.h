// >>> FILE: app/src/main/cpp/audio/PolyphaseResampler.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <cstring>

namespace client::audio {

/**
 * E-25: 12-точечный полифазный FIR-ресемплер 24 кГц -> 16 кГц (L=2, M=3).
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
        if (inFrames == 0) return 0;

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
        size_t totalFrames = FILTER_ORDER + inFrames;

        size_t outFrames = 0;
        size_t inputIndex = FILTER_ORDER + offset_;

        while (inputIndex < totalFrames) {
            const int32_t* H = (phase_ == 0) ? H0 : H1;

            int64_t acc = H[0] * workBuf[inputIndex]     +
                          H[1] * workBuf[inputIndex - 1] +
                          H[2] * workBuf[inputIndex - 2] +
                          H[3] * workBuf[inputIndex - 3] +
                          H[4] * workBuf[inputIndex - 4] +
                          H[5] * workBuf[inputIndex - 5];

            out[outFrames++] = static_cast<int16_t>(std::clamp<int32_t>(acc >> 15, -32768, 32767));

            size_t step = phase_ + 3;
            inputIndex += step / 2;
            phase_ = step % 2;
        }

        offset_ = inputIndex - totalFrames;
        std::memcpy(historyBuf_, workBuf + totalFrames - FILTER_ORDER, FILTER_ORDER * sizeof(int16_t));
        return outFrames;
    }

    int16_t historyBuf_[FILTER_ORDER]{0};
    size_t phase_{0};
    size_t offset_{0};
};

/**
 * E-07: Полифазный дециматор 3:1 (48 кГц -> 16 кГц) для входящего микрофонного тракта.
 */
class Decimator48To16 {
public:
    static constexpr size_t TAPS = 12;

    void reset() {
        std::memset(history_, 0, sizeof(history_));
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out) {
        if (inFrames == 0) return 0;

        static const int32_t COEFFS[12] = {
            -180, -320, 450, 2400, 5800, 8234, 8234, 5800, 2400, 450, -320, -180
        };

        size_t outCount = 0;
        for (size_t i = 0; i < inFrames; i += 3) {
            if (i + 3 > inFrames) break;

            int64_t acc = 0;
            for (size_t t = 0; t < TAPS; ++t) {
                int32_t sample = (i >= t) ? in[i - t] : history_[TAPS - 1 - (t - i)];
                acc += COEFFS[t] * sample;
            }
            out[outCount++] = static_cast<int16_t>(std::clamp<int32_t>(acc >> 15, -32768, 32767));
        }

        size_t toKeep = std::min(inFrames, TAPS);
        std::memcpy(history_, in + inFrames - toKeep, toKeep * sizeof(int16_t));
        return outCount;
    }

private:
    int16_t history_[TAPS]{0};
};

/**
 * Каузальный линейный интерполятор 24 кГц -> 48 кГц (L=2, M=1).
 */
class LinearResampler24To48 {
public:
    void reset() {
        lastSample_ = 0;
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out) {
        if (inFrames == 0) return 0;
        size_t outFrames = 0;

        int32_t prev = lastSample_;
        for (size_t i = 0; i < inFrames; ++i) {
            int32_t cur = in[i];
            out[outFrames++] = static_cast<int16_t>((prev + cur) >> 1);
            out[outFrames++] = static_cast<int16_t>(cur);
            prev = cur;
        }
        lastSample_ = static_cast<int16_t>(prev);
        return outFrames;
    }

private:
    int16_t lastSample_{0};
};

} // namespace client::audio