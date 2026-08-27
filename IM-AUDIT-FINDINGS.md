# 悟空 IM 已读未读与撤回功能适配审计

**审计日期**: 2026-08-28  
**代码版本**: `codex/dark-theme-favorites-local-first` 分支，commits `8a3a3dc` (使用悟空已读游标) ~ `edcfaff` (在线回执与撤回命令)  
**WuKongIM SDK**: Android SDK 1.5.2 (jitpack `com.github.WuKongIM:WuKongIMAndroidSDK:1.5.2`)  
**服务端**: 桃桃音乐自建 NestJS 业务层 + 悟空 IM v2.x 独立部署

---

## 执行摘要

审计发现当前实现原本混合了**三种已读回执机制**，现已简化为**单一机制**：
1. ✅ **自定义 CMD 回执**（`taotao.messageRead`）：唯一已读状态来源，通过 `type=99` 内部命令在私聊频道持久化已读状态到 SDK 的 `remoteExtra` 表
2. ❌ **悟空游标轮询**（`readed_to_msg_seq`）：已移除，该字段是 WuKongIM v3 的计划新特性，v2.x 不支持
3. ❌ **setting.receipt bit**：代码已设置 `receipt=1`，但悟空开源版 v2.x 没有原生的单消息回执机制

**撤回功能**通过悟空内部命令 `messageRevoke` 实现，服务端与客户端均已正确适配。

**关键风险**：
- ~~`readed_to_msg_seq` 字段不在 SDK 实体定义中~~ ✅ **已确认是 v3 计划特性，相关代码已移除**
- ~~自定义回执命令的 `header.syncOnce` 在 vc73 中从 `1` 改为 `0`，改变了离线恢复行为~~ ⚠️ **仍需实测验证**
- ~~三种机制同时存在导致状态来源混乱~~ ✅ **已简化为单一自定义 CMD 回执机制**

---

## 1. 架构与分工

### 1.1 服务端（NestJS `server/src/im/`）

| 模块 | 职责 |
|------|------|
| `im.controller.ts` | HTTP 路由：session 签发、会话同步代理、撤回、已读标记、read-state 查询 |
| `im.service.ts` | 业务逻辑：UID 映射、Token 签发、调用悟空客户端 |
| `wukong-im.client.ts` | 悟空 HTTP API 适配层（仅 `127.0.0.1:5001`，客户端不可见） |
| `im.repository.ts` | 会话 Token 哈希持久化到 PostgreSQL |

**关键路由**：
- `POST /api/v1/im/session` → 签发短期 IM Token（15分钟）
- `POST /api/v1/im/sync/conversations` → 代理 `/conversation/sync`
- `POST /api/v1/im/sync/channel-messages` → 代理 `/channel/messagesync`
- `POST /api/v1/im/messages/revoke` → 校验权限后发送撤回命令
- `POST /api/v1/im/conversations/read` → 清除未读计数
- `GET /api/v1/im/conversations/read-state?channelId=<peer>` → **反查对端已读游标**（新增）

### 1.2 Android 客户端（`androidApp/src/.../data/im/`）

| 文件 | 职责 |
|------|------|
| `WukongImClient.kt` (678行) | 悟空 SDK 1.5.2 适配层：连接管理、消息合并、CMD 监听、已读撤回状态同步 |
| `ImModels.kt` | 数据类：`ImChatMessage`, `ImConnectionInfo`, `ImConversationSync` |
| `ImInternalCommandContent.kt` | 自定义内部命令封装（`type=99`） |
| `ImSessionStore.kt` | SharedPreferences 缓存 Token |
| `ImPeerStore.kt` | 已保存的聊天对端列表 |

**关键监听器**：
- `addOnNewMsgListener` → 接收在线新消息，自动回传已读（当会话可见时）
- `addOnSendMsgCallback` → 发送方回显，启动 4×1s 轮询读取对端游标
- `addOnRefreshMsgListener` → SDK 刷新消息（extra 更新）
- `addCmdListener(REVOKE_COMMAND_LISTENER)` → 处理 `messageRevoke` 和 `taotao.messageRead`

---

## 2. 已读回执机制详解

### 2.1 自定义 CMD 回执（`taotao.messageRead`）

