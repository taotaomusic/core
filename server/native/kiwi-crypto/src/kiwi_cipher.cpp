#include "taotao/crypto/kiwi_cipher.h"

#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/rand.h>
#include <zstd.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <limits>
#include <memory>
#include <stdexcept>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#elif defined(__unix__) || defined(__APPLE__)
#include <sys/mman.h>
#endif

namespace taotao::crypto {
namespace {

constexpr std::array<std::uint8_t, 3> MAGIC = {'t', 'a', 'o'};
constexpr std::array<std::uint8_t, 4> TRAILER = {'y', 'u', 'a', 'n'};
constexpr std::array<std::uint8_t, 22> HKDF_INFO = {
    't', 'a', 'o', 't', 'a', 'o', '/', 'k', 'i', 'w', 'i', '-', 'c', 'r', 'y', 'p', 't', 'o', '/', 'v', '3', 0
};
constexpr std::uint8_t FLAG_COMPRESSED = 0x01;
constexpr std::uint8_t KNOWN_FLAGS = FLAG_COMPRESSED;
constexpr std::size_t ISSUED_AT_SIZE = 8;
constexpr std::size_t ORIGINAL_SIZE_SIZE = 4;
constexpr std::size_t HEADER_SIZE = MAGIC.size() + 1 + 1 + ISSUED_AT_SIZE + ORIGINAL_SIZE_SIZE;
constexpr std::size_t SALT_SIZE = 16;
constexpr std::size_t NONCE_SIZE = 12;
constexpr std::size_t TAG_SIZE = 16;
constexpr std::size_t MIN_CIPHERTEXT_SIZE =
    HEADER_SIZE + SALT_SIZE + NONCE_SIZE + TAG_SIZE + TRAILER.size();

static_assert(MIN_CIPHERTEXT_SIZE == KiwiCipher::FORMAT_OVERHEAD);

struct ContextDeleter {
    void operator()(EVP_CIPHER_CTX* context) const noexcept {
        EVP_CIPHER_CTX_free(context);
    }
};

struct CipherDeleter {
    void operator()(EVP_CIPHER* cipher) const noexcept {
        EVP_CIPHER_free(cipher);
    }
};

struct KdfDeleter {
    void operator()(EVP_KDF* kdf) const noexcept {
        EVP_KDF_free(kdf);
    }
};

struct KdfContextDeleter {
    void operator()(EVP_KDF_CTX* context) const noexcept {
        EVP_KDF_CTX_free(context);
    }
};

using Context = std::unique_ptr<EVP_CIPHER_CTX, ContextDeleter>;
using Cipher = std::unique_ptr<EVP_CIPHER, CipherDeleter>;
using Kdf = std::unique_ptr<EVP_KDF, KdfDeleter>;
using KdfContext = std::unique_ptr<EVP_KDF_CTX, KdfContextDeleter>;

class CleansedKey final {
public:
    std::array<std::uint8_t, KiwiCipher::KEY_SIZE> bytes{};

    ~CleansedKey() {
        OPENSSL_cleanse(bytes.data(), bytes.size());
    }
};

class CleansedBytes final {
public:
    KiwiCipher::Bytes bytes;

