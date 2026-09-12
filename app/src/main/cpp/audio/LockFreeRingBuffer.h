// >>> FILE: app/src/main/cpp/audio/LockFreeRingBuffer.h
#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <algorithm>
#include <vector>

namespace client::audio {

template <typename T, size_t Capacity>
class LockFreeRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    LockFreeRingBuffer() : buffer_(Capacity) {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    size_t write(const T* data, size_t count) {
        const size_t current_tail = tail_.load(std::memory_order_relaxed);
        const size_t current_head = head_.load(std::memory_order_acquire);

        const size_t free_space = Capacity - (current_tail - current_head);
        const size_t to_write = std::min(count, free_space);

        if (to_write == 0) return 0;

        const size_t mask = Capacity - 1;
        const size_t tail_idx = current_tail & mask;
        const size_t first_chunk = std::min(to_write, Capacity - tail_idx);

        std::memcpy(&buffer_[tail_idx], data, first_chunk * sizeof(T));
        if (to_write > first_chunk) {
            std::memcpy(&buffer_[0], data + first_chunk, (to_write - first_chunk) * sizeof(T));
        }

        tail_.store(current_tail + to_write, std::memory_order_release);
        return to_write;
    }

    size_t read(T* data, size_t count) {
        const size_t current_head = head_.load(std::memory_order_relaxed);
        const size_t current_tail = tail_.load(std::memory_order_acquire);

        const size_t available = current_tail - current_head;
        const size_t to_read = std::min(count, available);

        if (to_read == 0) return 0;

        const size_t mask = Capacity - 1;
        const size_t head_idx = current_head & mask;
        const size_t first_chunk = std::min(to_read, Capacity - head_idx);

        std::memcpy(data, &buffer_[head_idx], first_chunk * sizeof(T));
        if (to_read > first_chunk) {
            std::memcpy(data + first_chunk, &buffer_[0], (to_read - first_chunk) * sizeof(T));
        }

        head_.store(current_head + to_read, std::memory_order_release);
        return to_read;
    }

    size_t availableRead() const {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        return (t >= h) ? (t - h) : 0;
    }

    void clear() {
        const size_t t = tail_.load(std::memory_order_relaxed);
        head_.store(t, std::memory_order_release);
    }

private:
    std::vector<T> buffer_;

    // Выравнивание по 64 байтам исключает False Sharing в L1/L2 кэшах CPU
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
};

} // namespace client::audio