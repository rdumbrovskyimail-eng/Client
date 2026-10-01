#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <cstring>
#include <cmath>
#include <limits>
#include <memory>
#include <numeric>
#include <vector>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace client::audio {

/**
 * Базовый полиморфный интерфейс потокового ресемплера реального времени.
 */
class IStreamingResampler {
public:
    virtual ~IStreamingResampler() = default;
    virtual void reset() = 0;
    virtual size_t process(const int16_t* in, size_t inFrames, int16_t* out, size_t maxOutFrames) = 0;
    virtual uint64_t getTotalInSamples() const = 0;
    virtual uint64_t getTotalOutSamples() const = 0;
};

/**
 * Рациональный полифазный ресемплер L/M с прототипом «sinc × окно Кайзера».
 *
 * Заменяет прежние классы с «ручными» коэффициентами: измерения показали, что они не реализуют
 * заявленные фильтры (24→16 и 44.1→16 без антиалиасинга, 24→32 с искажениями −19 дБ уже на 1 кГц,
 * 48→16 с провалом −52 дБ на 5 кГц, 44.1→16 со щелчком на каждой границе блока, 32→16 с потерей
 * отсчётов при нечётных блоках).
 *
 * - Коэффициенты рассчитываются один раз в конструкторе; process()/reset() не аллоцируют память.
 * - Результат не зависит от размера входных блоков (проверено: блоки 1/97/480 дают идентичный выход).
 * - Вход всегда потребляется целиком: при нехватке maxOutFrames лишние выходные отсчёты
 *   отбрасываются, но состояние фильтра не рвётся.
 */
class RationalPolyphaseResampler : public IStreamingResampler {
public:
    static constexpr size_t CHUNK_SIZE = 1024;

    RationalPolyphaseResampler(int32_t inRate, int32_t outRate,
                               double passHz, double stopHz, double attenuationDb) {
        design(inRate, outRate, passHz, stopHz, attenuationDb);
        reset();
    }

    void reset() override {
        std::fill(work_.begin(), work_.end(), 0.0f);
        primed_ = false;
        nextIndex_ = 0;
        nextPhase_ = 0;
        consumed_ = 0;
        totalInSamples_ = 0;
        totalOutSamples_ = 0;
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out,
                   size_t maxOutFrames = std::numeric_limits<size_t>::max()) override {
        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames == 0) return 0;

        const size_t hist = taps_ - 1;
        if (!primed_) {
            // История = первый отсчёт: без щелчка на старте потока
            std::fill(work_.begin(), work_.begin() + static_cast<std::ptrdiff_t>(hist),
                      static_cast<float>(in[0]));
            primed_ = true;
        }

        size_t totalOut = 0;
        size_t processed = 0;
        while (processed < inFrames) {
            const size_t chunk = std::min(inFrames - processed, CHUNK_SIZE);
            for (size_t i = 0; i < chunk; ++i) {
                work_[hist + i] = static_cast<float>(in[processed + i]);
            }

            const uint64_t chunkEnd = consumed_ + chunk;
            while (nextIndex_ < chunkEnd) {
                // newest — позиция самого свежего входного отсчёта для текущего выхода
                const size_t newest = static_cast<size_t>(nextIndex_ - consumed_) + hist;
                const float* c = coeffs_.data() + static_cast<size_t>(nextPhase_) * taps_;
                const float* x = work_.data() + newest;
                float acc = 0.0f;
                for (size_t k = 0; k < taps_; ++k) {
                    acc += c[k] * *(x - k);
                }
                if (totalOut < maxOutFrames) {
                    const long r = std::lrintf(acc);
                    out[totalOut++] = static_cast<int16_t>(std::clamp<long>(r, -32768L, 32767L));
                }
                nextPhase_ += decim_;
                nextIndex_ += nextPhase_ / interp_;
                nextPhase_ %= interp_;
            }

            // История = последние (taps − 1) отсчётов окна
            std::memmove(work_.data(), work_.data() + chunk, hist * sizeof(float));
            consumed_ = chunkEnd;
            processed += chunk;
        }