    ~CleansedBytes() {
        OPENSSL_cleanse(bytes.data(), bytes.size());
    }
};

Context createContext() {
    Context context(EVP_CIPHER_CTX_new());
    if (!context) {
        throw std::runtime_error("无法创建加密上下文");
    }
    return context;
}

const EVP_CIPHER* cipherAlgorithm() {
    static const Cipher cipher(EVP_CIPHER_fetch(nullptr, "AES-256-GCM-SIV", nullptr));
    if (!cipher) {
        throw std::runtime_error("当前 OpenSSL 不支持 AES-256-GCM-SIV");
    }
    return cipher.get();
}

EVP_KDF* kdfAlgorithm() {
    static const Kdf kdf(EVP_KDF_fetch(nullptr, "HKDF-SHA256", nullptr));
    if (!kdf) {
        throw std::runtime_error("当前 OpenSSL 不支持 HKDF-SHA256");
    }
    return kdf.get();
}

void requireSuccess(int result, const char* message) {
    if (result != 1) {
        throw std::runtime_error(message);
    }
}

int checkedSize(std::size_t size) {
    if (size > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        throw std::invalid_argument("输入数据过大");
    }
    return static_cast<int>(size);
}

std::array<std::uint8_t, HEADER_SIZE> formatHeader(
    std::uint8_t flags,
    std::uint64_t issuedAtMillis,
    std::uint32_t originalSize
) {
    std::array<std::uint8_t, HEADER_SIZE> header{};
    std::copy(MAGIC.begin(), MAGIC.end(), header.begin());
    header[MAGIC.size()] = KiwiCipher::FORMAT_VERSION;
    header[MAGIC.size() + 1] = flags;
    constexpr std::size_t ISSUED_AT_OFFSET = MAGIC.size() + 2;
    for (std::size_t index = 0; index < ISSUED_AT_SIZE; ++index) {
        const std::size_t shift = (ISSUED_AT_SIZE - 1 - index) * 8;
        header[ISSUED_AT_OFFSET + index] = static_cast<std::uint8_t>(issuedAtMillis >> shift);
    }
    constexpr std::size_t ORIGINAL_SIZE_OFFSET = ISSUED_AT_OFFSET + ISSUED_AT_SIZE;
    for (std::size_t index = 0; index < ORIGINAL_SIZE_SIZE; ++index) {
        const std::size_t shift = (ORIGINAL_SIZE_SIZE - 1 - index) * 8;
        header[ORIGINAL_SIZE_OFFSET + index] = static_cast<std::uint8_t>(originalSize >> shift);
    }
    return header;
}

std::uint32_t originalSizeOf(const std::array<std::uint8_t, HEADER_SIZE>& header) {
    constexpr std::size_t OFFSET = MAGIC.size() + 2 + ISSUED_AT_SIZE;
    std::uint32_t value = 0;
    for (std::size_t index = 0; index < ORIGINAL_SIZE_SIZE; ++index) {
        value = (value << 8) | header[OFFSET + index];
    }
    return value;
}

bool compressIfSmaller(const KiwiCipher::Bytes& plaintext, CleansedBytes& compressed) {
    if (plaintext.empty()) {
        return false;
    }
    compressed.bytes.resize(ZSTD_compressBound(plaintext.size()));
    const std::size_t compressedSize = ZSTD_compress(
        compressed.bytes.data(),
        compressed.bytes.size(),
        plaintext.data(),
        plaintext.size(),
        1
    );
    if (ZSTD_isError(compressedSize) != 0) {
        throw std::runtime_error("Zstandard 压缩失败");
    }
    compressed.bytes.resize(compressedSize);
    return compressedSize < plaintext.size();
}

CleansedKey deriveMessageKey(
    const KiwiCipher::Key& masterKey,
    const std::array<std::uint8_t, SALT_SIZE>& salt
) {
    KdfContext context(EVP_KDF_CTX_new(kdfAlgorithm()));
    if (!context) {
        throw std::runtime_error("无法创建密钥派生上下文");
    }

    auto* keyData = const_cast<std::uint8_t*>(masterKey.data());
    auto* saltData = const_cast<std::uint8_t*>(salt.data());
    auto* infoData = const_cast<std::uint8_t*>(HKDF_INFO.data());
    OSSL_PARAM parameters[] = {
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_KEY, keyData, masterKey.size()),
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_SALT, saltData, salt.size()),
        OSSL_PARAM_construct_octet_string(OSSL_KDF_PARAM_INFO, infoData, HKDF_INFO.size()),
        OSSL_PARAM_construct_end()
    };

    CleansedKey derived;
    requireSuccess(
        EVP_KDF_derive(context.get(), derived.bytes.data(), derived.bytes.size(), parameters),
        "消息子密钥派生失败"
    );
    return derived;
}

