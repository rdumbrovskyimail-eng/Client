// >>> FILE: app/src/main/cpp/dsp/FastFft.h
#pragma once

#include <cstddef>
#include <atomic>
#include <cstdint>
#include <array>
#include "audio/AudioConstants.h"

namespace client::dsp {

// E-03: Согласованный снимок полос БПФ и RMS с выравниванием по границе 16 байт
struct alignas(16) SpectrumSnapshot {
    float bands[audio::SPECTRUM_BANDS]{0.0f};
    float micRms{0.0f};
    float outRms{0.0f};
};

class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    // ERR-09: Расчет спектра с учетом динамической частоты дискретизации sampleRate
    void process(const float* pcmInput, size_t count, float micRms, float outRms, int32_t sampleRate = audio::SAMPLE_RATE_GEMINI_OUT);

    // E-03, ERR-05: Истинно Wait-Free считывание когерентного среза из UI JNI
    void getLatestSnapshot(SpectrumSnapshot& out) const;

private:
    void computeFft(float* real, float* imag, size_t n);

    float smoothedBands_[audio::SPECTRUM_BANDS]{0.0f};

    // ERR-05 / concurrency fix:
    // A snapshot is published with a seqlock. The payload itself is stored as
    // atomic scalar values, so a concurrent UI read can never race a writer.
    alignas(64)
    std::array<std::atomic<float>, audio::SPECTRUM_BANDS> snapshotBands_{};

    std::atomic<float> snapshotMicRms_{0.0f};
    std::atomic<float> snapshotOutRms_{0.0f};

    // Even = stable snapshot, odd = writer is publishing.
    mutable std::atomic<uint32_t> snapshotSeq_{0};

    // Last snapshot that passed the stability check. Used only on bounded
    // fallback so callers never receive a mixed-epoch snapshot.
    mutable SpectrumSnapshot lastStableSnapshot_{};
};

} // namespace client::dsp