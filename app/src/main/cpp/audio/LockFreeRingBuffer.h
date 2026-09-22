// >>> FILE: app/src/main/cpp/audio/LockFreeRingBuffer.h
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

    // Strict SPSC contract:
    //
    //   exactly one producer may call write()
    //   exactly one consumer may call read()
    //
    //   producer owns tail_
    //   consumer owns head_
    //
    //   availableRead()/availableWrite() may be called concurrently because
    //   they only perform atomic observations.
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

        // Monotonic indices intentionally use unsigned wraparound arithmetic.
        // The invariant is that producer/consumer distance never exceeds
        // Capacity. Clamp defensively so a corrupted invariant cannot turn
        // into an oversized memcpy.
        const size_t distance =
            currentTail - currentHead;

        const size_t available =
            std::min(distance, Capacity);

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

        // Only the consumer writes head_.
        head_.store(
            currentHead + toRead,
            std::memory_order_release
        );

        return toRead;
    }

    // Lifecycle-only operation.
    //
    // PRECONDITION: the queue is fully quiescent. Neither the producer nor
    // the consumer may be executing write()/read() concurrently.
    //
    // This function intentionally has a quiescence-specific name so that a
    // lifecycle reset cannot be mistaken for a concurrent queue operation.
    void discardAllQuiesced() noexcept {
        // PRECONDITION: neither side is executing read()/write().
        // The AAudio playback producer additionally serializes its commit
        // with playbackControlMutex_, which is held by flushPlayback().
        const size_t tail =
            tail_.load(std::memory_order_seq_cst);

        head_.store(
            tail,
            std::memory_order_seq_cst
        );
    }

    // Lifecycle-only operation.
    //
    // PRECONDITION: full quiescence of both sides. Resetting both indices is
    // safe only while no producer/consumer is accessing the queue.
    void resetQuiesced() noexcept {
        head_.store(
            0,
            std::memory_order_seq_cst
        );

        tail_.store(
            0,
            std::memory_order_seq_cst
        );
    }

    size_t availableRead() const {
        // Consumer view: head is owned locally; tail is published by the
        // producer with release, therefore acquire is required for the
        // producer's committed payload/index.
        const size_t h =
            head_.load(std::memory_order_relaxed);

        const size_t t =
            tail_.load(std::memory_order_acquire);

        const size_t distance =
            t - h;

        return std::min(distance, Capacity);
    }

    size_t availableWrite() const {
        // Producer view: tail is owned locally; head is published by the
        // consumer with release, therefore acquire is required before
        // reusing the freed slots.
        const size_t h =
            head_.load(std::memory_order_acquire);

        const size_t t =
            tail_.load(std::memory_order_relaxed);

        const size_t distance =
            t - h;

        const size_t used =
            std::min(distance, Capacity);

        return Capacity - used;
    }

private:
    std::vector<T> buffer_;
    alignas(64)
    std::atomic<size_t> head_{0};

    alignas(64)
    std::atomic<size_t> tail_{0};
};

} // namespace client::audio
