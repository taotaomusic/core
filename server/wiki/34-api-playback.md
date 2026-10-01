# 播放记录契约:会话、最近播放、统计与倒带日记

[返回文档中心](README.md)

最后更新:2026-09-30

播放域分三层:播放会话(幂等累计快照)、最近播放(可清空的可见代际)、累计统计(不可清空)。相关表见 [41-database-tables-core.md](41-database-tables-core.md)。所有接口都需要访问令牌。

## 1. 播放会话上报

```http
POST /api/v1/playback/sessions
```

最小身份字段:`sessionId`、`deviceId`、`source`、`songId`、`startedAt`、`lastPlayedAt`、`listenedMs`;可选 `historyRevision`、`completed`、`durationSeconds`。

- 同一会话重复上报只计算累计增长量:`listened_ms`、`qualified`、`completed` 只能向前增长,重复或乱序请求不会重复累计。
- 身份字段(`sessionId`/`deviceId`/`source`/`songId`/`startedAt`)格式与因果校验失败返回 400/4006;会话身份字段冲突返回 409/4095;`historyRevision` 领先服务端状态返回 409/4096。
- 响应除回显会话外还带 `currentHistoryRevision`(服务端当前清空代际),客户端可在下一个会话前刷新本地状态。
- 客户端**不能用设备墙钟代替 revision 判断离线清空因果**。

## 2. 最近播放

- `GET /playback/recent?limit=` 默认 **500**,最大 500,返回**数组**(不是分页对象)。不传 `limit` 时会返回最多 500 首,不要按「不传只返回 50 条」做内存或性能假设。
- 3 秒后才进入最近播放;单次有效播放阈值为 `min(30 秒, durationSeconds * 50%)`。
- 最近播放直接查询 `user_song_stats` 汇总表(部分索引按 `last_history_at` 排序),不扫描全部播放会话。每条记录含 `source`、`songId`、`firstPlayedAt`、`lastPlayedAt`(即 `last_history_at`)、`playCount`、`completedCount`、`totalListenedMs`;不含标题、歌手等展示元信息,客户端仍要按 ID 补全展示数据。

## 3. 清空代际与 marker 幂等

- `GET /playback/recent/state` 返回 `clearedBefore`、`clearedAt`、`revision`、`marker`。
- `DELETE /playback/recent?marker=<uuid>` 推进清空代际;**相同 marker 重试始终返回第一次结果**,不会重复清空 —— 即使另一台设备已经再次清空。这是离线设备因果一致性的关键(表设计见 [41-database-tables-core.md](41-database-tables-core.md) 的 `playback_history_clear_operation`)。marker 对旧客户端可选:不带 marker 的清空仍会推进代际,只是没有幂等重试保障。
- 清空只影响最近列表,**不删除累计统计**:听歌次数和累计时长保留,离线设备补传边界之前的旧会话也不会恢复已清空列表。

## 4. 累计统计

`GET /playback/stats` 返回 `songCount`(累计歌曲数)、`playCount`、`completedCount`、`totalListenedMs`,以及全量口径的 `firstPlayedAt`/`lastPlayedAt`(可空)。按 `(user_id, source, song_id)` 汇总,不受清空最近播放影响。

## 5. 单曲倒带日记

```http
GET /api/v1/playback/diary?source=&songId=
```

需要访问令牌,返回一首歌的回访画像:

- 基础累计统计(次数、时长、首末播放时间;`firstPlayedAt`/`lastPlayedAt` 从未播放为 null)。
- 近一年(`playsLastYear`)/近半年(`playsLastHalfYear`)的合格会话数(口径与播放会话有效性一致)。
- 按天的播放峰值(`peakDay: {atMillis, count}`,按 UTC 天分桶)。
- `yearly`:最近 6 个自然年(含今年)的逐年合格播放次数,补零。
- `dailyCounts`:最近 180 天的逐日合格播放次数(点阵热力图,补零)。
- `records`:最近 50 条会话明细(倒序)。

歌曲身份校验与播放会话相同(400/4006);无数据时返回**零值而不是 404**。
