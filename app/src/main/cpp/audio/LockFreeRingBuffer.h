#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <algorithm>
#include <vector>
#include <type_traits>
#include <cstdint>
#include <limits>

namespace client::audio {

template <typename T, size_t Capacity>
class LockFreeRingBuffer {
    static_assert(
        std::is_trivially_copyable_v<T>,
        "LockFreeRingBuffer requires trivially copyable elements"
    );
    static_assert(
        Capacity > 0,
        "Capacity must be greater than zero"
    );
    static_assert(
        (Capacity & (Capacity - 1)) == 0,
        "Capacity must be a power of two"
    );
    static_assert(
        Capacity < (std::numeric_limits<size_t>::max() / 2),
        "Capacity must be small enough for unambiguous monotonic index arithmetic"
    );

public:
    LockFreeRingBuffer()
        : buffer_(Capacity) {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t write(const T* data, size_t count) {
        if (data == nullptr || count == 0) {
            return 0;
        }

        const size_t currentTail =
            tail_.load(std::memory_order_relaxed);

        const size_t currentHead =
            head_.load(std::memory_order_acquire);

        const size_t used = currentTail - currentHead;

        const size_t freeSpace =
            (used >= Capacity)
                ? 0
                : (Capacity - used);

        const size_t toWrite =
            std::min(count, freeSpace);

        if (toWrite == 0) {
            return 0;
        }

        const size_t mask = Capacity - 1;
        const size_t tailIndex = currentTail & mask;

        const size_t firstChunk =
            std::min(toWrite, Capacity - tailIndex);

        std::memcpy(
            &buffer_[tailIndex],
            data,
            firstChunk * sizeof(T)
        );

        if (toWrite > firstChunk) {
            std::memcpy(
                &buffer_[0],
                data + firstChunk,
                (toWrite - firstChunk) * sizeof(T)
            );
        }

        tail_.store(
            currentTail + toWrite,
            std::memory_order_release
        );

        return toWrite;
    }

    // УСТРАНЕНИЕ ДЕФЕКТОВ 21, 22, 25, 26: Wait-free потокобезопасное чтение.
    // Если во время копирования данных произошел сброс буфера (discardAll),
    // CAS-операция на head_ чисто отклонит устаревший сдвиг, предотвращая порчу данных.
    size_t read(T* data, size_t count) {
        if (data == nullptr || count == 0) {
            return 0;
        }

        size_t currentHead = head_.load(std::memory_order_relaxed);

        while (true) {
            const size_t currentTail = tail_.load(std::memory_order_acquire);
            const size_t distance = currentTail - currentHead;

            if (distance == 0 || distance > Capacity) {
                return 0;
            }

            const size_t available = std::min(distance, Capacity);
            const size_t toRead = std::min(count, available);

            if (toRead == 0) {
                return 0;
            }

            const size_t mask = Capacity - 1;
            const size_t headIndex = currentHead & mask;
            const size_t firstChunk = std::min(toRead, Capacity - headIndex);

            std::memcpy(
                data,
                &buffer_[headIndex],
                firstChunk * sizeof(T)
            );

            if (toRead > firstChunk) {
                std::memcpy(
                    data + firstChunk,
                    &buffer_[0],
                    (toRead - firstChunk) * sizeof(T)
                );
            }

            if (head_.compare_exchange_weak(
                    currentHead,
                    currentHead + toRead,
                    std::memory_order_release,
                    std::memory_order_relaxed)) {
                return toRead;
            }
        }
    }

    // УСТРАНЕНИЕ ДЕФЕКТОВ 21, 22: Мгновенный wait-free сброс без остановки RT-колбэка.
    // Может безопасно вызываться из любого потока без ожидания остановки чтения.
    void discardAll() noexcept {
        size_t currentHead = head_.load(std::memory_order_relaxed);
        while (true) {
            const size_t currentTail = tail_.load(std::memory_order_acquire);
            if (currentHead == currentTail) {
                break;
            }
            if (head_.compare_exchange_weak(
                    currentHead,
                    currentTail,
                    std::memory_order_release,
                    std::memory_order_relaxed)) {
                break;
            }
        }
    }

    void discardAllQuiesced() noexcept {
        discardAll();
    }

    void resetQuiesced() noexcept {
        head_.store(0, std::memory_order_seq_cst);
        tail_.store(0, std::memory_order_seq_cst);
    }

    size_t availableRead() const {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        const size_t distance = t - h;
        return std::min(distance, Capacity);
    }

    size_t availableWrite() const {
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        const size_t distance = t - h;
        const size_t used = std::min(distance, Capacity);
        return Capacity - used;
    }

private:
    std::vector<T> buffer_;
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

} // namespace client::audio