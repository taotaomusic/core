#include "taotao/crypto/kiwi_cipher.h"
#include "taotao/crypto/replay_guard.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

using taotao::crypto::KiwiCipher;
using taotao::crypto::InMemoryReplayStore;
using taotao::crypto::ReplayId;
using taotao::crypto::ReplayStore;
using taotao::crypto::decryptOnce;

class RecordingReplayStore final : public ReplayStore {
public:
    bool consume(const ReplayId&, std::chrono::milliseconds validFor) override {
        recordedValidFor = validFor;
        return true;
    }

    std::chrono::milliseconds recordedValidFor{0};
};

void require(bool condition, const std::string& message) {
    if (!condition) {
        throw std::runtime_error("测试失败：" + message);
    }
}

template <typename Action>
void requireRejected(Action action, const std::string& message) {
    try {
        action();
    } catch (const std::exception&) {
        return;
    }
    throw std::runtime_error("测试失败：" + message);
}

void testRoundTrip(
    KiwiCipher& cipher,
    const KiwiCipher::Bytes& plaintext,
    const KiwiCipher::Bytes& additionalAuthenticatedData = {}
) {
    const auto ciphertext = cipher.encrypt(plaintext, additionalAuthenticatedData);
    require(
        ciphertext.size() == plaintext.size() + KiwiCipher::FORMAT_OVERHEAD,
        "密文长度不符合版本 1 格式"
    );
    require(
        ciphertext[0] == 't' && ciphertext[1] == 'a' && ciphertext[2] == 'o',
        "缺少 tao 前缀"
    );
    require(
        ciphertext[ciphertext.size() - 4] == 'y'
            && ciphertext[ciphertext.size() - 3] == 'u'
            && ciphertext[ciphertext.size() - 2] == 'a'
            && ciphertext[ciphertext.size() - 1] == 'n',
        "缺少 yuan 尾标识"
    );
    require(
        cipher.decrypt(ciphertext, additionalAuthenticatedData) == plaintext,
        "往返加解密不一致"
    );
}

}  // namespace

