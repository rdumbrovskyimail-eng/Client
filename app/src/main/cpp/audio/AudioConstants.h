#pragma once

#include <cstdint>
#include <cstddef>

namespace client::audio {

// Нативные частоты Gemini Live API
constexpr int32_t SAMPLE_RATE_IN = 16000;    // 16 кГц вход
constexpr int32_t SAMPLE_RATE_OUT = 24000;   // 24 кГц выход

constexpr int32_t CHANNEL_COUNT_MONO = 1;

// Аппаратный квант (Burst) 10 мс
constexpr size_t CAPTURE_BURST_FRAMES = 160;   // 160 сэмплов = 10 мс при 16 кГц
constexpr size_t PLAYBACK_BURST_FRAMES = 240;  // 240 сэмплов = 10 мс при 24 кГц

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);

// Емкость кольцевых буферов (степень двойки для побитовой маски)
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 8192;   // ~512 мс буфера
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 16384; // ~682 мс буфера

// FFT константы для визуализатора
constexpr size_t FFT_SIZE = 256;
constexpr size_t SPECTRUM_BANDS = 5;

} // namespace client::audio