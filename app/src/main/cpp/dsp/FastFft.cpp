// >>> FILE: app/src/main/cpp/dsp/FastFft.cpp
#include "FastFft.h"
#include <cmath>
#include <algorithm>

namespace client::dsp {

static constexpr float PI = 3.14159265358979323846f;
static constexpr size_t N = audio::FFT_SIZE;

FastFft::FastFft()
    : readyIdx_(0),
      writeIdx_(1),
      readIdx_(2) {
}

void FastFft::computeFft(float* real, float* imag, size_t n) {
    size_t j = 0;
    for (size_t i = 0; i < n - 1; ++i) {
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
        size_t k = n / 2;
        while (k <= j) {
            j -= k;
            k /= 2;
        }
        j += k;
    }

    for (size_t len = 2; len <= n; len <<= 1) {
        float angle = -2.0f * PI / static_cast<float>(len);
        float wlen_r = std::cos(angle);
        float wlen_i = std::sin(angle);

        for (size_t i = 0; i < n; i += len) {
            float w_r = 1.0f;
            float w_i = 0.0f;
            for (size_t k = 0; k < len / 2; ++k) {
                float u_r = real[i + k];
                float u_i = imag[i + k];
                float v_r = real[i + k + len / 2] * w_r - imag[i + k + len / 2] * w_i;
                float v_i = real[i + k + len / 2] * w_i + imag[i + k + len / 2] * w_r;

                real[i + k] = u_r + v_r;
                imag[i + k] = u_i + v_i;
                real[i + k + len / 2] = u_r - v_r;
                imag[i + k + len / 2] = u_i - v_i;

                float next_w_r = w_r * wlen_r - w_i * wlen_i;
                w_i = w_r * wlen_i + w_i * wlen_r;
                w_r = next_w_r;
            }
        }
    }
}

void FastFft::process(const float* pcmInput, size_t count, float micRms, float outRms) {
    if (count < N) return;

    alignas(16) float real[N] = {0.0f};
    alignas(16) float imag[N] = {0.0f};

    for (size_t i = 0; i < N; ++i) {
        float hann = 0.5f * (1.0f - std::cos(2.0f * PI * i / (N - 1)));
        real[i] = pcmInput[i] * hann;
    }

    computeFft(real, imag, N);

    float rawBands[audio::SPECTRUM_BANDS] = {0.0f};

    // Sub-Bass (60-150 Гц) -> Бин 1
    rawBands[0] = std::sqrt(real[1] * real[1] + imag[1] * imag[1]) * 1.5f;

    // Bass (150-350 Гц) -> Бины 2-3
    for (size_t b = 2; b <= 3; ++b) {
        rawBands[1] += std::sqrt(real[b] * real[b] + imag[b] * imag[b]);
    }
    rawBands[1] *= 0.8f;

    // Mid (350-2000 Гц) -> Бины 4-21
    for (size_t b = 4; b <= 21; ++b) {
        rawBands[2] += std::sqrt(real[b] * real[b] + imag[b] * imag[b]);
    }
    rawBands[2] *= 0.12f;

    // Presence (2000-5000 Гц) -> Бины 22-53
    for (size_t b = 22; b <= 53; ++b) {
        rawBands[3] += std::sqrt(real[b] * real[b] + imag[b] * imag[b]);
    }
    rawBands[3] *= 0.08f;

    // Air (5000-12000 Гц) -> Бины 54-127
    for (size_t b = 54; b < 128; ++b) {
        rawBands[4] += std::sqrt(real[b] * real[b] + imag[b] * imag[b]);
    }
    rawBands[4] *= 0.06f;

    constexpr float alpha_attack = 0.65f;
    constexpr float alpha_decay = 0.12f;

    for (size_t i = 0; i < audio::SPECTRUM_BANDS; ++i) {
        float target = std::clamp(rawBands[i], 0.0f, 1.0f);
        if (target > smoothedBands_[i]) {
            smoothedBands_[i] += alpha_attack * (target - smoothedBands_[i]);
        } else {
            smoothedBands_[i] -= alpha_decay * (smoothedBands_[i] - target);
        }
    }

    // ERR-05: Запись в изолированный рабочий буфер писателя (полное отсутствие гонок)
    for (size_t i = 0; i < audio::SPECTRUM_BANDS; ++i) {
        pool_[writeIdx_].bands[i] = smoothedBands_[i];
    }
    pool_[writeIdx_].micRms = micRms;
    pool_[writeIdx_].outRms = outRms;

    // Неделимая публикация свежего буфера по алгоритму Андерсона:
    // Писатель передает writeIdx_ в readyIdx_ и забирает освободившийся слот
    writeIdx_ = readyIdx_.exchange(writeIdx_, std::memory_order_acq_rel);
}

void FastFft::getLatestSnapshot(SpectrumSnapshot& out) const {
    // Неделимое получение свежего буфера по алгоритму Андерсона:
    // Читатель забирает readyIdx_ в readIdx_ и возвращает старый прочитанный слот в пул
    readIdx_ = readyIdx_.exchange(readIdx_, std::memory_order_acq_rel);
    out = pool_[readIdx_];
}

} // namespace client::dsp