# Kiwi Crypto 加密模块

这是桃桃音乐可复用的 C++17 加密模块，底层使用 OpenSSL 3.6 的 HKDF-SHA256、AES-256-GCM-SIV 和可选的 Zstandard 压缩。它提供消息级子密钥、完整性认证、格式版本、附加认证数据（AAD，Additional Authenticated Data）和可替换的防重放存储。

## 目录

```text
include/taotao/crypto/kiwi_cipher.h  对外公开 API
include/taotao/crypto/replay_guard.h 防重放 API
src/kiwi_cipher.cpp                  OpenSSL 实现
src/replay_guard.cpp                 原子重放登记
tests/kiwi_cipher_test.cpp           独立测试
tests/side_channel_timing_test.cpp   时序侧信道烟雾测试
tests/performance_test.cpp           性能回退测试
cmake/                               CMake 包配置
```

## 构建和测试

在本目录执行：

```powershell
cmake -S . -B build -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  -DKIWI_CRYPTO_WARNINGS_AS_ERRORS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

只运行安全测试：

```powershell
ctest --test-dir build -L security --output-on-failure
```

只运行性能回退测试：

```powershell
ctest --test-dir build -L performance --output-on-failure
```

默认生成静态库。需要 DLL 时增加 `-DBUILD_SHARED_LIBS=ON`；CMake 已启用 Windows 符号自动导出。

## 在另一个 CMake 工程中使用

同一源码树内可直接引用：

```cmake
add_subdirectory(path/to/kiwi-crypto)
target_link_libraries(your_target PRIVATE taotao::kiwi_crypto)
```

也可以先安装：

```powershell
cmake --install build --prefix C:/tools/kiwi-crypto
```

使用方：

```cmake
find_package(KiwiCrypto 1 CONFIG REQUIRED)
target_link_libraries(your_target PRIVATE taotao::kiwi_crypto)
```

## API 示例

```cpp
#include <taotao/crypto/kiwi_cipher.h>

taotao::crypto::KiwiCipher::Key key = /* 从安全配置读取 32 字节密钥 */;
taotao::crypto::KiwiCipher cipher(key);

taotao::crypto::KiwiCipher::Bytes body = {'h', 'e', 'l', 'l', 'o'};
taotao::crypto::KiwiCipher::Bytes context = {
    'P', 'O', 'S', 'T', ' ', '/', 'a', 'p', 'i', '/', 'v', '1', '/', 's', 'e', 'c', 'u', 'r', 'e'
};

auto encrypted = cipher.encrypt(body, context);
auto decrypted = cipher.decrypt(encrypted, context);
```

对独立、不会与攻击者可控字段混合的较大数据，可显式启用自动压缩：

```cpp
auto encrypted = cipher.encrypt(
    body,
    context,
    taotao::crypto::KiwiCipher::CompressionMode::Automatic
);
```

模块使用 Zstandard level 1，每条消息独立压缩；只有压缩结果确实更小时才设置压缩标志，否则自动保存原文。短文本不会因压缩帧开销而膨胀。不要把秘密值和攻击者能反复猜测的输入拼在一起压缩，否则密文长度可能形成压缩侧信道。

AAD 不会出现在密文中。推荐至少绑定接口名和协议版本，防止一条接口的合法密文被原样转移到另一条接口。解密时必须提供与加密时完全一致的 AAD。

需要拒绝同一合法请求重复提交时，使用防重放入口：

```cpp
#include <taotao/crypto/replay_guard.h>

taotao::crypto::InMemoryReplayStore replayStore(100000);
auto plaintext = taotao::crypto::decryptOnce(
    cipher,
    replayStore,
    encrypted,
    std::chrono::minutes(5),
    context
);
```

内存存储仅适合单进程。多实例部署必须实现持久化 `ReplayStore`，具体要求见 [SECURITY.md](SECURITY.md)。

## 密文格式与密钥规则

版本 3 格式：

```text
tao | version(1B) | flags(1B) | issuedAtMs(8B) | originalSize(4B)
    | salt(16B) | nonce(12B) | ciphertext(NB) | tag(16B) | yuan
```

`tao` 和 `yuan` 都是公开格式标识，并且都会参与认证。它们能提供格式辨识和一定程度的外观迷惑，但不增加密码学强度；真正的安全性来自 HKDF、AES-256-GCM-SIV、AAD、签发时间与防重放存储。

- 密钥必须是 32 字节随机值，不得把口令直接填入 `Key`。
- 如果密钥来自口令，应在模块外使用 scrypt 或 Argon2id 派生，并保存随机盐。
- 密钥不得硬编码到业务源码、日志或仓库配置中。
- 每条消息使用随机盐派生独立子密钥，并生成新的 96 位随机 nonce。
- 密钥轮换时应由上层协议保存 key ID；当前密文格式不内嵌业务密钥标识。
- 认证失败统一抛出 `std::invalid_argument("密文认证失败")`，调用方不得返回可区分的内部错误细节。
- 当前版本最低要求 OpenSSL 3.6。
- 压缩依赖 Zstandard 1.5；解密前先认证，解压时严格限制并核对原始长度，防止解压炸弹。
- 默认限制明文为 16 MiB、AAD 为 64 KiB；可通过 `KiwiCipher::Limits` 收紧或调整，但不能超过 OpenSSL 单次 `int` 长度边界。
- OpenSSL cipher/KDF 算法句柄由进程安全缓存，每次消息仍创建独立上下文，兼顾线程安全与调用开销。

## 与 NestJS 的边界

该目录目前产出原生 C++ 库，不会自动进入 NestJS 的 TypeScript 构建。服务接口要直接调用它时，还需要增加 Node-API 包装层；不要通过拼接 shell 命令调用测试程序。包装层应只负责类型转换和异常映射，密钥仍由服务端安全配置提供。
