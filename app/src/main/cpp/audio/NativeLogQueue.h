// >>> FILE: app/src/main/cpp/audio/NativeLogQueue.h
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <ctime>

namespace client::logging {

struct NativeLogItem {
    int level{4};              // 2=V, 3=D, 4=I, 5=W, 6=E, 8=AUD
    char tag[32]{0};
    char message[256]{0};
    uint64_t timestampNs{0};
};

/**
 * Высокопроизводительная MPSC-очередь (Multi-Producer Single-Consumer)
 * фиксированной емкости на базе алгоритма последовательностей Дмитрия Вьюкова.
 *
 * Особенности:
 * - Zero-Allocation: память под все 1024 слота предвыделена статически.
 * - Без mutex и без выделения памяти в push()/pop().
 * - Операции используют sequence-поля ячеек для корректной публикации
 *   payload между несколькими producer-потоками и одним consumer-потоком.
 * - Это не является формально lock-free алгоритмом: producer может удерживать
 *   уже захваченную ячейку, пока другой producer/consumer ожидает ее публикации.
 * - Каждая Cell выровнена по 64 байтам, чтобы sequence разных ячеек не
 *   делили одну cache line из-за шага массива.
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

    bool push(int level, const char* tag, const char* msg) {
        Cell* cell = nullptr;
        uint64_t pos = enqueuePos_.load(std::memory_order_relaxed);

        for (;;) {
            cell = &buffer_[pos & (CAPACITY - 1)];
            uint64_t seq = cell->sequence.load(std::memory_order_acquire);
            intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos);

            if (diff == 0) {
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
            } else if (diff < 0) {
                // Очередь переполнена: слот занят и еще не прочитан consumer'ом.
                // Не продвигаем enqueuePos_ и не перезаписываем ячейку, сохраняя инвариант.
                droppedCount_.fetch_add(1, std::memory_order_relaxed);
                return false;
            } else {
                pos = enqueuePos_.load(std::memory_order_relaxed);
            }
        }

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

        // CLOCK_MONOTONIC_RAW is supported by Android bionic on the target
        // platform. Still, clock_gettime() can report failure; never consume
        // an uninitialized timespec in that case.
        timespec ts{};
        if (clock_gettime(CLOCK_BOOTTIME, &ts) == 0) {
            cell->item.timestampNs =
                static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL +
                static_cast<uint64_t>(ts.tv_nsec);
        } else {
            cell->item.timestampNs = 0;
        }

        cell->sequence.store(pos + 1, std::memory_order_release);
        return true;
    }

    bool pop(NativeLogItem& outItem) {
        Cell* cell = nullptr;
        uint64_t pos = dequeuePos_.load(std::memory_order_relaxed);

        for (;;) {
            cell = &buffer_[pos & (CAPACITY - 1)];
            uint64_t seq = cell->sequence.load(std::memory_order_acquire);
            intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(pos + 1);

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

    uint64_t getDroppedCount() const {
        return droppedCount_.load(std::memory_order_relaxed);
    }

private:
    struct alignas(64) Cell {
        std::atomic<uint64_t> sequence;
        NativeLogItem item;
    };

    NativeLogQueue() {
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