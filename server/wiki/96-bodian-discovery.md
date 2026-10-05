# 波点发现页与首页推荐接口

[返回文档中心](README.md)

最后更新:2026-10-04

本文记录从波点 Android APK（5.9.1，versionCode 476）反编译得到的接口，以及使用已登录账号对官方 `bd-api.kuwo.cn` 的只读实网验证结果。

本文把两个容易混淆的页面分开：

1. **启动后的上下滑动页**：接口是 `/rec/feed`，每次返回一个 Feed 歌曲/广告对象。
2. **发现页（音乐库页）**：先请求 `/service/home/index` 得到模块顺序，再按 `moduleId` 请求 `/service/home/module`。用户看到的“偶遇心动单曲”“宝藏歌单库”“心动收藏相似推荐”等都属于这一层。

## 1. 通用请求协议

官方 API 根地址：

```text
https://bd-api.kuwo.cn/api/
```

通用请求由波点客户端的 `ApiService` 通过 `encrypt=true` 发起。服务端复刻层把它实现为带官方参数的 GET：

```text
uid=<数字 uid>
token=<账号 token>
timestamp=<Unix 秒>
sign=<官方 MD5 签名>
```

同时发送 APK 的 Android 请求头，其中 `payload` 是 XOR 后再 Base64 的设备信息 JSON。签名实现位于 `server/src/upstream/bodian.client.ts` 的 `officialUrl`、`officialHeaders` 和 `officialSign`：

```text
normalizedQuery = query 去掉非字母数字字符后排序
bodyHash        = body 存在时 MD5(body + "kuwotest")，否则空串
sign            = MD5("kuwotest" + normalizedQuery + bodyHash + absolutePath)
```

发现页这些接口的官方响应信封是：

```json
{
  "code": 200,
  "msg": "success",
  "reqId": "...",
  "data": {},
  "profileId": "...",
  "curTime": 0
}
```

`code=200` 表示官方业务成功。它与桃桃后端自己的 `{code: 0, message, data}` 信封不是同一层，适配时不能把两个业务码混用。

## 2. 发现页总入口

### 2.1 模块索引

```http
GET /service/home/index
```

实网验证：HTTP `200`、业务 `code=200`。当前账号返回 `data.moduleList`，模块顺序与标题是服务端下发的，不应在客户端写死。当前返回的模块如下：

| moduleId | type | 官方标题 | 模块响应主字段 |
| ---: | ---: | --- | --- |
| 1 | 4 | 个性化歌单 | `songList` |
| 3 | 3 | 偶遇心动单曲 | `musicList` |
| 0 | 8 | 广告 | 通常为空，由跳转/广告逻辑处理 |
| 2 | 5 | 宝藏歌单库 | `songList` |
| 10 | 10 | 心动收藏相似推荐 | `musicList` |
| 6 | 1 | 轮播图 | `bannerList` |
| 12 | 11 | 你的主题歌单 | `songList` |
| 5 | 2 | 波波排行榜 | `bangList` |
| 9 | 9 | 音乐日历 | `calendar` |
| 4 | 6 | 波点实验室 | `lab` |
| 13 | 12 | 数字专辑馆 | `rank` |
| 11 | 7 | 听点不一样的 | `video` |

索引项的静态模型字段为 `id`、`type`、`name`、`status`、`delayMs`、可选 `jump`。例如数字专辑馆的 `jump` 是：

```json
{
  "sourceType": 5,
  "source": "https://h5app.kuwo.cn/m/bd_digitaltalbum_shop/index.html"
}
```

### 2.2 拉取一个模块

```http
GET /service/home/module?moduleId=<moduleId>
```

反编译证据：`discovery/homeV2` 的 `DiscPageBaseState.doRequest` 构造 `{moduleId: id}`，通过 `encrypt=true` 调用该路径。实网对全部当前 `moduleId` 做过只读请求，均 HTTP `200`、业务 `code=200`。

