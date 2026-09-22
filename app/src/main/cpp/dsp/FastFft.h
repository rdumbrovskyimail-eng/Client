#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

#include "audio/AudioConstants.h"

namespace client::dsp {

// E-03: coherent FFT/RMS snapshot aligned to a 16-byte boundary.
struct alignas(16) SpectrumSnapshot {
    float bands[audio::SPECTRUM_BANDS]{0.0f};
    float micRms{0.0f};
    float outRms{0.0f};
};

class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    // Calculate spectrum using the actual input sample rate.
    void process(
        const float* pcmInput,
        size_t count,
        float micRms,
        float outRms,
        int32_t sampleRate = audio::SAMPLE_RATE_GEMINI_OUT
    );

    // Read one coherent snapshot without exposing a mixed publication epoch.
    void getLatestSnapshot(
        SpectrumSnapshot& out
    ) const;

private:
    void computeFft(
        float* real,
        float* imag,
        size_t n
    );

    float smoothedBands_[
        audio::SPECTRUM_BANDS
    ]{0.0f};

    /*
     * Main publication path:
     *   - scalar payloads are atomic;
     *   - snapshotSeq_ forms the seqlock.
     *
     * The fallback snapshot is a normal object, therefore its accesses are
     * protected separately. This prevents concurrent readers from racing with
     * each other when updating/reading lastStableSnapshot_.
     */
    alignas(64)
    std::array<
        std::atomic<float>,
        audio::SPECTRUM_BANDS
    > snapshotBands_{};

    std::atomic<float> snapshotMicRms_{0.0f};
    std::atomic<float> snapshotOutRms_{0.0f};

    // Even = stable publication, odd = writer is publishing.
    mutable std::atomic<uint32_t> snapshotSeq_{0};

    // Last snapshot that passed the seqlock stability check.
    mutable SpectrumSnapshot lastStableSnapshot_{};

    // Protects the non-atomic fallback snapshot.
    mutable std::mutex lastStableSnapshotMutex_;
};

} // namespace client::dsp