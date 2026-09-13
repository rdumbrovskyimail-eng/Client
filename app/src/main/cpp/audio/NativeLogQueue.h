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
 * Высокопроизводительная безблокировочная MPSC-очередь (Multi-Producer Single-Consumer)
 * фиксированной емкости на алгоритме Дмитрия Вьюкова.
 * 
 * Особенности:
 * - Zero-Allocation: память под 1024 слота предвыделена статически.
 * - Lock-Free: в методе push() нет мьютексов и системных вызовов ядра,
 *   что гарантирует 100% безопасность вызова из RT-колбэков AAudio (AAudioStream_dataCallback).
 * - Выравнивание по 64 байтам (alignas(64)) исключает деградацию кэш-линий CPU (False Sharing).
 */
class NativeLogQueue {
public:
    static constexpr size_t CAPACITY = 1024;
    static_assert((CAPACITY & (CAPACITY - 1)) == 0, "CAPACITY must be a power of two");

    static NativeLogQueue& getInstance() {
        static NativeLogQueue instance;
        return instance;
    }

    void push(int level, const char* tag, const char* msg) {
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
                // Буфер переполнен: перезаписываем слот, принудительно продвигая позицию
                if (enqueuePos_.compare_exchange_weak(pos, pos + 1, std::memory_order_relaxed)) {
                    break;
                }
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

        timespec ts;
        clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
        cell->item.timestampNs = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);

        cell->sequence.store(pos + 1, std::memory_order_release);
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

private:
    struct Cell {
        std::atomic<uint64_t> sequence;
        NativeLogItem item;
    };

    NativeLogQueue() {
        for (size_t i = 0; i < CAPACITY; ++i) {
            buffer_[i].sequence.store(i, std::memory_order_relaxed);
        }
        enqueuePos_.store(0, std::memory_order_relaxed);
        dequeuePos_.store(0, std::memory_order_relaxed);
    }

    ~NativeLogQueue() = default;
    NativeLogQueue(const NativeLogQueue&) = delete;
    NativeLogQueue& operator=(const NativeLogQueue&) = delete;

    alignas(64) Cell buffer_[CAPACITY];
    alignas(64) std::atomic<uint64_t> enqueuePos_{0};
    alignas(64) std::atomic<uint64_t> dequeuePos_{0};
};

} // namespace client::logging