各模块真实响应形状如下。

#### 偶遇心动单曲（moduleId=3）

```json
{
  "moduleName": "偶遇心动单曲",
  "type": 3,
  "musicList": [
    {
      "id": 635389653,
      "name": "有缘无份",
      "artist": "文夫",
      "album": "有缘无份",
      "duration": 0,
      "vid": 0,
      "audios": [],
      "payInfo": {
        "listen_fragment": "1",
        "play": "1111",
        "download": "1111",
        "refrain_start": "0",
        "refrain_end": "0"
      }
    }
  ]
}
```

歌曲完整对象还可能带 `albumId`、`albumPic`、`artists`、`isMv`、`mvPic`、`mvduration`、`videoFmtCodes`、`musicRid`、`lrc_info`、`src`、`data_source`、`haveAudition` 等字段。不能只按 `id/name/artist` 建模后丢弃其余字段：播放档位、MV 和高潮区间都在扩展字段中。

#### 宝藏歌单库（moduleId=2）

响应主字段为 `songList`，但这里的每一项是歌单摘要，不是歌曲：

```json
{
  "moduleName": "宝藏歌单库",
  "type": 5,
  "songList": [
    {
      "id": 73334391,
      "name": "粤语DJ太上头！心跟着节奏跳动",
      "pic": "https://...",
      "creatorId": 0,
      "description": "...",
      "isPrivate": 0,
      "playNum": 0,
      "isFond": 0,
      "lastPlayTime": "",
      "sourceType": 6,
      "traceId": "..."
    }
  ]
}
```

APK 字符串表还确认了歌单详情相关路径：`service/playlist/info/%s`、`service/playlist/%s/musicList`、`service/playlist/%s/musicList/filter` 和 `service/playlist/playCount`。详情请求的关键参数已从 APK 汇编确认：路径中的 `%s` 是歌单 `id`，查询参数还必须带整数 `source`，其值直接取模块摘要的 `sourceType`。例如当前实网返回的 `sourceType=13` 歌单，调用 `/service/playlist/info/<id>?source=13` 成功；只带数字 ID 会返回 `code=-10（参数错误）`。

歌单详情响应字段包括 `id`、`author`、`name`、`pic`、`creatorId`、`creatorName`、`creatorIcon`、`description`、`praise`、`musicCount`、`isPrivate`、`playNum`、`isFond`、`createTime`、`lastPlayTime`、`collectTime`、`sourceType`、`collectedCnt`。

歌单歌曲列表同样带来源和分页：

```http
GET /service/playlist/<playlistId>/musicList?source=<sourceType>&pn=1&rn=20
```

实网验证 `source=13,pn=1,rn=20` 成功，响应为 MyBatis 风格分页对象：`pageNum`、`pageSize`、`size`、`startRow`、`endRow`、`total`、`pages`、`list`、`prePage`、`nextPage`、`isFirstPage`、`isLastPage`、`hasPreviousPage`、`hasNextPage`、`navigatePages`、`navigateFirstPage`、`navigateLastPage`、`firstPage`、`lastPage`；`list` 是完整歌曲对象。

#### 心动收藏相似推荐（moduleId=10）

这是发现页自己的推荐模块，不能与歌曲详情页的 `/rec/music/ip/relation` 混为一个接口：

```json
{
  "moduleName": "心动收藏相似推荐",
  "type": 10,
  "musicList": [
    {
      "id": 492312327,
      "name": "Underground (逆风小曲)",
      "artist": "Vahpe",
      "vid": 0,
      "isMv": 0,
      "payInfo": {
        "refrain_start": "162091",
        "refrain_end": "181657"
      }
    }
  ]
}
```

#### 个性化歌单（moduleId=1）

`type=4`，响应为 `songList`，每项是一个小歌单卡片：

