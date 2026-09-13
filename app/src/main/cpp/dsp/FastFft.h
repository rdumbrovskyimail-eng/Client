// >>> FILE: app/src/main/cpp/dsp/FastFft.h
#pragma once

#include <cstddef>
#include <atomic>
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

    // Расчет 5 спектральных полос и публикация снимка (вызывается из аудиопотока)
    void process(const float* pcmInput, size_t count, float micRms, float outRms);

    // E-03, ERR-05: Истинно Wait-Free считывание когерентного среза из UI JNI
    void getLatestSnapshot(SpectrumSnapshot& out) const;

private:
    void computeFft(float* real, float* imag, size_t n);

    float smoothedBands_[audio::SPECTRUM_BANDS]{0.0f};

    // ERR-05: Эталонный тройной буфер Дэвида Андерсона
    alignas(64) SpectrumSnapshot pool_[3];
    mutable std::atomic<size_t> readyIdx_{0}; // Разделяемый слот готовности
    size_t writeIdx_{1};                      // Приватный рабочий слот писателя (аудиопоток)
    mutable size_t readIdx_{2};               // Приватный рабочий слот читателя (UI-поток)
};

} // namespace client::dsp