void authenticate(
    EVP_CIPHER_CTX* context,
    const std::array<std::uint8_t, HEADER_SIZE>& header,
    const KiwiCipher::Bytes& additionalAuthenticatedData,
    bool encrypting
) {
    int produced = 0;
    const int headerResult = encrypting
        ? EVP_EncryptUpdate(
              context,
              nullptr,
              &produced,
              header.data(),
              static_cast<int>(header.size())
          )
        : EVP_DecryptUpdate(
              context,
              nullptr,
              &produced,
              header.data(),
              static_cast<int>(header.size())
          );
    requireSuccess(headerResult, "无法认证密文头");

    const int trailerResult = encrypting
        ? EVP_EncryptUpdate(
              context,
              nullptr,
              &produced,
              TRAILER.data(),
              static_cast<int>(TRAILER.size())
          )
        : EVP_DecryptUpdate(
              context,
              nullptr,
              &produced,
              TRAILER.data(),
              static_cast<int>(TRAILER.size())
          );
    requireSuccess(trailerResult, "无法认证密文尾");

    if (additionalAuthenticatedData.empty()) {
        return;
    }

    const int dataSize = checkedSize(additionalAuthenticatedData.size());
    const int dataResult = encrypting
        ? EVP_EncryptUpdate(
              context,
              nullptr,
              &produced,
              additionalAuthenticatedData.data(),
              dataSize
          )
        : EVP_DecryptUpdate(
              context,
              nullptr,
              &produced,
              additionalAuthenticatedData.data(),
              dataSize
          );
    requireSuccess(dataResult, "无法认证附加数据");
}

bool lockMemory(void* address, std::size_t size) noexcept {
#if defined(_WIN32)
    return VirtualLock(address, size) != 0;
#elif defined(__unix__) || defined(__APPLE__)
    return mlock(address, size) == 0;
#else
    static_cast<void>(address);
    static_cast<void>(size);
    return false;
#endif
}

void unlockMemory(void* address, std::size_t size) noexcept {
#if defined(_WIN32)
    static_cast<void>(VirtualUnlock(address, size));
#elif defined(__unix__) || defined(__APPLE__)
    static_cast<void>(munlock(address, size));
#else
    static_cast<void>(address);
    static_cast<void>(size);
#endif
}

}  // namespace

class KiwiCipher::SecureKey final {
public:
    explicit SecureKey(const Key& source) : bytes_(source) {
        locked_ = lockMemory(bytes_.data(), bytes_.size());
    }

    ~SecureKey() {
        OPENSSL_cleanse(bytes_.data(), bytes_.size());
        if (locked_) {
            unlockMemory(bytes_.data(), bytes_.size());
        }
    }

    [[nodiscard]] const Key& bytes() const noexcept {
        return bytes_;
    }

    [[nodiscard]] bool isLocked() const noexcept {
        return locked_;
    }

private:
    Key bytes_{};
    bool locked_ = false;
};

KiwiCipher::KiwiCipher(Key key, Limits limits) : limits_(limits) {
    if (limits_.maxPlaintextSize
            > static_cast<std::size_t>(std::numeric_limits<int>::max()) - FORMAT_OVERHEAD) {
        OPENSSL_cleanse(key.data(), key.size());
        throw std::invalid_argument("最大明文长度超过 OpenSSL 单次处理上限");
    }
    if (limits_.maxAdditionalAuthenticatedDataSize
            > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        OPENSSL_cleanse(key.data(), key.size());
        throw std::invalid_argument("最大附加认证数据长度超过 OpenSSL 单次处理上限");
    }
    try {
        key_ = std::make_unique<SecureKey>(key);
    } catch (...) {
        OPENSSL_cleanse(key.data(), key.size());
        throw;
    }
    OPENSSL_cleanse(key.data(), key.size());
}

KiwiCipher::~KiwiCipher() = default;

bool KiwiCipher::isKeyMemoryLocked() const noexcept {
    return key_->isLocked();
}

KiwiCipher::Bytes KiwiCipher::encrypt(
    const Bytes& plaintext,
    const Bytes& additionalAuthenticatedData
) const {
    return encrypt(plaintext, additionalAuthenticatedData, CompressionMode::Disabled);
}

