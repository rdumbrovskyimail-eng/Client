#pragma once

#include <cstdint>
#include <cstddef>

namespace client::audio {

// Нативные частоты Gemini Live API
constexpr int32_t SAMPLE_RATE_GEMINI_IN = 16000;    // 16 кГц вход Gemini
constexpr int32_t SAMPLE_RATE_GEMINI_OUT = 24000;   // 24 кГц выход Gemini

// Частоты Bluetooth тракта CMF Buds 2
constexpr int32_t SAMPLE_RATE_BT_HFP = 16000;       // 16 кГц mSBC Wideband Speech
constexpr int32_t SAMPLE_RATE_BT_LC3 = 24000;       // 24 кГц LE Audio LC3
constexpr int32_t SAMPLE_RATE_BT_A2DP = 48000;      // 48 кГц Стандартный A2DP / LDAC

constexpr int32_t CHANNEL_COUNT_MONO = 1;

// Кванты обработки
constexpr size_t BURST_10MS_16K = 160;              // 10 мс @ 16 кГц
constexpr size_t BURST_10MS_24K = 240;              // 10 мс @ 24 кГц
constexpr size_t BURST_40MS_16K = 640;              // 40 мс @ 16 кГц (батч отправки)

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);

// Емкость кольцевых буферов (строго степень двойки)
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 8192;   // ~512 мс буфера захвата
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 16384; // ~682 мс буфера вывода

// Параметры генератора Earcon (звуковой Barge-In для CMF Buds 2)
constexpr float EARCON_FREQ_HZ = 750.0f;
constexpr size_t EARCON_DURATION_FRAMES_24K = 360;  // 15 мс при 24 кГц

// FFT константы
constexpr size_t FFT_SIZE = 256;
constexpr size_t SPECTRUM_BANDS = 5;

} // namespace client::audio