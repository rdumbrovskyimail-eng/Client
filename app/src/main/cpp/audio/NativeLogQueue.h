#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <algorithm>

namespace client::logging {

struct NativeLogItem {
    int level{4};              // 2=V, 3=D, 4=I, 5=W, 6=E, 8=AUD, 9=VAD
    char tag[32]{0};
    char message[256]{0};
    uint64_t timestampNs{0};
};

/**
 * УСТРАНЕНИЕ ДЕФЕКТОВ 173, 174, 175:
 * Высокопроизводительная очередь MPSC (Multi-Producer Single-Consumer) фиксированной емкости
 * с гарантией Wait-Free поведения для критических потоков обработки звука (RT Audio Threads).
 *
 * Особенности:
 * 1. Zero-Allocation: память под 1024 слота предвыделена статически.
 * 2. Метод pushRt(): ограниченное число CAS-попыток (максимум 4). При переполнении или
 *    состоянии гонки слот немедленно сбрасывается с инкрементом droppedCount_,
 *    полностью исключая зависание потоков ЦОС в бесконечных spin-loop циклах (Дефект 175).
 * 3. На критическом пути аудио вызовы системного сокета logd (__android_log_print)
 *    и форматирование snprintf исключены (Дефекты 173 и 174).
 * 4. Каждая ячейка выровнена по 64 байтам (alignas(64)) для полного исключения False Sharing.
 */
class NativeLogQueue {
public:
    static constexpr size_t CAPACITY = 1024;
    static_assert(
        CAPACITY >= 2 && (CAPACITY & (CAPACITY - 1)) == 0,
        "CAPACITY must be a power of two and at least 2"
    );

    static NativeLogQueue& getInstance() {
        static NativeLogQueue instance;
        return instance;
    }

    /**
     * Wait-Free публикация для потоков реального времени (RT Audio Threads).
     * Не выполняет блокирующих системных вызовов и форматирования строк на стеке.
     */
    bool pushRt(int level, const char* tag, const char* msg) noexcept {
        uint64_t pos = enqueuePos_.load(std::memory_order_relaxed);

        // Ограниченный перебор для гарантии фиксированного времени отклика (O(1))
        for (int attempt = 0; attempt < 4; ++attempt) {
            Cell* cell = &buffer_[pos & (CAPACITY - 1)];
            const uint64_t seq = cell->sequence.load(std::memory_order_acquire);
            const intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos);

            if (diff == 0) {
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    copyPayload(cell, level, tag, msg, pos);
                    return true;
                }
            } else if (diff < 0) {
                // Очередь заполнена: мгновенный возврат без блокировки потока звука
                droppedCount_.fetch_add(1, std::memory_order_relaxed);
                return false;
            } else {
                pos = enqueuePos_.load(std::memory_order_relaxed);
            }
        }

        droppedCount_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    /**
     * Стандартная публикация для фоновых потоков управления.
     */
    bool push(int level, const char* tag, const char* msg) noexcept {
        Cell* cell = nullptr;
        uint64_t pos = enqueuePos_.load(std::memory_order_relaxed);

        for (;;) {
            cell = &buffer_[pos & (CAPACITY - 1)];
            const uint64_t seq = cell->sequence.load(std::memory_order_acquire);
            const intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos);

            if (diff == 0) {
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                droppedCount_.fetch_add(1, std::memory_order_relaxed);
                return false;
            } else {
                pos = enqueuePos_.load(std::memory_order_relaxed);
            }
        }

        copyPayload(cell, level, tag, msg, pos);
        return true;
    }

    /**
     * Неблокирующее извлечение элемента фоновым потоком JVM-логирования.
     */
    bool pop(NativeLogItem& outItem) noexcept {
        uint64_t pos = dequeuePos_.load(std::memory_order_relaxed);
        Cell* cell = nullptr;

        for (;;) {
            cell = &buffer_[pos & (CAPACITY - 1)];
            const uint64_t seq = cell->sequence.load(std::memory_order_acquire);
            const intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos + 1);

            if (diff == 0) {
                if (dequeuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                return false; // Очередь пуста
            } else {
                pos = dequeuePos_.load(std::memory_order_relaxed);
            }
        }

        outItem = cell->item;
        cell->sequence.store(pos + CAPACITY, std::memory_order_release);
        return true;
    }

    uint64_t getDroppedCount() const noexcept {
        return droppedCount_.load(std::memory_order_relaxed);
    }

private:
    struct alignas(64) Cell {
        std::atomic<uint64_t> sequence{0};
        NativeLogItem item{};
    };

    inline void copyPayload(Cell* cell, int level, const char* tag, const char* msg, uint64_t pos) noexcept {
        cell->item.level = level;

        if (tag != nullptr) {
            std::strncpy(cell->item.tag, tag, sizeof(cell->item.tag) - 1);
            cell->item.tag[sizeof(cell->item.tag) - 1] = '\0';
        } else {
            cell->item.tag[0] = '\0';
        }

        if (msg != nullptr) {
            std::strncpy(cell->item.message, msg, sizeof(cell->item.message) - 1);
            cell->item.message[sizeof(cell->item.message) - 1] = '\0';
        } else {
            cell->item.message[0] = '\0';
        }

        timespec ts{};
        if (clock_gettime(CLOCK_BOOTTIME, &ts) == 0) {
            cell->item.timestampNs =
                static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL +
                static_cast<uint64_t>(ts.tv_nsec);
        } else {
            cell->item.timestampNs = 0;
        }

        cell->sequence.store(pos + 1, std::memory_order_release);
    }

    NativeLogQueue() noexcept {
        for (size_t i = 0; i < CAPACITY; ++i) {
            buffer_[i].sequence.store(i, std::memory_order_relaxed);
        }
        enqueuePos_.store(0, std::memory_order_relaxed);
        dequeuePos_.store(0, std::memory_order_relaxed);
        droppedCount_.store(0, std::memory_order_relaxed);
    }

    ~NativeLogQueue() = default;
    NativeLogQueue(const NativeLogQueue&) = delete;
    NativeLogQueue& operator=(const NativeLogQueue&) = delete;

    alignas(64) Cell buffer_[CAPACITY];
    alignas(64) std::atomic<uint64_t> enqueuePos_{0};
    alignas(64) std::atomic<uint64_t> dequeuePos_{0};
    std::atomic<uint64_t> droppedCount_{0};
};

} // namespace client::logging