```json
{
  "moduleName": "个性化歌单",
  "type": 4,
  "num": 3,
  "songList": [
    {
      "id": 0,
      "title": "潮趣日推",
      "titleEng": "...",
      "songs": [/* 完整歌曲对象 */]
    }
  ]
}
```

#### 你的主题歌单（moduleId=12）

`type=11`，也是 `songList`，但每项多一个 `passRecName`：

```json
{
  "moduleName": "你的主题歌单",
  "type": 11,
  "num": 3,
  "songList": [
    {
      "title": "华文流行极致",
      "passRecName": "0_15",
      "songs": [/* 完整歌曲对象 */]
    }
  ]
}
```

#### 波波排行榜（moduleId=5）

`type=2`，响应为 `bangList`。每个榜单含 `id`、`name`、`pic`、`pub`、`musics`，`musics` 内是歌曲对象。

#### 轮播图（moduleId=6）

`type=1`，响应为 `bannerList`。常见字段：`id`、`pic`、`sourceType`、`source`、`title`、`startVersion`、`popType`、`popLimit`、`popRange`、`xhType`、`xhId`、`musicIds`、`vipType`。跳转必须按 `sourceType/source` 解析，不能假定都是站内页面。

#### 音乐日历、实验室、视频、数字专辑

| moduleId | type | 主字段 | 已确认结构 |
| ---: | ---: | --- | --- |
| 4 | 6 | `lab` | 含 `calendar`、`schemaJump`、`music` |
| 9 | 9 | `calendar` | 音乐日历数据 |
| 11 | 7 | `video` | 视频推荐数据 |
| 13 | 12 | `rank` | 数字专辑摘要：`name`、`albumType`、`albumId`、`singer`、`img`、`cntValue`、`onSale`、`jump` |

## 3. 主题歌单详情接口

APK 的 `disc_ai_songlist_block.dart`、`disc_theme_songlist_block.dart` 和 `details/ai_song_list_page.dart` 都引用同一个接口：

```http
GET /service/home/aiPlaylistDetail?index=<index>&passRecName=<passRecName>
```

`index` 是主题歌单在 `songList` 中的下标；`passRecName` 直接使用模块项返回的字符串，不能自行重算。实网验证：

- `index=0&passRecName=0_15` → HTTP `200`、`code=200`，`data` 键为 `title`、`subTitle`、`musicList`。
- `index=1&passRecName=0_15` → HTTP `200`、`code=200`，同样返回 `title`、`subTitle`、`musicList`。

## 4. 音乐库分类与分页

### 4.1 分类导航

```http
GET /play/music/library/navigation
```

实网返回数组，共 15 个一级分类。一级项字段：

```text
id, displayStyle, miniCount, childColor, flowerPic,
lightColorBgImg, deepColorBgImg, childType, longDesc,
priority, updateDate, coverPic, foreignLanguagePic,
albumTotal, typeToTal, ptypeName, externalTitle,
internalTitle, childList
```

`childList` 子项字段：`id`、`name`、`childType`、`longDesc`、`indexShow`、`ptypeId`、`color`、`longDescTitle`、`albumVo`。

以实测第一项“流行”为例：一级 `id=2005`，第一个子类 `id=8101`，该子类的 `ptypeId=3401`。

### 4.2 分类摘要

```http
GET /play/music/library/summary?pTypeId=<一级 id>&cTypeId=<子类 id>
```

参数关系已通过实网对照确认：`pTypeId` 使用一级项的 `id`（例如 `2005`），`cTypeId` 使用子项的 `id`（例如 `8101`）。把子项的 `ptypeId=3401` 填进 `pTypeId` 会得到空/无效数据。

响应模型：

```json
{
  "hotSongs": [],
  "hotAlbums": [],
  "hotSingers": []
}
```

### 4.3 专辑列表

```http
GET /play/music/library/albums?pTypeId=<一级 id>&cTypeId=<子类 id>&pn=<页码>&rn=<条数>&sort=<排序>
```