KiwiCipher::Bytes KiwiCipher::encrypt(
    const Bytes& plaintext,
    const Bytes& additionalAuthenticatedData,
    CompressionMode compressionMode
) const {
    if (plaintext.size() > limits_.maxPlaintextSize) {
        throw std::invalid_argument("明文超过配置的安全上限");
    }
    if (additionalAuthenticatedData.size()
            > limits_.maxAdditionalAuthenticatedDataSize) {
        throw std::invalid_argument("附加认证数据超过配置的安全上限");
    }
    checkedSize(additionalAuthenticatedData.size());
    CleansedBytes compressed;
    const Bytes* payload = &plaintext;
    std::uint8_t flags = 0;
    if (compressionMode == CompressionMode::Automatic
            && compressIfSmaller(plaintext, compressed)) {
        payload = &compressed.bytes;
        flags |= FLAG_COMPRESSED;
    }
    const int payloadSize = checkedSize(payload->size());
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    const auto issuedAt = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
    if (issuedAt < 0) {
        throw std::runtime_error("系统时间早于 Unix 纪元");
    }
    const auto header = formatHeader(
        flags,
        static_cast<std::uint64_t>(issuedAt),
        static_cast<std::uint32_t>(plaintext.size())
    );

    std::array<std::uint8_t, SALT_SIZE> salt{};
    std::array<std::uint8_t, NONCE_SIZE> nonce{};
    requireSuccess(RAND_bytes(salt.data(), static_cast<int>(salt.size())), "无法生成安全随机盐");
    requireSuccess(RAND_bytes(nonce.data(), static_cast<int>(nonce.size())), "无法生成安全随机 nonce");
    CleansedKey messageKey = deriveMessageKey(key_->bytes(), salt);

    const EVP_CIPHER* cipher = cipherAlgorithm();
    Context context = createContext();
    requireSuccess(
        EVP_EncryptInit_ex(context.get(), cipher, nullptr, nullptr, nullptr),
        "无法初始化 AES-256-GCM-SIV"
    );
    requireSuccess(
        EVP_EncryptInit_ex(
            context.get(),
            nullptr,
            nullptr,
            messageKey.bytes.data(),
            nonce.data()
        ),
        "无法设置消息子密钥和 nonce"
    );
    authenticate(context.get(), header, additionalAuthenticatedData, true);

    const int blockSize = EVP_CIPHER_get_block_size(cipher);
    Bytes encrypted(payload->size() + static_cast<std::size_t>(blockSize));
    int encryptedSize = 0;
    const std::uint8_t emptyInput = 0;
    const std::uint8_t* plaintextInput = payload->empty() ? &emptyInput : payload->data();
    requireSuccess(
        EVP_EncryptUpdate(
            context.get(),
            encrypted.data(),
            &encryptedSize,
            plaintextInput,
            payloadSize
        ),
        "加密失败"
    );

    int finalSize = 0;
    requireSuccess(
        EVP_EncryptFinal_ex(context.get(), encrypted.data() + encryptedSize, &finalSize),
        "加密收尾失败"
    );
    encrypted.resize(static_cast<std::size_t>(encryptedSize + finalSize));

    std::array<std::uint8_t, TAG_SIZE> tag{};
    requireSuccess(
        EVP_CIPHER_CTX_ctrl(
            context.get(),
            EVP_CTRL_AEAD_GET_TAG,
            static_cast<int>(tag.size()),
            tag.data()
        ),
        "无法生成认证标签"
    );

    Bytes output;
    output.reserve(FORMAT_OVERHEAD + encrypted.size());
    output.insert(output.end(), header.begin(), header.end());
    output.insert(output.end(), salt.begin(), salt.end());
    output.insert(output.end(), nonce.begin(), nonce.end());
    output.insert(output.end(), encrypted.begin(), encrypted.end());
    output.insert(output.end(), tag.begin(), tag.end());
    output.insert(output.end(), TRAILER.begin(), TRAILER.end());
    return output;
}