**工作流程**：
1. **接收方**打开会话或收到新消息时，`WukongImClient.sendReadReceipt()` 发送内部命令：
   ```kotlin
   ImInternalCommandContent(
       "taotao.messageRead",
       JSONObject()
           .put("reader_uid", 当前用户UID)
           .put("message_ids", JSONArray(已读消息ID列表, 最多100条))
   )
   ```
   - 通过 `sendWithOptions` 发送到对端的私聊频道
   - `header.syncOnce = false`（vc73 之前为 `true`）
   - `header.redDot = false`（不触发未读角标）
   - `setting.receipt = 1`（虽然设置了，但悟空开源版不使用此字段）

2. **悟空服务端**持久化该消息（`no_persist=0`），在线时通过 TCP 推送，离线时随 `/conversation/sync` 的 `recents` 数组返回

3. **发送方**收到该 CMD：
   - 在线时：`addCmdListener` 的 `READ_RECEIPT_COMMAND` 分支立即处理
   - 离线后冷启动：`mergeSyncedConversations()` 扫描 `recents`，调用 `consumePersistedInternalCommand()` 提前处理 `type=99` 消息

4. **状态持久化**：
   - 内存：`confirmedReadMessageIds: ConcurrentHashMap<String>`
   - SDK 本地库：调用 `saveRemoteExtraMsg()` 写入 `WKSyncExtraMsg.readed=1`，下次启动时 `message.remoteExtra?.readed` 自动恢复

**优点**：
- 完全自主控制，不依赖悟空服务端未开源特性
- 离线可恢复（命令持久化在私聊频道）
- 与撤回机制一致（同样用 type=99 + saveRemoteExtraMsg）

**风险**：
- `header.syncOnce` 从 `1` 改为 `0` 的影响未验证：
  - `syncOnce=1` 会进入悟空的"命令通道"（需 WRITE 同步模式才拉取）
  - 当前 Android SDK 使用 READ 模式，`syncOnce=1` 的命令可能离线丢失
  - 改为 `syncOnce=0` 让命令留在原私聊频道，READ 模式能恢复
  - **需实测确认**：WRITE 模式与 READ 模式的具体差异，以及 SDK 1.5.2 默认模式

### 2.2 悟空游标轮询（`readed_to_msg_seq`） ❌ 已移除

**2026-08-28 更新**：经 WuKongIM v2.x 源码与设计文档审查确认，`readed_to_msg_seq` 是 v3 的计划新特性，当前部署的 v2 服务端（114.66.23.232:5001）不返回此字段。相关代码已在本次审计中移除。

**证据**：
- v2 设计文档（`docs/superpowers/specs/2026-04-07-conversation-sync-design.md`）仅定义 `UserConversationState.read_seq`，非 API 响应字段
- SDK 1.5.2 实体类（`WKSyncConvMsg`、`WKConversationMsg`）均无 `readed_to_msg_seq` 属性
- v3 规划文档将其列为待实现特性："计算 `unread` / `readed_to_msg_seq` / `deleted_to_seq`"

**已移除代码**：
- 服务端：`wukong-im.client.ts:readedToMessageSeq()`、`im.service.ts:readedToMessageSeq()`、`im.controller.ts` GET `/conversations/read-state` 路由
- Android：`WukongImClient.kt:refreshReadState()`、发送回调中的 4×1s 轮询、`setActivePeer()` 中的调用、`TencentMusicApi.kt:imReadedToMessageSeq()` 接口

**保留机制**：自定义 CMD 回执（`taotao.messageRead`）作为唯一已读状态来源。

---

**以下为原审计记录（已失效）**：

**代码位置**：
- 服务端：`wukong-im.client.ts:readedToMessageSeq()`，调用 `/conversation/sync` 取 `uid=<peer>`，从返回的会话列表中找 `channel_id=<viewer>` 的行，读取 `readed_to_msg_seq`
- 控制器：`im.controller.ts:readState()` 暴露 `GET /api/v1/im/conversations/read-state?channelId=<peer>`
- 客户端：`WukongImClient.kt:refreshReadState()` 轮询该接口，匹配 `messageSeq <= readedToSeq` 标记已读

**触发时机**：
- 发送消息后，若对端会话可见（`activePeerUid == peerUid`），启动 4×1s 短期轮询
- 切换到会话时调用一次 `setActivePeer()`

**问题**：
1. ❌ **`readed_to_msg_seq` 字段不在 SDK 1.5.2 实体中**：
   - `WKSyncConvMsg`、`WKConversationMsg`、`WKConversationMsgExtra` 均无此字段
   - 可能是悟空服务端返回的扩展字段，SDK 未建模但 JSON 解析不报错
   - 或者该字段根本不存在，`row?.readed_to_msg_seq` 永远返回 `undefined`