        totalInSamples_ += inFrames;
        totalOutSamples_ += totalOut;
        return totalOut;
    }

    uint64_t getTotalInSamples() const override { return totalInSamples_; }
    uint64_t getTotalOutSamples() const override { return totalOutSamples_; }
    size_t tapsPerPhase() const { return taps_; }

private:
    static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        for (int k = 1; k < 64; ++k) {
            const double t = x / (2.0 * static_cast<double>(k));
            term *= t * t;
            sum += term;
            if (term < 1e-14 * sum) break;
        }
        return sum;
    }

    void design(int32_t inRate, int32_t outRate, double passHz, double stopHz, double attenuationDb) {
        constexpr double PI = 3.14159265358979323846;
        const int32_t g = std::gcd(inRate, outRate);
        interp_ = static_cast<uint32_t>(outRate / g);
        decim_ = static_cast<uint32_t>(inRate / g);

        // Длина фильтра по формуле Кайзера (на одну фазу, в отсчётах входной частоты)
        const double transition = std::max(1.0, stopHz - passHz) / static_cast<double>(inRate);
        const size_t taps = static_cast<size_t>(
            std::ceil((attenuationDb - 8.0) / (2.285 * 2.0 * PI * transition))) + 1;
        taps_ = std::clamp<size_t>(taps, 8, 256);

        const double beta = (attenuationDb > 50.0)
            ? 0.1102 * (attenuationDb - 8.7)
            : (attenuationDb >= 21.0
                ? 0.5842 * std::pow(attenuationDb - 21.0, 0.4) + 0.07886 * (attenuationDb - 21.0)
                : 0.0);

        const size_t n = taps_ * interp_;
        const double fsUp = static_cast<double>(inRate) * static_cast<double>(interp_);
        const double fc = 0.5 * (passHz + stopHz) / fsUp; // срез, циклов на отсчёт fsUp
        const double center = 0.5 * static_cast<double>(n - 1);
        const double i0b = besselI0(beta);

        std::vector<double> h(n);
        double sum = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double t = static_cast<double>(i) - center;
            const double r = t / center;
            const double w = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
            const double s = (std::fabs(t) < 1e-12)
                ? 2.0 * fc
                : std::sin(2.0 * PI * fc * t) / (PI * t);
            h[i] = s * w;
            sum += h[i];
        }

        // Усиление на DC = 1 для каждой фазы (сумма прототипа = L)
        const double scale = static_cast<double>(interp_) / sum;
        coeffs_.assign(n, 0.0f);
        for (size_t p = 0; p < interp_; ++p) {
            for (size_t k = 0; k < taps_; ++k) {
                coeffs_[p * taps_ + k] = static_cast<float>(h[k * interp_ + p] * scale);
            }
        }
        work_.assign(taps_ - 1 + CHUNK_SIZE, 0.0f);
    }

    std::vector<float> coeffs_;
    std::vector<float> work_;
    size_t taps_{8};
    uint32_t interp_{1};
    uint32_t decim_{1};
    uint32_t nextPhase_{0};
    uint64_t nextIndex_{0};
    uint64_t consumed_{0};
    bool primed_{false};
    uint64_t totalInSamples_{0};
    uint64_t totalOutSamples_{0};
};

/** 24 кГц → 16 кГц (вывод на Bluetooth HFP, захват 24 кГц). Полоса 0–7 кГц, подавление ≥ 70 дБ от 9 кГц. */
class PolyphaseResampler24To16 final : public RationalPolyphaseResampler {
public:
    PolyphaseResampler24To16() : RationalPolyphaseResampler(24000, 16000, 7000.0, 9000.0, 70.0) {}
};

/** 24 кГц → 32 кГц (вывод на LE Audio 32 кГц). Полоса 0–10.5 кГц, зеркала ≥ 70 дБ ниже от 13.5 кГц. */
class PolyphaseResampler24To32 final : public RationalPolyphaseResampler {
public:
    PolyphaseResampler24To32() : RationalPolyphaseResampler(24000, 32000, 10500.0, 13500.0, 70.0) {}
};

/** 48 кГц → 16 кГц (захват). Полоса 0–7 кГц, подавление ≥ 70 дБ от 9 кГц. */
class Decimator48To16 final : public RationalPolyphaseResampler {
public:
    Decimator48To16() : RationalPolyphaseResampler(48000, 16000, 7000.0, 9000.0, 70.0) {}
};

