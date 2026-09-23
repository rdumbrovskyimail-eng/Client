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

    // Problem #12: One-time precomputation of the Hann window coefficients (double precision).
    // Eliminates 256 calls to std::cos() on every FFT block (~48,000 calls/sec).
    for (size_t i = 0; i < N; ++i) {
        const double angle = 2.0 * static_cast<double>(PI) * static_cast<double>(i) / static_cast<double>(N - 1);
        hannWindow_[i] = static_cast<float>(0.5 * (1.0 - std::cos(angle)));
    }

    // Problem #12: One-time precomputation of 8-bit bit-reversal indices (log2(256) = 8).
    // Eliminates nested loops with scalar division and bit shifting during real-time processing.
    for (size_t i = 0; i < N; ++i) {
        size_t rev = 0;
        size_t temp = i;
        for (size_t b = 0; b < 8; ++b) {
            rev = (rev << 1) | (temp & 1u);
            temp >>= 1;
        }
        bitRev_[i] = static_cast<uint16_t>(rev);
    }

    // Problem #12: One-time precomputation of complex twiddle factors W_N^k = e^(-j*2*pi*k/N).
    // Completely eliminates runtime trigonometric calls and prevents numerical drift (Goldberg drift).
    for (size_t k = 0; k < N / 2; ++k) {
        const double angle = -2.0 * static_cast<double>(PI) * static_cast<double>(k) / static_cast<double>(N);
        twiddleR_[k] = static_cast<float>(std::cos(angle));
        twiddleI_[k] = static_cast<float>(std::sin(angle));
    }
}

void FastFft::computeFft(float* real, float* imag) {
    // 1. Bit-reversal permutation via fast table lookup (O(N) with zero bit-twiddling)
    for (size_t i = 0; i < N; ++i) {
        const size_t j = bitRev_[i];
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
    }

    // 2. Cooley-Tukey Radix-2 butterflies using direct L1d twiddle factor table indexing
    for (size_t len = 2; len <= N; len <<= 1) {
        const size_t halfLen = len >> 1;
        const size_t step = N / len;

        for (size_t i = 0; i < N; i += len) {
            for (size_t k = 0; k < halfLen; ++k) {
                const size_t tableIdx = k * step;
                const float w_r = twiddleR_[tableIdx];
                const float w_i = twiddleI_[tableIdx];

                const size_t posA = i + k;
                const size_t posB = posA + halfLen;

                const float u_r = real[posA];
                const float u_i = imag[posA];
                const float v_r = real[posB] * w_r - imag[posB] * w_i;
                const float v_i = real[posB] * w_i + imag[posB] * w_r;

                real[posA] = u_r + v_r;
                imag[posA] = u_i + v_i;
                real[posB] = u_r - v_r;
                imag[posB] = u_i - v_i;
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
        // applied with precomputed Hann window weights (zero runtime std::cos calls).
        for (size_t i = 0; i < N; ++i) {
            const size_t idx = i * 2;
            const float prev = (idx > 0) ? pcmInput[idx - 1] : pcmInput[idx];
            const float curr = pcmInput[idx];
            const float next = (idx + 1 < count) ? pcmInput[idx + 1] : curr;
            const float filtered = 0.25f * prev + 0.5f * curr + 0.25f * next;
            real[i] = filtered * hannWindow_[i];
        }
    } else {
        // Linear 1:1 indexing for native Gemini rates (e.g. 24 kHz or 16 kHz)
        // using vectorized precomputed Hann window (direct memory multiplication).
        for (size_t i = 0; i < N; ++i) {
            real[i] = pcmInput[i] * hannWindow_[i];
        }
    }

    computeFft(real, imag);

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