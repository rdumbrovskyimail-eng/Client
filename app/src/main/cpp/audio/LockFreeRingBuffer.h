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
    static_assert(
        (Capacity & (Capacity - 1)) == 0,
        "Capacity must be a power of two"
    );

public:
    LockFreeRingBuffer()
        : buffer_(Capacity) {
        head_.store(0, std::memory_order_relaxed);
        tail_.store(0, std::memory_order_relaxed);
    }

    // Strict SPSC contract:
    //
    //   exactly one producer may call write()
    //   exactly one consumer may call read()
    //
    //   producer owns tail_
    //   consumer owns head_
    //
    // Lifecycle-only discard/clear operations require full quiescence
    // of both producer and consumer.
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

    size_t read(T* data, size_t count) {
        if (data == nullptr || count == 0) {
            return 0;
        }

        const size_t currentHead =
            head_.load(std::memory_order_relaxed);

        const size_t currentTail =
            tail_.load(std::memory_order_acquire);

        const size_t available =
            currentTail - currentHead;

        const size_t toRead =
            std::min(count, available);

        if (toRead == 0) {
            return 0;
        }

        const size_t mask = Capacity - 1;
        const size_t headIndex = currentHead & mask;

        const size_t firstChunk =
            std::min(toRead, Capacity - headIndex);

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

        // IMPORTANT:
        // Only the consumer writes head_.
        head_.store(
            currentHead + toRead,
            std::memory_order_release
        );

        return toRead;
    }

    // Lifecycle-only operation.
    //
    // Caller MUST guarantee that neither producer nor consumer is
    // concurrently touching this queue.
    void discardAllQuiesced() {
        const size_t tail =
            tail_.load(std::memory_order_acquire);

        head_.store(
            tail,
            std::memory_order_release
        );
    }

    // Retained for lifecycle code.
    // Same full-quiescence requirement.
    void discardAll() {
        discardAllQuiesced();
    }

    // Lifecycle-only.
    void clear() {
        head_.store(
            0,
            std::memory_order_relaxed
        );

        tail_.store(
            0,
            std::memory_order_relaxed
        );
    }

    size_t availableRead() const {
        const size_t h =
            head_.load(std::memory_order_relaxed);

        const size_t t =
            tail_.load(std::memory_order_acquire);

        return t - h;
    }

    size_t availableWrite() const {
        const size_t h =
            head_.load(std::memory_order_acquire);

        const size_t t =
            tail_.load(std::memory_order_relaxed);

        const size_t used = t - h;

        return (used >= Capacity)
            ? 0
            : (Capacity - used);
    }

private:
    std::vector<T> buffer_;

    alignas(64)
    std::atomic<size_t> head_{0};

    alignas(64)
    std::atomic<size_t> tail_{0};
};

} // namespace client::audio