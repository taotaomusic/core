# 播放高潮区间接口与客户端进度标记

[返回文档中心](README.md)

最后更新:2026-10-01

酷我官方搜索和歌曲详情响应中的 `payInfo.refrain_start`、`payInfo.refrain_end` 会被服务端转换为歌曲对象的 `refrainStartMs`、`refrainEndMs`，单位均为毫秒。字段缺失时不伪造值，JSON 中为空。

客户端解析搜索结果和歌曲详情时保留这两个字段。公共播放进度条在歌曲时长有效且区间完整时，用红色细线标出高潮范围；拖动和播放位置计算仍使用整首歌曲时长，不影响 seek。

实测歌曲“再见”（酷我 musicId=112051）官方返回 `refrain_start=39482`、`refrain_end=69719`，服务端对应为 `refrainStartMs=39482`、`refrainEndMs=69719`。

## MV

酷我 MV 详情通过 `GET /api/v1/songs/{musicId}/mv?source=kuwo` 获取，响应直接返回官方 `service/mv/info` 中的 `mv` 对象，包含 `mid`、`name`、`coverUrl`、`highUrl`、`lowUrl`、`mvDuration`、清晰度地址对应的 P2P ID 和码率。没有 MV 时返回上游错误；其他音源会返回“不支持 MV”。

相关篇目:字段模型见 [94-shared-module.md](94-shared-module.md) §3;搜索接口字段见 [32-api-search-music.md](32-api-search-music.md) §3;进度条渲染见 [93-player-ui.md](93-player-ui.md) §4。
