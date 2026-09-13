#pragma once

#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <cstring>
#include <cstdint>

namespace client::logging {

struct NativeLogItem {
    int level; // 2=V, 3=D, 4=I, 5=W, 6=E, 8=AUD
    char tag[32];
    char message[256];
    uint64_t timestampNs;
};

class NativeLogQueue {
public:
    static constexpr size_t CAPACITY = 1024;

    static NativeLogQueue& getInstance() {
        static NativeLogQueue instance;
        return instance;
    }

    void push(int level, const char* tag, const char* msg) {
        uint64_t currentTail = tail_.load(std::memory_order_relaxed);
        uint64_t currentHead = head_.load(std::memory_order_acquire);

        if (currentTail - currentHead >= CAPACITY) {
            head_.store(currentHead + 1, std::memory_order_relaxed);
        }

        size_t idx = currentTail & (CAPACITY - 1);
        NativeLogItem& item = items_[idx];
        item.level = level;
        
        strncpy(item.tag, tag, sizeof(item.tag) - 1);
        item.tag[sizeof(item.tag) - 1] = '\0';

        strncpy(item.message, msg, sizeof(item.message) - 1);
        item.message[sizeof(item.message) - 1] = '\0';

        timespec ts;
        clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
        item.timestampNs = static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + ts.tv_nsec;

        tail_.store(currentTail + 1, std::memory_order_release);
    }

    bool pop(NativeLogItem& outItem) {
        uint64_t currentHead = head_.load(std::memory_order_relaxed);
        uint64_t currentTail = tail_.load(std::memory_order_acquire);

        if (currentHead >= currentTail) {
            return false;
        }

        size_t idx = currentHead & (CAPACITY - 1);
        outItem = items_[idx];
        head_.store(currentHead + 1, std::memory_order_release);
        return true;
    }

private:
    NativeLogQueue() = default;
    alignas(64) NativeLogItem items_[CAPACITY];
    alignas(64) std::atomic<uint64_t> head_{0};
    alignas(64) std::atomic<uint64_t> tail_{0};
};

} // namespace client::logging