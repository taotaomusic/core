# 搜索与播放契约

[返回文档中心](README.md)

最后更新:2026-10-04

本文覆盖搜索联想、热搜、NDJSON 搜索、播放地址、播放代理、歌词和 MV 信息。上游协议适配的内部结构见 [11-architecture-modules.md](11-architecture-modules.md) 的 `upstream/` 一节;音源账号对播放链路的影响见 [53-feature-music-sources.md](53-feature-music-sources.md)。排障见 [72-troubleshooting-music.md](72-troubleshooting-music.md)。

## 1. 搜索联想

```http
GET /api/v1/search/suggestions?keyword=雨&limit=10&source=kuwo
Authorization: Bearer <accessToken>
```

返回普通 JSON 信封,`data` 是按官方顺序排列的联想词数组:

```json
{
  "code": 0,
  "message": "success",
  "data": ["雨爱", "雨一直下", "雨蝶"]
}
```

- `keyword` 必填,去除首尾空白后为空返回 400/4001。
- `limit` 默认 10,最大 20;限制在本服务本地执行。
- `source` 当前仅支持 `kuwo`,省略时默认使用 `kuwo`;指定其他音源返回 400/4007(该源不支持联想)。
- 官方波点 5.9.1 调用 `search/tip/v2/list`,只传业务参数 `keyword` 与 `tipsFrom=bd`,读取 `data.resultList[].relword`。该接口没有分页参数,**不要自行添加 `pn` / `rn`**,以免联想排序或召回结果偏离官方客户端。

## 2. 热搜

```http
GET /api/v1/search/hot?limit=20&source=kuwo
Authorization: Bearer <accessToken>
```

