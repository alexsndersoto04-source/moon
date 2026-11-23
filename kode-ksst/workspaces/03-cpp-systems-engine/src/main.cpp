#include <iostream>
#include <chrono>
#include "ring_buffer.hpp"

int main() {
    RingBuffer<int, 1024> rb;
    auto t0 = std::chrono::steady_clock::now();

    long long checksum = 0;
    for (int iter = 0; iter < 50000; ++iter) {
        rb.push(iter);
        if (auto v = rb.pop()) {
            checksum += *v;
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    auto us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();

    std::cout << "C++20 RingBuffer Benchmark\n";
    std::cout << "Operations: 100,000 push/pop\n";
    std::cout << "Checksum:   " << checksum << "\n";
    std::cout << "Elapsed:    " << us << " us\n";
    return 0;
}
