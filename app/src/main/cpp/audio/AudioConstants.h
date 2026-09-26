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

// Квант DSP воспроизведения: 10 мс (240 сэмплов @ 24 кГц)
constexpr size_t PLAYBACK_DSP_INPUT_CHUNK_FRAMES = BURST_10MS_24K;

// Емкость кольцевых буферов
constexpr size_t RING_BUFFER_CAPACITY_CAPTURE = 4096; // ~256 мс @ 16 кГц (8 КиБ)
constexpr size_t RING_BUFFER_CAPACITY_PLAYBACK = 8192; // ~341 мс @ 24 кГц (16 КиБ)

// УСТРАНЕНИЕ ДЕФЕКТОВ 216 и 217: Адаптивный сетевой горизонт воспроизведения (RFC 3550 / NetEQ)
constexpr size_t PLAYBACK_TARGET_BUFFER_MS = 25;
constexpr size_t PLAYBACK_TARGET_BUFFER_BT_MIN_MS = 30;     // 30 мс адаптивный минимум для CMF Buds 2
constexpr size_t PLAYBACK_TARGET_BUFFER_BT_MAX_MS = 60;     // 60 мс адаптивный максимум для CMF Buds 2
constexpr size_t PLAYBACK_TARGET_BUFFER_SPEAKER_MIN_MS = 20; // 20 мс для встроенного динамика S23 Ultra
constexpr size_t PLAYBACK_TARGET_BUFFER_SPEAKER_MAX_MS = 40; // 40 мс для встроенного динамика S23 Ultra
constexpr size_t PLAYBACK_MAX_QUEUE_HORIZON_MS = 150;       // Защитный предел против bufferbloat

// Множитель бёрстов AAudio для минимизации джиттера
constexpr size_t PLAYBACK_BURST_MIN_MULTIPLIER = 2;

// УСТРАНЕНИЕ ДЕФЕКТА 181: Мгновенный Soft-Flush без 600-мс блокирующего ожидания
constexpr size_t PLAYBACK_DSP_RESET_TIMEOUT_MS = 15;

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

// Физические задержки контроллеров и аудиосервера
constexpr float ESTIMATED_HAL_SPEAKER_LATENCY_MS = 12.0f;
constexpr float ESTIMATED_HAL_BT_SCO_LATENCY_MS = 45.0f;
constexpr float ESTIMATED_HAL_BT_BLE_LATENCY_MS = 25.0f;

// Константы отслеживания минимального фонового шума (ITU-T G.160)
constexpr float NOISE_FLOOR_MIN_RMS = 0.003f;
constexpr float NOISE_FLOOR_MAX_RMS = 0.080f;
constexpr float NOISE_FLOOR_DECAY_COEFF = 0.995f;
constexpr float NOISE_FLOOR_ATTACK_COEFF = 0.005f;

/**
 * УСТРАНЕНИЕ ДЕФЕКТА 225:
 * Формальные измеримые приёмочные критерии качества (Engineering KPIs по IEEE 29119).
 */
constexpr uint32_t KPI_MAX_CALLBACK_LATENCY_US = 800; // < 0.8 мс при кванте 2.0 мс на S23 Ultra
constexpr uint32_t KPI_MAX_BARGE_IN_REACTION_MS = 45; // < 45 мс от детекции до тишины динамика
constexpr uint32_t KPI_MAX_ROUTE_SWITCH_MS = 80;      // < 80 мс переключение маршрута на CMF Buds 2
constexpr float KPI_MAX_BATTERY_DRAIN_PER_HOUR = 7.0f; // < 7.0% аккумулятора в час на Snapdragon 8 Gen 2

} // namespace client::audio