#pragma once

#include <cstdint>
#include <cstddef>
#include <limits>

namespace client::audio {

// Базовые частоты дискретизации Gemini Live API
constexpr int32_t SAMPLE_RATE_GEMINI_IN = 16000;    // 16 кГц вход микрофона Gemini
constexpr int32_t SAMPLE_RATE_GEMINI_OUT = 24000;   // 24 кГц нативный выход Gemini

// Аппаратные частоты дискретизации Android и профилей Bluetooth
constexpr int32_t SAMPLE_RATE_NATIVE_SPEAKER = 48000; // Нативная частота динамика Qualcomm WCD9385
constexpr int32_t SAMPLE_RATE_BT_HFP = 16000;         // Bluetooth HFP 1.8 Wideband Speech (mSBC)
constexpr int32_t SAMPLE_RATE_BT_HFP_CVSD = 8000;     // Bluetooth HFP Narrowband Speech (CVSD)
constexpr int32_t SAMPLE_RATE_BT_LC3_24K = 24000;     // Bluetooth LE Audio LC3 (24 кГц Standard)
constexpr int32_t SAMPLE_RATE_BT_LC3_32K = 32000;     // Bluetooth LE Audio LC3 (32 кГц Voice BAP)
constexpr int32_t SAMPLE_RATE_BT_LC3_48K = 48000;     // Bluetooth LE Audio LC3 (48 кГц High-Quality BAP)
constexpr int32_t SAMPLE_RATE_BT_A2DP = 48000;        // Bluetooth Classic A2DP Music Rate

constexpr int32_t CHANNEL_COUNT_MONO = 1;
constexpr int32_t CHANNEL_COUNT_STEREO = 2;

// Кванты обработки по стандарту ITU-T G.114 (фреймирование 10 мс)
constexpr size_t BURST_10MS_16K = 160;              // 10 мс @ 16 кГц
constexpr size_t BURST_10MS_24K = 240;              // 10 мс @ 24 кГц
constexpr size_t BURST_10MS_32K = 320;              // 10 мс @ 32 кГц
constexpr size_t BURST_10MS_48K = 480;              // 10 мс @ 48 кГц
constexpr size_t BURST_40MS_16K = 640;              // 40 мс @ 16 кГц

constexpr size_t BYTES_PER_SAMPLE = sizeof(int16_t);

// Квант DSP воспроизведения: 10 мс (240 сэмплов)
constexpr size_t PLAYBACK_DSP_INPUT_CHUNK_FRAMES = BURST_10MS_24K;

// Емкость кольцевых буферов
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 4096; // ~256 мс @ 16 кГц (8 КиБ)
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 8192; // ~341 мс @ 24 кГц (16 КиБ)

// Аппаратный целевой буфер задержки (ITU-T G.114)
constexpr size_t PLAYBACK_TARGET_BUFFER_MS = 25;

// Множитель бёрстов AAudio для минимизации джиттера
constexpr size_t PLAYBACK_BURST_MIN_MULTIPLIER = 3;

// Таймаут синхронизации воркера при смене эпохи
constexpr size_t PLAYBACK_DSP_RESET_TIMEOUT_MS = 50;

// Ресемплинг и децимация (Zero-Allocation scratch space)
constexpr size_t RESAMPLE_SCRATCH_CAPACITY = 65536;      // 131 КиБ scratch space
constexpr size_t CAPTURE_DECIMATE_CAPACITY = 32768;      // 64 КиБ decimator scratch space

// Earcon генератор тактильного подтверждения
constexpr float EARCON_FREQ_HZ = 750.0f;
constexpr float EARCON_DURATION_MS = 15.0f;
constexpr size_t EARCON_INACTIVE_PHASE = std::numeric_limits<size_t>::max();

// FFT константы спектрального анализа
constexpr size_t FFT_SIZE = 256;
constexpr size_t FFT_HOP_SIZE = 128;
constexpr size_t SPECTRUM_BANDS = 5;

// Размер гистограммы ошибок AAudio (RFC 7004)
constexpr size_t ERROR_HISTOGRAM_BUCKETS = 16;

// Аппаратные особенности наушников CMF Buds 2 (чип Bestechnic BES2600)
constexpr uint32_t CMF_BUDS_2_ENC_LATENCY_MS = 35; // Алгоритмическая задержка Clear Voice ENC

// УСТРАНЕНИЕ ДЕФЕКТА 128: Физические задержки контроллеров и аудиосервера
constexpr float ESTIMATED_HAL_SPEAKER_LATENCY_MS = 12.0f;
constexpr float ESTIMATED_HAL_BT_SCO_LATENCY_MS = 45.0f;
constexpr float ESTIMATED_HAL_BT_BLE_LATENCY_MS = 25.0f;

// УСТРАНЕНИЕ ДЕФЕКТА 140: Константы отслеживания минимального фонового шума
constexpr float NOISE_FLOOR_MIN_RMS = 0.003f;
constexpr float NOISE_FLOOR_MAX_RMS = 0.080f;
constexpr float NOISE_FLOOR_DECAY_COEFF = 0.995f;
constexpr float NOISE_FLOOR_ATTACK_COEFF = 0.005f;

} // namespace client::audio