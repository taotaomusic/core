#include "taotao/crypto/replay_guard.h"

#include <openssl/crypto.h>
#include <openssl/evp.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <mutex>
#include <queue>
#include <unordered_map>
#include <utility>
#include <vector>

namespace taotao::crypto {
namespace {

constexpr std::array<std::uint8_t, 22> REPLAY_DOMAIN = {
    't', 'a', 'o', 't', 'a', 'o', '/', 'k', 'i', 'w', 'i', '/', 'r', 'e', 'p', 'l', 'a', 'y', '/', 'v', '1', 0
};

class DigestContext final {
public:
    DigestContext() : context_(EVP_MD_CTX_new()) {
        if (!context_) {
            throw std::runtime_error("无法创建重放摘要上下文");
        }
    }

    ~DigestContext() {
        EVP_MD_CTX_free(context_);
    }

    EVP_MD_CTX* get() const noexcept {
        return context_;
    }

private:
    EVP_MD_CTX* context_;
};

ReplayId replayIdOf(
    const KiwiCipher::Bytes& ciphertext,
    const KiwiCipher::Bytes& additionalAuthenticatedData
) {
    DigestContext digest;
    if (EVP_DigestInit_ex(digest.get(), EVP_sha256(), nullptr) != 1) {
        throw std::runtime_error("无法初始化重放摘要");
    }

    const std::uint64_t aadSize = additionalAuthenticatedData.size();
    std::array<std::uint8_t, 8> encodedSize{};
    for (std::size_t index = 0; index < encodedSize.size(); ++index) {
        const std::size_t shift = (encodedSize.size() - 1 - index) * 8;
        encodedSize[index] = static_cast<std::uint8_t>(aadSize >> shift);
    }

    const auto update = [&](const std::uint8_t* data, std::size_t size) {
        if (size != 0 && EVP_DigestUpdate(digest.get(), data, size) != 1) {
            throw std::runtime_error("无法计算重放摘要");
        }
    };
    update(REPLAY_DOMAIN.data(), REPLAY_DOMAIN.size());
    update(encodedSize.data(), encodedSize.size());
    update(additionalAuthenticatedData.data(), additionalAuthenticatedData.size());
    update(ciphertext.data(), ciphertext.size());

    ReplayId id{};
    unsigned int outputSize = 0;
    if (EVP_DigestFinal_ex(digest.get(), id.data(), &outputSize) != 1 || outputSize != id.size()) {
        throw std::runtime_error("无法完成重放摘要");
    }
    return id;
}

std::uint64_t issuedAtMillisOf(const KiwiCipher::Bytes& ciphertext) {
    constexpr std::size_t OFFSET = 5;
    constexpr std::size_t SIZE = 8;
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < SIZE; ++index) {
        value = (value << 8) | ciphertext[OFFSET + index];
    }
    return value;
}

std::uint64_t systemNowMillis() {
    const auto nowDuration = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    );
    if (nowDuration.count() < 0) {
        throw ReplayRejected();
    }
    return static_cast<std::uint64_t>(nowDuration.count());
}

void validateTimestamp(
    std::uint64_t issuedAt,
    std::uint64_t now,
    std::chrono::milliseconds maximumAge,
    std::chrono::milliseconds allowedFutureSkew
) {
    if (issuedAt > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
        throw ReplayRejected();
    }
    const auto maximumAgeCount = static_cast<std::uint64_t>(maximumAge.count());
    const auto futureSkewCount = static_cast<std::uint64_t>(allowedFutureSkew.count());
    if (issuedAt > now && issuedAt - now > futureSkewCount) {
        throw ReplayRejected();
    }
    if (now > issuedAt && now - issuedAt >= maximumAgeCount) {
        throw ReplayRejected();
    }
}

[[noreturn]] void clearAndReject(KiwiCipher::Bytes& plaintext) {
    OPENSSL_cleanse(plaintext.data(), plaintext.size());
    throw ReplayRejected();
}

struct ReplayIdHash {
    std::size_t operator()(const ReplayId& id) const noexcept {
        std::size_t value = 0;
        constexpr std::size_t copySize = std::min(sizeof(value), sizeof(ReplayId));
        std::memcpy(&value, id.data(), copySize);
        return value;
    }
};

}  // namespace

class InMemoryReplayStore::State final {
public:
    using Clock = std::chrono::steady_clock;
    using TimePoint = Clock::time_point;

    struct ExpiringId {
        TimePoint expiresAt;
        ReplayId id;
    };

