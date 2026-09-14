// >>> FILE: app/src/main/cpp/audio/PolyphaseResampler.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <cstring>

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
 * ИСПРАВЛЕННЫЙ Дециматор 3:1 (48 кГц -> 16 кГц) для микрофонного тракта.
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

    size_t process(const int16_t* in, size_t inFrames, int16_t* out, size_t maxOutFrames) {
        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames == 0) return 0;

        static const int32_t COEFFS[TAPS] = {
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

        // Обновление циклической истории без повреждения границ буфера
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
 * Непрерывный кубический интерполятор Эрмита 24 кГц -> 48 кГц (Catmull-Rom C1 Spline).
 * Полностью устраняет разрывы производной и фазовый дребезг при выводе на 48 кГц ЦАП.
 */
class HermiteResampler24To48 {
public:
    HermiteResampler24To48() {
        reset();
    }

    void reset() {
        p0_ = 0;
        p1_ = 0;
        p2_ = 0;
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out) {
        if (in == nullptr || out == nullptr || inFrames == 0) return 0;
        size_t outFrames = 0;

        for (size_t i = 0; i < inFrames; ++i) {
            const int32_t p3 = in[i];

            // 1. Четный сэмпл: оригинальный отсчет модели без малейших искажений
            out[outFrames++] = static_cast<int16_t>(p2_);

            // 2. Нечетный сэмпл: 4-точечная гладкая интерполяция Эрмита (-p0 + 9*p1 + 9*p2 - p3) / 16
            const int32_t interpolated = (-p0_ + 9 * (p1_ + p2_) - p3 + 8) >> 4;
            out[outFrames++] = static_cast<int16_t>(std::clamp<int32_t>(interpolated, -32768, 32767));

            p0_ = p1_;
            p1_ = p2_;
            p2_ = p3;
        }

        return outFrames;
    }

private:
    int32_t p0_{0};
    int32_t p1_{0};
    int32_t p2_{0};
};

} // namespace client::audio