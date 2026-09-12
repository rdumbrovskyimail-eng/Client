// >>> FILE: app/src/main/cpp/dsp/NeonDspUtils.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace client::dsp {

// Векторная нормализация PCM16 -> Float32
inline void pcm16ToFloat(const int16_t* src, float* dst, size_t count, float gain = 1.0f) {
    size_t i = 0;
    const float scale_val = (1.0f / 32768.0f) * gain;

#if defined(__ARM_NEON) || defined(__aarch64__)
    const float32x4_t vscale = vdupq_n_f32(scale_val);

    for (; i + 8 <= count; i += 8) {
        int16x8_t s16 = vld1q_s16(src + i);
        int32x4_t low32 = vmovl_s16(vget_low_s16(s16));
        int32x4_t high32 = vmovl_s16(vget_high_s16(s16));

        float32x4_t f_low = vmulq_f32(vcvtq_f32_s32(low32), vscale);
        float32x4_t f_high = vmulq_f32(vcvtq_f32_s32(high32), vscale);

        vst1q_f32(dst + i, f_low);
        vst1q_f32(dst + i + 4, f_high);
    }
#endif

    for (; i < count; ++i) {
        dst[i] = static_cast<float>(src[i]) * scale_val;
    }
}

// Расчет среднеквадратичного значения (RMS)
inline float calculateRms(const int16_t* src, size_t count) {
    if (count == 0) return 0.0f;
    double sum = 0.0;
    size_t i = 0;

#if defined(__ARM_NEON) || defined(__aarch64__)
    int64x2_t vsum = vdupq_n_s64(0);

    for (; i + 8 <= count; i += 8) {
        int16x8_t s = vld1q_s16(src + i);
        int32x4_t s_low = vmovl_s16(vget_low_s16(s));
        int32x4_t s_high = vmovl_s16(vget_high_s16(s));

        int32x2_t low_low = vget_low_s32(s_low);
        int32x2_t low_high = vget_high_s32(s_low);
        int32x2_t high_low = vget_low_s32(s_high);
        int32x2_t high_high = vget_high_s32(s_high);

        int64x2_t sq1 = vmull_s32(low_low, low_low);
        int64x2_t sq2 = vmull_s32(low_high, low_high);
        int64x2_t sq3 = vmull_s32(high_low, high_low);
        int64x2_t sq4 = vmull_s32(high_high, high_high);

        vsum = vaddq_s64(vsum, vaddq_s64(vaddq_s64(sq1, sq2), vaddq_s64(sq3, sq4)));
    }
    sum = static_cast<double>(vgetq_lane_s64(vsum, 0) + vgetq_lane_s64(vsum, 1));
#endif

    for (; i < count; ++i) {
        double v = src[i];
        sum += v * v;
    }

    return static_cast<float>(std::sqrt(sum / count) / 32768.0);
}

} // namespace client::dsp