# 桃桃音乐三端播放器与双端客户端功能说明

本文说明 Android 与 Windows 客户端共同支持的音乐功能、Web 分享播放器、云端数据边界和开发验收方式。AI 图片与 IM 属于 Android 现有能力，Windows 与 Web 当前不接入；本轮只对齐音乐主链路。

源码模块边界、关键符号、跨端调用关系和 CodeGraph 刷新命令见 [client-code-index.md](client-code-index.md)。

## 功能范围

两端都支持：

- 账号登录、注册和账号级数据隔离
- 腾讯音乐与网易云音乐搜索、队列播放、歌词、收藏、最近播放和离线下载
- 播放音质与下载音质选择，以及上游不可用时的明确降级提示
- 云端“我的歌单”：创建、改名、删除、查看、加入歌曲、移除歌曲和排序
- 定时播放：选择倒计时，倒计时结束后停止当前播放并清除计时状态

Windows 使用 Compose Desktop + JVM 播放器，Android 使用 Compose + Media3，Web 分享页使用 Compose Multiplatform + Kotlin/Wasm。三端复用 `player-ui` 中的品牌主题、歌曲信息、进度、主控制区以及加载、空数据、错误状态视图；Android 与 Windows 还共用歌曲行和迷你播放器骨架，通过插槽保留收藏、下载进度、菜单、拖拽、音量、定时器和队列等平台能力。封面加载、歌词、播放队列、下载能力和实际播放引擎仍由各端适配。`shared` 继续承载 `Song`、`Lyric`、`AudioQuality` 等纯模型和业务规则。

## 定时播放

定时播放是播放器级状态，不是页面级按钮。启用后客户端按“实际播放时间”倒计时：切换页面、窗口隐藏或 Android 进入后台不会暂停计时，手动暂停则冻结剩余时间，切歌不会重置；到期执行播放器 `stop`，而不是只暂停当前媒体。停止后计时器自动关闭，下一次播放不会意外继承旧计时。

Android 提供 15、30、45、60、90、120 分钟及 1-1440 分钟自定义时长；Windows 提供 5、10、15、30、60、90 分钟及同范围自定义时长。剩余时间显示在播放页和设置页；重新选择时长会从当前时刻重新计时，选择“关闭”立即取消。计时状态由 Android 播放服务或 Windows 客户端进程持有，应用/播放服务被系统销毁后不会假装恢复旧倒计时，重新打开后可重新设置。

定时器不会删除设备上的下载文件或账号历史。Windows 会保留队列快照，之后手动播放时重新装载；Android 到期会释放当前 Media3 媒体会话，设备级队列文件仍保留。停止动作仍按正常播放生命周期记录已听时长；若网络不可用，统计先进入本地 outbox，恢复网络后补传。

## 云端歌单

歌单属于登录账号，服务端是权威副本；队列和下载仍是设备级数据。客户端打开歌单页时刷新云端，写操作成功后用服务端返回的 `revision` 和 `updatedAt` 更新本地快照。网络失败时保留页面上的本地快照并提示“同步失败”，不会把空响应当成云端已清空。

歌曲身份始终由 `source + songId` 组成。`songId` 是字符串，既可以是数字 ID，也可以是上游 `mid`；因此 `songID=0` 的 mid-only 歌曲不会与其他来源或歌曲冲突。歌曲展示信息随歌单项保存，播放时仍通过音乐接口重新解析短时直链。

### 接口

接口前缀为 `/api/v1`，以下请求均需 `Authorization: Bearer <access-token>`。普通 JSON 响应遵循项目统一信封：`{"code":0,"data":...}`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/playlists` | 当前账号的歌单列表，按更新时间倒序 |
| `POST` | `/playlists` | 创建歌单， body：`{"name":"夜间歌单","description":""}` |
| `GET` | `/playlists/:id` | 歌单详情及 `songs` |
| `PATCH` | `/playlists/:id` | 修改名称或描述 |
| `DELETE` | `/playlists/:id` | 删除歌单及其歌曲 |
| `POST` | `/playlists/:id/songs` | 添加歌曲，body 含 `source`、`songId`，可带 `mid/title/artist/album/duration/coverUrl/type` |
| `DELETE` | `/playlists/:id/songs/:source/:songId` | 移除指定歌曲（路径字段需 URL 编码） |
| `PATCH` | `/playlists/:id/songs/order` | 排序，body 可用 `songs`、`songIds` 或 `order`，元素含 `source`、`songId` |
| `PUT` | `/playlists/:id/songs` | 用完整有序数组替换歌曲，供离线同步；body：`{"songs":[...]}` |

歌单和歌单项的每次变更都会递增 `revision` 并更新 `updatedAt`。所有查询按当前用户过滤，不能通过修改路径中的 ID 读取他人歌单。重复添加同一 `source + songId` 时服务端保持幂等，不产生重复项。

## 数据流与缓存

搜索返回裸 NDJSON，客户端逐行渲染；点击歌曲后才解析上游直链，避免把过期 URL 写入队列。收藏和最近播放缓存带账号校验，退出或切换账号会清空当前内存快照；播放统计采用会话 + revision，跨设备清空后旧会话不会复活历史。

歌单元数据与歌曲快照存于服务端 PostgreSQL。客户端可以在本地保留最近一次成功快照，但不能把本地空列表直接上传覆盖云端；全量替换接口只在用户明确触发同步时使用。

## 开发与验收

在项目根目录使用 JDK 21 和 Gradle Wrapper：

```powershell
.\gradlew.bat :shared:allTests
.\gradlew.bat :player-ui:desktopTest
.\gradlew.bat :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest
.\gradlew.bat :desktopApp:compileKotlin :desktopApp:test
.\gradlew.bat :webApp:compileKotlinWasmJs
```

后端改动还需执行：

```powershell
cd server
npm exec tsc -- -p tsconfig.json --noEmit
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

Windows 运行和打包：

```powershell
.\gradlew.bat :desktopApp:run
.\gradlew.bat :desktopApp:packageUberJarForCurrentOS
```

MSI/EXE 需要有效的 WiX Toolset 3.11；便携 JAR 不依赖 WiX，但需要 JDK/JRE 21。Android 发布验收仍以 `:androidApp:assembleRelease` 生成的 APK 和 `output-metadata.json` 为准。

## 已知边界

Windows 的 FFmpeg 播放链路覆盖常见 MP3、M4A/AAC、WAV、FLAC、OGG/Opus 容器；腾讯 NAC 私有格式暂不保证可解码，界面会提示选择可用档位。Windows 暂不实现 AI 图片工作台、悟空 IM、系统托盘之外的设备协同控制；这些不属于本轮音乐功能对标范围。
