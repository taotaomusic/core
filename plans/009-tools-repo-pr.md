# PR：传输加密引入按设备派生的会话密钥（protocol v+1）

> 贴到独立仓库 `hdppppppp/tools`。本文件是给那边的改动规格，不是本仓库代码。
> 目标：让会话密钥按真机设备号派生，并把设备号绑进 AAD，使协议即便被逆向，
> 攻击者仍需逐台真机提取设备号才能冒充该机。消费侧（music / music-server）
> 等这里 CI 出新产物后再接线。

## 一、协议变更总览

1. **会话密钥按设备派生**：`per_device_psk = HKDF-Expand(HKDF-Extract(salt = device_id_bytes, ikm = embedded_psk), info = "taotao-device-v1", L = 32)`。
   - `device_id_bytes` = 设备号 UTF-8 字节（Android `ANDROID_ID`、Windows `MachineGuid`）。
   - HKDF 哈希沿用现有套件（与握手用的一致，别引入第二种）。
2. **AAD 绑设备号**：`aad_context(method, path_and_query, device_id)`，把 `device_id`
   追加进现有 AAD 拼接（末尾，长度前缀，避免与 path 边界歧义）。
3. **握手携带设备号**：ClientHello 里新增 `device_id` 字段；`Server::accept` 读出后
   现算 `per_device_psk` 再验证握手。
4. **`protocol_version()` 返回值 +1**：报文不兼容，服务端按版本分派；旧版客户端一律拒绝。

## 二、各绑定签名改动

### Rust 核心（crate 内）
- `Client::new(psk_id: &str, psk_hex: &str, device_id: &str)` —— 构造时做 per-device 派生，
  内部保存派生后的密钥而非原始 `psk_hex`。
- `fn aad_context(method: &str, path_and_query: &str, device_id: &str) -> Vec<u8>`。
- `Server`：ClientHello 解析出 `device_id`；用同一 HKDF 现算 per-device PSK。
  `put_psk(psk_id, psk_hex)` 保持登记原始 PSK，派生在 accept 时按报文里的 `device_id` 做。
- `protocol_version()` 常量 +1。

### wasm（`taotao_crypto.d.ts` 对应导出）
- `Client` 构造：`new Client(psk_id, psk_hex, device_id)`。
- `aad_context(method, path_and_query, device_id)`。
- Web 分享页**本次不接入**：wasm 仍编译导出这些符号，但调用方不动；
  匿名握手模式留待后续（不在本 PR）。

### Node addon（`taotao_crypto.node`）
- `Client` 构造与 `aadContext` 同步加 `device_id` 形参。
- 服务端 `Server.accept` 从 ClientHello 拿 `device_id`，签名不变（设备号在报文内）。

### Android JNI（`libtaotao_crypto.so`）
- 导出与 Kotlin 绑定匹配的 JNI 符号，`Client` 构造签名加 `device_id: String`，
  `aadContext` 加 `deviceId`。消费侧 `System.loadLibrary("taotao_crypto")` 后按这组
  符号写 external fun，符号名/签名需在本 PR 定死并写进 `docs/design.md`。

### Windows JVM（`taotao_crypto.dll`）
- 同 Android，导出同名 JNI 符号，桌面端 `System.load` 后共用同一 Kotlin 绑定接口。

## 三、兼容性与迁移

- **无灰度共存**：`protocol_version +1` 后服务端只认新版握手；旧版客户端握手失败，
  自动回退明文路径（消费侧加密「可选头触发」，握手失败即不加密，不阻断功能）。
- **device_id 缺失**：客户端取不到硬件号时传固定占位串（消费侧已定
  `unknown-android-device` / `fallback-<uuid>`），协议侧不特殊处理，照常派生。
- 测试向量：新增「同 psk、不同 device_id → 不同会话密钥」「AAD 含 device_id 后
  换 device_id 则 open 失败」两组跨端一致性用例，四端都要过（防协议漂移）。

## 四、CI / 发布

- `ci.yml`：占位密钥编四端产物 + 跑跨端一致性测试向量。
- `release.yml`：打 tag、注真密钥、发四端产物 + `SHA256SUMS.txt`（消费侧 `fetch-crypto.ps1` 校验）。
- `docs/design.md`：更新 HKDF 参数、AAD 拼接顺序、握手报文新增字段、JNI 符号表、
  `protocol_version` 新值。

## 五、验收（本仓库 CI）

- 四端产物齐全、`SHA256SUMS.txt` 生成。
- 跨端一致性测试全绿（含上面两组新向量）。
- `protocol_version()` 已 +1 并写进 `docs/design.md`。

产物 release 后，music / music-server 用 `tools/fetch-crypto.ps1 -Version <新tag>` 拉取接线。
