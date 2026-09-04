# 原生加密模块

## 定位

`server/native/kiwi-crypto/` 是独立的 C++17 加密模块，CMake target 为 `taotao::kiwi_crypto`。它当前不会自动进入 NestJS 的 TypeScript 构建；接口需要直接调用时，应增加 Node-API 包装层，不得通过 shell 启动测试程序。

模块负责：

- 机密性和完整性认证；
- 接口上下文 AAD 绑定；
- 消息签发时间和新鲜度检查；
- 可替换的原子防重放存储；
- 主密钥内存锁定和临时子密钥清除；
- 静态库、DLL、安装包与消费方 CMake 配置。

模块不负责：

- 从环境变量、KMS 或 HSM 加载生产密钥；
- NestJS DTO、错误信封或访问令牌鉴权；
- 多实例防重放数据库表或 Redis 连接；
- 物理功耗、电磁、故障注入和微架构攻击认证。

## 版本 3 格式

```text
tao | version(1B) | flags(1B) | issuedAtMs(8B) | originalSize(4B)
    | salt(16B) | nonce(12B) | ciphertext(NB) | tag(16B) | yuan
```

- 数值按大端序编码。
- `tao` 和 `yuan` 是公开固定标识，并一起参与认证。
- 固定标识只用于格式辨识和外观迷惑，不增加密码学强度。
- 尾部 `yuan` 的 ASCII 十六进制是 `7975616e`。
- `flags` 当前只定义 bit 0：`1` 表示 ciphertext 解密后是 Zstandard 帧。
- `originalSize` 是压缩前长度，最大受 `KiwiCipher::Limits` 约束。
- 任何头、时间、盐、nonce、密文、标签、尾或 AAD 的修改都会使认证失败。
- 解析器只接受偏移 0 的 `tao`，并要求 `yuan` 恰好位于输入末尾；不搜索标识，也不忽略前置或尾随字节。
- 密文段长度由容器总长度减去固定开销得到；压缩时不能用 `originalSize` 推算密文长度。
- 版本 1、2 尚未上线，不保留兼容解密分支，避免长期携带旧协议攻击面。

## 加密链路

```text
32 字节主密钥
  + 16 字节随机盐（后 8 字节混入进程内原子序列）
  + 固定域分离信息
          │
          ▼
    HKDF-SHA256
          │
          ▼
32 字节消息子密钥 + 12 字节随机 nonce
          │
          ▼
 AES-256-GCM-SIV
          │
          ▼
版本 3 密文容器
```

每条消息派生独立子密钥。GCM-SIV 能降低 nonce 意外复用的破坏，但不能代替正确随机数和密钥轮换。原子序列只提供同一进程生命周期内的随机盐重复回退，不保证跨进程或重启全局唯一；需要严格全局序号时由接口层持久化并通过 AAD 绑定。

## 可选压缩

调用方必须显式传入 `CompressionMode::Automatic` 才会压缩。模块使用 Zstandard level 1，每条消息独立处理，并且只有压缩结果确实更小时才使用；短消息和不可压缩数据保持原样。

压缩前加密会暴露长度关系。禁止把 Cookie、令牌、验证码、API Key 等秘密与攻击者可控字符串放在同一明文中压缩，否则可能形成 CRIME/BREACH 类长度侧信道。AAD 不参与压缩。无法证明数据边界安全时保持默认关闭。

解密严格按受认证的 `originalSize` 分配输出，并核对 Zstandard 帧声明长度和实际解压长度，阻止解压炸弹。接口层仍需设置请求体上限。

OpenSSL cipher 与 KDF 算法句柄在进程内缓存，消息级上下文仍然独立，因此减少 provider 重复查询，同时保持并发安全。默认明文上限是 16 MiB，AAD 上限是 64 KiB，接口层还应设置更贴合业务的 body limit。

## AAD 规则

建议使用稳定、规范化且带版本的字符串，例如：

```text
taotao-api/v1|POST|/api/v1/example/submit
```

