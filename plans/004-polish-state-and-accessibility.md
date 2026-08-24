# 004 — 补齐状态过渡与无障碍语义

- **状态**：DONE
- **基线提交**：89c8bb0
- **严重度**：中
- **类别**：状态提示、无障碍、物理性
- **预计范围**：5 个 Kotlin 文件，约 150 行

## 问题

登录/注册表单的 `AuthPage.kt:184-203` 使用默认 `AnimatedVisibility`，会走尺寸展开且未接入 token。`TaotaoMusicApp.kt:1740-1771` 的播放队列 Tab 内容直接替换。搜索状态 `SearchPage.kt:108-127` 缺少辅助技术可读的状态语义；同步歌词的点击动作 `LyricPanel.kt:125-129` 没有说明会跳转播放时间。

## 目标

- 注册附加字段使用 opacity + `translateY` 小幅进入/退出，不动画化尺寸；时长 200ms，采用 `AnimationCurves.emphasizedIn`。
- 播放队列 Tab 内容在 220ms opacity 过渡中切换，不使用列表尺寸动画。
- 搜索加载、错误、空结果提供 `liveRegion` 状态提示；歌词行提供“跳转到此句”的点击标签。
- 音质选项与当前队列项的颜色/图标状态使用 140ms 颜色/alpha 反馈，不生成新的布局动画。

## 遵循的项目约定

- 所有时长和曲线来自 `AnimationTheme.kt:39-96`。
- `ModalBottomSheet` 保留 Material 3 管理的手势、可打断和系统动画，见 `QualitySheet.kt:68-94`。

## 步骤

1. 为 `AuthPage.kt` 的 `AnimatedVisibility` 显式配置 opacity 与垂直 transform 过渡，避免默认 expand/shrink。
2. 将播放队列的 Tab 正文包入共享的 content fade 过渡，切换只影响正文区域。
3. 给 `SearchPage.kt` 的状态容器添加合适的 `liveRegion` 与文本语义。
4. 给 `LyricPanel.kt` 可点击歌词添加明确的 `onClickLabel`。
5. 为 `QualitySheet.kt` 和队列当前项补充不改变大小的状态反馈。

## 边界

- 不改变 TalkBack 的现有控件 content description。
- 不修改系统底部面板的手势、焦点或 dismissal 行为。
- 不增加外部依赖。

## 验证

- **机械验证**：`./gradlew.bat :androidApp:assembleRelease` 成功。
- **体验检查**：切换注册模式和队列来源时无布局抖动；TalkBack 聚焦歌词行时能听到“跳转到此句”；搜索结束时可读出结果状态。
- **完成标准**：状态变化既不会瞬移，也不会用尺寸动画牺牲帧时间；辅助技术能理解异步状态和歌词点击动作。
