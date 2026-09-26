// >>> FILE: app/src/main/cpp/audio/AudioConstants.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <limits>

namespace client::audio {

// Gemini Live API's native audio rates.
// Live API input is natively 16 kHz and output is 24 kHz.
constexpr int32_t SAMPLE_RATE_GEMINI_IN = 16000;    // 16 kHz Gemini input
constexpr int32_t SAMPLE_RATE_GEMINI_OUT = 24000;   // 24 kHz Gemini output

// Common/optimized Android audio rates used by this application.
constexpr int32_t SAMPLE_RATE_NATIVE_SPEAKER = 48000; // Common native speaker rate
constexpr int32_t SAMPLE_RATE_BT_HFP = 16000;         // mSBC / wideband HFP path
constexpr int32_t SAMPLE_RATE_BT_LC3 = 24000;         // LC3 24 kHz
constexpr int32_t SAMPLE_RATE_BT_LC3_32K = 32000;     // LC3 32 kHz (LE Audio Voice BAP)
constexpr int32_t SAMPLE_RATE_BT_A2DP = 48000;        // Common A2DP high-quality rate

constexpr int32_t CHANNEL_COUNT_MONO = 1;

// Processing quanta
constexpr size_t BURST_10MS_16K = 160;              // 10 ms @ 16 kHz
constexpr size_t BURST_10MS_24K = 240;              // 10 ms @ 24 kHz
constexpr size_t BURST_10MS_32K = 320;              // 10 ms @ 32 kHz
constexpr size_t BURST_10MS_48K = 480;              // 10 ms @ 48 kHz
constexpr size_t BURST_40MS_16K = 640;              // 40 ms @ 16 kHz (send batch)

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);

// УСТРАНЕНИЕ ДЕФЕКТА 30: Рациональные размеры кольцевых буферов под L2/L3 кэш процессора
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 32768;   // ~2048 ms @ 16 kHz mono (64 KiB)
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 32768;  // ~1365 ms @ 24 kHz / ~682 ms @ 48 kHz (64 KiB)

// УСТРАНЕНИЕ ДЕФЕКТОВ 28 и 29: Снижение аппаратного буферного балласта со 140 мс до 25 мс (ITU-T G.114)
constexpr size_t PLAYBACK_TARGET_BUFFER_MS = 25;

// Аппаратный множитель бёрстов AAudio (снижен с 8 до 3 для достижения минимальной задержки)
constexpr size_t PLAYBACK_BURST_MIN_MULTIPLIER = 3;

// Таймаут синхронизации воркера при смене поколения эпохи (миллисекунды)
constexpr size_t PLAYBACK_DSP_RESET_TIMEOUT_MS = 50;

// Ресемплинг и децимация (Zero-Allocation scratch space)
constexpr size_t RESAMPLE_SCRATCH_CAPACITY = 65536;      // 131 KiB scratch space
constexpr size_t CAPTURE_DECIMATE_CAPACITY = 32768;      // 64 KiB decimator scratch space

// Earcon генератор
constexpr float EARCON_FREQ_HZ = 750.0f;
constexpr float EARCON_DURATION_MS = 15.0f;
constexpr size_t EARCON_INACTIVE_PHASE = std::numeric_limits<size_t>::max();

// FFT константы
constexpr size_t FFT_SIZE = 256;
constexpr size_t FFT_HOP_SIZE = 128;
constexpr size_t SPECTRUM_BANDS = 5;

} // namespace client::audio