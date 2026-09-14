// >>> FILE: app/src/main/cpp/audio/AudioConstants.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <limits>

namespace client::audio {

// Нативные частоты Gemini Live API
constexpr int32_t SAMPLE_RATE_GEMINI_IN = 16000;    // 16 кГц вход Gemini
constexpr int32_t SAMPLE_RATE_GEMINI_OUT = 24000;   // 24 кГц выход Gemini

// Частоты аппаратных аудиоинтерфейсов Android / Qualcomm HAL
constexpr int32_t SAMPLE_RATE_NATIVE_SPEAKER = 48000; // 48 кГц нативный ЦАП S23 Ultra WCD9385
constexpr int32_t SAMPLE_RATE_BT_HFP = 16000;         // 16 кГц mSBC Wideband Speech
constexpr int32_t SAMPLE_RATE_BT_LC3 = 24000;         // 24 кГц LE Audio LC3
constexpr int32_t SAMPLE_RATE_BT_A2DP = 48000;        // 48 кГц Стандартный A2DP / LDAC

constexpr int32_t CHANNEL_COUNT_MONO = 1;

// Кванты времени и обработки
constexpr size_t BURST_10MS_16K = 160;              // 10 мс @ 16 кГц
constexpr size_t BURST_10MS_24K = 240;              // 10 мс @ 24 кГц
constexpr size_t BURST_10MS_48K = 480;              // 10 мс @ 48 кГц
constexpr size_t BURST_40MS_16K = 640;              // 40 мс @ 16 кГц (батч отправки)

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);

// Емкость кольцевых буферов (строго степень двойки)
// Источники 24 & 62: буфер обязан вмещать сетевые залпы Gemini (до 38+ КБ на пакет и 250 КБ / сек)
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 32768;   // ~2048 мс @ 16 кГц (64 КБ)
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 262144; // ~10.9 сек @ 24 кГц / ~5.46 сек @ 48 кГц (512 КБ)

// Емкость статических скретч-буферов ресемплинга и децимации (Zero-Allocation)
// Гарантирует прием пакетов до 65536 сэмплов даже с двукратным повышением частоты (24 -> 48 кГц)
constexpr size_t RESAMPLE_SCRATCH_CAPACITY = 131072;     // 262 КБ скретчпад ресемплера
constexpr size_t CAPTURE_DECIMATE_CAPACITY = 32768;      // 64 КБ скретчпад дециматора

// Параметры генератора Earcon (задаются во времени)
constexpr float EARCON_FREQ_HZ = 750.0f;
constexpr float EARCON_DURATION_MS = 15.0f;
constexpr size_t EARCON_INACTIVE_PHASE = std::numeric_limits<size_t>::max();

// FFT константы спектрального анализатора
constexpr size_t FFT_SIZE = 256;
constexpr size_t FFT_HOP_SIZE = 128;
constexpr size_t SPECTRUM_BANDS = 5;

} // namespace client::audio