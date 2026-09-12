// >>> FILE: app/src/main/cpp/dsp/FastFft.h
#pragma once

#include <cstddef>
#include <array>
#include <vector>

namespace client::dsp {

class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    // Рассчитывает 5 спектральных полос [SubBass, Bass, Mid, Presence, Air]
    void process(const float* pcmInput, size_t count);

    // Считывает сглаженные баллистикой значения [0.0 ... 1.0]
    std::array<float, 5> getBands() const;

private:
    void computeFft(float* real, float* imag, size_t n);

    std::array<float, 5> smoothedBands_{0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    std::vector<float> windowBuffer_;
};

} // namespace client::dsp