/** 32 кГц → 16 кГц (захват, в т.ч. LE Audio). Полоса 0–7 кГц, подавление ≥ 70 дБ от 9 кГц. */
class Decimator32To16 final : public RationalPolyphaseResampler {
public:
    Decimator32To16() : RationalPolyphaseResampler(32000, 16000, 7000.0, 9000.0, 70.0) {}
};

/** 44.1 кГц → 16 кГц (захват). L/M = 160/441, полоса 0–7 кГц, подавление ≥ 70 дБ от 9 кГц. */
class Resampler44100To16000 final : public RationalPolyphaseResampler {
public:
    Resampler44100To16000() : RationalPolyphaseResampler(44100, 16000, 7000.0, 9000.0, 70.0) {}
};

/** 8 кГц → 16 кГц (захват CVSD). Полоса 0–3.4 кГц, зеркала ≥ 60 дБ ниже от 4.6 кГц. */
class Upsampler8000To16000 final : public RationalPolyphaseResampler {
public:
    Upsampler8000To16000() : RationalPolyphaseResampler(8000, 16000, 3400.0, 4600.0, 60.0) {}
};

/**
 * Полуполосный КИХ-интерполятор 24 кГц -> 48 кГц: 95 отводов, окно Кайзера (β = 8.6).
 * Неравномерность полосы 0–10.5 кГц < 0.001 дБ, подавление зеркальных частот ≥ 87 дБ от 13.5 кГц.
 * (Прежний фильтр давал всего −16 дБ на 14 кГц: «металлический» призвук сибилянтов.)
 * Задержка: 24 входных отсчёта (1 мс). Коэффициенты рассчитываются один раз в конструкторе.
 */
class HalfbandResampler24To48 : public IStreamingResampler {
public:
    static constexpr size_t PAIRS = 24;
    static constexpr size_t HISTORY = PAIRS * 2 - 1; // 47 предыдущих входных отсчётов
    static constexpr size_t CHUNK_SIZE = 1024;

    HalfbandResampler24To48() {
        designCoefficients();
        reset();
    }

    void reset() override {
        std::memset(history_, 0, sizeof(history_));
        primed_ = false;
        totalInSamples_ = 0;
        totalOutSamples_ = 0;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames = std::numeric_limits<size_t>::max()) override {

        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames < 2) return 0;

        if (!primed_) {
            // Инициализация истории первым отсчётом: без щелчка на старте фразы
            std::fill(history_, history_ + HISTORY, static_cast<float>(in[0]));
            primed_ = true;
        }

        size_t processed = 0;
        size_t totalOut = 0;

        while (processed < inFrames && totalOut + 2 <= maxOutFrames) {
            const size_t chunk = std::min(inFrames - processed, CHUNK_SIZE);
            const int16_t* chunkIn = in + processed;

            std::memcpy(work_, history_, HISTORY * sizeof(float));
            for (size_t i = 0; i < chunk; ++i) {
                work_[HISTORY + i] = static_cast<float>(chunkIn[i]);
            }

            size_t consumed = 0;
            for (size_t i = 0; i < chunk; ++i) {
                if (totalOut + 2 > maxOutFrames) break;
                const size_t idx = HISTORY + i;

                const float direct = work_[idx - PAIRS];
                float odd = 0.0f;
                for (size_t k = 0; k < PAIRS; ++k) {
                    odd += coeffs_[k] * (work_[idx - PAIRS - k] + work_[idx - PAIRS + 1 + k]);
                }

                out[totalOut++] = toPcm16(direct);
                out[totalOut++] = toPcm16(odd);
                ++consumed;
            }

            // История = последние HISTORY отсчётов из обработанной части окна
            std::memcpy(history_, work_ + consumed, HISTORY * sizeof(float));
            processed += consumed;
            if (consumed < chunk) break;
        }

        totalInSamples_ += processed;
        totalOutSamples_ += totalOut;
        return totalOut;
    }

    uint64_t getTotalInSamples() const override { return totalInSamples_; }
    uint64_t getTotalOutSamples() const override { return totalOutSamples_; }