    struct ExpiresLater {
        bool operator()(const ExpiringId& left, const ExpiringId& right) const noexcept {
            return left.expiresAt > right.expiresAt;
        }
    };

    explicit State(std::size_t requestedCapacity) : capacity(requestedCapacity) {}

    void removeExpired(TimePoint now) {
        while (!expiryQueue.empty() && expiryQueue.top().expiresAt <= now) {
            const ExpiringId expired = expiryQueue.top();
            expiryQueue.pop();
            const auto found = entries.find(expired.id);
            if (found != entries.end() && found->second == expired.expiresAt) {
                entries.erase(found);
            }
        }
    }

    const std::size_t capacity;
    mutable std::mutex mutex;
    std::unordered_map<ReplayId, TimePoint, ReplayIdHash> entries;
    std::priority_queue<ExpiringId, std::vector<ExpiringId>, ExpiresLater> expiryQueue;
};

InMemoryReplayStore::InMemoryReplayStore(std::size_t capacity)
    : state_(std::make_unique<State>(capacity)) {
    if (capacity == 0) {
        throw std::invalid_argument("防重放缓存容量必须大于 0");
    }
}

InMemoryReplayStore::~InMemoryReplayStore() = default;

bool InMemoryReplayStore::consume(
    const ReplayId& id,
    std::chrono::milliseconds validFor
) {
    if (validFor <= std::chrono::milliseconds::zero()) {
        throw std::invalid_argument("防重放有效期必须大于 0");
    }

    const State::TimePoint now = State::Clock::now();
    const State::TimePoint expiresAt = now + validFor;
    std::lock_guard<std::mutex> lock(state_->mutex);
    state_->removeExpired(now);

    if (state_->entries.find(id) != state_->entries.end()) {
        return false;
    }
    if (state_->entries.size() >= state_->capacity) {
        return false;
    }

    state_->entries.emplace(id, expiresAt);
    state_->expiryQueue.push({expiresAt, id});
    return true;
}

std::size_t InMemoryReplayStore::size() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    state_->removeExpired(State::Clock::now());
    return state_->entries.size();
}

ReplayRejected::ReplayRejected() : std::invalid_argument("消息已被使用或暂时不可接受") {}

KiwiCipher::Bytes decryptOnce(
    const KiwiCipher& cipher,
    ReplayStore& store,
    const KiwiCipher::Bytes& ciphertext,
    std::chrono::milliseconds maximumAge,
    const KiwiCipher::Bytes& additionalAuthenticatedData,
    std::chrono::milliseconds allowedFutureSkew
) {
    constexpr auto MAXIMUM_AGE = std::chrono::hours(24 * 365);
    constexpr auto MAXIMUM_FUTURE_SKEW = std::chrono::hours(1);
    if (maximumAge <= std::chrono::milliseconds::zero()
            || maximumAge > MAXIMUM_AGE) {
        throw std::invalid_argument("消息最大年龄必须在 1 毫秒到 365 天之间");
    }
    if (allowedFutureSkew < std::chrono::milliseconds::zero()
            || allowedFutureSkew > MAXIMUM_FUTURE_SKEW) {
        throw std::invalid_argument("允许的未来时钟偏差必须在 0 到 1 小时之间");
    }

    // 时间戳是公开但尚未认证的字段。先作只用于拒绝的廉价预筛，避免明显过期的
    // 数据消耗 AEAD；通过预筛不代表消息可信，认证后必须再次校验。
    if (ciphertext.size() < KiwiCipher::FORMAT_OVERHEAD) {
        throw ReplayRejected();
    }
    const std::uint64_t issuedAt = issuedAtMillisOf(ciphertext);
    const std::uint64_t preflightNow = systemNowMillis();
    validateTimestamp(issuedAt, preflightNow, maximumAge, allowedFutureSkew);

    KiwiCipher::Bytes plaintext = cipher.decrypt(ciphertext, additionalAuthenticatedData);
    try {
        validateTimestamp(
            issuedAt,
            systemNowMillis(),
            maximumAge,
            allowedFutureSkew
        );
    } catch (const ReplayRejected&) {
        clearAndReject(plaintext);
    }

    const ReplayId id = replayIdOf(ciphertext, additionalAuthenticatedData);
    // 存储有效期以服务端收到时间为基准，不信任客户端签发时间。
    if (!store.consume(id, maximumAge + allowedFutureSkew)) {
        clearAndReject(plaintext);
    }
    return plaintext;
}

}  // namespace taotao::crypto
