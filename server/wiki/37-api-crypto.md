# 传输加密协议

[返回文档中心](README.md)

最后更新:2026-09-30

传输层加密覆盖 `/api/v1/**`:带 `X-Taotao-Crypto` 头的请求在 body parser 之前由中间件逐块 AEAD 解密,无加密头的请求完全透明。**加密是可选增强而非强依赖**——链路未启用(产物缺失或协议版本不符)时所有接口照常走明文;但已接入加密的白名单接口(强制加密清单)在链路启用后**拒绝明文降级**。源码在 `server/src/crypto/`,加密层 Rust 源码在 monorepo `crypto-src/`(改动流程见 [README.md](README.md) 维护规则第 6 条)。

具体业务码以 `crypto.controller.ts`、`crypto.middleware.ts` 和 `native-loader.ts` 的实际实现为准。

## 1. 握手与 PSK 下发

```http
POST /api/v1/crypto/handshake
Content-Type: application/json

{ "clientHello": "<base64>", "deviceId": "<设备号>" }
```

- `@Public()` 显式公开:握手时客户端尚无会话,不能要求登录。
- 成功 HTTP 200:`{ "serverHello": "<base64>" }`。
- `deviceId` 折进握手密钥(**协议 v2,握手密钥绑定设备号**):Android 取 `ANDROID_ID`、Windows 取 `MachineGuid`;缺失按空串处理(等价不绑定)。
- 走 JSON 而不是裸字节:握手请求本身不加密、量小,base64 让它在既有 JSON 中间件里通行无阻。

**PSK 由后端动态下发**,客户端不再内嵌密钥:

```http
GET /api/v1/crypto/psk
Authorization: Bearer <accessToken>
```

- **不加 `@Public()`,受全局访问令牌守卫保护**:客户端登录后用它拿 `{ pskId, pskHex }`,再以 `clientNew(pskId, pskHex, deviceId)` 握手——两端用的是同一把(后端这把),密钥不可能对不上,`.so` 内嵌钥易被提取的问题也一并消除。
- 未启用加密时返回 503/5031,客户端回退明文。
- 安全权衡:下发经 HTTPS + 登录令牌保护,此层不再是独立于 TLS 的第二把秘密,主要提供设备绑定与抗一般篡改。

## 2. 失败语义

| 场景 | 响应 | 客户端行为 |
| --- | --- | --- |
| 服务端未启用(产物缺失或协议版本 <2) | 503/5031「传输加密未启用」 | 回退明文,不阻断功能 |
| `clientHello` 缺失或非法 base64 | 400/4006 | 修正报文 |
| device_id 与 PSK 不匹配、报文非法 | 400/4013「握手被拒绝」 | 检查设备号来源与 `/crypto/psk` 下发的密钥版本 |
| 强制加密白名单内的接口收到明文请求(且链路已启用) | 426/4007「此接口要求加密访问」 | 升级客户端或接入加密链路 |
| 加密头格式错误、解密失败 | 400/4006 | 重新握手后重放请求 |
| 加密会话过期或序号不连续(请求阶段) | 409/4091「加密会话失效,请重新握手」 | 重新握手后重放请求 |

PSK **不存在「未配置即关闭加密」的状态**:`CRYPTO_PSK_HEX` 留空时服务端启动会随机生成一把并照常启用加密(仅打 WARN 提示「重启会变、多实例不一致」),503/5031 只对应产物缺失或协议版本不符。

## 3. 中间件约束

- AAD 绑定 `method + path + query`,**改路由形状会直接导致解密失败**(400/4006)。
- `RAW_BODY_PATHS` 白名单(`app/admin/releases`、`app/admin/patches` 上传)与 `*/play` 音频流不参与解密。
- **强制加密白名单**:命中且链路已启用时,无加密头的明文请求被拒 426/4007(防降级攻击)。当前只有 `GET /api/v1/favorites`,随客户端逐个接入再扩;白名单外的接口仍对明文透明,不影响 Web / 旧客户端 / 未接入的接口。
- 对 `/api/v1/**` 请求默认打一行「明文 / 加密」可观测日志(带明文头 `X-Taotao-Device` 上报的机型);设 `CRYPTO_REQUEST_LOG=off` 关闭。
- 解密中间件必须挂载在 body parser **之前**,否则拿到的是密文(挂载顺序见 [11-architecture-modules.md](11-architecture-modules.md))。
- 服务端会话在内存中管理,每分钟清理过期会话;**重启后客户端需重新握手**(409/4091 引导),多实例部署时各实例会话独立。

## 4. 配置与产物

- 配置:`CRYPTO_PSK_ID`(留空回落 `prod-v1`)与 `CRYPTO_PSK_HEX`(留空时**随机生成一把**,加密仍启用)。两者只在需要与外部工具联调时才必须手工对齐,客户端一律以 `/crypto/psk` 下发的为准。完整说明见 [21-configuration.md](21-configuration.md)。
- 产物:`crypto/dist/` 只放四端编译产物;本地**不需要** Rust / Android NDK / wasm-bindgen 交叉编译环境。在项目根目录执行 `powershell tools/fetch-crypto.ps1`,按 [README.md](README.md) 的仓库架构从 GitHub Release 拉取对应平台产物到 `crypto/dist/node/<platform>/taotao_crypto.node`(带 SHA256 校验)。
- 产物缺失时服务只是启动 WARN 并按明文链路运行,不阻断启动;强制加密白名单在链路未启用时也不生效(明文照常放行)。
- 改加密协议去 `crypto-src/` 改(由 core 单仓根 CI 的 `crypto-src/**` 路径过滤触发交叉编译),不要动 `crypto/dist/` 里的产物。

## 5. 本地验证加密链路

配置好产物后,`POST /api/v1/crypto/handshake` 应返回 `serverHello`,已登录客户端调 `GET /api/v1/crypto/psk` 应返回 `{pskId, pskHex}`;产物缺失的实例,两者固定 503/5031,可用于证明明文链路健在。

开启 `CRYPTO_REQUEST_LOG`(`CRYPTO_REQUEST_LOG` 未设为 `off` 时默认开启)后,日志里每个 `/api/v1` 请求都会标注「明文」或「加密」,可用来确认客户端真的走了加密链路。

排障:握手 503/5031、400/4013、请求 400/4006、409/4091、426/4007 的定位路径见 [72-troubleshooting-music.md](72-troubleshooting-music.md) 的传输加密一节。
