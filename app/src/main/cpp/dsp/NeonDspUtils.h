// >>> FILE: app/src/main/cpp/dsp/NeonDspUtils.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <cmath>
#include <cstring>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace client::dsp {

// E-26: Активация аппаратного режима Flush-To-Zero (FZ) в ARMv8/ARMv9
inline void enableHardwareFtz() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24); // Бит 24: FZ (Flush-to-Zero для float32)
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#endif
}

inline void pcm16ToFloat(
    const int16_t* src,
    float* dst,
    size_t count,
    float gain = 1.0f) {

    // A non-empty conversion requires both valid input and output buffers.
    // Reject invalid pointers before either the NEON or scalar path accesses them.
    if (src == nullptr || dst == nullptr || count == 0) {
        return;
    }

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

inline float calculateRms(const int16_t* src, size_t count) {
    // count > 0 implies that src must designate a readable PCM buffer.
    if (src == nullptr || count == 0) {
        return 0.0f;
    }

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

    return static_cast<float>(std::sqrt((sum + 1e-9) / count) / 32768.0);
}

// Problem #21: Векторизованное сведение стерео -> моно с масштабированием гейна.
// Устраняет скалярные деления, перегоны GPR <-> FPU и ветвления std::clamp.
inline void stereoToMonoWithGain(
    const int16_t* src,
    int16_t* dst,
    size_t numFrames,
    float gain) {

    if (src == nullptr || dst == nullptr || numFrames == 0) return;

    size_t i = 0;
    const bool applyGain = std::abs(gain - 1.0f) > 0.001f;

#if defined(__ARM_NEON) || defined(__aarch64__)
    if (!applyGain) {
        for (; i + 8 <= numFrames; i += 8) {
            // Аппаратный деинтерливинг 8 стереопар на каналы L и R
            int16x8x2_t stereo = vld2q_s16(src + i * 2);
            // Аппаратное округленное полусуммирование без переполнения: (L + R + 1) >> 1
            int16x8_t mono = vrhaddq_s16(stereo.val[0], stereo.val[1]);
            vst1q_s16(dst + i, mono);
        }
    } else {
        const float32x4_t vgain = vdupq_n_f32(gain);
        for (; i + 8 <= numFrames; i += 8) {
            int16x8x2_t stereo = vld2q_s16(src + i * 2);
            int16x8_t mono = vrhaddq_s16(stereo.val[0], stereo.val[1]);

            int32x4_t low32 = vmovl_s16(vget_low_s16(mono));
            int32x4_t high32 = vmovl_s16(vget_high_s16(mono));

            float32x4_t flow = vmulq_f32(vcvtq_f32_s32(low32), vgain);
            float32x4_t fhigh = vmulq_f32(vcvtq_f32_s32(high32), vgain);

            // Аппаратное векторное округление к ближайшему целому (ties away)
            int32x4_t rlow = vcvtaq_s32_f32(flow);
            int32x4_t rhigh = vcvtaq_s32_f32(fhigh);

            // Аппаратное насыщающее сужение без ветвлений в диапазон [-32768, 32767]
            int16x8_t res = vcombine_s16(vqmovn_s32(rlow), vqmovn_s32(rhigh));
            vst1q_s16(dst + i, res);
        }
    }
#endif

    // Скалярный хвост для некратных остатков (numFrames % 8 != 0)
    for (; i < numFrames; ++i) {
        int32_t mixed = (static_cast<int32_t>(src[i * 2]) + static_cast<int32_t>(src[i * 2 + 1]) + 1) >> 1;
        if (applyGain) {
            mixed = static_cast<int32_t>(std::round(static_cast<float>(mixed) * gain));
        }
        dst[i] = static_cast<int16_t>(std::clamp(mixed, -32768, 32767));
    }
}

// Problem #21: Векторизованное масштабирование гейна монофонического тракта.
inline void applyGainInPlace(
    const int16_t* src,
    int16_t* dst,
    size_t numFrames,
    float gain) {

    if (src == nullptr || dst == nullptr || numFrames == 0) return;

    size_t i = 0;
    const bool applyGain = std::abs(gain - 1.0f) > 0.001f;

    if (!applyGain) {
        if (src != dst) {
            std::memcpy(dst, src, numFrames * sizeof(int16_t));
        }
        return;
    }

#if defined(__ARM_NEON) || defined(__aarch64__)
    const float32x4_t vgain = vdupq_n_f32(gain);
    for (; i + 8 <= numFrames; i += 8) {
        int16x8_t s16 = vld1q_s16(src + i);

        int32x4_t low32 = vmovl_s16(vget_low_s16(s16));
        int32x4_t high32 = vmovl_s16(vget_high_s16(s16));

        float32x4_t flow = vmulq_f32(vcvtq_f32_s32(low32), vgain);
        float32x4_t fhigh = vmulq_f32(vcvtq_f32_s32(high32), vgain);

        int32x4_t rlow = vcvtaq_s32_f32(flow);
        int32x4_t rhigh = vcvtaq_s32_f32(fhigh);

        int16x8_t res = vcombine_s16(vqmovn_s32(rlow), vqmovn_s32(rhigh));
        vst1q_s16(dst + i, res);
    }
#endif

    // Скалярный хвост для некратных остатков
    for (; i < numFrames; ++i) {
        int32_t amplified = static_cast<int32_t>(std::round(static_cast<float>(src[i]) * gain));
        dst[i] = static_cast<int16_t>(std::clamp(amplified, -32768, 32767));
    }
}

} // namespace client::dsp