private:
    static inline int16_t toPcm16(float v) {
        const long r = std::lrintf(v);
        return static_cast<int16_t>(std::clamp<long>(r, -32768L, 32767L));
    }

    static double besselI0(double x) {
        double sum = 1.0;
        double term = 1.0;
        for (int k = 1; k < 64; ++k) {
            const double t = x / (2.0 * static_cast<double>(k));
            term *= t * t;
            sum += term;
            if (term < 1e-14 * sum) break;
        }
        return sum;
    }

    void designCoefficients() {
        constexpr double BETA = 8.6;
        constexpr double PI = 3.14159265358979323846;
        const double m1 = static_cast<double>(2 * PAIRS); // M + 1, где M = 2·PAIRS − 1
        const double i0Beta = besselI0(BETA);
        double sum = 0.0;
        double raw[PAIRS];
        for (size_t k = 0; k < PAIRS; ++k) {
            const double j = static_cast<double>(2 * k + 1);
            const double r = j / m1;
            const double window = besselI0(BETA * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0Beta;
            const double x = j / 2.0;
            const double sinc = std::sin(PI * x) / (PI * x);
            raw[k] = 2.0 * 0.5 * sinc * window;
            sum += 2.0 * raw[k];
        }
        // Коэффициент передачи нечётной ветви на DC = 1 (иначе появится тон 24 кГц)
        for (size_t k = 0; k < PAIRS; ++k) {
            coeffs_[k] = static_cast<float>(raw[k] / sum);
        }
    }

    float coeffs_[PAIRS]{};
    float history_[HISTORY]{};
    float work_[HISTORY + CHUNK_SIZE]{};
    bool primed_{false};
    uint64_t totalInSamples_{0};
    uint64_t totalOutSamples_{0};
};

/**
 * Потоковый ресемплер для динамического управления скоростью воспроизведения (TSM).
 */
class StreamingLinearResampler : public IStreamingResampler {
public:
    StreamingLinearResampler() = default;

    void configure(int32_t inputRate, int32_t outputRate) {
        if (inputRate <= 0 || outputRate <= 0) {
            reset();
            inputRate_ = 0;
            outputRate_ = 0;
            return;
        }

        if (inputRate_ != inputRate || outputRate_ != outputRate) {
            inputRate_ = inputRate;
            outputRate_ = outputRate;
            // ИСКЛЮЧЕНИЕ ДЕСТРУКТИВНОГО СБРОСА: не вызываем reset(), чтобы сохранить непрерывность
            // фазового аккумулятора (phase_), отсчетов firHistory_ и previousSample_
            // при плавной подстройке джиттер-буфера. Это полностью исключает фазовые щелчки.
        }
    }

    void reset() override {
        sourceIndex_ = 0;
        phase_ = 0.0;
        previousSample_ = 0;
        hasPreviousSample_ = false;
        firHistory_[0] = 0;
        firHistory_[1] = 0;
        hasFirHistory_ = false;
        totalInSamples_ = 0;
        totalOutSamples_ = 0;
    }

