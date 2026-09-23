// >>> FILE: app/src/main/cpp/dsp/FastFft.cpp
#include "FastFft.h"
#include <cmath>
#include <algorithm>

namespace client::dsp {

static constexpr float PI = 3.14159265358979323846f;
static constexpr size_t N = audio::FFT_SIZE;

FastFft::FastFft()
    : snapshotMicRms_(0.0f),
      snapshotOutRms_(0.0f),
      snapshotSeq_(0) {
    for (auto& value : snapshotBands_) {
        value.store(0.0f, std::memory_order_relaxed);
    }
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

void FastFft::process(
    const float* pcmInput,
    size_t count,
    float micRms,
    float outRms,
    int32_t sampleRate) {

    // A valid FFT block requires both a complete input buffer and enough
    // samples for one N-point transform.
    if (pcmInput == nullptr || count < N) {
        return;
    }

    if (sampleRate <= 0) {
        sampleRate = audio::SAMPLE_RATE_GEMINI_OUT;
    }

    alignas(16) float real[N] = {0.0f};
    alignas(16) float imag[N] = {0.0f};

    int32_t effectiveSr = sampleRate;
    if (sampleRate >= 44100 && count >= N * 2) {
        effectiveSr = sampleRate / 2;
        // 3-point anti-aliasing FIR filter [0.25, 0.5, 0.25] before 2:1 decimation
        // to prevent high-frequency spectral components (> 11 kHz) from folding into low bins.
        for (size_t i = 0; i < N; ++i) {
            float hann = 0.5f * (1.0f - std::cos(2.0f * PI * i / (N - 1)));
            const size_t idx = i * 2;
            const float prev = (idx > 0) ? pcmInput[idx - 1] : pcmInput[idx];
            const float curr = pcmInput[idx];
            const float next = (idx + 1 < count) ? pcmInput[idx + 1] : curr;
            const float filtered = 0.25f * prev + 0.5f * curr + 0.25f * next;
            real[i] = filtered * hann;
        }
    } else {
        // Linear 1:1 indexing for native Gemini rates (e.g. 24 kHz or 16 kHz).
        // Corrects previous copy-paste bug (i * 2) that caused heap buffer over-read.
        for (size_t i = 0; i < N; ++i) {
            float hann = 0.5f * (1.0f - std::cos(2.0f * PI * i / (N - 1)));
            real[i] = pcmInput[i] * hann;
        }
    }

    computeFft(real, imag, N);

    auto freqToBin = [effectiveSr](float freq) -> size_t {
        float binF = std::round((freq * static_cast<float>(N)) / static_cast<float>(effectiveSr));
        return static_cast<size_t>(std::clamp(binF, 1.0f, static_cast<float>(N / 2 - 1)));
    };

    struct BandDef {
        float lowFreq;
        float highFreq;
        float gain;
    };

    static const BandDef BANDS[audio::SPECTRUM_BANDS] = {
        {   60.0f,   150.0f, 1.50f }, // Sub-Bass
        {  150.0f,   350.0f, 1.60f }, // Bass
        {  350.0f,  2000.0f, 2.16f }, // Mid
        { 2000.0f,  5000.0f, 2.56f }, // Presence
        { 5000.0f, 12000.0f, 4.44f }  // Air
    };

    float rawBands[audio::SPECTRUM_BANDS] = {0.0f};

    for (size_t i = 0; i < audio::SPECTRUM_BANDS; ++i) {
        size_t startBin = freqToBin(BANDS[i].lowFreq);
        size_t endBin = freqToBin(BANDS[i].highFreq);
        if (endBin < startBin) endBin = startBin;

        float sumMag = 0.0f;
        for (size_t b = startBin; b <= endBin; ++b) {
            sumMag += std::sqrt(real[b] * real[b] + imag[b] * imag[b]);
        }

        size_t binCount = endBin - startBin + 1;
        constexpr float fftNormFactor = static_cast<float>(N) * 0.25f;
        rawBands[i] = (sumMag / (static_cast<float>(binCount) * fftNormFactor)) * BANDS[i].gain;
    }

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

    // Publish with a seqlock. Odd value = writer owns snapshot; even value = stable payload.
    snapshotSeq_.fetch_add(1, std::memory_order_acq_rel);

    for (size_t i = 0; i < audio::SPECTRUM_BANDS; ++i) {
        snapshotBands_[i].store(
            smoothedBands_[i],
            std::memory_order_relaxed
        );
    }

    snapshotMicRms_.store(
        micRms,
        std::memory_order_relaxed
    );
    snapshotOutRms_.store(
        outRms,
        std::memory_order_relaxed
    );

    snapshotSeq_.fetch_add(1, std::memory_order_release);
}

void FastFft::getLatestSnapshot(SpectrumSnapshot& out) const {
    for (int attempt = 0; attempt < 16; ++attempt) {
        const uint32_t seq1 =
            snapshotSeq_.load(std::memory_order_acquire);

        if ((seq1 & 1u) != 0u) {
            continue;
        }

        SpectrumSnapshot candidate{};

        for (size_t i = 0; i < audio::SPECTRUM_BANDS; ++i) {
            candidate.bands[i] =
                snapshotBands_[i].load(std::memory_order_relaxed);
        }

        candidate.micRms =
            snapshotMicRms_.load(std::memory_order_relaxed);
        candidate.outRms =
            snapshotOutRms_.load(std::memory_order_relaxed);

        const uint32_t seq2 =
            snapshotSeq_.load(std::memory_order_acquire);

        if (
            seq1 == seq2 &&
            (seq2 & 1u) == 0u
        ) {
            {
                // Synchronize fallback snapshot mutation to eliminate C++ data races across reader threads.
                std::lock_guard<std::mutex> lock(fallbackMutex_);
                lastStableSnapshot_ = candidate;
            }
            out = candidate;
            return;
        }
    }

    // Bounded fallback: return last verified consistent snapshot under lock.
    std::lock_guard<std::mutex> lock(fallbackMutex_);
    out = lastStableSnapshot_;
}

} // namespace client::dsp