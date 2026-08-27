# 移除无效游标轮询机制 - 执行计划

## 背景

审计确认 `readed_to_msg_seq` 是 WuKongIM v3 的计划新特性，当前部署的 v2.x 服务端（114.66.23.232:5001）**不返回此字段**。

**证据**：
- v2 设计文档仅定义 `UserConversationState.read_seq`，非响应字段
- SDK 1.5.2 实体类无 `readed_to_msg_seq` 属性
- v3 规划文档将其列为待实现特性

**影响**：
- `wukong-im.client.ts:readedToMessageSeq()` 永远返回 `0`
- Android 每次发消息启动 4×1s 无效轮询
- 浪费 HTTP 请求配额（限流：300次/15分钟/用户）

---

## 待删除代码

### 服务端（3 处）

#### 1. `server/src/im/wukong-im.client.ts:45-52`
```typescript
async readedToMessageSeq(viewerUid: string, peerUid: string): Promise<number> {
  const rows = await this.syncConversations(peerUid, "", 1, 0);
  if (!Array.isArray(rows)) throw ApiErrors.upstream("悟空 IM 会话同步响应格式不正确");
  const row = rows.find((item) => item && typeof item === "object" && (item as Record<string, unknown>).channel_id === viewerUid) as Record<string, unknown> | undefined;
  const value = Number(row?.readed_to_msg_seq ?? 0);
  return Number.isSafeInteger(value) && value > 0 ? value : 0;
}
```

#### 2. `server/src/im/im.service.ts` 中的 `readedToMessageSeq()` 方法
需查找调用 `wukongImClient.readedToMessageSeq()` 的包装方法

#### 3. `server/src/im/im.controller.ts:79-84`
```typescript
@Get("conversations/read-state")
async readState(@CurrentUser() currentUser: User, @Query("channelId") channelId: string): Promise<{ readedToSeq: number }> {
  const seq = await this.imService.readedToMessageSeq(currentUser.id, channelId);
  return { readedToSeq: seq };
}
```

### Android 客户端（2 处）

#### 4. `WukongImClient.kt:514-525`
```kotlin
private fun refreshReadState(peerUid: String) {
    syncScope.launch {
        runCatching { api.imReadedToMessageSeq(peerUid) }.onSuccess { readedToSeq ->
            if (readedToSeq <= 0) return@onSuccess
            _messages.value = _messages.value.map { message ->
                if (message.isMine && message.peerUid == peerUid &&
                    message.messageSeq > 0 && message.messageSeq <= readedToSeq
                ) message.copy(isRead = true) else message
            }
        }
    }
}
```

#### 5. `WukongImClient.kt` 中所有 `refreshReadState()` 调用点
- 发送消息后的 4×1s 轮询（`addOnSendMsgCallback` 内）
- 切换活跃对端时的单次调用（`setActivePeer` 内）

### API 接口层（1 处）

#### 6. `TaotaoMusicApi.kt` 中的 `imReadedToMessageSeq()` 声明
```kotlin
@GET("/api/v1/im/conversations/read-state")
suspend fun imReadedToMessageSeq(@Query("channelId") channelId: String): Int
```

---

## 保留机制：自定义 CMD 回执

**已验证可行**的 `taotao.messageRead` 命令机制**不受影响**，继续使用：

```kotlin
// WukongImClient.kt:338-356
private fun sendReadReceipt(peerUid: String, messageIds: List<String>) {
    wkIm.msgManager.sendWithOptions(
        ImInternalCommandContent(
            READ_RECEIPT_COMMAND,  // "taotao.messageRead"
            JSONObject()
                .put("reader_uid", readerUid)
                .put("message_ids", JSONArray(messageIds.take(100))),
        ),
        WKChannel(peerUid, WKChannelType.PERSONAL),
        WKSendOptions().apply {
            header.redDot = false
            header.syncOnce = false  // 留在原频道，READ 模式可恢复
        },
    )
}
```

**优点**：
- ✅ 完全自主控制，不依赖服务端未实现特性
- ✅ 持久化在私聊频道（`no_persist=0`），离线可恢复
- ✅ 按 `message_id` 精确追踪，不依赖 `message_seq`

---

## 执行步骤

### 1. 服务端删除（3 个文件）

```bash
cd F:/music/server/src/im

# 1. 删除 wukong-im.client.ts:45-52 的 readedToMessageSeq() 方法
# 2. 删除 im.service.ts 中对应的包装方法
# 3. 删除 im.controller.ts:79-84 的 GET /conversations/read-state 路由
```

### 2. Android 客户端删除（2 个文件）

```bash
cd F:/music/androidApp/src/main/java/com/taotao/music/data

# 1. 删除 im/WukongImClient.kt:514-525 的 refreshReadState() 方法
# 2. 删除所有 refreshReadState() 调用（发送回调和 setActivePeer 中）
# 3. 删除 api/TaotaoMusicApi.kt 中的 imReadedToMessageSeq() 接口声明
```

### 3. 验证编译

```bash
# 服务端
cd F:/music/server
npm run build

# Android
cd F:/music
./gradlew :androidApp:assembleDebug
```

### 4. 更新审计文档

在 `IM-AUDIT-FINDINGS.md` 第 2.2 节标注：

```markdown
### 2.2 悟空游标轮询（`readed_to_msg_seq`） ❌ 已移除

**2026-08-28 更新**：经源码审查确认，`readed_to_msg_seq` 是 WuKongIM v3 的计划新特性，
当前 v2.x 服务端不返回此字段。相关代码已在 commit `<hash>` 中移除。

保留机制：自定义 CMD 回执（`taotao.messageRead`）作为唯一已读状态来源。
```

---

## 预期收益

- ✅ 减少无效 HTTP 请求（每次发消息节省 4 次轮询）
- ✅ 降低限流压力（300次/15分钟/用户）
- ✅ 简化状态管理（单一真相来源）
- ✅ 移除误导性代码（避免未来维护人员困惑）

---

## 风险评估

**无风险**：
- 游标轮询机制从未生效（永远返回 0）
- 自定义 CMD 回执已独立工作
- 不影响撤回功能（使用独立的 `messageRevoke` 命令）

---

## 执行人员

待定（建议由熟悉 TypeScript 和 Kotlin 的开发者执行）

## 预计工时

- 代码删除：30 分钟
- 编译验证：15 分钟
- 文档更新：15 分钟
- **总计：1 小时**
