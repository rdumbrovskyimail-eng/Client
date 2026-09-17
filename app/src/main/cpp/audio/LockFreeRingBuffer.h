// >>> FILE: app/src/main/cpp/audio/LockFreeRingBuffer.h
#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <algorithm>
#include <vector>
#include <cstdint>

namespace client::audio {

template <typename T, size_t Capacity>
class LockFreeRingBuffer {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    LockFreeRingBuffer() : buffer_(Capacity) {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
        flushGeneration_.store(0, std::memory_order_relaxed);
        flushAcknowledgedGeneration_.store(0, std::memory_order_relaxed);
    }

    size_t write(const T* data, size_t count) {
        if (data == nullptr || count == 0) return 0;

        const size_t current_tail = tail_.load(std::memory_order_relaxed);
        const size_t current_head = head_.load(std::memory_order_acquire);

        const size_t used = current_tail - current_head;
        const size_t free_space = (used >= Capacity) ? 0 : (Capacity - used);
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
        if (data == nullptr || count == 0) return 0;

        const uint64_t requested = flushGeneration_.load(std::memory_order_acquire);
        const uint64_t acknowledged = flushAcknowledgedGeneration_.load(std::memory_order_relaxed);

        if (requested != acknowledged) {
            const size_t t = tail_.load(std::memory_order_acquire);
            head_.store(t, std::memory_order_release);
            flushAcknowledgedGeneration_.store(requested, std::memory_order_release);
            return 0;
        }

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

    void discardAll() {
        const size_t t = tail_.load(std::memory_order_acquire);
        head_.store(t, std::memory_order_release);
        const uint64_t requested = flushGeneration_.load(std::memory_order_acquire);
        flushAcknowledgedGeneration_.store(requested, std::memory_order_release);
    }

    void clear() {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
        flushGeneration_.store(0, std::memory_order_relaxed);
        flushAcknowledgedGeneration_.store(0, std::memory_order_relaxed);
    }

    uint64_t requestFlush(uint64_t generation) {
        if (generation == 0) {
            return flushGeneration_.load(std::memory_order_acquire);
        }

        uint64_t current = flushGeneration_.load(std::memory_order_acquire);
        while (generation > current) {
            if (flushGeneration_.compare_exchange_weak(
                    current, generation,
                    std::memory_order_release,
                    std::memory_order_acquire)) {
                return generation;
            }
        }
        return current;
    }

    uint64_t getFlushGeneration() const {
        return flushGeneration_.load(std::memory_order_acquire);
    }

    uint64_t getFlushAcknowledgedGeneration() const {
        return flushAcknowledgedGeneration_.load(std::memory_order_acquire);
    }

    bool isFlushAcknowledged(uint64_t generation) const {
        return flushAcknowledgedGeneration_.load(std::memory_order_acquire) >= generation;
    }

    // AUD-002: Consumer side: own head relaxed, producer tail acquire
    size_t availableRead() const {
        const size_t h = head_.load(std::memory_order_relaxed);
        const size_t t = tail_.load(std::memory_order_acquire);
        return (t - h);
    }

    // AUD-002: Producer side: own tail relaxed, consumer head acquire
    size_t availableWrite() const {
        const size_t h = head_.load(std::memory_order_acquire);
        const size_t t = tail_.load(std::memory_order_relaxed);
        const size_t used = t - h;
        return (used >= Capacity) ? 0 : (Capacity - used);
    }

private:
    std::vector<T> buffer_;

    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
    alignas(64) std::atomic<uint64_t> flushGeneration_{0};
    alignas(64) std::atomic<uint64_t> flushAcknowledgedGeneration_{0};
};

} // namespace client::audio