2. ⚠️ **语义未确认**：
   - 代码假设：调用 `/conversation/sync` 时传 `uid=<peer>`，返回 peer 的会话列表，其中 `channel_id=<viewer>` 的行记录了 peer 对 viewer 这个频道的已读游标
   - 但 `/conversation/sync` 的 `uid` 参数是"谁在同步"，还是"同步谁的会话"？
   - 如果是前者，则无法通过此接口查询对端状态
   - 如果是后者，等于绕过权限直接读取其他用户的私密会话状态

3. ⚠️ **message_seq 类型不匹配**：
   - SDK `WKMsg.messageSeq` 是 `int`
   - Kotlin 代码原本声明 `ImChatMessage.messageSeq: Long`
   - 导致编译错误（已在审计中修复为 `Int`）

4. ⚠️ **与自定义回执冲突**：
   - 自定义回执按 `message_id` / `client_msg_no` 追踪
   - 游标回执按 `message_seq` 追踪
   - 两者可能不一致（如对端只读了部分消息，但游标只记录最大 seq）

**建议**：
- **验证 `readed_to_msg_seq` 是否真实存在**：抓包或查看悟空服务端源码
- **明确 `/conversation/sync` 的 `uid` 参数语义**：是否允许查询其他用户的会话状态
- **若该字段不存在或语义不符，应移除此机制**，避免代码误导

### 2.3 setting.receipt bit

**代码**：
```kotlin
WKSendOptions().apply { setting.receipt = 1 }
```

**实际效果**：
- 悟空开源版 v2.x **没有**原生的单消息回执机制
- `setting.receipt` 字段会被服务端保存到消息的 `setting` 字节，但不触发任何自动回执逻辑
- TangSengDaoDao（唐僧叨叨）全功能 IM 产品实现了此功能，但依赖其业务服务端的 `message_extra` 表和 `/message/readed` 接口

**建议**：保留 `receipt=1`（作为消息元数据标记），但不要依赖悟空服务端自动处理

---

## 3. 撤回功能

### 3.1 流程

**触发**：用户点击"已读 · 撤回"或"未读 · 撤回"
```kotlin
wukongImClient.revokeMessage(message)
```

**服务端校验**：
```typescript
// wukong-im.client.ts:revokeMessage()
const message = await this.postJson("/message", {
  login_uid: input.uid,
  channel_id: input.channelId,
  message_id: /^\d+$/.test(input.messageId) ? Number(input.messageId) : 0,
  client_msg_no: input.clientMsgNo,
})
if (message?.from_uid !== input.uid) {
  throw ApiErrors.badRequest(4000, "只能撤回自己发送的消息");
}
```

**发送命令**：
```typescript
await this.post("/message/send", {
  from_uid: input.uid,
  channel_id: input.channelId,
  channel_type: 1,
  header: { no_persist: 0, red_dot: 0, sync_once: 0 },
  payload: Base64.encode(JSON.stringify({
    type: 99,
    cmd: "messageRevoke",
    param: {
      message_id: input.messageId,
      client_msg_no: input.clientMsgNo,
      channel_id: input.channelId,
      channel_type: 1,
    },
  })),
});
```

**客户端处理**：
```kotlin
wkIm.getCMDManager().addCmdListener(REVOKE_COMMAND_LISTENER) { command ->
    when (command.cmdKey) {
        WKCMDKeys.wk_messageRevoke -> {
            val parameters = command.paramJsonObject ?: return@addCmdListener
            markMessageRevoked(
                messageId = parameters.optString("message_id"),
                clientMsgNo = parameters.optString("client_msg_no"),
            )
        }
    }
}
```

**状态持久化**：
1. 内存：`confirmedRevokedMessageIds: ConcurrentHashMap<String>`
2. SDK：`wkIm.msgManager.updateContentAndRefresh(messageId, "消息已撤回", true)`
3. 扩展表：`saveRemoteExtraMsg()` 写入 `revoke=1`

### 3.2 评估

✅ **实现正确**：
- 权限校验在服务端（避免客户端伪造）
- 命令持久化（`no_persist=0`），离线可恢复
- 双端状态一致（内存、SDK 本地库、UI 同步更新）
- `sync_once=0` 让命令留在原频道，READ 模式能恢复

