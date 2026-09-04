#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace taotao::crypto {

/**
 * 基于 HKDF-SHA256、AES-256-GCM-SIV 和可选 Zstandard 的带认证密文容器。
 *
 * 版本 3 的二进制格式：
 *   "tao" | version(1B) | flags(1B) | issuedAtMs(8B) | originalSize(4B)
 *   | salt(16B) | nonce(12B) | ciphertext(NB) | tag(16B) | "yuan"
 *
 * 每条消息先用 HKDF-SHA256 从主密钥派生独立子密钥，再使用
 * AES-256-GCM-SIV 加密。GCM-SIV 能降低 nonce 意外重复造成的灾难性影响，
 * 但调用方仍不得主动复用密文或依赖这一性质代替正确的密钥管理。
 *
 * additionalAuthenticatedData（附加认证数据）不会写入密文，但会参与认证。
 * 调用方可用它绑定接口名、用户 ID 或业务版本，解密时必须传入完全相同的内容。
 */
class KiwiCipher final {
public:
    static constexpr std::size_t KEY_SIZE = 32;
    static constexpr std::uint8_t FORMAT_VERSION = 3;
    static constexpr std::size_t FORMAT_OVERHEAD = 65;

    enum class CompressionMode {
        Disabled,
        Automatic
    };

    struct Limits {
        constexpr Limits(
            std::size_t plaintextSize = 16 * 1024 * 1024,
            std::size_t additionalAuthenticatedDataSize = 64 * 1024
        ) :
            maxPlaintextSize(plaintextSize),
            maxAdditionalAuthenticatedDataSize(additionalAuthenticatedDataSize) {}

        std::size_t maxPlaintextSize;
        std::size_t maxAdditionalAuthenticatedDataSize;
    };

    using Bytes = std::vector<std::uint8_t>;
    using Key = std::array<std::uint8_t, KEY_SIZE>;

    explicit KiwiCipher(Key key, Limits limits = Limits{});
    ~KiwiCipher();

    KiwiCipher(const KiwiCipher&) = delete;
    KiwiCipher& operator=(const KiwiCipher&) = delete;
    KiwiCipher(KiwiCipher&&) = delete;
    KiwiCipher& operator=(KiwiCipher&&) = delete;

    /** 主密钥所在内存是否成功锁定，服务启动时可据此执行严格策略。 */
    [[nodiscard]] bool isKeyMemoryLocked() const noexcept;

    [[nodiscard]] Bytes encrypt(
        const Bytes& plaintext,
        const Bytes& additionalAuthenticatedData = {}
    ) const;

    [[nodiscard]] Bytes encrypt(
        const Bytes& plaintext,
        const Bytes& additionalAuthenticatedData,
        CompressionMode compressionMode
    ) const;

    [[nodiscard]] Bytes decrypt(
        const Bytes& ciphertext,
        const Bytes& additionalAuthenticatedData = {}
    ) const;

private:
    class SecureKey;
    std::unique_ptr<SecureKey> key_;
    Limits limits_;
};

}  // namespace taotao::crypto
