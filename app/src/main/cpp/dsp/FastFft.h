#pragma once

#include <cstddef>
#include <atomic>
#include <cstdint>
#include <array>
#include "audio/AudioConstants.h"

namespace client::dsp {

/**
 * Согласованный снимок полос БПФ и среднеквадратичной мощности.
 * Выровнен по границе 16 байт для эффективного копирования.
 */
struct alignas(16) SpectrumSnapshot {
    float bands[audio::SPECTRUM_BANDS]{0.0f};
    float micRms{0.0f};
    float outRms{0.0f};
};

/**
 * Высокопроизводительный БПФ-анализатор спектра реального времени.
 *
 * УСТРАНЕНИЕ ДЕФЕКТА 172:
 * Полная ликвидация гибридной блокировки (Seqlock + Fallback Mutex).
 * Реализован канонический неблокирующий Seqlock алгоритм без единого мьютекса:
 * - Писатель (DSP Tap Worker) монопольно публикует снимок через memory_order_release.
 * - Читатели (UI JNI на 120 FPS) выполняют неблокирующее считывание за 1-2 попытки.
 * - Нулевая вероятность взаимной блокировки потоков.
 */
class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    void process(
        const float* pcmInput,
        size_t count,
        float micRms,
        float outRms,
        int32_t sampleRate = audio::SAMPLE_RATE_GEMINI_OUT);

    void getLatestSnapshot(SpectrumSnapshot& out) const;

private:
    void computeFft(float* real, float* imag);

    alignas(16) float hannWindow_[audio::FFT_SIZE]{0.0f};
    alignas(16) float twiddleR_[audio::FFT_SIZE / 2]{0.0f};
    alignas(16) float twiddleI_[audio::FFT_SIZE / 2]{0.0f};
    uint16_t bitRev_[audio::FFT_SIZE]{0};

    float smoothedBands_[audio::SPECTRUM_BANDS]{0.0f};

    // Четное значение = стабильный снимок, нечетное = идет публикация писателем
    mutable std::atomic<uint32_t> snapshotSeq_{0};

    // Однородная структура снимка без разделения на 7 отдельных атомиков
    alignas(16) SpectrumSnapshot activeSnapshot_{};
};

} // namespace client::dsp