KiwiCipher::Bytes KiwiCipher::decrypt(
    const Bytes& input,
    const Bytes& additionalAuthenticatedData
) const {
    if (input.size() < MIN_CIPHERTEXT_SIZE) {
        throw std::invalid_argument("密文太短");
    }
    if (input.size() > limits_.maxPlaintextSize + FORMAT_OVERHEAD) {
        throw std::invalid_argument("密文超过配置的安全上限");
    }
    if (additionalAuthenticatedData.size()
            > limits_.maxAdditionalAuthenticatedDataSize) {
        throw std::invalid_argument("附加认证数据超过配置的安全上限");
    }
    if (!std::equal(MAGIC.begin(), MAGIC.end(), input.begin())) {
        throw std::invalid_argument("无效的密文标识");
    }
    if (input[MAGIC.size()] != FORMAT_VERSION) {
        throw std::invalid_argument("不支持的密文版本");
    }
    const std::uint8_t flags = input[MAGIC.size() + 1];
    if ((flags & static_cast<std::uint8_t>(~KNOWN_FLAGS)) != 0) {
        throw std::invalid_argument("密文包含未知格式标志");
    }
    if (!std::equal(TRAILER.begin(), TRAILER.end(), input.end() - TRAILER.size())) {
        throw std::invalid_argument("无效的密文尾标识");
    }

    std::array<std::uint8_t, HEADER_SIZE> header{};
    std::copy_n(input.data(), header.size(), header.data());
    const std::uint32_t originalSize = originalSizeOf(header);
    if (originalSize > limits_.maxPlaintextSize) {
        throw std::invalid_argument("密文声明的原始长度超过安全上限");
    }
    checkedSize(additionalAuthenticatedData.size());
    const std::size_t saltOffset = HEADER_SIZE;
    const std::size_t nonceOffset = saltOffset + SALT_SIZE;
    const std::size_t ciphertextOffset = nonceOffset + NONCE_SIZE;
    const std::size_t tagOffset = input.size() - TRAILER.size() - TAG_SIZE;
    const std::size_t ciphertextSize = tagOffset - ciphertextOffset;
    const int checkedCiphertextSize = checkedSize(ciphertextSize);

    std::array<std::uint8_t, SALT_SIZE> salt{};
    std::copy_n(input.data() + saltOffset, salt.size(), salt.data());
    const std::uint8_t* nonce = input.data() + nonceOffset;
    const std::uint8_t* ciphertext = input.data() + ciphertextOffset;
    const std::uint8_t* tag = input.data() + tagOffset;
    CleansedKey messageKey = deriveMessageKey(key_->bytes(), salt);

    const EVP_CIPHER* cipher = cipherAlgorithm();
    Context context = createContext();
    requireSuccess(
        EVP_DecryptInit_ex(context.get(), cipher, nullptr, nullptr, nullptr),
        "无法初始化 AES-256-GCM-SIV"
    );
    requireSuccess(
        EVP_DecryptInit_ex(
            context.get(),
            nullptr,
            nullptr,
            messageKey.bytes.data(),
            nonce
        ),
        "无法设置消息子密钥和 nonce"
    );
    requireSuccess(
        EVP_CIPHER_CTX_ctrl(
            context.get(),
            EVP_CTRL_AEAD_SET_TAG,
            static_cast<int>(TAG_SIZE),
            const_cast<std::uint8_t*>(tag)
        ),
        "无法设置认证标签"
    );
    authenticate(context.get(), header, additionalAuthenticatedData, false);

    const int blockSize = EVP_CIPHER_get_block_size(cipher);
    Bytes payload(ciphertextSize + static_cast<std::size_t>(blockSize));
    int payloadSize = 0;
    const std::uint8_t emptyInput = 0;
    const std::uint8_t* ciphertextInput = ciphertextSize == 0 ? &emptyInput : ciphertext;
    requireSuccess(
        EVP_DecryptUpdate(
            context.get(),
            payload.data(),
            &payloadSize,
            ciphertextInput,
            checkedCiphertextSize
        ),
        "解密失败"
    );

    int finalSize = 0;
    if (EVP_DecryptFinal_ex(context.get(), payload.data() + payloadSize, &finalSize) != 1) {
        throw std::invalid_argument("密文认证失败");
    }

    payload.resize(static_cast<std::size_t>(payloadSize + finalSize));
    if ((flags & FLAG_COMPRESSED) == 0) {
        if (payload.size() != originalSize) {
            OPENSSL_cleanse(payload.data(), payload.size());
            throw std::invalid_argument("密文原始长度不一致");
        }
        return payload;
    }

    Bytes plaintext(originalSize);
    try {
        const unsigned long long frameSize = ZSTD_getFrameContentSize(
            payload.data(),
            payload.size()
        );
        if (frameSize == ZSTD_CONTENTSIZE_ERROR
                || frameSize == ZSTD_CONTENTSIZE_UNKNOWN
                || frameSize != originalSize) {
            throw std::invalid_argument("压缩帧原始长度不一致");
        }
        const std::size_t decompressedSize = ZSTD_decompress(
            plaintext.data(),
            plaintext.size(),
            payload.data(),
            payload.size()
        );
        if (ZSTD_isError(decompressedSize) != 0 || decompressedSize != originalSize) {
            throw std::invalid_argument("压缩数据无效");
        }
    } catch (...) {
        OPENSSL_cleanse(payload.data(), payload.size());
        OPENSSL_cleanse(plaintext.data(), plaintext.size());
        throw;
    }
    OPENSSL_cleanse(payload.data(), payload.size());
    return plaintext;
}

}  // namespace taotao::crypto