返回普通 JSON 信封,`data` 为官方排序的热搜对象数组:

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "keyword": "起风了",
      "type": 0,
      "icon": "",
      "sort": 1,
      "searchType": 4,
      "jumpUrl": "123456"
    }
  ]
}
```

- `limit` 默认 10,最大 20;`source` 省略时默认 `kuwo`。
- `keyword` 可直接作为普通搜索词使用。
- `searchType` / `jumpUrl` 保留官方定向跳转信息;不需要定向跳转的客户端可忽略。
- 官方波点 5.9.1 使用无业务参数的 `GET search/topic/word/list`,热搜列表位于 `data.hotWord`。同一响应还含专题、运营入口等内容,它们不是热搜,不混入本接口。

## 3. NDJSON 搜索

```http
GET /api/v1/search?keyword=周杰伦&page=1&num=60&quality=10
Authorization: Bearer <accessToken>
```

响应是**裸 NDJSON**,不是信封。行序固定为 **artist 行 → album 行 → song 行 → end 行**。每首歌一行:

```json
{"type":"song","data":{"id":97773,"mid":"...","favorited":false,"vip":false,"playable":true}}
```

song 行之前可以出现两类先行行(单音源模式且该音源支持时):

```json
{"type":"artist","data":{"source":"kuwo","id":336,"name":"周杰伦","pic":"https://...","songCount":1750,"albumCount":49}}
{"type":"album","data":{"source":"kuwo","id":87758985,"name":"太阳之子","pic":"https://...","artist":"周杰伦","artistId":336,"songCount":13,"showtime":"2026-03-25"}}
```

- 仅当 `source` 指定**单个音源**、且该音源的上游适配器实现了歌手/专辑搜索时输出,且**只在第 1 页**输出(翻页不重复下发,客户端翻页时保留第 1 页的区块即可);当前只有 `kuwo`(波点)支持。`source=all` 聚合模式一律没有这两类行(聚合的腾讯/网易没有该能力,多源同名义歌手去重也刻意不做)。
- artist 行最多 3 条,album 行最多 6 条;`data.source` 是当前请求的音源名。
- 上游歌手/专辑搜索失败或召回为空时**整段省略**(不输出空行、不占位),song 行照常下发;客户端对未知 `type` 静默跳过,旧版本不受影响。开放接口 `/open/search/stream` 与本接口同格式,同样适用。
- `songCount` / `albumCount` 是归一化后的字段名(上游叫 `songNum` / `albumNum` / `musicCount`,出流前统一改名);`showtime`(发行日期)与 `pic` 上游可能缺失或为空串。

末行(`meta` 为完整形状,旧客户端读不到多出的字段不受影响):

```json
{"type":"end","meta":{"page":1,"limit":60,"quality":10,"count":60,"dropped":0,"droppedBySource":{},"total":97773,"hasMore":true,"source":"all"}}
```

- `keyword` 必填,为空返回 400/4001;`page` 默认 1,`num`/`limit` 二者等价(历史参数名是 `num`),默认 60、上限 60。
- `source` 省略或 `all` 时聚合全部音源;指定单个音源时只查该源。聚合搜索允许单个上游短暂故障,另一个音源的结果照常下发,两个都失败才报上游错误。
- `meta.count` 是实际下发条数,`total`/`hasMore` 来自上游分页信息。

### `playable` 的语义

`playable` 是**这首歌能不能拿到播放地址**。`false` 时客户端必须置灰并标注,不要发起播放(发起也只会拿到一句「没有可用播放链接」)。

- 与 `vip` **不是一回事**:`vip` 是「上游标它付费」,`playable` 是「实测取不到地址」。
- **不可播的歌照常下发**,不丢。依据是波点 App 自己也不滤:同一关键词下它综合页的 `musicpage` 与我们的原始列表逐条一致,它把放不了的歌也列出来。
  2026-09-19 之前我们按 `listen_fragment` 滤掉,导致搜「周杰伦」上游 30 条全被滤、客户端拿到 0 条 —— 用户看到的是「搜不到歌」,第一反应是账号或音源坏了。那 30 首在酷我上都没有版权(周杰伦是 TME 独家),上游逐首回 `code 20012 歌曲已下线`。
- 只有酷我会判断;QQ 音乐与网易云恒为 `true`。

### `dropped` 的语义

`dropped` 是**上游返回了、但没有下发**的条数。**目前恒为 0** —— 酷我也不再丢歌了。字段与 `droppedBySource` 都**不能删**:装机的旧客户端会读 `dropped` 做算术,拿到 `undefined` 会算出 `NaN`。保留它们只为契约兼容,不再承载信息。

### `refrainStartMs` / `refrainEndMs`(高潮区间)

`/search`、`GET /songs/:id/info` 与 `GET /songs/batch-info` 的歌曲对象都带这两个字段(毫秒):波点协议解析出 `payInfo.refrain_start/refrain_end`,服务端只做改名透传(值本身就是毫秒);上游缺失时键不存在,**不伪造默认值**(QQ 音乐与网易云源恒缺失)。客户端在进度条上以主题强调色标出高潮范围,seek 计算不受影响。字段语义与实测样本见 [95-playback-refrain.md](95-playback-refrain.md)。

### 红线

- `data.id` 是 JSON number。
- 收藏接口的 `songId` 是 JSON string。
- `coverUrl` 是绝对 HTTPS。
- `lyricUrl` 是带 `/api/v1/` 的相对路径。
- `favorited`、`vip`、`playable` 是 boolean。
- 响应头包含 `X-Accel-Buffering: no`。
- 搜索只返回元信息,不逐首解析播放地址。

## 4. 播放地址

```http
GET /api/v1/songs/97773/link?quality=10&mid=&type=
```

- 返回上游 HTTPS 直链和实际音质,响应字段为 `songId`、`url`、`quality`、`requestedQuality`、`kbps`、`fallback`。
- 请求档位不存在时服务端降级,并设置 `fallback: true`(`quality != requestedQuality`)。

## 5. 播放代理

`/play` 是旧客户端兼容和解析失败兜底。

- Range 请求应返回 206 与 `Content-Range`;上游不支持 Range 时回落 200 全量。
- 目标地址不在媒体域名白名单内返回 400/4002「不允许转发此地址」。
- 上游非 2xx 统一映射为 **502/5021**,不能透传上游 401(否则客户端会当成自己的令牌失效去续期)。

## 6. 歌词

- 默认:裸 LRC 文本(`text/plain`,旧客户端直接展示响应体)。
- `format=json`:返回 `{lrc, yrc, trans}`,走信封。
- 无歌词:502,**不能返回 401**。

## 7. 音质与音源账号

- 请求的 `quality` 档位由上游音质阶梯决定降级路径;`fallback: true` 表示实际下发的音质低于请求档位。
- 酷我/波点链路的取址依赖服务端音源账号凭据;凭据失效时表现为「搜得到放不出」,处理见 [53-feature-music-sources.md](53-feature-music-sources.md)。

## 8. MV 信息

```http
GET /api/v1/songs/97773/mv?source=kuwo
Authorization: Bearer <accessToken>
```

返回普通 JSON 信封,`data` 是上游官方 `service/mv/info` 响应里的 `mv` 对象,字段**原样透传、不改名**:

- `mid`:MV 的数字 ID;`name`:MV 名称;`coverUrl`:封面地址。
- `highUrl` / `lowUrl`:高、低两档清晰度的播放地址;`highP2pid` / `lowP2pid` 与 `highBitrate` / `lowBitrate` 分别是这两档对应的 P2P ID 与码率。
- `mvDuration`:MV 时长。

- 路径参数是**歌曲的酷我数字 ID**(搜索结果里的 `data.id`),不是 MV 的 ID;只接受数字,不接受 `mid`(酷我没有 mid)。
- `source` 必须显式传 `kuwo`:省略时与 `/songs/:id/link` 一样默认按腾讯音乐解析,而腾讯/网易适配器没有实现 MV 能力,只会拿到 400/4001「QQ 音乐暂不支持 MV」;未知音源 400/4001「不支持的音乐来源」。
- 鉴权与搜索一致:路由未标 `@Public()`,走全局访问令牌守卫;该路由未配置限流桶。
- **歌曲没有 MV**(上游业务码不是 200,或响应里没有 `mv` 对象):适配器统一归为「取不到」,服务端报 **502/5020「MV 信息不可用」**;上游的具体业务码**不透传**。
- **音源不支持 MV**:400/4001,文案「{音源名}暂不支持 MV」。