    size_t process(
        const int16_t* in,
        size_t inFrames,
        int16_t* out,
        size_t maxOutFrames) override {

        if (in == nullptr || out == nullptr ||
            inFrames == 0 || maxOutFrames == 0 ||
            inputRate_ <= 0 || outputRate_ <= 0) {
            return 0;
        }

        const double step = static_cast<double>(inputRate_) / static_cast<double>(outputRate_);
        const bool hadPrev = hasPreviousSample_;
        const size_t logicalSize = inFrames + (hadPrev ? 1u : 0u);
        size_t outCount = 0;

        int32_t h0 = hasFirHistory_ ? firHistory_[0] : 0;
        int32_t h1 = hasFirHistory_ ? firHistory_[1] : 0;

        while (outCount < maxOutFrames) {
            if (sourceIndex_ + 1u >= logicalSize) break;

            const int32_t s0 = getSample(in, inFrames, sourceIndex_, hadPrev);
            const int32_t s1 = getSample(in, inFrames, sourceIndex_ + 1u, hadPrev);

            const double interpolated =
                static_cast<double>(s0) + (static_cast<double>(s1) - static_cast<double>(s0)) * phase_;

            const int32_t rawSample = static_cast<int32_t>(std::lround(interpolated));

            int32_t smoothed = rawSample;
            if (hasFirHistory_) {
                smoothed = (h0 + (h1 << 1) + rawSample + 2) >> 2;
            } else {
                h0 = rawSample;
                h1 = rawSample;
                hasFirHistory_ = true;
            }

            h0 = h1;
            h1 = rawSample;

            out[outCount++] = static_cast<int16_t>(std::clamp<int32_t>(smoothed, -32768, 32767));

            const double advancedPhase = phase_ + step;
            const double wholePart = std::floor(advancedPhase);
            const size_t wholeFrames = static_cast<size_t>(wholePart);

            phase_ = advancedPhase - wholePart;
            sourceIndex_ += wholeFrames;
        }

        firHistory_[0] = static_cast<int16_t>(h0);
        firHistory_[1] = static_cast<int16_t>(h1);

        previousSample_ = in[inFrames - 1u];
        hasPreviousSample_ = true;

        const size_t historyShift = hadPrev ? inFrames : (inFrames - 1u);
        if (sourceIndex_ >= historyShift) {
            sourceIndex_ -= historyShift;
        } else {
            sourceIndex_ = 0;
        }

        totalInSamples_ += inFrames;
        totalOutSamples_ += outCount;
        return outCount;
    }

    uint64_t getTotalInSamples() const override { return totalInSamples_; }
    uint64_t getTotalOutSamples() const override { return totalOutSamples_; }

private:
    inline int32_t getSample(const int16_t* in, size_t inFrames, size_t index, bool hadPrev) const {
        if (hadPrev && index == 0u) return static_cast<int32_t>(previousSample_);
        const size_t localIdx = hadPrev ? (index - 1u) : index;
        if (localIdx >= inFrames) return static_cast<int32_t>(in[inFrames - 1u]);
        return static_cast<int32_t>(in[localIdx]);
    }

    int32_t inputRate_{0};
    int32_t outputRate_{0};
    size_t sourceIndex_{0};
    double phase_{0.0};
    int16_t previousSample_{0};
    bool hasPreviousSample_{false};
    int16_t firHistory_[2]{0, 0};
    bool hasFirHistory_{false};
    uint64_t totalInSamples_{0};
    uint64_t totalOutSamples_{0};
};

/**
 * Унифицированный менеджер ресемплинга тракта захвата микрофона.
 */
class UnifiedCaptureResampler {
public:
    UnifiedCaptureResampler() = default;

    void configure(int32_t captureRate, int32_t targetRate = 16000) {
        captureRate_ = captureRate;
        targetRate_ = targetRate;
        reset();
    }

    void reset() {
        decimator48To16_.reset();
        decimator32To16_.reset();
        resampler44100To16000_.reset();
        resampler24To16_.reset();
        upsampler8To16_.reset();
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out, size_t maxOutFrames) {
        if (in == nullptr || out == nullptr || inFrames == 0 || maxOutFrames == 0) return 0;

        if (captureRate_ == 48000) {
            return decimator48To16_.process(in, inFrames, out, maxOutFrames);
        } else if (captureRate_ == 44100) {
            return resampler44100To16000_.process(in, inFrames, out, maxOutFrames);
        } else if (captureRate_ == 32000) {
            return decimator32To16_.process(in, inFrames, out, maxOutFrames);
        } else if (captureRate_ == 24000) {
            return resampler24To16_.process(in, inFrames, out, maxOutFrames);
        } else if (captureRate_ == 8000) {
            return upsampler8To16_.process(in, inFrames, out, maxOutFrames);
        } else if (captureRate_ == 16000) {
            const size_t toCopy = std::min(inFrames, maxOutFrames);
            std::memcpy(out, in, toCopy * sizeof(int16_t));
            return toCopy;
        }
        return 0;
    }

private:
    int32_t captureRate_{48000};
    int32_t targetRate_{16000};

    Decimator48To16 decimator48To16_;
    Decimator32To16 decimator32To16_;
    Resampler44100To16000 resampler44100To16000_;
    PolyphaseResampler24To16 resampler24To16_;
    Upsampler8000To16000 upsampler8To16_;
};

} // namespace client::audio