- `pn` 从 `1` 开始。
- `sort=1` 是“精品”，`sort=2` 是“最新”（由 APK 的排序选项构造证实）。
- `rn` 是客户端按屏幕高度计算的分页大小，默认状态为 `10`，不是固定的官方常量。

响应为 `{total, list}`。实测 `pTypeId=2005,cTypeId=8101,pn=1,rn=10,sort=1` 返回 `total=5178` 和 10 个专辑项。列表项至少含 `id`、`albumId`、`name`、`pic`、`artist`、`artistId`、`artists`、`showtime`、`info`、`sourceType`、`musicCount`、`lastPlayTime`、`isshow`。

## 5. 启动后的上下滑动 Feed

这一页与发现页是另一条链路。

### 5.1 取当前 Feed 项

```http
GET /rec/feed
```

实网返回 HTTP `200`、业务 `code=200`，`data` 直接是一个歌曲或广告歌曲对象，不是数组。对象除标准歌曲字段外可能包含：

```text
feedConfig, bgMusic, videoType, videoFmtCodes, mvduration,
payInfo, audios, haveAudition, lrc_info, offline
```

`feedConfig` 可能包含：`id`、`type`、`pic`、`bgMusic`、`bgColor`、`btnColor`、`btnText`、`sourceType`、`source`、`clickFlag`、`firstShow`、`intervalMusic`、`feedIntervalSeconds`、`guideAdvertNum`。

静态模型还确认过 Feed 配置类型：`man_you_card`、`none_song`、`new_song`、`songList_feed_song`。当前实网样本曾返回广告型 `feedConfig.type=1`，所以客户端必须允许 `data` 不是普通歌曲。

### 5.2 上下滑动推荐候选

```http
GET /service/music/recommendList
```

APK 的通用推荐构造器会使用以下参数（空值按场景省略）：

```text
fg, lock, lastColdStartTime, scrollNum, resourceId,
recoMode, source, sourceId, mvFirstPlay, totalNum
```

卡片模式会额外带：

```text
select_card=1
repeat_cut=1
repeat_cut_ids=<逗号分隔的已展示歌曲 ID>
```

实网 `GET /service/music/recommendList?select_card=1` 返回 `musicList`，当前样本为 7 首。歌曲对象同样带 `payInfo.refrain_start/refrain_end`、`audios`、`vid` 等字段。

### 5.3 Feed 上报

```http
GET /rec/feed/report?feedId=<Feed 歌曲/卡片 id>
```

这是行为上报接口，不属于只读取数；调试文档只记录其参数，不在验证脚本里主动上报，避免污染账号推荐状态。

## 6. 歌曲详情、相似推荐和 MV

### 6.1 单曲详情

```http
GET /service/music/info?musicId=<歌曲 ID>
```

实网对歌曲 `188215744` 验证成功。详情比推荐列表更完整，包含 `favorite`、`share`、`comment`、`downloadAdvert`、完整 `audios` 音质目录、`payInfo` 权限位、`lrc_info`、MV 字段和高潮区间。

### 6.2 歌曲详情页的相似/关联歌曲

```http
GET /rec/music/ip/relation?pn=<页码>&rn=<条数>&ipId=<歌曲或 IP ID>
```

APK 将该来源标识为 `songDetailRelatedRecommend`，页面文案为“更多心动歌曲/相似歌曲”。它与发现页 `moduleId=10` 的“心动收藏相似推荐”是两条不同接口。

播放页还引用：

```http
GET /rec/music/reason?musicId=<歌曲 ID>
GET /rec/config/similar
GET /rec/music/newSongReport
```

实网验证 `/rec/music/ip/relation?pn=1&rn=10&ipId=188215744` 成功，响应键为 `list`、`hasNextPage`；`/rec/config/similar` 成功返回 `similarRecDayLimit`、`similarRecInterval`、`similarRecSwitch`。`/rec/music/reason` 不带参数会明确返回缺少 `musicId`，所以它不是无参配置接口。`/rec/music/newSongReport` 是行为上报，不应在只读验证中调用。

