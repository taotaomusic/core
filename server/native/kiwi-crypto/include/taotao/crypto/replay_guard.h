#pragma once

#include "taotao/crypto/kiwi_cipher.h"

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>

namespace taotao::crypto {

using ReplayId = std::array<std::uint8_t, 32>;

/**
 * 防重放存储接口。
 *
 * consume 必须是原子操作：同一个 ReplayId 在有效期内只能有一个调用返回 true。
 * 多实例服务应使用带唯一约束的数据库或 Redis SET NX 实现该接口。
 */
class ReplayStore {
public:
    virtual ~ReplayStore() = default;

    virtual bool consume(
        const ReplayId& id,
        std::chrono::milliseconds validFor
    ) = 0;
};

/** 单进程线程安全实现；容量满时拒绝新消息，不会提前驱逐仍有效的记录。 */
class InMemoryReplayStore final : public ReplayStore {
public:
    explicit InMemoryReplayStore(std::size_t capacity = 100000);
    ~InMemoryReplayStore() override;

    InMemoryReplayStore(const InMemoryReplayStore&) = delete;
    InMemoryReplayStore& operator=(const InMemoryReplayStore&) = delete;
    InMemoryReplayStore(InMemoryReplayStore&&) = delete;
    InMemoryReplayStore& operator=(InMemoryReplayStore&&) = delete;

    bool consume(
        const ReplayId& id,
        std::chrono::milliseconds validFor
    ) override;

    [[nodiscard]] std::size_t size() const;

private:
    class State;
    std::unique_ptr<State> state_;
};

class ReplayRejected final : public std::invalid_argument {
public:
    ReplayRejected();
};

/**
 * 认证并解密一条只能使用一次的消息。
 *
 * 只有通过 AEAD 认证后才计算 ReplayId 并调用 store.consume，避免无效输入占满缓存。
 * 存储拒绝时会先清除已解密的明文，再抛出统一异常。
 */
[[nodiscard]] KiwiCipher::Bytes decryptOnce(
    const KiwiCipher& cipher,
    ReplayStore& store,
    const KiwiCipher::Bytes& ciphertext,
    std::chrono::milliseconds maximumAge,
    const KiwiCipher::Bytes& additionalAuthenticatedData = {},
    std::chrono::milliseconds allowedFutureSkew = std::chrono::seconds(30)
);

}  // namespace taotao::crypto