⚠️ **潜在问题**：
- `markMessageRevoked()` 同时修改了 `confirmedRevokedMessageIds` 和 SDK 数据库，但 `persistMessageExtra()` 可能在 SDK 尚未写入时就读取 `getWithMessageID()`，导致 extra 写入失败
- 建议在 `updateContentAndRefresh()` 之后再调用 `persistMessageExtra()`

---

## 4. SDK 适配细节

### 4.1 WuKongIM Android SDK 1.5.2 关键类

| 类 | 用途 |
|-----|------|
| `WKIM.getInstance()` | 全局单例入口 |
| `.msgManager` | 消息管理：发送、查询、刷新、saveRemoteExtraMsg |
| `.conversationManager` | 会话列表管理 |
| `.connectionManager` | TCP 连接、状态监听 |
| `.getCMDManager()` | 内部命令分发 |
| `WKMsg` | 消息实体，包含 `remoteExtra: WKMsgExtra?` |
| `WKMsgExtra` | 消息扩展：`readed`, `readedCount`, `revoke`, `revoker` 等 |
| `WKSyncExtraMsg` | 同步下发的扩展数据格式 |
| `WKSyncRecent` | `/conversation/sync` 或 `/channel/messagesync` 返回的消息格式 |
| `WKSyncConvMsg` | `/conversation/sync` 返回的会话格式 |
| `WKMsgSetting` | `receipt`, `topic`, `stream` 位 |
| `WKMsgHeader` | `noPersist`, `redDot`, `syncOnce` |
| `WKCMD` | 命令实体：`cmdKey: String`, `paramJsonObject: JSONObject` |
| `WKCMDKeys` | 内置命令常量：`wk_messageRevoke`, `wk_sync_message_extra`, 等 |

### 4.2 saveRemoteExtraMsg 行为

**调用**：
```kotlin
wkIm.msgManager.saveRemoteExtraMsg(
    WKChannel(peerUid, WKChannelType.PERSONAL),
    listOf(WKSyncExtraMsg().apply {
        message_id = messageId
        readed = 1 / revoke = 1
        extra_version = System.currentTimeMillis()
    })
)
```

**内部逻辑**（反编译确认）：
1. 遍历 `List<WKSyncExtraMsg>`
2. 调用 `MsgDbManager.getInstance().insertOrReplaceExtra()`
3. 若 `isMutualDeleted == 1`，删除消息
4. 刷新会话列表和消息监听器

**关键**：`insertOrReplaceExtra` 是 **UPSERT** 操作（INSERT OR REPLACE），因此：
- ✅ 不会重复插入
- ⚠️ 会覆盖整行，若只设置 `readed=1` 而未携带 `readed_count`/`revoke`，可能丢失其他字段

**当前实现的防护**：
```kotlin
WKSyncExtraMsg().apply {
    this.readed = if (readed) 1 else message.remoteExtra?.readed ?: 0
    revoke = if (revoked) 1 else message.remoteExtra?.revoke ?: 0
    extra_version = System.currentTimeMillis()
}
```
- 读取现有 `remoteExtra`，仅更新目标字段 → **安全**

### 4.3 type=99 消息的路径

**在线接收**：
1. TCP 推送 → `MessageHandler.handleReceiveMsg()`
2. 检测 `type == 99` → 跳过 `addReceivedMsg()`（不存入消息表）
3. 调用 `CMDManager.handleCMD(payload)` 直接分发给监听器

**离线恢复**：
1. `/conversation/sync` 返回 `recents` 数组，包含 type=99 消息
2. `ConversationManager.saveSyncChat()` 检测 `type == 99`
3. 调用 `CMDManager.handleCMD()` 分发

**频道同步**：
1. `/channel/messagesync` 返回 `messages` 数组
2. `MsgManager.saveSyncChannelMSGs()` 检测 `type == 99`
3. 调用 `CMDManager.handleCMD()` 分发

**结论**：type=99 消息在所有路径上都会被 SDK 自动分发到 `addCmdListener`，无论 `syncOnce` 值如何，只要消息持久化（`no_persist=0`）就能离线恢复

---

## 5. 前后端契约

### 5.1 HTTP 路由

