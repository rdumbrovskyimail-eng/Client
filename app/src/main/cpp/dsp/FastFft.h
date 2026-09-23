// >>> FILE: app/src/main/cpp/dsp/FastFft.h
#pragma once

#include <cstddef>
#include <atomic>
#include <cstdint>
#include <array>
#include <mutex>
#include "audio/AudioConstants.h"

namespace client::dsp {

// Согласованный снимок полос БПФ и RMS с выравниванием по границе 16 байт
struct alignas(16) SpectrumSnapshot {
    float bands[audio::SPECTRUM_BANDS]{0.0f};
    float micRms{0.0f};
    float outRms{0.0f};
};

class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    // Расчет спектра с учетом динамической частоты дискретизации sampleRate
    void process(const float* pcmInput, size_t count, float micRms, float outRms, int32_t sampleRate = audio::SAMPLE_RATE_GEMINI_OUT);

    // Ограниченное по числу попыток считывание когерентного среза из UI JNI
    void getLatestSnapshot(SpectrumSnapshot& out) const;

private:
    void computeFft(float* real, float* imag);

    // Problem #12: Precomputed lookup tables for real-time FFT execution.
    // Aligned to 16 bytes for direct vectorization via ARM NEON (vmulq_f32).
    alignas(16) float hannWindow_[audio::FFT_SIZE]{0.0f};
    alignas(16) float twiddleR_[audio::FFT_SIZE / 2]{0.0f};
    alignas(16) float twiddleI_[audio::FFT_SIZE / 2]{0.0f};
    uint16_t bitRev_[audio::FFT_SIZE]{0};

    float smoothedBands_[audio::SPECTRUM_BANDS]{0.0f};

    alignas(64)
    std::array<std::atomic<float>, audio::SPECTRUM_BANDS> snapshotBands_{};

    std::atomic<float> snapshotMicRms_{0.0f};
    std::atomic<float> snapshotOutRms_{0.0f};

    // Even = stable snapshot, odd = writer is publishing.
    mutable std::atomic<uint32_t> snapshotSeq_{0};

    // Мьютекс для защиты fallback-снимка от состояния гонки (Data Race)
    // между читающими потоками. Аудио-поток (process()) никогда не захватывает этот мьютекс.
    mutable std::mutex fallbackMutex_;

    // Последний снимок, прошедший проверку целостности Seqlock.
    mutable SpectrumSnapshot lastStableSnapshot_{};
};

} // namespace client::dsp