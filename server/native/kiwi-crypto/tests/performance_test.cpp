#include "taotao/crypto/kiwi_cipher.h"

#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>

int main() {
    try {
        using taotao::crypto::KiwiCipher;
        const KiwiCipher::Key key = {
            0x91, 0x73, 0x25, 0x4A, 0xE8, 0x10, 0x6D, 0xBC,
            0x02, 0xF7, 0x39, 0x85, 0xC1, 0x5E, 0xA4, 0x68,
            0xD3, 0x0B, 0x7F, 0x42, 0x16, 0xED, 0x59, 0xA0,
            0x34, 0xC8, 0x6A, 0x1D, 0xB5, 0x80, 0xF2, 0x47
        };
        KiwiCipher cipher(key);
        KiwiCipher::Bytes plaintext(4096, 0x5A);
        constexpr int ITERATIONS = 2000;
        std::uint64_t checksum = 0;

        const auto startedAt = std::chrono::steady_clock::now();
        for (int index = 0; index < ITERATIONS; ++index) {
            plaintext[0] = static_cast<std::uint8_t>(index);
            const auto encrypted = cipher.encrypt(plaintext);
            const auto decrypted = cipher.decrypt(encrypted);
            if (decrypted != plaintext) {
                throw std::runtime_error("性能测试中的往返加解密不一致");
            }
            checksum += encrypted[40];
        }
        const auto finishedAt = std::chrono::steady_clock::now();
        const double seconds = std::chrono::duration<double>(finishedAt - startedAt).count();
        const double processedBytes =
            static_cast<double>(plaintext.size()) * ITERATIONS * 2.0;
        const double mebibytesPerSecond = processedBytes / (1024.0 * 1024.0) / seconds;
        const double operationsPerSecond = ITERATIONS * 2.0 / seconds;

        KiwiCipher::Bytes compressible(4096, 'A');
        std::size_t compressedContainerSize = 0;
        const auto compressionStartedAt = std::chrono::steady_clock::now();
        for (int index = 0; index < ITERATIONS; ++index) {
            compressible[0] = static_cast<std::uint8_t>(index);
            const auto encrypted = cipher.encrypt(
                compressible,
                {},
                KiwiCipher::CompressionMode::Automatic
            );
            const auto decrypted = cipher.decrypt(encrypted);
            if (decrypted != compressible) {
                throw std::runtime_error("压缩性能测试中的往返加解密不一致");
            }
            compressedContainerSize = encrypted.size();
            checksum += encrypted[45];
        }
        const auto compressionFinishedAt = std::chrono::steady_clock::now();
        const double compressionSeconds = std::chrono::duration<double>(
            compressionFinishedAt - compressionStartedAt
        ).count();
        const double compressionThroughput = processedBytes
            / (1024.0 * 1024.0)
            / compressionSeconds;

        std::cout << "往返吞吐量(MiB/s)：" << mebibytesPerSecond << '\n';
        std::cout << "加解密操作数/秒：" << operationsPerSecond << '\n';
        std::cout << "自动压缩往返吞吐量(MiB/s)：" << compressionThroughput << '\n';
        std::cout << "4096 字节压缩容器大小：" << compressedContainerSize << '\n';
        std::cout << "校验值：" << checksum << '\n';

        // 宽松下限用于捕获算法句柄重复加载等严重性能回退，不作为硬件跑分。
        if (mebibytesPerSecond < 10.0) {
            throw std::runtime_error("加密模块出现显著性能回退");
        }
        if (compressionThroughput < 10.0 || compressedContainerSize >= 512) {
            throw std::runtime_error("自动压缩出现显著性能或体积回退");
        }
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
