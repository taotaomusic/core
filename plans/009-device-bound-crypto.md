# 009 · 设备号绑定的传输层加密接入（三端）

> **状态**：设计阶段。协议改动落在独立仓库 [`hdppppppp/tools`](https://github.com/hdppppppp/tools)，
> 本仓库只拉产物、不编 Rust。产物到位前，主仓库仅落设计与接线骨架，不激活链路。

| 项 | 值 |
| --- | --- |
| 严重度 | 高（改动传输层协议版本，四端产物需同步重编） |
| 依赖 | [008](008-rust-crypto-layer.md) 的 PSK 握手 + AEAD 帧协议 |
| 范围 | Android JVM、Windows JVM、Node 服务端**三端**；Web 分享页**不接入** |
| 代码 | 协议：独立仓库；接线：本仓库 `server/` + `androidApp/` + `desktopApp/` |

> **构建铁律：不在本地编译加密层，一律走云端。**
> 加密层 Rust 源码只在独立仓库 `hdppppppp/tools`，其 GitHub Actions 负责交叉编译
> 四端产物（`ci.yml` 占位密钥、`release.yml` 注真密钥）。本仓库开发机**不需要也不得**
> 安装 Rust / Android NDK / wasm-bindgen / MSVC 等交叉编译环境，只用
> `tools/fetch-crypto.ps1` 从 Release 拉产物到 `crypto/dist/`。
> 三端接线代码（Kotlin / TS）的构建同样以云端为准：Android/桌面走 music 仓库
> `client.yml`、后端走 music-server 仓库 `backend.yml`（见 AGENTS.md「云端构建」）。
> 本地编译仅用于定位问题，**不作为验收依据**。

---

## 一、目标与威胁模型

### 要解决的问题

在 008 的传输加密之上再叠一层**设备绑定**，使得：

1. **防滥用**：即使加密协议被逆向攻破，攻击者仍需**逐台真机**提取硬件设备号，
   才能冒充该机发请求——把成本从「逆向出一个全局 PSK 就能冒充所有人」
   抬到「每台机单独逆向 + 提设备号」。
2. **可追溯**：服务端审计能按设备号反查异常流量，定位到具体机器。

### 沿用 008 的边界

- 不追求「密钥/设备号不可提取」。硬件设备号在 root/管理员权限下一定可提取，
  目标是**抬高成本**，不是不可破。
- 不加密音频流、大文件上传（见 008 第一节边界表）。
- 不替代 TLS，是应用层第二道防线。

### 设备号来源（用户选定：硬件标识）

| 端 | 设备号来源 | 稳定性说明 |
| --- | --- | --- |
| Android | `Settings.Secure.ANDROID_ID` | 恢复出厂 / 换签名密钥会变，正常使用稳定 |
| Windows | 注册表 `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid` | 重装系统会变，正常使用稳定 |
| Web | 无 | 不接入 |

> **注意**：Android 现有 `DeviceIdStore.kt` 是灰度分桶用的随机 UUID，
> **不复用**它——用途不同（分桶 vs 设备绑定），且用户明确要硬件标识。

---

## 二、双重绑定设计

### A. PSK 按设备派生（防滥用，核心）

```
per_device_psk = HKDF(embedded_psk, salt = device_id, info = "taotao-device-v1")
```

- 内嵌 PSK 仍随包分发，但**单独一个内嵌 PSK 握不了手**，必须叠加真机
  `device_id` 才能推出会话密钥。
- 攻击者逆向出算法后，仍需在**每台真机**上提 `ANDROID_ID` / `MachineGuid`，
  且提到的只能冒充**那一台**。

### B. AAD 绑设备号（防换机重放 + 排查）

```
aad_context(method, path_and_query, device_id)
```

- 帧被篡改**或**换一台机重放，`open()` 直接解密失败（AAD 不匹配）。

### 叠加效果

算法懂了 → 仍需逐台真机提设备号 → 提了也只能冒充那一台 →
服务端审计里那台机流量异常立即暴露。

---

## 三、协议改动（独立仓库 `hdppppppp/tools`，仅设计）

四端产物需**一起重编**，`protocol_version()` **必须 +1**（新旧端握手报文不兼容）：

| 现状签名 | 改为 |
| --- | --- |
| `Client::new(psk_id, psk_hex)` | `Client::new(psk_id, psk_hex, device_id)`，构造时做 per-device HKDF |
| `aad_context(method, path)` | `aad_context(method, path, device_id)` |
| `Server::accept(client_hello, now)` | ClientHello 需携带 `device_id`（或随 `psk_id` 传），服务端用同一 HKDF 现算 per-device PSK |

配套：
- Android 产物需导出 **JNI 符号**（`System.loadLibrary("taotao_crypto")` 可解析），
  本仓库 Kotlin 绑定按这组符号写。
- Web 端 wasm 本次**不改调用方**（保持不接入）。若将来 Web 接匿名模式，
  握手报文要能区分「带设备 / 匿名」——本次记为**待定**，不阻塞三端。
- 独立仓库 `docs/design.md` 同步；CI 占位密钥、release 注真密钥流程不变。

---

## 四、Node 服务端接线（`server/`）

### 解密/加密中间件（`main.ts`）

- 挂在 `parseJson` 中间件（`main.ts:98-106`）**之前**：要先拿原始字节 `open()`
  出明文，再交给 body parser 与下游路由；响应侧 `seal()` 回帧。
  沿用现有 `bodyParser:false` + 手动 `app.use` 的模式。
- **只对带 `X-Taotao-Crypto` 头**且路径在 `/api/v1/*` 的请求生效；无头请求
  完全透明走原明文路径（本次是灰度接入，不能一刀切强制）。
- 排除：`express.static`（`/admin`、分享页）、音频流 `/songs/:id/play`、
  `RAW_BODY_PATHS`（大文件上传，见 `main.ts:104`）。

### 握手路由（公开）

- 新增公开路由处理 ClientHello → `Server.accept` → ServerHello；
  服务端按请求声明的 `device_id` 现算 per-device PSK。标 `@Public()`。

### crypto 模块

- 新增 `server/src/crypto/`，加载 `crypto/dist/node/{linux-x64,windows-x64}/taotao_crypto.node`
  （按 `process.platform` 选），注册进 `app.module.ts` 的 `imports`。
- 会话态：单例 `Server` 实例管理会话 + 定时 `sweep_expired`。

### 设备登记 + 审计

- 记录 `session_id ↔ device_id`，异常按设备号可查。
- 客户端流量审计与管理端 `AdminAuditService` 是两回事，另建**轻量设备会话表**，
  不要混用管理审计。

### 契约影响（逐条核对 `RELEASE.md` + `verify-contract.mjs`）

- **中间件对下游必须完全透明**：解出明文后，路由看到的请求/响应与明文路径
  一字不差——`/search` 裸 NDJSON、401 不变 403、SQL 别名双引号等既有契约不能变形。
- `verify-contract.mjs`（当前 155 项）跑明文实例：验证客户端**不带加密头**时应全绿；
  另**补一条**加密握手 + seal/open 的契约用例。**验收以 verify-contract 全绿为准**。

---

## 五、Android 客户端接线（`androidApp/`）

- 设备号：新增取 `Settings.Secure.ANDROID_ID` 的来源类（不复用 `DeviceIdStore`）。
- JNI：`System.loadLibrary("taotao_crypto")` 加载
  `crypto/dist/android/{arm64-v8a,x86_64}/libtaotao_crypto.so`；确认
  `androidApp/build.gradle` 的 `jniLibs.srcDirs` 指向 `crypto/dist/android`。
  新增 Kotlin JNI 绑定类，放 `com.taotao.music` 包下。
- HTTP 封装：现有多处用 `HttpURLConnection`（`AuthSession.kt`、`TencentMusicApi.kt` 等），
  抽一个统一加密请求封装：握手 → 每请求 `seal` body + 加 `X-Taotao-Crypto` 头
  → 读响应 `open`。逐个改造调用点走这层。

---

## 六、Windows 桌面接线（`desktopApp/`）

- 设备号：读注册表 `MachineGuid`。desktop 已在 `DesktopMusicApi.kt:478/503` 传
  `deviceId` 参数——确认来源，统一改成 `MachineGuid`（若已是则复用）。
- 加载 `crypto/dist/windows/taotao_crypto.dll`（`System.load` 绝对路径或
  `java.library.path`）；`packageDesktopUpdateBundle` 打包时把 dll 纳入分发。
- HTTP 封装同 Android。绑定接口可共享（放 `shared/` 的 expect/actual），
  但**库加载与设备号取值是平台相关**，分端实现。

---

## 七、分期与验收

1. **本仓库先出**（本设计文档）+ 主仓库接线骨架（不激活，无产物无法联调）。
2. **独立仓库**改 Rust，`protocol_version +1`，CI 出四端新产物，release 注真密钥。
3. **产物到位后**：`tools/fetch-crypto.ps1 -Version <新版>` 拉产物 →
   服务端接线 → 一个客户端联调握手 + seal/open → 灰度放量。

**验收标准**（构建与验证以**云端 CI** 为准，本地仅用于定位问题）：
- 后端：music-server 仓库 `backend.yml` 里 `verify-contract.mjs` 全绿
  （明文契约不破 + 新增加密用例）。
- Android：music 仓库 `client.yml` 的 `assembleRelease` 出签名包；真机握手成功、
  加密请求 200、换机 / 改包后 `open()` 解密失败。
- Windows：`client.yml` 的 `packageDesktopUpdateBundle` 产物含 dll，桌面握手成功。
- 审计：服务端能按 `device_id` 查到会话与异常流量。

## 八、待拍板（不阻塞开工）

- Web 将来是否接匿名模式（决定 Rust 要不要维护双握手模式）——本次不做。
- 灰度策略：本设计按「可选头触发」渐进接入，服务端对无头请求透明；
  是否某版本起强制加密另议。
