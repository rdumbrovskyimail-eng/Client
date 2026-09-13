// >>> FILE: app/src/main/cpp/dsp/FastFft.h
#pragma once

#include <cstddef>
#include <atomic>
#include "audio/AudioConstants.h"

namespace client::dsp {

// E-03: Согласованный снимок полос БПФ и RMS
struct SpectrumSnapshot {
    float bands[audio::SPECTRUM_BANDS]{0.0f};
    float micRms{0.0f};
    float outRms{0.0f};
};

class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    // Расчет 5 спектральных полос и публикация снимка
    void process(const float* pcmInput, size_t count, float micRms, float outRms);

    // E-03: Wait-free безопасное считывание когерентного среза из UI JNI
    void getLatestSnapshot(SpectrumSnapshot& out) const;

private:
    void computeFft(float* real, float* imag, size_t n);

    float smoothedBands_[audio::SPECTRUM_BANDS]{0.0f};

    // E-03: Тройной буфер снимков
    SpectrumSnapshot pool_[3];
    mutable std::atomic<size_t> readyIdx_{0};
    mutable std::atomic<size_t> readIdx_{1};
};

} // namespace client::dsp