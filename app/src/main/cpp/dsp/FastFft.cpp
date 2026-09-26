#include "FastFft.h"
#include <cmath>
#include <algorithm>
#include <atomic>

namespace client::dsp {

static constexpr float PI = 3.14159265358979323846f;
static constexpr size_t N = audio::FFT_SIZE;

FastFft::FastFft() {
    // 1. Предрасчет окна Ханна высокой точности
    for (size_t i = 0; i < N; ++i) {
        const double angle = 2.0 * static_cast<double>(PI) * static_cast<double>(i) / static_cast<double>(N - 1);
        hannWindow_[i] = static_cast<float>(0.5 * (1.0 - std::cos(angle)));
    }

    // 2. Предрасчет 8-битной перестановки бит-реверса (log2(256) = 8)
    for (size_t i = 0; i < N; ++i) {
        size_t rev = 0;
        size_t temp = i;
        for (size_t b = 0; b < 8; ++b) {
            rev = (rev << 1) | (temp & 1u);
            temp >>= 1;
        }
        bitRev_[i] = static_cast<uint16_t>(rev);
    }

    // 3. Предрасчет поворотных коэффициентов W_N^k = e^(-j*2*pi*k/N)
    for (size_t k = 0; k < N / 2; ++k) {
        const double angle = -2.0 * static_cast<double>(PI) * static_cast<double>(k) / static_cast<double>(N);
        twiddleR_[k] = static_cast<float>(std::cos(angle));
        twiddleI_[k] = static_cast<float>(std::sin(angle));
    }

    snapshotSeq_.store(0, std::memory_order_relaxed);
}

void FastFft::computeFft(float* real, float* imag) {
    // 1. Бит-реверсивная перестановка через таблицу
    for (size_t i = 0; i < N; ++i) {
        const size_t j = bitRev_[i];
        if (i < j) {
            std::swap(real[i], real[j]);
            std::swap(imag[i], imag[j]);
        }
    }

    // 2. Бабочки Кули-Тьюки Radix-2
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
        // 3-точечный анти-алиасинг фильтр [0.25, 0.5, 0.25]
        for (size_t i = 0; i < N; ++i) {
            const size_t idx = i * 2;
            const float prev = (idx > 0) ? pcmInput[idx - 1] : pcmInput[idx];
            const float curr = pcmInput[idx];
            const float next = (idx + 1 < count) ? pcmInput[idx + 1] : curr;
            const float filtered = 0.25f * prev + 0.5f * curr + 0.25f * next;
            real[i] = filtered * hannWindow_[i];
        }
    } else {
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

    // УСТРАНЕНИЕ ДЕФЕКТА 172: Атомарная публикация через Seqlock без мьютекса
    const uint32_t currentSeq = snapshotSeq_.load(std::memory_order_relaxed);
    snapshotSeq_.store(currentSeq + 1, std::memory_order_release); // Нечетное: идет запись

    for (size_t i = 0; i < audio::SPECTRUM_BANDS; ++i) {
        activeSnapshot_.bands[i] = smoothedBands_[i];
    }
    activeSnapshot_.micRms = micRms;
    activeSnapshot_.outRms = outRms;

    snapshotSeq_.store(currentSeq + 2, std::memory_order_release); // Четное: снимок стабилен
}

// УСТРАНЕНИЕ ДЕФЕКТА 172: 100% неблокирующее считывание снимка спектра UI без mutex
void FastFft::getLatestSnapshot(SpectrumSnapshot& out) const {
    for (int attempt = 0; attempt < 8; ++attempt) {
        const uint32_t seq1 = snapshotSeq_.load(std::memory_order_acquire);

        if ((seq1 & 1u) != 0u) {
            continue; // Писатель обновляет снимок, повторяем попытку
        }

        const SpectrumSnapshot candidate = activeSnapshot_;
        std::atomic_thread_fence(std::memory_order_acquire);

        const uint32_t seq2 = snapshotSeq_.load(std::memory_order_acquire);

        if (seq1 == seq2 && (seq2 & 1u) == 0u) {
            out = candidate;
            return;
        }
    }

    // При коллизии возвращаем текущее состояние без захвата блокировок
    out = activeSnapshot_;
}

} // namespace client::dsp