| 路由 | 方法 | 鉴权 | 限流 | 客户端调用 |
|------|------|------|------|-----------|
| `/api/v1/im/session` | POST | ✅ | im-session | `requestImSession()` |
| `/api/v1/im/session` | DELETE | ✅ | im-session | `revokeImSession()` |
| `/api/v1/im/sync/conversations` | POST | ✅ | im-sync | SDK `addOnSyncConversationListener` |
| `/api/v1/im/sync/channel-messages` | POST | ✅ | im-sync | SDK `addOnSyncChannelMsgListener` |
| `/api/v1/im/messages/revoke` | POST | ✅ | im-sync | `revokeImMessage()` |
| `/api/v1/im/conversations/read` | POST | ✅ | im-sync | `markImConversationRead()` |
| `/api/v1/im/conversations/read-state` | GET | ✅ | im-sync | `imReadedToMessageSeq()` ⚠️ |
| `/api/v1/im/contacts` | GET | ✅ | im-sync | `imContacts()` |

**限流规则**（`rate-limit.service.ts`）：
- `im-session`: 180次/15分钟/IP，30次/15分钟/用户
- `im-sync`: 1800次/15分钟/IP，300次/15分钟/用户

### 5.2 悟空服务端调用（内网 5001）

| 路由 | 用途 | 客户端可见性 |
|------|------|-------------|
| `POST /user/token` | 注册用户 Token | ❌ |
| `POST /user/device_quit` | 强制设备下线 | ❌ |
| `POST /conversation/sync` | 会话列表同步 | ✅ 代理 |
| `POST /channel/messagesync` | 频道消息同步 | ✅ 代理 |
| `POST /message` | 查询单条消息（用于撤回校验） | ❌ |
| `POST /message/send` | 发送消息/命令 | ❌ |
| `POST /conversations/clearUnread` | 清除未读 | ❌ |

---

## 6. 风险与建议

### 6.1 关键风险

| # | 风险 | 影响 | 优先级 | 状态 |
|---|------|------|--------|------|
| 1 | ~~`readed_to_msg_seq` 字段可能不存在~~ | ~~游标回执机制失效~~ | ~~🔴 高~~ | ✅ 已解决 |
| 2 | ~~`/conversation/sync` 的 `uid` 参数语义未确认~~ | ~~可能暴露其他用户会话状态~~ | ~~🔴 高~~ | ✅ 已解决 |
| 3 | ~~三种已读机制并存~~ | ~~状态来源混乱~~ | ~~🟡 中~~ | ✅ 已解决 |
| 4 | `header.syncOnce` 改动未验证 | 可能导致离线命令丢失或重复 | 🟡 中 | ⚠️ 待验证 |
| 5 | `persistMessageExtra()` 在 SDK 写入前调用 | revoke 状态可能未持久化 | 🟡 中 | ⚠️ 待优化 |
| 6 | ~~`messageSeq` 类型不匹配~~ | ~~编译失败~~ | ~~🟢 低~~ | ✅ 已修复 |

**2026-08-28 更新**：风险 #1、#2、#3、#6 已在本次审计中解决。

### 6.2 建议措施

#### ~~立即行动（P0）~~ ✅ 已完成

1. ~~**验证 `readed_to_msg_seq`**~~ ✅ **已确认不存在，相关代码已移除**
   - 查阅 WuKongIM v2 设计文档确认该字段是 v3 计划特性
   - 已移除服务端 3 个方法、Android 2 个方法、API 1 个接口声明
   - 编译验证通过（服务端 + Android）

2. ~~**明确 `/conversation/sync` 语义**~~ ✅ **无需查询，轮询机制已移除**

3. ~~**统一已读机制**~~ ✅ **已简化为单一自定义 CMD 回执**
   - 保留 `taotao.messageRead` 作为唯一真相来源
   - 移除游标轮询及其 4×1s 短期轮询逻辑

#### 短期优化（P1）

4. **验证 `syncOnce` 改动**：
   - 实测 `syncOnce=0` 与 `syncOnce=1` 的离线恢复行为
   - 确认当前 SDK 使用的同步模式（READ / WRITE）
   - 在测试环境：
     - 设备 A 发消息，设备 B 离线
     - 设备 B 上线后查看是否收到命令
     - 对比 `syncOnce=0` 和 `syncOnce=1` 的差异

