// >>> FILE: app/src/main/cpp/audio/PolyphaseResampler.h
#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>
#include <vector>

namespace client::audio {

/**
 * ERR-019: Потоковый 12-точечный полифазный FIR-ресемплер 24 кГц -> 16 кГц (L=2, M=3).
 * Работает на базе канонического фазового автомата с сохранением предыстории (FILTER_ORDER = 5) 
 * и точного временного смещения (offset_).
 * Полностью исключает выход за границы памяти (OOB), дрейф фазы и накопление паразитных остатков.
 */
class PolyphaseResampler24To16 {
public:
    static constexpr size_t FILTER_ORDER = 5; // 5 сэмплов предыстории для 6-tap ветвей

    PolyphaseResampler24To16() {
        reset();
    }

    void reset() {
        inBuf_.assign(FILTER_ORDER, 0);
        phase_ = 0;
        offset_ = 0;
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out) {
        if (inFrames == 0) return 0;

        // Коэффициенты полифазных ветвей (Q15, нормализованы к 32768, DC Gain = 0 dB)
        static const int32_t H0[6] = { -151, -1039, 11689, 20454,  2522, -707 };
        static const int32_t H1[6] = { -707,  2522, 20454, 11689, -1039, -151 };

        inBuf_.insert(inBuf_.end(), in, in + inFrames);

        size_t outFrames = 0;
        size_t inputIndex = FILTER_ORDER + offset_;

        while (inputIndex < inBuf_.size()) {
            const int32_t* H = (phase_ == 0) ? H0 : H1;

            int64_t acc = H[0] * inBuf_[inputIndex]     +
                          H[1] * inBuf_[inputIndex - 1] +
                          H[2] * inBuf_[inputIndex - 2] +
                          H[3] * inBuf_[inputIndex - 3] +
                          H[4] * inBuf_[inputIndex - 4] +
                          H[5] * inBuf_[inputIndex - 5];

            out[outFrames++] = static_cast<int16_t>(std::clamp<int32_t>(acc >> 15, -32768, 32767));

            size_t step = phase_ + 3;
            inputIndex += step / 2; // +1 при phase 0, +2 при phase 1
            phase_ = step % 2;      // 0 -> 1 -> 0 -> 1
        }

        // Фиксируем смещение по времени на следующий чанк (всегда 0 или 1)
        offset_ = inputIndex - inBuf_.size();

        // Инвариант: сохраняем ровно последние 5 сэмплов буфера как предысторию
        std::vector<int16_t> nextBuf(inBuf_.end() - FILTER_ORDER, inBuf_.end());
        inBuf_ = std::move(nextBuf);

        return outFrames;
    }

private:
    std::vector<int16_t> inBuf_;
    size_t phase_{0};
    size_t offset_{0};
};

/**
 * ERR-020: Каузальный линейный интерполятор 24 кГц -> 48 кГц (L=2, M=1).
 * Работает с нулевым начальным условием (ZIC), устраняя дублирование отсчётов на границах сетевых чанков.
 */
class LinearResampler24To48 {
public:
    void reset() {
        lastSample_ = 0; // Нулевое начальное условие (ZIC)
    }

    size_t process(const int16_t* in, size_t inFrames, int16_t* out) {
        if (inFrames == 0) return 0;
        size_t outFrames = 0;

        int32_t prev = lastSample_;
        for (size_t i = 0; i < inFrames; ++i) {
            int32_t cur = in[i];

            // 1. Промежуточная точка между прошлым и текущим отсчётом
            out[outFrames++] = static_cast<int16_t>((prev + cur) >> 1);
            // 2. Сам текущий отсчёт
            out[outFrames++] = static_cast<int16_t>(cur);

            prev = cur;
        }
        lastSample_ = static_cast<int16_t>(prev);
        return outFrames;
    }

private:
    int16_t lastSample_{0};
};

} // namespace client::audio