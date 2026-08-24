# 002 — 收紧页面层级与高频导航动效

- **状态**：DONE
- **基线提交**：89c8bb0
- **严重度**：高
- **类别**：缓动、目的与频率
- **预计范围**：2 个 Kotlin 文件，约 80 行

## 问题

`AnimationTheme.kt:68-75` 的 `emphasizedOut` 与 `standardOut` 是先慢后快的离场曲线，并被详情关闭、迷你播放器收起、页面离场和内容淡出复用。用户发起返回后会先感到迟滞。

```kotlin
// AnimationTheme.kt:68-75 — 当前代码
val emphasizedOut: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val standardOut: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
```

同层底栏导航在 `TaotaoMusicApp.kt:744-745,750-764` 每次执行 220ms 全页淡入淡出，不能解释空间关系且阻滞高频切换。

## 目标

- 入场、离场均使用快速起步的 Material 明确曲线：`CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)`；页面和内容时长不超过既有 280ms。
- 详情页保留从底部升起、关闭时落下的空间语义；迷你播放器保留进入/退出。
- `home` 与 `mine` 同层切换即时完成，不创建或叠加两个页面。

## 遵循的项目约定

- 全局 token 位于 `AnimationTheme.kt:39-96`，禁止在页面中写时长或曲线字面量。
- 页面层级语义由 `pageDepthOf` 与 `pageTransition` 控制，见 `AnimationTheme.kt:104-169`。

## 步骤

1. 将所有离场 tween 改为快速起步的全局离场 token，避免 UI 离场使用先慢后快曲线。
2. 为同层页面切换返回 `EnterTransition.None togetherWith ExitTransition.None using null`。
3. 保留详情页和搜索/设置层级切换的 transform + opacity 组合，不添加尺寸动画。

## 边界

- 不改变导航状态、返回栈或三处新增页面检查清单。
- 不改 ModalBottomSheet 的系统手势物理效果。

## 验证

- **机械验证**：`./gradlew.bat :androidApp:assembleRelease` 成功。
- **体验检查**：反复点“音乐/我的”应立即稳定；打开/收起详情页和迷你播放器应立即启动、无先停顿再加速感。
- **完成标准**：高频一级导航不含页面过渡，层级页面保持 280ms 以内的可读空间运动。