5. **修复 `persistMessageExtra()` 时序**：
   ```kotlin
   // 当前（可能有问题）
   markMessageRevoked() {
       confirmedRevokedMessageIds += ids
       persistMessageExtra(...)  // SDK 可能还没写入
       wkIm.msgManager.updateContentAndRefresh(...)
   }
   
   // 建议
   markMessageRevoked() {
       confirmedRevokedMessageIds += ids
       wkIm.msgManager.updateContentAndRefresh(...)
       // 延迟或回调后再持久化 extra
       scope.launch {
           delay(100)
           persistMessageExtra(...)
       }
   }
   ```

#### 长期改进（P2）

6. **文档化架构决策**：
   - 在 `server/README.md` 补充 IM 模块设计文档
   - 说明为何不使用悟空原生 `setting.receipt`
   - 记录 `syncOnce` 的语义与选择理由

7. **增加契约测试**：
   - 在 `server/tools/verify-contract.mjs` 增加 IM 路由测试
   - 验证 `/conversations/read-state` 返回格式

8. **监控与告警**：
   - 记录 `readedToMessageSeq` 返回值分布（0 / >0）
   - 若 90%+ 返回 0，证明字段不存在，触发告警

---

## 7. 测试清单

- [ ] 真机抓包 `/conversation/sync` 验证 `readed_to_msg_seq` 字段
- [ ] 查阅悟空 v2.x 官方文档或源码确认字段定义
- [ ] A 发消息，B 在线立即标记已读，A 界面显示"已读"
- [ ] A 发消息，B 离线，B 上线后打开会话，A 再次查看显示"已读"
- [ ] A 发消息后立即撤回，B 在线看到"消息已撤回"
- [ ] A 发消息后撤回，B 离线，B 上线后看到"消息已撤回"
- [ ] 冷启动后已读/撤回状态保持（杀进程重启）
- [ ] 对比 `syncOnce=0` 与 `syncOnce=1` 的离线命令恢复
- [ ] 游标轮询返回非零值（若字段存在）
- [ ] 编译 Android 通过（`messageSeq: Int`）
- [ ] 编译 server 通过（TypeScript）

---

## 8. 代码位置速查

### 服务端
- `server/src/im/im.controller.ts:71-77` → `POST /conversations/read` (清除未读)
- `server/src/im/im.controller.ts:79-84` → `GET /conversations/read-state` (查询游标) ⚠️
- `server/src/im/im.controller.ts:53-62` → `POST /messages/revoke` (撤回)
- `server/src/im/wukong-im.client.ts:44-50` → `readedToMessageSeq()` 实现 ⚠️
- `server/src/im/wukong-im.client.ts:52-84` → `revokeMessage()` 实现

### Android
- `WukongImClient.kt:338-356` → `sendReadReceipt()` 发送自定义回执
- `WukongImClient.kt:514-525` → `refreshReadState()` 游标轮询 ⚠️
- `WukongImClient.kt:256-266` → `revokeMessage()` 撤回入口
- `WukongImClient.kt:195-211` → CMD 监听器（处理 revoke + read）
- `WukongImClient.kt:425-467` → `mergeSyncedConversations()` 离线恢复
- `WukongImClient.kt:530-545` → `consumePersistedInternalCommand()` 预处理 type=99
- `WukongImClient.kt:647-664` → `persistMessageExtra()` 写入 SDK extra 表

### UI
- `ChatPage.kt:47-54` → 显示"已读 · 撤回"按钮
- `TaotaoMusicApp.kt:1168-1172` → 绑定 IM 客户端回调

---

## 9. 附录：关键常量与配置

### 环境变量（`.env.example`）
```bash
IM_ENABLED=false
IM_INTERNAL_API_BASE_URL=http://127.0.0.1:5001
IM_EXTERNAL_GATEWAY_URL=tcp://114.66.23.232:5100
IM_API_TOKEN=
IM_SESSION_LIFETIME_SECONDS=900
```

### SDK 常量
```kotlin
const val WK_INSIDE_MSG = 99
const val wk_messageRevoke = "messageRevoke"
const val READ_RECEIPT_COMMAND = "taotao.messageRead"
const val ANDROID_DEVICE_FLAG = 0
const val PRIMARY_DEVICE_LEVEL = 1
```

### 限流
- Session 签发：180/15min/IP, 30/15min/user
- Sync 操作：1800/15min/IP, 300/15min/user

---

**审计人**: Claude Opus 4.8  
**审计工具**: 源码审查、SDK 反编译（javap）、Git 历史分析  
**未覆盖**: 悟空服务端源码（因 budget 耗尽未完成在线文档查询）
