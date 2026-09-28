# 传输加密协议

[返回文档中心](README.md)

最后更新:2026-09-27

传输层加密覆盖 `/api/v1/**`:带 `X-Taotao-Crypto` 头的请求在 body parser 之前由中间件逐块 AEAD 解密,无加密头的请求完全透明。**加密是可选增强而非强依赖**,明文链路始终可用。源码在 `server/src/crypto/`,加密层 Rust 源码在 monorepo `crypto-src/`(改动流程见 [README.md](README.md) 维护规则第 6 条)。

具体业务码以 `crypto.controller.ts`、`crypto.middleware.ts` 和 `native-loader.ts` 的实际实现为准。

## 1. 握手

```http
POST /api/v1/crypto/handshake
Content-Type: application/json

{ "clientHello": "<base64>", "deviceId": "<设备号>" }
```

- `@Public()` 显式公开:握手时客户端尚无会话,不能要求登录。
- 成功 HTTP 200:`{ "serverHello": "<base64>" }`。
- `deviceId` 折进握手密钥(**协议 v2,握手密钥绑定设备号**):Android 取 `ANDROID_ID`、Windows 取 `MachineGuid`;缺失按空串处理(等价不绑定)。
- 走 JSON 而不是裸字节:握手请求本身不加密、量小,base64 让它在既有 JSON 中间件里通行无阻。

## 2. 失败语义

| 场景 | 响应 | 客户端行为 |
| --- | --- | --- |
| 服务端未启用(产物缺失或协议版本 <2) | 503/5031「传输加密未启用」 | 回退明文,不阻断功能 |
| `clientHello` 缺失或非法 base64 | 400/4006 | 修正报文 |
| PSK 未配置、device_id 与 PSK 不匹配、报文非法 | 400/4013「握手被拒绝」 | 检查 PSK 配置与设备号来源 |
| 加密会话过期或序号不连续(请求阶段) | 409/4091「加密会话失效,请重新握手」 | 重新握手后重放请求 |

## 3. 中间件约束

- AAD 绑定 `method + path + query`,**改路由形状会直接导致解密失败**(400/4006)。
- `RAW_BODY_PATHS` 白名单(APK/补丁/桌面上传)与 `*/play` 音频流不参与解密。
- 解密中间件必须挂载在 body parser **之前**,否则拿到的是密文(挂载顺序见 [11-architecture-modules.md](11-architecture-modules.md))。
- 服务端会话在内存中管理,每分钟清理过期会话;**重启后客户端需重新握手**(409/4091 引导),多实例部署时各实例会话独立。

## 4. 配置与产物

- 配置:`CRYPTO_PSK_ID` / `CRYPTO_PSK_HEX`(两值需与客户端侧一致),完整说明见 [21-configuration.md](21-configuration.md)。
- 产物:`crypto/dist/` 只放四端编译产物;本地**不需要** Rust / Android NDK / wasm-bindgen 交叉编译环境。在项目根目录执行 `powershell tools/fetch-crypto.ps1`,从 GitHub `hdppppppp/tools` 仓库的 Release 拉取对应平台产物到 `crypto/dist/node/<platform>/taotao_crypto.node`(带 SHA256 校验)。
- 产物缺失时服务只是启动 WARN 并按明文链路运行,不阻断启动。
- 改加密协议去 `crypto-src/` 改(经 `tools/sync-repos.ps1` 推到 tools 仓库交叉编译),不要动 `crypto/dist/` 里的产物。

## 5. 本地验证加密链路

配置 `CRYPTO_PSK_ID` / `CRYPTO_PSK_HEX` 后,`POST /api/v1/crypto/handshake` 应返回 `serverHello`;**未配置时该接口固定 503/5031**,可用于证明明文链路健在。

部署冒烟测试里有一条固定断言:未配置加密的实例,握手应返回 503/5031(见 [60-deploy-backend.md](60-deploy-backend.md))。

排障:握手 503/5031、400/4013、请求 400/4006、409/4091 的定位路径见 [72-troubleshooting-music.md](72-troubleshooting-music.md) 的传输加密一节。