不要把查询参数原始顺序、大小写不稳定的 header 或未经规范化的 URL 直接作为 AAD，否则加密端和解密端容易得到不同字节。AAD 不写入密文，双方必须通过接口契约知道它的准确内容。

## 防重放

需要一次性语义的接口必须调用 `decryptOnce`，不能调用普通 `decrypt`。

执行顺序：

1. 对公开、尚未认证的签发时间作廉价拒绝预筛；
2. 验证完整 AEAD 并解密；
3. 再次检查已经受认证的签发时间未超过 `maximumAge`，未来时间未超过允许时钟偏差；
4. 对 AAD 和完整密文计算 SHA-256 Replay ID；
5. 以服务端接收时间为基准，通过 `ReplayStore::consume` 原子登记 `maximumAge + allowedFutureSkew`；
6. 只有首次登记成功才返回明文。

`InMemoryReplayStore` 只适合单进程开发或单实例服务。生产多实例必须实现持久化存储：

```sql
INSERT INTO crypto_replay (replay_id, expires_at)
VALUES ($1, $2)
ON CONFLICT DO NOTHING;
```

`expires_at` 必须由服务端当前时间加 `maximumAge + allowedFutureSkew` 计算，不能信任客户端 `issuedAtMs`。接口层必须把格式错误、认证失败、过期和重放统一映射为同一种外部错误，避免形成可区分的响应 oracle。

必须根据受影响行数判断是否首次消费，不能先 `SELECT` 再 `INSERT`。Redis 可使用 `SET key value NX PX <毫秒>`。过期记录需要后台清理，但清理延迟不能让仍有效记录提前消失。

## 构建与测试

在 `server/native/kiwi-crypto/` 执行：

```powershell
cmake -S . -B build -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  -DKIWI_CRYPTO_WARNINGS_AS_ERRORS=ON `
  -DKIWI_CRYPTO_SIDE_CHANNEL_TESTS=ON
cmake --build build
ctest --test-dir build --output-on-failure
```

构建 DLL：

```powershell
cmake -S . -B build-shared -G Ninja `
  -DCMAKE_BUILD_TYPE=Release `
  -DBUILD_SHARED_LIBS=ON
cmake --build build-shared
ctest --test-dir build-shared --output-on-failure
```

测试分组：

```powershell
ctest --test-dir build -L security --output-on-failure
ctest --test-dir build -L performance --output-on-failure
```

性能测试使用 4 KiB 消息，覆盖加密、HKDF、分配和解密。它设置宽松的 10 MiB/s 回退门槛，只用于发现句柄重复加载等严重退化，不用于跨机器排名，也不等价于实际电池功耗测量。

## 性能与耗电策略

- 只做一次 HKDF 和一次 AEAD，不叠加无意义的“双重加密”。
- 压缩使用 Zstandard level 1 且仅在结果变小时保留，减少网络传输耗电而不过度消耗 CPU。
- 缓存不可变算法描述符，不缓存或共享消息上下文。
- 优先使用 OpenSSL provider 的平台优化实现。
- 接口按真实业务收紧明文、AAD、并发和频率限制。
- 大文件不要整块塞入当前单次消息 API；应设计带序号和总长度认证的分块协议。
- 性能变化同时看吞吐、操作数和目标设备耗电，桌面基准不能替代 Android 真机能耗测试。

## 生产密钥

- 使用 CSPRNG 生成 32 字节随机主密钥，不能使用 README 示例密钥。
- 密钥通过 KMS、HSM、受控环境变量或受权限保护的文件加载，不写入数据库普通字段、源码和日志。
- 服务启动时检查 `isKeyMemoryLocked()`；高威胁环境下失败应拒绝启动。
- 密钥轮换需要在上层协议保存 key ID；当前版本 3 容器不内嵌业务 key ID。
- 部署环境还需提供 Zstandard 1.5；静态链接或 DLL 策略必须与消费方统一。
- OpenSSL 最低版本为 3.6；部署 DLL 时必须携带同一工具链对应的运行库。

更完整的威胁边界见 [`server/native/kiwi-crypto/SECURITY.md`](../native/kiwi-crypto/SECURITY.md)。
