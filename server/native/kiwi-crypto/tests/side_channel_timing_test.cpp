#include "taotao/crypto/kiwi_cipher.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <stdexcept>

namespace {

using taotao::crypto::KiwiCipher;

class RunningStatistics final {
public:
    void add(double value) {
        ++count_;
        const double delta = value - mean_;
        mean_ += delta / static_cast<double>(count_);
        const double deltaAfterMean = value - mean_;
        squaredDeviation_ += delta * deltaAfterMean;
    }

    [[nodiscard]] double mean() const {
        return mean_;
    }

    [[nodiscard]] double variance() const {
        return count_ > 1
            ? squaredDeviation_ / static_cast<double>(count_ - 1)
            : 0.0;
    }

    [[nodiscard]] std::size_t count() const {
        return count_;
    }

private:
    std::size_t count_ = 0;
    double mean_ = 0.0;
    double squaredDeviation_ = 0.0;
};

double measureRejectedDecrypt(
    const KiwiCipher& cipher,
    const KiwiCipher::Bytes& ciphertext
) {
    const auto startedAt = std::chrono::steady_clock::now();
    try {
        static_cast<void>(cipher.decrypt(ciphertext));
        throw std::runtime_error("侧信道测试输入没有被拒绝");
    } catch (const std::invalid_argument&) {
    }
    const auto finishedAt = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::nano>(finishedAt - startedAt).count();
}

double welchT(const RunningStatistics& left, const RunningStatistics& right) {
    const double leftTerm = left.variance() / static_cast<double>(left.count());
    const double rightTerm = right.variance() / static_cast<double>(right.count());
    const double denominator = std::sqrt(leftTerm + rightTerm);
    return denominator == 0.0 ? 0.0 : (left.mean() - right.mean()) / denominator;
}

}  // namespace

int main() {
    try {
        const KiwiCipher::Key key = {
            0x63, 0x7D, 0x21, 0x84, 0xA9, 0x11, 0x5E, 0xC0,
            0x72, 0x18, 0x93, 0x4A, 0xD5, 0x36, 0x0F, 0xB2,
            0x44, 0xE1, 0x98, 0x2C, 0x7A, 0x55, 0xD0, 0x03,
            0xAF, 0x61, 0x19, 0xCE, 0x82, 0x34, 0xF7, 0x0B
        };
        KiwiCipher cipher(key);
        const KiwiCipher::Bytes plaintext(1024, 0xA5);
        const auto valid = cipher.encrypt(plaintext);

        auto firstTagByteWrong = valid;
        firstTagByteWrong[firstTagByteWrong.size() - 20] ^= 0x01;
        auto lastTagByteWrong = valid;
        lastTagByteWrong[lastTagByteWrong.size() - 5] ^= 0x01;

        for (int index = 0; index < 100; ++index) {
            static_cast<void>(measureRejectedDecrypt(cipher, firstTagByteWrong));
            static_cast<void>(measureRejectedDecrypt(cipher, lastTagByteWrong));
        }

        RunningStatistics firstStatistics;
        RunningStatistics lastStatistics;
        std::uint32_t orderState = 0xC0FFEEU;
        constexpr int SAMPLE_PAIRS = 1500;
        for (int index = 0; index < SAMPLE_PAIRS; ++index) {
            orderState ^= orderState << 13;
            orderState ^= orderState >> 17;
            orderState ^= orderState << 5;
            if ((orderState & 1U) == 0U) {
                firstStatistics.add(measureRejectedDecrypt(cipher, firstTagByteWrong));
                lastStatistics.add(measureRejectedDecrypt(cipher, lastTagByteWrong));
            } else {
                lastStatistics.add(measureRejectedDecrypt(cipher, lastTagByteWrong));
                firstStatistics.add(measureRejectedDecrypt(cipher, firstTagByteWrong));
            }
        }

        const double tValue = welchT(firstStatistics, lastStatistics);
        std::cout << "认证标签首字节错误平均耗时(ns)：" << firstStatistics.mean() << '\n';
        std::cout << "认证标签末字节错误平均耗时(ns)：" << lastStatistics.mean() << '\n';
        std::cout << "Welch t 值：" << tValue << '\n';

        // 这是用于发现明显提前退出的回归阈值，不是侧信道安全证明。
        if (std::abs(tValue) >= 12.0) {
            throw std::runtime_error("检测到认证失败路径存在显著时序差异");
        }
        std::cout << "时序侧信道烟雾测试通过。\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
