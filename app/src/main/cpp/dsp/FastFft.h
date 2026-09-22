#pragma once

#include <cstddef>
#include <atomic>
#include <cstdint>
#include <array>
#include <mutex>

#include "audio/AudioConstants.h"

namespace client::dsp {

// E-03: Согласованный снимок полос БПФ и RMS с выравниванием по границе 16 байт
struct alignas(16) SpectrumSnapshot {
    float bands[audio::SPECTRUM_BANDS]{0.0f};
    float micRms{0.0f};
    float outRms{0.0f};
};

class FastFft {
public:
    FastFft();
    ~FastFft() = default;

    // ERR-09: Расчет спектра с учетом динамической частоты дискретизации sampleRate
    void process(
        const float* pcmInput,
        size_t count,
        float micRms,
        float outRms,
        int32_t sampleRate =
            audio::SAMPLE_RATE_GEMINI_OUT
    );

    /*
     * Возвращает когерентный snapshot.
     *
     * Основной путь чтения использует атомарный payload + seqlock.
     * При редкой ситуации, когда snapshot не удалось получить за
     * ограниченное число попыток, fallback читается под отдельным
     * mutex, защищающим lastStableSnapshot_ от concurrent access.
     */
    void getLatestSnapshot(
        SpectrumSnapshot& out
    ) const;

private:
    void computeFft(
        float* real,
        float* imag,
        size_t n
    );

    /*
     * Рабочее состояние FFT изменяется только потоком,
     * выполняющим process().
     */
    float smoothedBands_[
        audio::SPECTRUM_BANDS
    ]{0.0f};

    /*
     * Публикуемый payload snapshot.
     *
     * Каждый scalar является atomic, поэтому конкурентное чтение
     * этих значений не создаёт data race.
     */
    alignas(64)
    std::array<
        std::atomic<float>,
        audio::SPECTRUM_BANDS
    > snapshotBands_{};

    std::atomic<float> snapshotMicRms_{0.0f};

    std::atomic<float> snapshotOutRms_{0.0f};

    /*
     * Even = stable snapshot.
     * Odd  = writer находится в процессе публикации.
     */
    mutable std::atomic<uint32_t> snapshotSeq_{0};

    /*
     * Последний snapshot, успешно прошедший seqlock-проверку.
     *
     * Нужен только как bounded fallback.
     */
    mutable SpectrumSnapshot lastStableSnapshot_{};

    /*
     * Защищает lastStableSnapshot_ от ситуации, когда несколько
     * вызывающих потоков одновременно используют fallback либо
     * один поток обновляет fallback, пока другой его читает.
     *
     * Основной успешный seqlock-path этот mutex не использует.
     */
    mutable std::mutex lastStableSnapshotMutex_;
};

} // namespace client::dsp