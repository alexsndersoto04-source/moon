#pragma once
#include <array>
#include <optional>
#include <cstddef>

template <typename T, std::size_t Capacity>
class RingBuffer {
public:
    bool push(const T& value) {
        if (count_ == Capacity) return false;
        data_[tail_] = value;
        tail_ = (tail_ + 1) % Capacity;
        ++count_;
        return true;
    }

    std::optional<T> pop() {
        if (count_ == 0) return std::nullopt;
        T value = data_[head_];
        head_ = (head_ + 1) % Capacity;
        --count_;
        return value;
    }

    std::size_t size() const { return count_; }
    constexpr std::size_t capacity() const { return Capacity; }

private:
    std::array<T, Capacity> data_{};
    std::size_t head_ = 0;
    std::size_t tail_ = 0;
    std::size_t count_ = 0;
};
