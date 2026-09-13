// >>> FILE: app/src/main/cpp/audio/AudioConstants.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <limits>

namespace client::audio {

// Нативные частоты Gemini Live API
constexpr int32_t SAMPLE_RATE_GEMINI_IN = 16000;    // 16 кГц вход Gemini
constexpr int32_t SAMPLE_RATE_GEMINI_OUT = 24000;   // 24 кГц выход Gemini

// Частоты Bluetooth трактов
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

// E-10: Параметры генератора Earcon (задаются во времени)
constexpr float EARCON_FREQ_HZ = 750.0f;
constexpr float EARCON_DURATION_MS = 15.0f;
constexpr size_t EARCON_INACTIVE_PHASE = std::numeric_limits<size_t>::max();

// FFT константы
constexpr size_t FFT_SIZE = 256;
constexpr size_t FFT_HOP_SIZE = 128;
constexpr size_t SPECTRUM_BANDS = 5;

} // namespace client::audio