# 播放高潮区间接口与客户端进度标记

酷我官方搜索和歌曲详情响应中的 `payInfo.refrain_start`、`payInfo.refrain_end` 会被服务端转换为歌曲对象的 `refrainStartMs`、`refrainEndMs`，单位均为毫秒。字段缺失时不伪造值，JSON 中为空。

客户端解析搜索结果和歌曲详情时保留这两个字段。公共播放进度条在歌曲时长有效且区间完整时，用红色细线标出高潮范围；拖动和播放位置计算仍使用整首歌曲时长，不影响 seek。

实测歌曲“再见”（酷我 musicId=112051）官方返回 `refrain_start=39482`、`refrain_end=69719`，服务端对应为 `refrainStartMs=39482`、`refrainEndMs=69719`。