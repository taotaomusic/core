# 001 — 隔离歌词的帧级状态

- **状态**：DONE
- **基线提交**：89c8bb0
- **严重度**：高
- **类别**：性能、状态提示
- **预计范围**：2 个 Kotlin 文件，约 100 行

## 问题

`androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt:1455-1468` 在播放时每帧更新 `positionMs`；`androidApp/src/main/java/com/taotao/music/ui/LyricPanel.kt:98-105` 将该值传给每一个可见歌词行，尽管非当前行 `LyricRow` 根本不读取它。当前行切换还会在 `LyricPanel.kt:119-140` 直接切换字号、行高和字重，自动滚动时会发生布局跳动。

```kotlin
// LyricPanel.kt:98-105 — 当前代码
LyricRow(
    line = line,
    active = lyric.synced && index == currentIndex,
    centered = lyric.synced,
    positionMs = positionMs,
    onClick = if (lyric.synced) ({ onSeek(line.timeMs) }) else null,
)
```

## 目标

- 只有逐字高亮的当前行接收帧级播放位置；普通行只接收稳定的 `active` 状态。
- 当前行样式只变化颜色与字重，不再动画化字号、行高或列表几何尺寸。
- 自动定位维持 `animateScrollToItem`，用户滑动后的 2,500ms 保护不变。

## 遵循的项目约定

- 动效时长和曲线集中在 `androidApp/src/main/java/com/taotao/music/ui/AnimationTheme.kt:39-96`。
- `LyricPanel.kt:72-86` 已正确用 `snapshotFlow` 避免中断滚动协程，必须保留。

## 步骤

1. 在 `LyricPanel.kt` 将 `LyricRow` 拆分为普通行和当前逐字行；普通行不再接收 `positionMs`。
2. 普通行和当前行使用相同的字号 `18.sp`、行高 `26.sp`，仅用颜色和字重表示当前状态，避免每句歌词改变 LazyColumn 布局高度。
3. 保留逐字行的 `FlowRow` 与 `Brush.horizontalGradient`；它仍接收帧级 `positionMs`，因为这是逐字进度的必要输入。
4. 在 `TaotaoMusicApp.kt` 保持当前的 `withFrameMillis` 播放位置精度，不用降低歌词同步精度来换性能。

## 边界

- 不改歌词解析、时间轴数据结构或播放器 seek 行为。
- 不添加第三方依赖。

## 验证

- **机械验证**：`./gradlew.bat :androidApp:assembleRelease` 成功。
- **体验检查**：播放含逐字歌词歌曲，连续观察 30 秒；当前行应平滑逐字推进，非当前行不应随每帧重新绘制，换句时不应有整行高度跳动。
- **完成标准**：调试重组计数器时，帧级更新仅影响当前逐字行、进度区域与必要的歌词容器。