int main() {
    try {
        const KiwiCipher::Key key = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
        };
        KiwiCipher cipher(key);
        require(cipher.isKeyMemoryLocked(), "主密钥内存未能锁定");

        testRoundTrip(cipher, {});
        testRoundTrip(cipher, {'H', 'e', 'l', 'l', 'o', ',', ' ', 'w', 'o', 'r', 'l', 'd', '!'});
        testRoundTrip(cipher, std::vector<std::uint8_t>(16, 0xA5));
        testRoundTrip(cipher, {0x00, 0xFF, 0x80, 0x7F, 0x00});

        const KiwiCipher::Bytes compressible(64 * 1024, 'A');
        const auto compressedCiphertext = cipher.encrypt(
            compressible,
            {},
            KiwiCipher::CompressionMode::Automatic
        );
        require((compressedCiphertext[4] & 0x01) != 0, "可压缩数据没有启用压缩标志");
        require(
            compressedCiphertext.size() < compressible.size() / 10,
            "高重复数据压缩后仍然过大"
        );
        require(cipher.decrypt(compressedCiphertext) == compressible, "压缩密文无法正确解密");

        const KiwiCipher::Bytes shortPlaintext = {'s', 'h', 'o', 'r', 't'};
        const auto shortAutomatic = cipher.encrypt(
            shortPlaintext,
            {},
            KiwiCipher::CompressionMode::Automatic
        );
        require((shortAutomatic[4] & 0x01) == 0, "短数据被强制压缩后反而膨胀");
        require(cipher.decrypt(shortAutomatic) == shortPlaintext, "自动跳过压缩后无法解密");

        auto modifiedCompressionFlag = compressedCiphertext;
        modifiedCompressionFlag[4] ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(modifiedCompressionFlag)); },
            "篡改压缩标志后仍能解密"
        );
        auto unknownFlag = compressedCiphertext;
        unknownFlag[4] |= 0x80;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(unknownFlag)); },
            "未知格式标志未被拒绝"
        );

        const KiwiCipher::Bytes routeContext = {
            'P', 'O', 'S', 'T', ' ', '/', 'a', 'p', 'i', '/', 'v', '1', '/', 's', 'e', 'c', 'u', 'r', 'e'
        };
        testRoundTrip(cipher, {'b', 'o', 'u', 'n', 'd'}, routeContext);

        const KiwiCipher::Bytes sample = {'t', 'a', 'o', 't', 'a', 'o'};
        const auto first = cipher.encrypt(sample);
        const auto second = cipher.encrypt(sample);
        require(first != second, "重复加密必须使用不同 nonce");

        auto modifiedNonce = first;
        modifiedNonce[33] ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(modifiedNonce)); },
            "篡改 nonce 后仍能解密"
        );

        auto modifiedCiphertext = first;
        modifiedCiphertext[45] ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(modifiedCiphertext)); },
            "篡改密文后仍能解密"
        );

        auto modifiedTag = first;
        modifiedTag[modifiedTag.size() - 5] ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(modifiedTag)); },
            "篡改认证标签后仍能解密"
        );

        auto modifiedTrailer = first;
        modifiedTrailer.back() ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(modifiedTrailer)); },
            "篡改 yuan 尾标识后仍能解密"
        );

        auto appendedGarbage = first;
        appendedGarbage.push_back('x');
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(appendedGarbage)); },
            "yuan 后附加垃圾仍能解密"
        );

        auto appendedFakeTrailer = first;
        appendedFakeTrailer.insert(
            appendedFakeTrailer.end(),
            {'x', 'y', 'u', 'a', 'n'}
        );
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(appendedFakeTrailer)); },
            "密文后附加伪造 yuan 仍能解密"
        );

        auto prependedGarbage = first;
        prependedGarbage.insert(prependedGarbage.begin(), 'x');
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(prependedGarbage)); },
            "tao 前附加垃圾仍能解密"
        );

        auto modifiedVersion = first;
        modifiedVersion[3] ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(modifiedVersion)); },
            "未知格式版本未被拒绝"
        );

        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(KiwiCipher::Bytes(31, 0))); },
            "过短输入未被拒绝"
        );

        const auto boundCiphertext = cipher.encrypt(sample, routeContext);
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(boundCiphertext)); },
            "缺少附加认证数据时仍能解密"
        );
        auto wrongContext = routeContext;
        wrongContext.back() ^= 0x01;
        requireRejected(
            [&] { static_cast<void>(cipher.decrypt(boundCiphertext, wrongContext)); },
            "错误的附加认证数据未被拒绝"
        );

        InMemoryReplayStore replayStore;
        const auto oneTimeCiphertext = cipher.encrypt(sample, routeContext);
        require(
            decryptOnce(
                cipher,
                replayStore,
                oneTimeCiphertext,
                std::chrono::minutes(5),
                routeContext
            ) == sample,
            "首次使用的一次性消息未能解密"
        );

        RecordingReplayStore recordingStore;
        const auto ttlCiphertext = cipher.encrypt(sample, routeContext);
        static_cast<void>(decryptOnce(
            cipher,
            recordingStore,
            ttlCiphertext,
            std::chrono::minutes(5),
            routeContext,
            std::chrono::seconds(30)
        ));
        require(
            recordingStore.recordedValidFor == std::chrono::minutes(5) + std::chrono::seconds(30),
            "Replay ID 有效期没有以服务端接收时间和允许时钟偏差计算"
        );
        requireRejected(
            [&] {
                static_cast<void>(decryptOnce(
                    cipher,
                    replayStore,
                    oneTimeCiphertext,
                    std::chrono::minutes(5),
                    routeContext
                ));
            },
            "同一密文被重复接受"
        );

        InMemoryReplayStore unpollutedStore;
        auto invalidCiphertext = oneTimeCiphertext;
        invalidCiphertext.back() ^= 0x01;
        requireRejected(
            [&] {
                static_cast<void>(decryptOnce(
                    cipher,
                    unpollutedStore,
                    invalidCiphertext,
                    std::chrono::minutes(5),
                    routeContext
                ));
            },
            "篡改密文未被拒绝"
        );
        require(unpollutedStore.size() == 0, "无效密文污染了防重放缓存");
        require(
            decryptOnce(
                cipher,
                unpollutedStore,
                oneTimeCiphertext,
                std::chrono::minutes(5),
                routeContext
            ) == sample,
            "无效请求之后的合法密文被错误拒绝"
        );

        InMemoryReplayStore concurrentStore;
        std::atomic<int> acceptedCount = 0;
        std::vector<std::thread> workers;
        for (int index = 0; index < 8; ++index) {
            workers.emplace_back([&] {
                try {
                    static_cast<void>(decryptOnce(
                        cipher,
                        concurrentStore,
                        oneTimeCiphertext,
                        std::chrono::minutes(5),
                        routeContext
                    ));
                    ++acceptedCount;
                } catch (const std::exception&) {
                }
            });
        }
        for (auto& worker : workers) {
            worker.join();
        }
        require(acceptedCount == 1, "并发重放时不是恰好一个请求成功");

        InMemoryReplayStore expiringStore;
        require(
            decryptOnce(
                cipher,
                expiringStore,
                oneTimeCiphertext,
                std::chrono::milliseconds(50),
                routeContext,
                std::chrono::milliseconds(0)
            ) == sample,
            "短期一次性消息首次使用失败"
        );
        std::this_thread::sleep_for(std::chrono::milliseconds(80));
        requireRejected(
            [&] {
                static_cast<void>(decryptOnce(
                    cipher,
                    expiringStore,
                    oneTimeCiphertext,
                    std::chrono::milliseconds(50),
                    routeContext,
                    std::chrono::milliseconds(0)
                ));
            },
            "超过最大年龄的消息仍被接受"
        );
        require(expiringStore.size() == 0, "过期 Replay ID 未被清理");

        KiwiCipher limitedCipher(key, KiwiCipher::Limits{4, 3});
        requireRejected(
            [&] { static_cast<void>(limitedCipher.encrypt(KiwiCipher::Bytes(5, 0))); },
            "超过明文上限的输入未被拒绝"
        );
        requireRejected(
            [&] {
                static_cast<void>(limitedCipher.encrypt(
                    KiwiCipher::Bytes(4, 0),
                    KiwiCipher::Bytes(4, 0)
                ));
            },
            "超过 AAD 上限的输入未被拒绝"
        );

        std::cout << "全部测试通过：GCM-SIV、HKDF、安全内存、AAD、篡改检测和防重放正常。\n";
        const KiwiCipher::Bytes hello = {0xE4, 0xBD, 0xA0, 0xE5, 0xA5, 0xBD};
        const auto helloCiphertext = cipher.encrypt(hello);
        require(cipher.decrypt(helloCiphertext) == hello, "你好示例密文无法解密");
        std::cout << "你好示例密文(hex)：";
        for (std::uint8_t byte : helloCiphertext) {
            std::cout << std::hex << std::setw(2) << std::setfill('0')
                      << static_cast<unsigned int>(byte);
        }
        std::cout << '\n';
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