### 6.3 MV

歌曲详情的 MV 信息接口：

```http
GET /service/mv/info?musicId=<歌曲 ID>
```

实网对 `musicId=188215744` 验证成功，响应为 `{mv: {...}}`，字段包括：`mid`、`name`、`coverUrl`、`highUrl`、`lowUrl`、`mvDuration`、`highP2pid`、`lowP2pid`、`highBitrate`、`lowBitrate`。客户端应优先使用 `highUrl`，失败再回退 `lowUrl`，并在应用内播放器加载 URL，不要把它当 H5 跳转链接。

MV 歌词接口：

```http
GET /service/mv/lyric?musicId=<歌曲 ID>
```

实网返回 `{content: "..."}`，`content` 是 Base64 文本，解码后为 LRC；它不是普通歌曲歌词接口的明文 JSON。

### 6.4 高潮区间

所有发现/推荐/详情歌曲对象都可能携带：

```text
payInfo.refrain_start
payInfo.refrain_end
```

单位已经是毫秒。服务端统一映射成 `refrainStartMs` / `refrainEndMs`，不能再乘 1000。值为 `0/0` 或缺失表示官方没有提供可绘制高潮区间。

## 7. 旧版发现页接口（兼容分支）

APK 仍保留旧的 `discovery/home` 页面，不能与当前 `homeV2` 模块入口混用：

```http
GET /service/v2/finds/index?uid=<uid>&categoryId=<categoryId>
GET /service/finds/module?moduleId=<moduleId>
GET /service/resource/recommend
GET /service/category/<category>/musics?pn=<页码>&rn=60
GET /service/category/playlist
GET /service/category/<category>/playlist
```

当前用户描述的“偶遇心动单曲/宝藏歌单库/心动收藏相似推荐”已经由 `/service/home/index` + `/service/home/module` 实网确认，接入新 App 时应优先走新链路；只有兼容旧页面或旧深链时才保留上述接口。

## 8. 验证结论与接入顺序

本次只读实网验证覆盖：

- `/service/home/index`
- `/service/home/module` 的全部当前模块 ID（0、1、2、3、4、5、6、9、10、11、12、13）
- `/service/home/aiPlaylistDetail`
- `/service/playlist/info/<playlistId>?source=<sourceType>`
- `/service/playlist/<playlistId>/musicList?source=<sourceType>&pn=1&rn=20`
- `/play/music/library/navigation`
- `/play/music/library/summary`
- `/play/music/library/albums`
- `/rec/feed`
- `/service/music/recommendList?select_card=1`
- `/service/music/info`
- `/service/mv/info`
- `/service/mv/lyric`
- `/rec/music/ip/relation?pn=1&rn=10&ipId=188215744`
- `/rec/config/similar`

推荐实现顺序：

1. 启动后先请求 `/service/home/index`，按 `moduleList` 动态渲染模块顺序。
2. 进入发现页后按需请求 `/service/home/module?moduleId=...`，不要一次把所有模块固定写死。
3. 模块 3、10 直接消费 `musicList`；模块 1、12 消费内层 `songs`；模块 2 消费歌单摘要。
4. 播放前从歌曲对象的 `audios` 和 `payInfo` 判断可展示音质；最终能否播放仍以取址接口结果为准。
5. 有 `vid/isMv/mvduration` 时再调用 `/service/mv/info`，MV 播放失败时走 `lowUrl`。
6. 只有在用户真正产生上下滑动或播放行为时才调用 `/rec/feed/report`、`/rec/music/newSongReport` 等上报接口。

发现页标题、模块顺序、推荐歌曲、广告和 `jump` 地址都是服务端动态数据。客户端只能依赖字段和 `type` 分派，不能依赖今天的固定顺序或固定歌曲。
