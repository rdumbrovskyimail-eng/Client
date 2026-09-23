// >>> FILE: app/src/main/cpp/audio/AudioConstants.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <limits>

namespace client::audio {

// Gemini Live API's native audio rates.
// Live API input is natively 16 kHz and output is 24 kHz.
// The service can resample other explicitly declared input rates.
constexpr int32_t SAMPLE_RATE_GEMINI_IN = 16000;    // 16 kHz Gemini input
constexpr int32_t SAMPLE_RATE_GEMINI_OUT = 24000;   // 24 kHz Gemini output

// Common/optimized Android audio rates used by this application.
// These are not universal hardware guarantees for every device or codec.
constexpr int32_t SAMPLE_RATE_NATIVE_SPEAKER = 48000; // Common native speaker rate
constexpr int32_t SAMPLE_RATE_BT_HFP = 16000;         // mSBC / wideband HFP path
constexpr int32_t SAMPLE_RATE_BT_LC3 = 24000;         // Common LC3/LE Audio rate
constexpr int32_t SAMPLE_RATE_BT_A2DP = 48000;        // Common A2DP high-quality rate

constexpr int32_t CHANNEL_COUNT_MONO = 1;

// Processing quanta
constexpr size_t BURST_10MS_16K = 160;              // 10 ms @ 16 kHz
constexpr size_t BURST_10MS_24K = 240;              // 10 ms @ 24 kHz
constexpr size_t BURST_10MS_48K = 480;              // 10 ms @ 48 kHz
constexpr size_t BURST_40MS_16K = 640;              // 40 ms @ 16 kHz (send batch)

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);

// Ring-buffer capacities (must be powers of two)
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 32768;   // ~2048 ms @ 16 kHz mono (64 KiB)
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 262144; // ~10.9 s @ 24 kHz / ~5.46 s @ 48 kHz (512 KiB)

// Problem #7: Jitter-absorbing playback pre-buffering constants.
// 140 ms baseline is strictly compliant with ITU-T G.114 (<150 ms conversational threshold),
// WebRTC NetEQ playout recommendations, and comfortably absorbs Linux CFS scheduling
// jitter, CPU cluster migrations (big.LITTLE), and DVFS frequency ramp-up delays.
constexpr size_t PLAYBACK_TARGET_BUFFER_MS = 140;

// Hardware burst multiple as recommended by Google Audio / Phil Burk (Oboe):
// ensures buffer holds at least 8 hardware burst periods regardless of hardware quantization.
constexpr size_t PLAYBACK_BURST_MIN_MULTIPLIER = 8;

// Problem #10: Playback DSP worker reset acknowledgment watchdog timeout (milliseconds).
// Extended from fragile 100 ms to 600 ms based on NASA software safety margin (3x worst-case)
// and Google SRE tail-latency guidelines, absorbing Android ART Generational CC GC pauses,
// CFS thread preemption, and mutex contention without falsely killing healthy AAudio streams.
constexpr size_t PLAYBACK_DSP_RESET_TIMEOUT_MS = 600;

// Resampling/decimation scratch capacities (zero-allocation processing)
constexpr size_t RESAMPLE_SCRATCH_CAPACITY = 131072;     // 262 KiB scratch space
constexpr size_t CAPTURE_DECIMATE_CAPACITY = 32768;      // 64 KiB decimator scratch space

// Earcon generator parameters
constexpr float EARCON_FREQ_HZ = 750.0f;
constexpr float EARCON_DURATION_MS = 15.0f;
constexpr size_t EARCON_INACTIVE_PHASE = std::numeric_limits<size_t>::max();

// FFT constants
constexpr size_t FFT_SIZE = 256;
constexpr size_t FFT_HOP_SIZE = 128;
constexpr size_t SPECTRUM_BANDS = 5;

} // namespace client::audio