# 003 — 精简高频控件与搜索结果动效

- **状态**：DONE
- **基线提交**：89c8bb0
- **严重度**：中
- **类别**：目的与频率、性能
- **预计范围**：2 个 Kotlin 文件，约 70 行

## 问题

`components.kt:186-197` 的播放/暂停使用 140ms `Crossfade`，而它被迷你播放器和详情页的核心控制频繁调用。`SearchPage.kt:155-175` 让每条搜索结果进入时执行淡入、平移、缩放和弹簧，首批和翻页结果抵达时干扰浏览。

## 目标

- 播放/暂停图标即时切换，保留 Material 控件的按压波纹作为反馈。
- 搜索结果直接进入列表；保留骨架、错误、空态的 220ms opacity 过渡，避免异步状态突变。
- 保留封面淡入和收藏的状态反馈；收藏缩放改为低回弹、无多余图标重叠。

## 遵循的项目约定

- `AnimationDurations.MICRO = 140` 与 `AnimationDurations.FADE = 220` 位于 `AnimationTheme.kt:39-57`。
- Coil 封面交叉淡入位于 `components.kt:70-87`，不属于高频手势动画。

## 步骤

1. 将 `PlayPauseIcon` 改为根据 `isPlaying` 直接选择图标，移除 `Crossfade`。
2. 移除 `SearchPage.kt` 单行的首次 `AnimatedVisibility` 和相关 `animated` 集合，仅保留带稳定 key 的 `LazyColumn` 项。
3. 不移除搜索骨架、错误和空态的共享 `contentFadeIn/contentFadeOut`。

## 边界

- 不改变分页、去重、预取或收藏业务逻辑。
- 不为列表添加按下标的延迟动画。

## 验证

- **机械验证**：`./gradlew.bat :androidApp:assembleRelease` 成功。
- **体验检查**：连续点击播放/暂停时图标应即时、没有双影；多次搜索和加载更多时新结果应可立即阅读，不重复播放进场动画。
- **完成标准**：高频核心操作不保留装饰性 Crossfade 或列表入场弹簧。
