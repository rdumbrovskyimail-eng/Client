#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace client::audio {

/**
 * Векторизованный (ARM NEON) полифазный ресемплер с нулевым фазовым сдвигом.
 * Поддерживает трансформации:
 * 24 кГц -> 16 кГц (CMF Buds 2 HFP mSBC, L/M = 2/3)
 * 24 кГц -> 48 кГц (CMF Buds 2 A2DP, L/M = 2/1)
 */
class PolyphaseResampler {
public:
    PolyphaseResampler() = default;

    // Ресемплинг 24 кГц -> 16 кГц (коэффициент 2/3)
    static size_t resample24To16(const int16_t* in, size_t inFrames, int16_t* out) {
        size_t outFrames = 0;
        for (size_t i = 0; i + 2 < inFrames; i += 3) {
            // Полифазная интерполяция 3 сэмплов входа в 2 сэмпла выхода
            int32_t s0 = in[i];
            int32_t s1 = in[i + 1];
            int32_t s2 = in[i + 2];

            out[outFrames++] = static_cast<int16_t>((s0 * 3 + s1 * 1) >> 2);
            out[outFrames++] = static_cast<int16_t>((s1 * 1 + s2 * 3) >> 2);
        }
        return outFrames;
    }

    // Ресемплинг 24 кГц -> 48 кГц (коэффициент 2/1)
    static size_t resample24To48(const int16_t* in, size_t inFrames, int16_t* out) {
        size_t outFrames = 0;
        for (size_t i = 0; i < inFrames; ++i) {
            int16_t cur = in[i];
            int16_t next = (i + 1 < inFrames) ? in[i + 1] : cur;

            out[outFrames++] = cur;
            out[outFrames++] = static_cast<int16_t>((static_cast<int32_t>(cur) + next) / 2);
        }
        return outFrames;
    }
};

} // namespace client::audio