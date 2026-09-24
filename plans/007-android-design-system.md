# 007 — Android 设计系统与组件库选型

- **状态**：阶段 1 DONE、阶段 2 DONE、阶段 2 收尾（两批）DONE（2026-09-20）；阶段 3 未开始；阶段 0 调研后**暂缓**（实测为 AGP 大版本迁移，需独立立项）
- **基线提交**：e3ce07b
- **严重度**：中高（不阻塞发布，但持续拉低第一印象）
- **类别**：设计系统、组件库边界、跨平台 UI
- **预计范围**：4 个阶段；阶段 0 为工具链升级，阶段 1–2 为主体，阶段 3 为可选增强

## 结论

先给答案，后面是依据：

1. **不要整体替换组件库。** 现在用的是 Material 3，问题不在这个库。
2. **不要换成第三方成套 UI 库**（Miuix / Compose Cupertino 等）。它们会把品牌换成别人的，而且必须先把工具链抬到 CMP 1.12，代价远大于收益。
3. **混合方案成立，但「混合」指的是分层，不是堆库**：M3 做结构骨架 + 自建四套 token + 自建复合组件 + 极少数专项能力库。
4. **真正的根因是 token 只做了颜色**。排版、形状、间距、层次四套完全没有规范，全靠页面里写字面量。
5. **工具链升级比预想的大得多，且不阻塞阶段 2。** 实测 CMP 1.12.0 要求 **AGP 9.1.0+ 与 compileSdk 37**，
   而 Kotlin 2.4.20 移除了 `webApp` 那段 binaryen workaround 所依赖的公开 API，**且无替代属性**。
   详见「阶段 0 调研结论」。**结论：先做阶段 2，阶段 0 单独立项。**
6. **阶段 2 已完成，效果可量化。** 两个核心文件的 UI 字面量从 143 降到 12（-91.6%），
   5 份标题实现收敛成 1 个组件，并补上了阶段 1 漏掉的第五类 token（组件固有尺寸）。
   同时修掉 3 处 `FontWeight.SemiBold` 违规与 1 处死代码。安卓真机四屏截图已验证。
7. **阶段 2 收尾（第一批）又清掉 143 处。** 三个未 token 化的页面（`PlaylistPages` / `AuthPage` / `SearchPage`）
   从 **143 降到 3（-97.9%）**，页头实现从 7 份彻底收敛到 1 份（本轮又发现第 6、7 份），
   返回按钮抽成共用组件，并补了 4 个 token 条目。
8. **阶段 2 收尾（第二批）把整份「未做」清单清完了。** `androidApp/ui/` 从 **466 降到 18（-96.1%）**、
   `player-ui` 非 token 文件降到 **0**、`webApp/Main.kt` 从 17 降到 **6**。
   最关键的结论有两条：
   - **剩下的 24 处（18 + 6）全部是页面级布局的私有命名常量**，一处内联圆角、一处内联字号都没有了。
     也就是说「字面量」这个层面的问题**已经清零**，剩下的 24 个数字是**这一屏该占多高**的设计参数，
     本来就不该进 token。
   - **`desktopApp/` 的 255 处与基线完全一致** —— 桌面端从未纳入阶段 2 范围（阶段 2 只覆盖安卓 + `player-ui`）。
     这是最大的一块剩余，需要单独排期。

一句话：**这是设计系统的缺失，不是组件库的选型问题。换库解决不了，只会换一种难看。**

## 问题

### 已经做对的部分（本方案不碰）

诊断时先确认哪些不该动，避免把好资产一起推倒：

- **颜色 token 是合格的。** 全应用（`androidApp` + `player-ui`）只有 26 处 `Color(0x...)`，其中 24 处集中在 `PlayerTheme.kt` 与 `Theme.kt` 两个主题文件里，业务页面几乎零硬编码。这比多数项目做得好。
- **动效 token 是样板级的。** `AnimationTheme.kt:82-146` 把时长、曲线、弹簧全部收口，还接了系统「关闭动画」开关（`rememberReduceMotion`），并有 `pageDepthOf` 这样的层级语义。**这套规范要成为后面所有 token 的模板。**
- **跨端边界是清楚的。** `player-ui` 被 `androidApp`、`desktopApp`、`webApp` 三处引用，`SharedSongRow` 这类组件已经在做插槽化（封面、下载进度、操作按钮由平台注入）。

### 真正缺的四层 token

| 层 | 现状 | 证据 |
| --- | --- | --- |
| 排版 | **完全没有定义** | `PlayerTheme.kt:56` 是 `typography: Typography = Typography()`，全项目唯一一处 `Typography` 引用就是这行默认值。实际字号靠页面写死：22 种不同 `.sp`，从 `10.sp` 到 `132.sp`，光 `12.sp` 就出现 31 次、`13.sp` 17 次、`14.sp` 13 次 —— 13 和 14 并存说明没有刻度 |
| 形状 | **完全没有定义** | 只有 `AppleStyleTheme.kt` 定义了 4 个圆角，其余全靠字面量：12 种不同 `RoundedCornerShape`（4/6/8/10/12/14/16/18/20/22/24/32 dp）。`14.dp` 出现 9 次、`12.dp` 8 次、`16.dp` 6 次，彼此没有关系 |
| 间距 | **完全没有定义** | `androidApp` UI 层 391 处裸 `.dp`，其中 158 处直接在 `padding(...)` 里。同一屏内 `12/13/14/18/22` 混用 |
| 层次 | **几乎没有语言** | 全应用只有 2 处阴影：`PlayerSurface.kt:38` 和 `SharedMiniPlayer.kt:54`。M3 的 tonal elevation 完全没用。卡片、列表、浮层、播放页全在同一平面上 —— 这是「看起来平、看起来廉价」最直接的来源 |

### 组件库不是根因

同样用 Material 3 的应用可以很精致。差距不在库，在于上面四层没人定规矩，于是每个页面各自决定字号和圆角。**当前观感是「不一致」，不是「库不行」。**

### 附带问题

- `TaotaoMusicApp.kt` 3257 行，承载了 `MinePage`、`PlayerDetailPage`、`PlaybackQueueSheet` 等多个完整页面。这属于 005 的范围，但改 UI 时会被反复牵动，两个方案要对齐节奏。

## 为什么不建议整体替换组件库

四条硬理由，按重要性排序：

1. **共享边界不允许。** `player-ui` 是 `commonMain`，三端共用。任何组件库必须在 Android + Desktop(JVM) + Web(WasmJs) 同时可用。这一条直接淘汰掉绝大多数 Android 专属库。
2. **工具链差了一代半。** 当前 CMP `1.7.1` + Kotlin `2.0.21` + Compose BOM `2024.12.01`。而：
   - CMP 1.8.0 起框架完全转向 K2，要求 **Kotlin ≥ 2.1.0**，官方建议 iOS/Web 目标用 **2.2.20+**；
   - 最新 CMP 是 **1.12.0**（对应 Jetpack Compose 1.12.0）。
   第三方库（尤其 Miuix）按 CMP 1.12 编译，进来之前必须先升工具链 —— 这本身就是一个独立阶段。
3. **品牌会被换掉。** 现在是珊瑚红 `#FA5E5B` 的自有品牌色。Miuix 是小米 HyperOS 观感，Cupertino 是 iOS 观感。换过去是「像小米」或「像 iOS」，不是「像桃桃音乐」。
4. **换库不解决不一致。** 新库自带一套组件，但页面里那 391 处裸 dp、22 种字号、12 种圆角会原样保留，只是长在别人的组件旁边，可能更乱。

## 候选库评估

按「能否进 `player-ui` commonMain」和「维护活跃度」两个硬门槛筛过：

| 库 | 定位 | 三端可用 | 版本状态 | 结论 |
| --- | --- | --- | --- | --- |
| **androidx Material 3**（现用） | 结构骨架 | ✅ 已是 | CMP 1.12 起对应 JC 1.12.0 | **保留为地基** |
| **Material 3 Expressive** | M3 官方表现力扩展（`MaterialExpressiveTheme`、`MotionScheme`、35 种 `MaterialShapes` + 形变） | 随 M3 升级获得 | JC material3 1.4.0+ 已有 | **升级后采用**，与自建 token 同层，不冲突 |
| **Haze** | 背景模糊 / 玻璃拟态 | ✅ Android/iOS/macOS/Desktop/Web | `2.0.0-rc02`（2026-09-18）、稳定线 `1.7.3` | **推荐**，单项收益最高 |
| **Miuix** | HyperOS 风格成套组件 | ✅ | `0.9.x`，需 CMP `1.12.0-rc01`，官方标注 experimental | **不采用**。整套替换 + 换品牌 |
| **Compose Cupertino** | iOS 风格成套组件 | ✅ | `0.2.0-alpha05`，最后提交 2025-10 | **不采用**。alpha 且维护低频，风险高于收益 |
| **Coil 3** | KMP 图片加载 | ✅ | 稳定 | **可选**。目前 Android 用 Coil 2，共享层封面加载靠插槽注入，暂时不是瓶颈 |

> 上表中 Miuix / Haze 的**最低 CMP 版本要求需要在阶段 0 落地时实测确认**，本文只记录了查阅到的编译基线，未在本项目验证过。

## 方案：混合分层

「混合组件库」的正确形态是**分层**，每层只解决一类问题，且**依赖方向单向向下**：

```text
L4  平台外壳        androidApp / desktopApp / webApp
                    Activity、导航、系统能力、平台插槽实现
                        ^
                        |
L3  专项能力库      Haze（模糊）、Coil（图片）、Reorderable（拖拽）
                    只补「自己写成本高」的单点能力，不接管布局
                        ^
                        |
L2  复合组件层      TaotaoCard / TaotaoListItem / TaotaoBottomBar / TaotaoEmptyState
                    player-ui commonMain，项目自有，三端共用
                        ^
                        |
L1  Token 层        TaotaoTypography / TaotaoShapes / TaotaoSpacing / TaotaoElevation
                    + 现有颜色与 AnimationTheme（已有，作为模板）
                        ^
                        |
L0  地基            androidx Material 3（结构、语义、无障碍、滚动、手势）
                    + Material 3 Expressive（升级后）
```

**分工原则**（这条要写进 `AGENTS.md` 的 UI 约定里）：

- M3 负责**行为和语义**：`LazyColumn` 的回收、`TextField` 的 IME、`ModalBottomSheet` 的手势与打断、无障碍语义树、RTL。这些不要自己造。
- 自建层负责**视觉一致性**：字号、圆角、间距、层次、颜色用法。
- 第三方库只允许**补单点能力**，不允许接管布局和主题。判断标准：如果引入一个库会要求你改 `TaotaoTheme` 的入口，就不引入。

## 阶段与步骤

### 阶段 0 — 工具链升级（前置，独立提交）

不升级则阶段 3 全部无法进行，且 M3 Expressive 拿不到。

1. Kotlin `2.0.21` → `2.2.20+`；CMP `1.7.1` → `1.12.0`；Compose BOM 同步到对应版本。
   - 同步改 `build.gradle.kts:8-13` 与 `androidApp/build.gradle.kts:66` 的 BOM。
   - `settings.gradle.kts` 的 `PREFER_PROJECT` 与 wasm 工具链仓库保持不动。
2. Compose Compiler Gradle 插件版本必须与 Kotlin 插件版本一致（CMP 1.8+ 要求）。
3. **验收**：`:androidApp:assembleRelease` 出包 + `:webApp` 能构建 + `:desktopApp` 能构建。三端都要过，不能只验安卓。
4. 记录 `output-metadata.json` 的版本号作为发布登记依据（见 `RELEASE.md`）。

### 阶段 0 调研结论（2026-09-20，实测后暂缓）

上面第 1 条写的「改几个版本号」**不成立**。实测把版本改完后，真正的依赖链是这样：

| 组件 | 当前 | CMP 1.12.0 实际要求 | 结论 |
| --- | --- | --- | --- |
| Kotlin | 2.0.21 | ≥ 2.1.0，官方建议 2.2.20+；当前稳定版是 **2.4.20** | 可升 |
| CMP | 1.7.1 | 1.12.0 | 可升 |
| Compose BOM | 2024.12.01 | **2026.09.00**（ui 1.12.1 / material3 1.4.0） | 可升 |
| AGP | 8.7.3 | **9.1.0 或更高** | ⚠️ 大版本迁移 |
| compileSdk | 35 | **37** | ⚠️ 本机未装 android-37 |
| Gradle | 8.14.3 | AGP 9.1 所需版本（Kotlin 2.4.20 支持到 9.7.0） | ⚠️ 需跟 AGP 走 |

报错原文（`androidx.compose.ui:ui-android:1.12.1`）：

> requires libraries and applications that depend on it to compile against version 37 or later of the Android APIs
> requires Android Gradle plugin 9.1.0 or higher

**真正卡死的是第二个问题：binaryen。**

`webApp/build.gradle.kts` 有一段 workaround —— `download = false` + 指向 npm 的 `wasm-opt`，
目的是避免生产构建直连 GitHub Release。实测：

- Kotlin 2.4.20 **删除了这个公开 API**。`BinaryenRootExtension` 改名迁包为
  `org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExtension`，
  而 `download` / `command` / `version` / `downloadBaseUrl` 全部降为 **internal**
  （JVM 签名带 `$kotlin_gradle_plugin_common` 后缀），Kotlin DSL 无法访问。
- **没有任何 Gradle 属性可以替代**：全量扫描插件字符串，只有 Kotlin/Native 有
  `kotlin.native.distribution.baseDownloadUrl`，binaryen 没有对应键。
- 删掉该配置后，`:webApp:wasmJsBrowserDistribution` 的任务图里
  `:webApp:kotlinWasmBinaryenSetup` **依然存在**，实测**卡住 16 分钟无任何输出**
  （即在从 GitHub 拉取）。也就是说这条 workaround 仍然必需，但已无公开入口。

**顺带发现的两件事**（不阻塞，但迟早要处理）：

1. **CMP 1.10.0-beta01 起，`compose.*` 依赖别名（`compose.runtime` / `compose.material3` 等）已弃用。**
   官方指引是「在 version catalog 里直接引用坐标」，**CMP 目前没有 BOM**（官方表述为「未来希望提供」）。
   实测这些弃用**只是警告，不会中断构建**（先前那次失败是 binaryen 编译错误连带的误判）。
   直接坐标（从插件常量与官方 Dependencies 表读出，注意版本线不同）：
   `org.jetbrains.compose.runtime:runtime:1.12.0`、`...foundation:foundation:1.12.0`、
   `...ui:ui:1.12.0`、`...material:material:1.12.0`、
   **`...material3:material3:1.9.0`（material3 走自己的版本线，不是 1.12.0）**、
   `...components:components-resources:1.12.0`。
2. **`compose.materialIconsExtended` 被永久钉在 `1.7.3`**，官方弃用通知写明「不再更新，
   要么显式用这个版本，要么迁移到 Material Symbols（矢量资源）」。
   本项目 `Icons.Default.*` 用量很大，迁移是独立工作量，需要单独立项。

**M3 Expressive 的位置**：不在稳定线里。CMP 稳定版 material3 是 `1.9.0`；
Expressive 在 `org.jetbrains.compose.material3:material3:1.12.0-alpha03`
（对应 androidx material3 `1.5.0-alpha22`）。也就是说**阶段 3 要拿 M3 Expressive，得吃 alpha**。

**结论与建议**：

- 阶段 0 的真实范围是 **AGP 大版本迁移 + 新 SDK 平台 + 发布链路重验**，
  不是「工具链版本升级」。它必须单独成篇、单独提交，不能在脏工作区里做。
- 风险面：`build-logic` 的热修复插桩用的是 AGP 的 `AsmClassVisitorFactory`；
  `desktopApp` 的签名出包、`desktopModules` 模块化发布、`RELEASE.md` 里那批不能破的契约，
  都必须在 AGP 9 下重新验一遍。
- **建议顺序调整**：先做**阶段 2（复合组件层）**。它不需要任何升级，且直接兑现
  「UI 太难看」这个原始诉求。阶段 0/3 作为独立项目排在其后。
- 若确实要推进阶段 0，二选一：
  - **全量跳**：AGP 9.1+ / Gradle 9.x / compileSdk 37 / Kotlin 2.4.20 / CMP 1.12.0。收益最大，风险最大。
  - **折中**：找一个「Compose 要求 ≤ compileSdk 36」的 CMP 版本（需实测 1.11.x 或 1.10.x），
    AGP 停在 8.11.1。**但仍绕不开 binaryen 问题** —— 只要 Kotlin 升到 2.2+，那段 workaround 就失效。

### 阶段 1 — Token 层（主体，收益最大）

全部放 `player-ui/src/commonMain/.../theme/`，与 `AppleStyleTheme` 同级；`AnimationTheme.kt` 保持在 `androidApp`（依赖 Android `Settings`），但**命名与结构对齐**。

1. **`TaotaoTypography.kt`** —— 定义一套有刻度的字号。
   - 现有 22 种字号收敛到约 8 级（如 `displayLarge / titleLarge / titleMedium / bodyLarge / bodyMedium / bodySmall / labelMedium / labelSmall`）。
   - 从现有实际用法取众数：`12/13/14` 合并为两级（bodySmall + labelMedium），`17/18` 合并为 titleMedium，`20/22` 合并为 titleLarge。
   - 接入 `TaotaoPlayerTheme` 的 `typography` 参数，替换掉 `Typography()` 默认值。
   - 中文字体要显式选型（默认 Roboto 对中文走系统 fallback，字形和字重都不受控，是「排版不好看」的直接原因之一）。
2. **`TaotaoShapes.kt`** —— 12 种圆角收敛到 5 级（`xs/s/m/l/xl`），由 `AppleStyleTheme` 的 4 个语义名迁移而来（保留旧名做 `@Deprecated` 别名，避免一次性改 100+ 处）。
3. **`TaotaoSpacing.kt`** —— 建立 4dp 基数刻度（`4/8/12/16/20/24/32`），先定义、后替换；替换按页面逐个进行，不搞一次性全量替换。
4. **`TaotaoElevation.kt`** —— 定义 3 级层次（列表项 / 卡片 / 浮层），基于 M3 tonal elevation + 极轻阴影。
5. **验收**：
   - `TaotaoPlayerTheme` 不再使用默认 `Typography()`。
   - `:player-ui:allTests` 通过；新增 token 的单元测试（刻度单调性、亮暗对比度）。
   - 亮暗两套配色下逐页人工核对，重点看暗色（现有 `LyricDim` 这类手写色要一并纳入 token）。

### 阶段 1 执行结果（2026-09-20，DONE）

#### 新增文件（`player-ui/src/commonMain/.../theme/`）

| 文件 | 内容 |
| --- | --- |
| `TaotaoTypography.kt` | 10 个尺寸档位的 `TaotaoTypeScale` + 接入主题的 `TaotaoTypography` |
| `TaotaoShapes.kt` | 5 档圆角 `TaotaoShapes` + `TaotaoStroke`（描边宽度） |
| `TaotaoSpacing.kt` | 8 档 4dp 基数间距 `TaotaoSpacing` |
| `TaotaoElevation.kt` | 4 级层次 `TaotaoElevation` + 亮暗自适应的 `taotaoShadowColors()` |

`AppleStyleTheme` 改为 `@Deprecated` 别名指向 `TaotaoShapes`，**调用点可逐个迁移**。
本次已把 `androidApp/ui/components.kt` 的 2 处迁完，全项目弃用警告清零。

#### 本次一并修改的存量文件

| 文件 | 改动 |
| --- | --- |
| `player-ui/PlayerTheme.kt` | `TaotaoPlayerTheme` 新增 `typography` / `shapes` 参数，替换 `Typography()` 默认值 |
| `player-ui/PlayerSurface.kt` | **修掉封面方块鬼影**（新增 `shape` 参数同时作用于阴影与裁切）；`12.sp` 手写字号改 `labelMedium`；间距改 token |
| `player-ui/SharedSongRow.kt` | 圆角、间距、描边全部 token 化 |
| `player-ui/SharedMiniPlayer.kt` | 阴影 `16.dp` → `TaotaoElevation.raised`；`SemiBold` → `Bold`（与 `SharedSongRow` 对齐同一角色） |
| `player-ui/SharedContentState.kt` | 间距与 `TaotaoStroke.thick` token 化 |
| `webApp/Main.kt` | **修掉绕过 `TaotaoTypography` 导致的三端排版分叉**；4 处字面量 token 化 |
| `androidApp/ui/components.kt` | 2 处 `AppleStyleTheme.ButtonShape` → `TaotaoShapes.button` |

#### 实测收敛效果

| 指标 | 改动前 | 改动后 |
| --- | --- | --- |
| `player-ui/commonMain` 硬编码字号 | 22 种（10–132 sp） | **0**（全部走 `MaterialTheme.typography`） |
| `player-ui/commonMain` 圆角字面量 | 12 种 | **0**（全部走 `TaotaoShapes`） |
| `player-ui/commonMain` 裸 `.dp` | 约 90 处 | 45 处（剩余均为图标/封面等组件尺寸） |
| 阴影透明度写法 | 2 处相差 5 倍（0.5 / 0.1） | 统一走 `taotaoShadowColors()`，亮暗各自取值 |

#### 关键取舍：只压缩从不使用的槽位

槽位映射有一条硬约束 —— **页面已经在用的槽位字号保持不变**。
`androidApp` 实际只用 `titleLarge` / `headlineSmall` / `bodySmall` / `labelMedium` / `labelSmall`，
`player-ui` 用 `bodyLarge` / `bodyMedium` / `labelMedium` / `labelSmall` / `titleMedium` / `headlineMedium`。
这些槽位全部保持 Material 原字号，**只改中文字距与行距**。

真正被压缩的是从不使用的顶部槽位（`displayLarge` 57→40、`headlineLarge` 32→26 等）。
否则「统一排版」会顺手把播放页歌曲名从 28sp 压到 22sp —— 那是回归不是优化。
这条约束已落成测试 `typographyNeverExceedsMaterialDefaults`。

**收益来源因此有两处**：一是从 22 种字号收敛到 8 种（页面里写死的那些留给阶段 2），
二是中文行距与字距的修正 —— 后者**不改任何页面就已经生效**。

#### 中文字体的三条约束（已落成测试）

1. 字重只用 `Normal(400)` / `Medium(500)` / `Bold(700)`。中文字体字重覆盖远不如拉丁字体，
   `SemiBold(600)` 会被不同 ROM 吸附到 500 或 700，同一套主题在不同设备上字重不一致。
   （顺带修掉了 `SharedMiniPlayer` 用 SemiBold 而 `SharedSongRow` 用 Bold 的同一角色不一致。）
2. `letterSpacing` 一律为 0。Material 默认给正文加 0.25–0.5sp 正字距，那是为拉丁字母设计的。
3. `lineHeight` 按 1.3–1.55 倍字号取偶数 sp，比 Material 默认更松。

#### 验证

```bash
./gradlew :player-ui:desktopTest :androidApp:compileDebugKotlin \
          :desktopApp:compileKotlin :webApp:compileKotlinWasmJs
```

- `BUILD SUCCESSFUL`，三端（Android / Windows / Web）全部编译通过。
- 新增 `TaotaoTokensTest`，10 个用例覆盖档位数量上限、单调性、取值集合、字距/字重/行距约束。
- 断言全部针对**结构**而非具体数值：调刻度不会让测试变红，破坏刻度结构才会。

#### 视觉验证链路（本次新建）

本机没有 Android 模拟器，但 `webApp` 与安卓共用 `player-ui` 的 `commonMain`，
所以**对 Web 端截图可以验证共享组件的排版、间距、圆角、阴影**（验证不到安卓专属的 Material 控件）。

做法（Playwright + Chromium，脚本放在系统 temp 目录，不进仓库）：

1. `./gradlew :webApp:wasmJsBrowserDistribution` 产出 wasm 站点。
2. **不能直接挂根路径** —— `webApp/index.html` 里有 `<base href="/share/">`，
   挂根路径会让所有资源 404（表现是白屏 + `canvas=0`）。
   正确做法是搭一个双目录结构：`site/index.html` 是入口副本，`site/share/` 放完整资源树，
   服务 `site/` 根目录并访问 `/`，此时 `pathname` 解析不出 token，会走内置的 `demoShareSong()` 渲染。
3. 截图脚本按 `color_scheme` 迭代亮/暗两套，viewport `430×932`、`device_scale_factor=2`，
   等 9 秒让 wasm 与字体初始化完，并打印 `canvas` 数量与页面错误。

**关键**：wasm 渲染进 canvas，DOM 里没有文字节点，**必须靠像素采样而不是读 DOM**。

#### 截图发现的真实缺陷：封面方块鬼影（已修）

修复前后的对照截图归档在 `artifacts/design-system-tokens/`（`artifacts/*` 默认不入库）：

| 文件 | 内容 |
| --- | --- |
| `artwork-shadow-before-light.png` / `-dark.png` | 修复前：圆形封面背后一块方形阴影 |
| `artwork-shadow-after-light.png` / `-dark.png` | 修复后：完整的圆形阴影 |

用 ASCII 偏差图对比截图，发现 `PlayerArtworkSlot` 存在**改动前就有的**形状不匹配：

- 阴影固定按 `TaotaoShapes.artwork`（20dp 圆角矩形）投影，而封面内容画的是 `CircleShape`。
- 结果是圆形封面背后露出一圈**直角矩形阴影带**，实测每边多出约 30dp，四个角最明显。
- 旧代码阴影 alpha 是 `0.5`，比 `SharedMiniPlayer` 的 `0.1` 重 5 倍，**所以鬼影一直很显眼**。

修复：给 `PlayerArtworkSlot` 加 `shape: Shape = CircleShape` 参数，**同一个 `shape` 同时作用于 `shadow` 与 `clip`**，
并在文档注释里写死这条约束（形状必须与 `content` 实际画出的形状一致）。

修复前后的偏差图对比（亮色主题）：

```
修复前 —— 矩形阴影带，四角外露，直边固定在同一个 x
 350    .=+++++++++++++@@@@@@@@@@@++++++++++++++-.
 362   .=*%@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@%#-.
 398   :#@@@    @@@@@@@@@@@@@@@@@@@@@@@@@@    :@@@-.
 698   :#@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@-.

修复后 —— 完整圆形轮廓，四角消失，边缘逐行内收
 350                       .....
 362                  .:@@@@@@@@@-:.
 410           :@@@@@@@@@@@@@@@@@@@@@@@@@@:
 698    :#@@@@@@@@@@@::::@@@@@@@@@@@@@@@@@@@@@@@#-.
```

#### 顺带修掉的三端排版分叉

`webApp/Main.kt` 此前写的是 `typography = Typography().withFontFamily(webFontFamily)` ——
**绕过了 `TaotaoTypography`**，等于 Web 端用 Material 默认排版、安卓与桌面用新 token，
三端排版会分叉。已改为 `TaotaoTypography.withFontFamily(webFontFamily)`。

同一文件里另有 `fontSize = 18.sp`（不在刻度上）、`RoundedCornerShape(8.dp)`（8dp 不在 6/10/14/20/28 刻度上）等字面量，一并换成 token。

#### 尚未验证 / 需要人工确认

- **已在 Web 端截图验证**（见上一节），覆盖 `player-ui` 共享组件的排版、间距、圆角、阴影。
- **安卓真机 / 模拟器仍未验证**，安卓专属的 Material 控件（`NavigationBar`、`TopAppBar`、
  对话框、底部弹窗）不在这条截图链路的覆盖范围内。本机无法运行 Android 模拟器。
- 以下改动是**有视觉 delta 的**，需要你装上后确认：
  1. 封面阴影：`0.5` 透明度 + 24dp → `taotaoShadowColors()` + 12dp，**会明显变轻**。
     （形状 bug 修复后，方块鬼影消失，封面外圈比之前干净。）
  2. 迷你播放器阴影：16dp → 4dp（`TaotaoElevation.raised`），**会明显变平**。
  3. 列表行圆角 12→14dp、卡片圆角 32→28dp，差异细微。
  4. `headlineMedium` 28→26sp（播放页歌曲名）、`headlineSmall` 24→22sp（聊天页标题）。
- **暗色主题没有逐页核对**，`LyricDim` 这类手写色尚未纳入 token。
  截图链路已支持亮暗两套，暗色核对成本已降低，但仍需人工判断观感。
- **中文字体未内嵌**，仍走系统字体。是否内嵌需先评估 APK 体积增量（中文全字库通常数 MB）。

#### 下一步建议

先按上面的 4 项确认观感，再决定是否进入阶段 2。若封面阴影或迷你播放器层次觉得改过头了，
调 `TaotaoElevation` 一个数值即可，不必回退整个 token 层。

阶段 2 之前还有一件事：**把截图验证链路固化下来**（当前脚本在 temp 目录，跑完即弃）。
如果要长期用，建议放 `tools/` 并写进 `RELEASE.md` 的验收清单 —— 它是目前唯一能在无模拟器环境下
验证共享组件视觉的手段。

### 阶段 2 — 复合组件层（主体）

把页面里的重复结构提到 `player-ui`，**每提取一个组件，就顺手把该页面的字面量换成 token**。

优先顺序按复用次数：

1. `TaotaoCard`（替代 `components.kt:360` 的 `CardWithTitle` + 各处裸 `Column.background`）
2. `TaotaoSectionHeader`（替代 `TaotaoMusicApp.kt:2510` 的 `SectionTitle`、`PageTitle`、`LibraryPageHeader` 三份近似实现）
3. `TaotaoBottomBar`（`player-ui` 已用 `NavigationBar`，但样式在两端各自调）
4. `TaotaoEmptyState`（已有 `SharedContentState`，补齐图标与插图位）
5. `TaotaoListItem` 的骨架微调（`SharedSongRow` 已合格，主要补层次感）

**硬约束**：

- 组件只接收数据和回调，不引入平台依赖（继续沿用 `SharedSongRow` 的插槽模式）。
- 每个新组件必须在 `player-ui/src/commonTest` 有对应测试。
- 三端都要回归，不能只测安卓。

### 阶段 2 执行结果（2026-09-20，DONE）

#### 新增复合组件（`player-ui/src/commonMain/.../playerui/`）

| 文件 | 内容 |
| --- | --- |
| `SharedSectionHeader.kt` | `SharedSectionLevel`（PAGE / SECTION / CARD）+ 3 个 internal 纯函数 + 组件本体 |
| `SharedCard.kt` | 圆角卡片，标题复用 `SharedSectionHeader` 的 CARD 档 |

命名沿用既有的 `Shared*` 前缀（`SharedSongRow` / `SharedMiniPlayer` / `SharedContentState`），
**没有**叫 `TaotaoCard` —— `Taotao*` 前缀留给 `theme/` 下的 token 对象，两个层级不要混用。

`TaotaoEmptyState` 最终**没有新建**：`player-ui` 已有 `SharedContentState`（覆盖 LOADING / EMPTY / ERROR），
再建一个只会造成同类重复。

「层级 → 样式」被抽成**接收 `Typography` 的纯函数**（`sectionTitleStyle`），
既尊重主题覆盖（`webApp` 会换字体族），又能在 `commonTest` 里直接断言而不必拉起 Compose 运行时。

#### 标题实现从 5 份收敛到 1 份

| 原实现 | 位置 | 原字号 | 现档位 |
| --- | --- | --- | --- |
| `LibraryPageHeader` | `MineLibraryPages.kt` | 26sp | `PAGE`（26sp，不变） |
| `PageTitle`（**0 处调用**，死代码） | `components.kt` | 24sp | 删除 |
| `SectionTitle` | `TaotaoMusicApp.kt` | 21sp | `SECTION`（22sp） |
| `CardWithTitle` | `components.kt` | 18sp | `CARD`（16sp） |
| `AccountSectionTitle` | `AccountAndAnnouncementDialogs.kt` | 17sp | `CARD`（16sp） |

原先 5 种字号（17/18/21/24/26）里只有 18 落在 `TaotaoTypeScale` 上，其余全是随手写的。

`PAGE` 档的上下内边距刻意不对称（12 / 4 dp）：列表页标题若留出等量下边距，会把第一首歌推得太远 ——
这条约束原先只写在 `LibraryPageHeader` 的行内注释里，现在落成了测试 `pageLevelKeepsBottomPaddingTighterThanTop`。

#### 补齐了阶段 1 漏掉的第五类 token：`TaotaoSizes`

阶段 1 定义了排版 / 形状 / 间距 / 层次四类，**漏了「组件固有尺寸」**。
后果是 95 个裸 `.dp` 里有 34 个是 `size` / `width` / `height`，无处可去。具体表现：

| 语义角色 | 原尺寸 | 现 token |
| --- | --- | --- |
| 列表行封面 | **42 / 48 / 52 dp**（三个数表示同一件事） | `artworkRow` = 48dp |
| 网格封面 | 112dp | `artworkGrid` = 112dp |
| 播放页封面 | 132dp | `artworkHero` = 132dp |
| 品牌标识 | 88dp | `artworkBrand` = 88dp |
| 头像 | 62 / 56 dp | `avatar` = 64dp |
| 图标 | **16 / 18 / 19 / 24 / 30 dp** | `iconXs/Sm/Md/Lg` = 16/18/24/30 |
| 图标按钮触达区 | 34 / 36 dp | `iconButton` = 36dp |

两个设计决定：

- **`iconButton` 不放进 `iconScale` 刻度** —— 它描述的是触达区域，不是图形大小，
  混进同一个刻度会让「图标该多大」失去唯一答案。有独立测试固定它与最大图标的大小关系。
- **`AlbumArt` 的占位音符字号改为按封面尺寸的一半推算**（`iconSize` 参数默认 `null`），
  5 个调用点不再各写一个字号；形状也改为按尺寸自动判定（大于一个列表行封面即圆形）。
  按尺寸派生而不是再加一组「音符字号 token」—— 后者会引入第六类刻度。

#### 实测收敛效果

| 范围 | 基线 | 改动后 | 降幅 |
| --- | --- | --- | --- |
| `components.kt` | 34 | **7** | 79% |
| `TaotaoMusicApp.kt` | 109 | **5** | 95% |
| **合计** | **143** | **12** | **91.6%** |

> ⚠️ **口径修正**：阶段 2 启动时报的基线 156 是错的 —— 旧计数脚本把
> `RoundedCornerShape(16.dp)` 里的 `16.dp` **同时**算作「圆角」和「裸 dp」，重复计数 13 次。
> 修正后的真实基线是 **143**（`.sp` 35 + `RoundedCornerShape` 13 + 裸 `.dp` 95）。
> 本表的 12 = 全部刻意保留的项。

残留的 12 处**全部是有意保留**的，不是漏改：

| 位置 | 内容 | 为什么保留 |
| --- | --- | --- |
| `components.kt` | `VipBadge` 的 `vertical = 1.dp` | 徽标纵向留白，撑到 4dp 会把整行顶高 |
| `components.kt` | 骨架屏 4 个占位条尺寸（150/16/90/12 dp） | 骨架跟随真实行尺寸，它不该反过来定义刻度 |
| `components.kt` | 分页点 8 / 6 dp | 指示器专用，已提为命名常量并由此推导放大倍数 |
| `TaotaoMusicApp.kt` | `strokeWidth = 2.dp` | 描边宽度，`TaotaoStroke` 无此档 |
| `TaotaoMusicApp.kt` | 3 个页面级布局高度 + 队列视口 360dp | 描述「这一屏占多高」，换页就不成立，已提为命名常量 |

#### 顺带修掉的两个真实缺陷

1. **`FontWeight.SemiBold` 违规**（3 处：`TaotaoMusicApp.kt:2437`、`AccountAndAnnouncementDialogs.kt:314`、
   `PlaylistPages.kt:384`）。中文字体只稳定提供 400/500/700，600 会被不同 ROM 吸附到 500 或 700，
   同一套主题在不同设备上字重不一致。已全部改走 `TaotaoTypeScale` 的中等字重档。
   这三处**不会报错、也不会被类型检查发现**，只有对着 `TaotaoTypography` 的中文约束逐条核对才看得见。
2. **`components.kt` 的 `PageTitle` 是死代码**（0 处调用），删除。

#### 验证

- 三端编译：`:androidApp:compileDebugKotlin` / `:desktopApp:compileKotlin` / `:webApp:compileKotlinWasmJs`
  全部 `BUILD SUCCESSFUL`。
- `:player-ui:desktopTest`：**27 项全绿**（`PlayerActionsTest` 5 + `SharedComponentsTest` 9 +
  `TaotaoTokensTest` 13），`failures=0 errors=0`。其中新增 3 条尺寸刻度护栏：
  `iconScaleStepsStayDistinguishable`（相邻档位至少差 2dp，防止再出现 18 vs 19）、
  `artworkScaleCoversThreeRealRoles`、`iconButtonIsLargerThanAnyIcon`。
- **安卓真机截图**：首页 / 我的 / 设置 / 最近播放 四屏（亮色），归档在
  `artifacts/design-system-tokens/stage2/`。

#### 安卓真机截图链路（本轮新建）

此前结论是「截图链路覆盖不到安卓专属界面」。本轮打通：

```bash
# 1. 起模拟器（swiftshader，无窗口）
emulator -avd Medium_Phone_API_36.1 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
# 2. 装 debug 包 —— 必须 debug，只有 debuggable 包能用 run-as 写 shared_prefs
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# 3. 启动一次让应用建好 shared_prefs 目录，然后强停
# 4. 预置会话绕过登录页（登录要真账号，服务端是生产环境）
printf '%s' '<xml .../>' | adb shell "run-as com.taotao.music sh -c 'cat > shared_prefs/auth.xml'"
# 5. 开飞行模式：否则 401 会触发 CredentialsRejectedException 把会话清掉、弹回登录页
adb shell cmd connectivity airplane-mode enable
# 6. 关掉 ANR 弹窗 —— swiftshader 下 SystemUI 必 ANR，会盖住截图
adb shell settings put global hide_error_dialogs 1
```

两个关键点：

- `auth.xml` 的 `expires_at` 要写到**远期**（如 `4102444800000`）。`isAccessValid` 为真时
  `validToken()` 直接返回本地令牌，**根本不发起刷新请求**，会话最稳。
  `AuthSession.renew()` 只在服务端明确拒绝（`CredentialsRejectedException`）时清空会话，
  网络不可用时保留本地令牌 —— 飞行模式因此是安全的。
- **用完必须恢复**：`settings put global hide_error_dialogs 0` + `cmd connectivity airplane-mode disable`。
  留在模拟器上会让后续调试看不到崩溃弹窗。

⚠️ 本机沙箱下 `assembleDebug` 会顺带跑 `incrementVersion`（`finalizedBy`），
本轮把版本从 1.0.196 推到了 1.0.197。按 `RELEASE.md` 的规矩**不回滚** —— 跳号无害，重号才会静默覆盖已发布记录。

#### 需要人工确认的视觉 delta（阶段 2 新增）

| 项 | 原值 | 新值 | 说明 |
| --- | --- | --- | --- |
| 列表行封面 | 52dp（歌曲行）/ 48dp（队列）/ 42dp（歌单详情） | **48dp** | 三者本是同一语义角色 |
| 头像 | 62dp（我的页）/ 56dp（资料页） | **64dp** | |
| 图标底衬圆 | 34dp | **36dp** | 并入 `iconButton` |
| 列表行内图标 | 19dp | **18dp** | 并入 `iconSm` |
| 卡片圆角 | 22 / 18 / 16 dp 三种并存 | **20dp**（`TaotaoShapes.card`） | 收敛为单档 |
| 卡片内边距 | 18dp | **16dp** | |
| 单行提示条圆角 | 14dp | 14dp（不变） | `TaotaoShapes.medium` |
| 「我的」页标题 | 30sp | **26sp** | 与首页标题统一 |
| 「今日推荐」 | 21sp | **22sp** | |
| 快捷入口卡高度 | 118dp | **120dp** | 对齐 4dp 栅格 |
| 列表页标题上/下留白 | 12 / 6 dp | 12 / 4 dp | |
| 分页点间距 | 6dp | 8dp | |

#### 未做的部分（下一批）

`ui/` 全目录的 538 处字面量只迁移了被本阶段直接触及的文件。剩余未 token 化的大头：

| 文件 | 裸 `.dp` 数量 | 备注 |
| --- | --- | --- |
| `PlaylistPages.kt` | 约 50 | 只改了 `AlbumArt` 调用点与 1 处 `SemiBold` |
| `AuthPage.kt` | 约 26 | 只改了 `AlbumArt` 调用点 |
| `SearchPage.kt` | 约 19 | 未动 |

这三个文件**不在阶段 2 的验收口径内**（口径只覆盖 `components.kt` 与 `TaotaoMusicApp.kt`），
但它们的圆角/字号/间距同样是硬编码，建议作为阶段 2 的收尾单独提交。
`SearchPage.kt` 的 `RoundedCornerShape(50)` 两处是 `TaotaoShapes.pill`，可直接替换。

### 阶段 2 收尾（2026-09-20，DONE）

上一节列的三个文件已全部迁移完毕，另有两个「本来以为做完了」的漏网一并修掉。

#### 又发现两份页头实现 —— 总共 7 份，现在只剩 1 份

阶段 2 收敛时数的是 5 份，收尾时又撞见 2 份：

| 原实现 | 位置 | 原字号 | 现档位 |
| --- | --- | --- | --- |
| `PlaylistPageHeader` | `PlaylistPages.kt` | 26sp | `PAGE`（26sp，不变） |
| 行内 `Row { IconButton + Text }` | `SearchPage.kt` | 22sp | `PAGE`（26sp） |

`PlaylistPageHeader` 的 `action` 是 `@Composable RowScope.() -> Unit`，需要并排两个 `IconButton`；
而 `SharedSectionHeader.trailing` 刻意**不带 `RowScope` 接收者**（带了之后调用点无法传入
`(@Composable () -> Unit)?` 这种可空参数）。收尾的做法是在调用点自己包一层 `Row { }`，
而不是把接收者加回组件 —— 加回去会让所有只需要一个按钮的调用点都多一层噪声。

#### 返回按钮从 4 份收敛到 1 份

`MineLibraryPages` 的私有 `LibraryBackButton`、`PlaylistPages` 的页头内联、
`SearchPage` 与 `SettingsPage` 里的行内 `IconButton` —— 四处**连图标和 `contentDescription` 都逐字相同**。
已抽成 `player-ui` 的 `SharedBackButton`（用 `AutoMirrored` 变体，RTL 语言下箭头会自动翻转）。

#### 本轮补的 4 个 token 条目

| token | 值 | 为什么它无处可去 |
| --- | --- | --- |
| `TaotaoSizes.progressInline` | 20dp | 行内进度圈（按钮内、列表底部），既不是图标也不是触达区 |
| `TaotaoSizes.stateIcon` | 44dp | 内容状态图示。原先加载态 36 / 空态 44 / 失败态 44，**光是加载完成就会让标题跳 8dp**，现在三种状态共用一个尺寸 |
| `TaotaoStroke.medium` | 2dp | 行内进度圈描边：1dp 细到看不见，3dp 糊成一团 |
| `TaotaoSpacing.badgeVertical` | 2dp | 徽标纵向内边距。**刻度之外的例外**（同 `screenHorizontal`）：徽标高度应由行高决定，撑到 4dp 会把整行顶高 |

新增护栏测试 `offScaleRolesOutgrowPlainIcons`（脱离刻度的角色尺寸必须大于普通图标，
否则会被读成「又一个图标」）与 `strokeScaleStaysOrdered`。

#### 顺手修掉 `player-ui` 的漏网

| 位置 | 原值 | 现 token | 说明 |
| --- | --- | --- | --- |
| `SharedSongRow` 列表行封面 | **52dp** | `TaotaoSizes.artworkRow`（48dp） | 阶段 2 把它算进了「已收敛」，实际这个文件没改到 |
| `SharedSongRow` 行高下限 | `68.dp` | 由 `artworkRow + listItemVertical × 2` 推出 | 封面一改行高自动跟上，不会再出现「封面变小了但行还是原来的高度」 |
| `SharedSongRow` 下载标记 / 徽标内边距 | 15dp / 5dp / 2dp | `iconXs` / `xxs` / `badgeVertical` | |
| `SharedMiniPlayer` 进度条高度 | 2dp | `TaotaoStroke.medium` | |
| `SharedMiniPlayer` 封面 | 48dp | `TaotaoSizes.artworkRow` | |
| `SharedMiniPlayer` 播放图标 | 28dp | `TaotaoSizes.iconLg`（30dp） | |
| `layout/SharedMainLayout` | `tonalElevation = 0.dp` | `TaotaoElevation.flat` | 这正是「平铺」这一级的定义 |
| `PlayerWideLayout` | 32dp / 48dp | `xxl` / `xxxl` | |
| `components.kt` `VipBadge` | `vertical = 1.dp` | `TaotaoSpacing.badgeVertical` | 与 `SharedVipBadge` 统一 |

#### 实测收敛效果

| 范围 | 基线 | 改动后 | 降幅 |
| --- | --- | --- | --- |
| `PlaylistPages.kt` | 72 | **1** | 99% |
| `AuthPage.kt` | 33 | **1** | 97% |
| `SearchPage.kt` | 38 | **1** | 97% |
| **三个文件合计** | **143** | **3** | **97.9%** |
| `components.kt` | 7 | 6 | |
| `player-ui` 五个共用组件 | 16 | **0** | 100% |

三个文件各剩 1 处，都是**页面级布局常量**，已提为命名常量：

| 位置 | 常量 | 为什么不该进 token |
| --- | --- | --- |
| `AuthPage.kt` | `AuthButtonHeight = 52.dp` | 这一屏的布局决定（切加载态时按钮不能变矮），不是会复用的组件尺寸 |
| `SearchPage.kt` | `HistoryChipMaxWidth = 160.dp` | 单个 chip 的文字宽度上限 |
| `PlaylistPages.kt` | `PickerDialogContentMaxHeight = 420.dp` | 两个选择器原先各写 460 / 420，差异只是「早滚一行还是晚滚一行」，已合并 |

#### 验证

- 三端编译 `BUILD SUCCESSFUL`（`:androidApp:compileDebugKotlin` / `:desktopApp:compileKotlin` / `:webApp:compileKotlinWasmJs`）。
- `:player-ui:desktopTest` **29 项全绿**（`PlayerActionsTest` 5 + `SharedComponentsTest` 9 + `TaotaoTokensTest` 15），
  `failures=0 errors=0`。
- **安卓模拟器截图 7 屏**（亮色），归档在 `artifacts/design-system-tokens/stage2-closeout/`：

  | 文件 | 覆盖的改动 |
  | --- | --- |
  | `01-home.png` | 首页（`SharedSongRow` 未直接体现，作基线） |
  | `02-search.png` | **搜索页页头 22→26sp**，`SharedSectionHeader(PAGE)` 替换行内 `Row` |
  | `03-mine.png` | 「我的」页 |
  | `04-playlists.png` | **第 6 份页头收敛后的效果**：返回 + 标题 + 副标题 + 两个并排操作按钮（`trailing = { Row { … } }`）不溢出 |
  | `05-history.png` | 最近播放空态（`stateIcon` 44dp） |
  | `06-settings.png` | 设置页（`SharedBackButton`） |
  | `07-auth.png` | **登录页 56→40dp 顶部留白 / 22→20dp 表单容器圆角 / 27→26sp 标题** |

  ⚠️ 飞行模式下所有接口都拿不到数据，所以**列表里有真实歌曲时的 `SharedSongRow`（52→48dp）没能验证到** ——
  这条限制一直存在，见「还没有的东西」。

#### 本轮又踩到的三个坑（已写进技能）

1. **`assembleDebug` 会在 `dexBuilderDebug` 偶发失败**（`Failed to process: ...transformDebugClassesWithAsm\dirs`），
   重跑一次即过。**不要据此判断代码有问题** —— 先 `--stacktrace` 看根因，`Caused by` 里是沙箱文件访问就直接重跑。
2. **`emulator … &` 会随该次 Bash 调用一起被杀**，`adb devices` 里连 `offline` 都看不到。必须用受管后台任务。
3. **ANR 弹窗这次没被 `hide_error_dialogs 1` 压住**，点掉它的两种方式（DPAD / `input tap`）**都会让屏幕变黑**，
   需要 `input keyevent KEYCODE_WAKEUP` 再等几秒才恢复。正确做法是**在启动应用之前**就把
   `hide_error_dialogs 1` 设好并重启一次 SystemUI，而不是事后去关弹窗。

#### 需要人工确认的视觉 delta（收尾新增）

| 项 | 原值 | 新值 | 说明 |
| --- | --- | --- | --- |
| 列表行封面 | 52dp | **48dp** | 影响三端所有歌曲列表 |
| 歌单卡片封面 | 62dp | **48dp** | 与歌曲行统一为同一语义角色 |
| 歌单选择器里的封面 | 40dp | **48dp** | 同上 |
| 歌单卡片圆角 / 内边距 | 18dp / 14dp | **20dp / 16dp** | |
| 歌单选择行圆角 | 12dp | **14dp** | `TaotaoShapes.medium` |
| 搜索页标题 | 22sp | **26sp** | 与其他页头统一为 `PAGE` 档 |
| 「搜索历史 / 搜索联想 / 热门搜索」 | 16 / 16 / 18 sp | **18sp** | 三个同级区块标题此前三种字号 |
| 搜索历史 chip 拖拽阴影 | `12f`（**像素**） | `12dp` | 原写法在高密度屏上只有 4dp，属于按密度不一致的 bug |
| 搜索历史 chip 删除图标 | 14dp | **16dp** | |
| 登录页顶部留白 | 56dp | **40dp** | 对齐 `xxxl` 档 |
| 登录页表单容器圆角 / 主按钮圆角 | 22dp / 16dp | **20dp / 14dp** | |
| 登录页品牌标题 | 27sp | **26sp** | |
| 迷你播放器播放/暂停图标 | 28dp | **30dp** | |
| 宽屏播放布局左右留白 | 48dp | **40dp** | |
| 徽标纵向内边距 | 安卓 1dp / 共用 2dp | **2dp** | 统一为 `badgeVertical` |
| 13sp 正文说明 | 13sp | **12sp** | 全项目收敛到 `bodySmall` 档 |

#### 仍未做（截至本批）

| 文件 | 残留 | 说明 |
| --- | --- | --- |
| `desktopApp/DesktopShell.kt` | 186 | **桌面端从未纳入阶段 2 范围** |
| `desktopApp/DesktopPlaylistPage.kt` | 49 | 同上 |
| `desktopApp/Main.kt` | 20 | 同上 |
| `AiStudioPage.kt` | 40 | 未触及 |
| `ChatPage.kt` | 23 | 未触及 |
| `UpdateGate.kt` | 20 | 未触及 |
| `QualitySheet.kt` | 19 | 未触及 |
| `LyricPanel.kt` | 9 | 未触及 |
| `SleepTimerDialog.kt` | 4 | 未触及 |
| `TaotaoMusicApp.kt` | 5 | 4 个页面级布局高度 + `strokeWidth = 2.dp`（可改 `TaotaoStroke.medium`） |
| `PlayerSurface.kt` / `AppleStyleComponents.kt` | 3 / 4 | **播放控件尺寸**（上/下一首 36dp、播放圆 64dp、圆内图标 32dp）是一套独立刻度，需要先定档（30 还是 36）再迁移 |

以上合计 **约 127 处**（不含 token 文件自身）。剩下的都是「同一个动作再做一遍」，
风险已经从「设计决策」降到「机械替换」，可按文件分批提交。

**另有一件小事**：`kotlin-js-store/wasm/package-lock.json`（wasm 构建生成，8KB）未被 git 跟踪、
也不在 `.gitignore` 里，每跑一次 wasm 构建就会重新出现。建议加进 `.gitignore`。

### 阶段 2 收尾 · 第二批（2026-09-20，DONE）

第一批末尾列了 8 行「未做」、约 127 处，判断是「剩下的都是同一个动作再做一遍」。
本批把这份清单**一次清完**，并纠正了那个判断里错的地方。

#### 实测收敛效果（对照基线提交 e3ce07b）

| 范围 | 基线 | 第一批后 | 本批后 | 总降幅 |
| --- | --- | --- | --- | --- |
| `androidApp/ui/` | 466 | 133 | **18** | **-96.1%** |
| `player-ui`（非 `theme/`） | 47 | 0 | **0** | **-100%** |
| `player-ui/theme/AppleStyleComponents.kt` | 4 | 4 | **2** | -50% |
| `webApp/Main.kt` | 17 | 17 | **6** | **-64.7%** |
| `desktopApp/` | 255 | 255 | 255 | **未纳入范围** |

> 口径：`count-ui-literals.py`（圆角调用算 1 处，其内部的 dp 不重复计），统计时排除 `theme/` 下的 token 定义文件。
> 「第一批后」的数字取自上一批的「仍未做」表，两者口径一致。

#### 逐文件

| 文件 | 前 | 后 | 主要动作 |
| --- | --- | --- | --- |
| `AiStudioPage.kt` | 40 | **0** | 6 种圆角按语义归到 `TaotaoShapes` 五档；20sp 走 `subtitle`；24sp 走 `headlineSmall` |
| `ChatPage.kt` | 23 | **0** | 头像/菜单图标走 `iconMd` / `iconLg`；气泡圆角走 `card` |
| `UpdateGate.kt` | 20 | 3 | 3 个页面级高度提为命名常量；其余全部 token 化 |
| `QualitySheet.kt` | 19 | **0** | 标题走 `sectionTitle`；选项行 `medium`；音质标签 `badge`；进度圈 `progressInline` |
| `LyricPanel.kt` | 9 | **0** | 歌词行样式改为基于 `sectionTitle.copy(...)`；装饰音符走 `hero` |
| `SleepTimerDialog.kt` | 4 | **0** | 选项间距 `tightVertical`；说明 13sp → `bodySmall` |
| `TaotaoMusicApp.kt` | 5 | 4 | `strokeWidth = 2.dp` → `TaotaoStroke.medium` |
| `AccountAndAnnouncementDialogs.kt` | 4 | 2 | 两个高度提为命名常量；2 处描边走 `TaotaoStroke.medium` |
| `PlayerSurface.kt` | 3 | **0** | 24 → `iconMd`；上/下一首 36 → `iconButton` |
| `AppleStyleComponents.kt` | 4 | 2 | 播放按钮默认尺寸改走新 token；滑块尺寸提为命名常量 |
| `webApp/Main.kt` | 17 | 6 | 分享页 6 个布局常量提为命名常量 |

#### 剩下的 24 处是什么

`androidApp/ui/` 的 18 处 + `webApp/Main.kt` 的 6 处，**没有一处是内联字面量**，
全部是文件私有的命名常量，且都有「为什么不该进 token」的注释：

| 位置 | 常量 |
| --- | --- |
| `AuthPage.kt` | `AuthButtonHeight = 52.dp` |
| `SearchPage.kt` | `HistoryChipMaxWidth = 160.dp` |
| `PlaylistPages.kt` | `PickerDialogContentMaxHeight = 420.dp` |
| `UpdateGate.kt` | `UpdateHeroIconSize` / `UpdateNoteMaxHeight` / `OptionalUpdateNoteMaxHeight` |
| `AccountAndAnnouncementDialogs.kt` | `ProfileSheetMaxHeight` / `AnnouncementDialogMaxHeight` |
| `components.kt` | `SkeletonTitleWidth` / `SkeletonTitleHeight` / `SkeletonSubtitleWidth` / `SkeletonSubtitleHeight` / `PagerDotActiveSize` / `PagerDotIdleSize` |
| `TaotaoMusicApp.kt` | `MineShortcutCardHeight` / `HomePromoCardHeight` / `CrashLogViewportMaxHeight` / `PlaybackQueueContentHeight` |
| `webApp/Main.kt` | `ArtworkSizeCompact` / `ArtworkSizeWide` / `CompactWidthBreakpoint` / `ShareContentMaxWidth` / `DownloadButtonHeight` / `FallbackGlyphSize` |
| `AppleStyleComponents.kt` | `SliderTrackHeight` / `SliderThumbSize` |

判据统一为：**它描述的是「这一屏占多高 / 这一块多宽」，换一屏就不成立**。
把这类数字提成 token 只会让刻度表里出现一堆只有一个调用点的条目。

#### 新增的 token

| token | 值 | 为什么必须新增 |
| --- | --- | --- |
| `TaotaoSizes.playButton` | 64dp | 播放按钮的圆。与 `avatar`(64) 同值但不同语义（头像跟列表走，播放按钮跟播放控件缩放），故不共用名字 |
| `TaotaoSizes.playButtonIcon` | 32dp | 圆内的播放/暂停图标。放进 `icons` 刻度会让「图标该多大」多一个无关候选 |
| `TaotaoSpacing.tightVertical` | 2dp | **由 `badgeVertical` 改名而来**，见下 |

#### 把 `badgeVertical` 改名为 `tightVertical`（本轮的一处纠错）

上一批加的 `TaotaoSpacing.badgeVertical`（2dp，刻度外例外）名字太窄 —— 它只描述了徽标。
本批实际有 **5 处**需要 2dp：徽标内边距（3 处既有）、歌词行纵向内边距、消息元信息、
紧凑选项列表、音质说明间距。**硬套 `badgeVertical` 会写出语义错误的代码**，
而另开一个同值 token 又违反「一个值只给一个答案」。

改名后的判据写进了文档：**留白是在「分隔」还是在「撑高」** ——
分隔（两个独立元素的常规间隔）走 `xs` 及以上；撑高走 `tightVertical`。
它仍然刻意不进 `steps`，否则会破坏「所有间距都是 4dp 整数倍」这条断言。

#### 视觉 delta（本批新增，需要人工确认）

| 位置 | 改前 | 改后 | 原因 |
| --- | --- | --- | --- |
| `QualitySheet` 选项行圆角 | 12dp | **14dp** | 归到「列表行」档 `medium` |
| `QualitySheet` 选项行纵向内边距 | 13dp | **12dp** | 13 不在刻度上，取 `sm` |
| `QualitySheet` 音质标签圆角 | 8dp | **6dp** | 归到「徽标」档 `badge` |
| `QualitySheet` 音质标签内边距 | 10 / 5 dp | **8 / 4 dp** | 归到 `xs` / `xxs` |
| `QualitySheet` 加载圈 | 22dp | **20dp** | 归到 `progressInline` |
| `QualitySheet` 底部弹层左右内边距 | 20dp | **20dp** | 改走 `screenHorizontal`，无变化 |
| `QualitySheet` 底部内边距 | 28dp | **24dp** | 取 `xl` |
| `UpdateGate` 页面内边距 | 30dp | **32dp** | 30 更靠近 32，取 `xxl` |
| `UpdateGate` 更新说明卡片圆角 | 16dp | **20dp** | 归到「卡片」档 `card` |
| `UpdateGate` 说明区上下间距 | 26dp | **24dp** | 取 `xl` |
| `ChatPage` 气泡圆角 | 16dp | **20dp** | 归到「容器」档 `card` |
| `ChatPage` 菜单图标 | 28dp | **30dp** | 取 `iconLg` |
| `ChatPage` 聊天气泡图标 | 26dp | **24dp** | 取 `iconMd` |
| `ChatPage` 页面内边距 | 20 / 18 dp | **20 / 16 dp** | 取 `lg` / `md` |
| `ChatPage` 会话行纵向内边距 | 14dp | **12dp** | 取 `sm` |
| `AiStudioPage` 会话标题字重 | **Bold** | **Medium** | 走 `TaotaoTypeScale.subtitle`（20sp 档的字重是 Medium） |
| `AiStudioPage` 抽屉标题 | 24sp | **22sp** | 24 不在刻度上，取 `title` 档 |
| `AiStudioPage` 输入框圆角 | 22dp | **20dp** | 归到 `large` |
| `AiStudioPage` 发送按钮圆角 | 18dp | **20dp** | 归到 `large` |
| `AiStudioPage` 图片预览弹窗圆角 | 24dp | **28dp** | 归到「页面级容器」档 `extraLarge`（M3 对话框默认也是 28） |
| `AiStudioPage` 会话行圆角 | 12dp | **14dp** | 归到 `medium` |
| `AiStudioPage` 气泡圆角 | 20dp | **20dp** | 改走 `card`，无变化 |
| `AiStudioPage` 页面左右内边距 | 18dp | **16dp** | 取 `md` |
| `AiStudioPage` 会话行左右内边距 | 12 / 4 dp | **12 / 4 dp** | 无变化（走 `sm` / `xxs`） |
| `LyricPanel` 空歌词占位块 | 音符行高 = `bodyLarge` 的 24sp | **`hero` 的 52sp** | 顺带修掉一个潜在问题：40sp 字形原先被塞在 24sp 的线盒里，是溢出的 |
| `LyricPanel` 歌词行圆角 | 10dp | **10dp** | 走 `small`，无变化 |
| `SleepTimerDialog` 说明文字 | 13sp | **12sp** | 全项目收敛到 `bodySmall` |
| `SleepTimerDialog` 自定义输入框上间距 | 6dp | **4dp** | 6 是 4/8 的中点，向小取 |
| `PlayerSurface` 上/下一首图标 | 36dp | **36dp** | 走 `iconButton`，**零视觉变化** |
| `webApp` 分享页左右内边距 | 28dp | **24dp** | 取 `xl` |
| `webApp` 分享页下载图标 | 20dp | **18dp** | 20 是 18/24 的中点，向小取 |
| `AppleStyleComponents` 播放按钮/图标 | 64 / 32 dp | **64 / 32 dp** | 改走新 token，**零视觉变化** |

#### 一个**故意没改**的设计问题

`PlayerSurface` 的播放控件目前是：上/下一首 **36dp**、圆内播放图标 **32dp**、随机/循环 **24dp**。
**次级操作（上/下一首）比主操作（播放）还大**，层级上说不通。

本批**没有顺手改小**，因为那是设计决定而不是 token 迁移该做的事 ——
迁移的原则是「换写法，不换观感」。建议后续把上/下一首收到 **30dp**（`iconLg`），
让层级变成 24 / 30 / 32，届时改一个 token 即可三端生效。

#### 验证

- 三端编译 `BUILD SUCCESSFUL`（`:androidApp:compileDebugKotlin` / `:desktopApp:compileKotlin` / `:webApp:compileKotlinWasmJs`）。
- `:player-ui:desktopTest` **30 项全绿**（`PlayerActionsTest` 5 + `SharedComponentsTest` 9 + `TaotaoTokensTest` 16），
  `failures=0 errors=0`。新增 1 条 `playControlScaleNests`（圆 > 圆内图标 > 页面标准图标）。
- `:webApp:wasmJsBrowserDistribution` 出包成功，本地静态服务渲染正常（分享页有 `demoShareSong()` 兜底路径，不需要网络）。
- **安卓模拟器截图 5 屏**（亮色），归档在 `artifacts/design-system-tokens/stage2-final/`：

  | 文件 | 覆盖的本批改动 |
  | --- | --- |
  | `00-home.png` | 基线 |
  | `01-mine.png` | 基线 |
  | `02-settings.png` | `QualityChip`（`badge` 6dp + 8/4 内边距） |
  | `03-quality-sheet.png` | `QualitySheet` 全部改动：标题、说明、选项行圆角与内边距、选中态 |
  | `04-sleep-timer.png` | `SleepTimerDialog`：选项紧凑堆叠（`tightVertical`）、说明 12sp |

#### 本批**未验证**的部分（如实列出）

| 未验证 | 原因 |
| --- | --- |
| `UpdateGate.kt`（强制/可选更新页） | 需要服务端下发「有新版本」，离线无法触发 |
| `ChatPage.kt` / `AiStudioPage.kt` | 需要登录 + IM 连接，飞行模式下不可达 |
| `PlayerSurface.kt` / `AppleStyleComponents.kt` | 需要实际播放，离线无音源 |
| `AccountAndAnnouncementDialogs.kt` | 入口要求先成功拉到 `profile()`，**离线点不动**（实测确认） |
| `LyricPanel.kt` | 需要播放到歌词页 |
| 暗色主题下的以上各屏 | 同上，且本轮未逐屏核对暗色 |

这些改动都只有「三端编译通过 + token 单测通过」这一层保证，
**没有视觉回归证据**。下一批若要动这些文件附近，建议先补一次联网环境的截图。

#### 本批踩到的三个坑

1. **`MaterialTheme.typography` 里没有 `caption` 槽位。**
   `caption` 是 `TaotaoTypeScale` 的成员（12sp/Normal），M3 的对应槽位叫 **`bodySmall`**。
   写成 `MaterialTheme.typography.caption` 会编译失败 —— 编译一次就抓到了，但**迁移脚本本身不会发现**。
   已把「12sp Normal → `bodySmall`、12sp Medium → `TaotaoTypeScale.label`」写进技能的口径表。
2. **对同一个文件并行发两个编辑会丢更新。**
   我给 `TaotaoSizes.kt` 同时发了「新增 `playButton`」和「改 `iconButton` 文档」两个编辑，
   第二个基于旧内容写盘，**把第一个覆盖掉了** —— 两个都报「成功」，文件里却只有后者。
   表现是 `AppleStyleComponents.kt` 报 `Unresolved reference 'playButton'`。
   **规则：同一个文件的两个编辑必须串行。**
3. **删 `import` 之前要确认该文件的字面量真的清零了。**
   `AppleStyleComponents.kt` 我删掉了 `dp` 导入，却同时又新增了 `4.dp` / `12.dp` 两个常量，
   直接编译失败。**新增常量与删除导入是矛盾的，必须一起判断。**

### 阶段 3 — 专项能力库（可选，按收益排序）

1. **Haze 做真实模糊。** 收益最高的一步：迷你播放器、播放页背景、底部栏用 `hazeSource` / `hazeEffect`，把「平」变成「有深度」。
   - 只加在 2–3 个位置，不要全应用铺开（模糊是 GPU 成本）。
   - 必须做降级：低端机 / 关闭动画时退回纯色半透明。
   - 参考 `AnimationTheme.kt` 的 `reduceMotion` 模式做开关。
2. **Material 3 Expressive 的 `MotionScheme`**：升级后可用官方物理动效，与 `AnimationTheme.kt` 的曲线并存 —— 但**同一语义只能有一个来源**，不要同一处既用 `taotaoSpring` 又用 `MotionScheme`。建议 `MotionScheme` 只用于新增组件，存量保持不动。
3. **可拖拽排序**（如 `sh.calvin.reorderable`）：`components.kt:129` 已有 `dragHandle` 插槽，说明拖拽需求存在，但目前实现未共用。确认现状后决定是自建还是引入。

## 验收

- 阶段 0：三端（Android / Desktop / Web）均可构建，Release APK 可出包并签名验证通过。
- 阶段 1：`TaotaoPlayerTheme` 传入自定义 `Typography` 与 `Shapes`；token 单测通过。
- 阶段 2：**DONE**。`components.kt` 与 `TaotaoMusicApp.kt` 的 UI 字面量（`.sp` / `RoundedCornerShape` / 裸 `.dp`）
  从修正后基线 **143 降到 12（-91.6%）**，目标为 ≥60%。三端编译通过，`player-ui` 27 项测试全绿。
  残留 12 处逐条列出保留理由（见「阶段 2 执行结果」）。
- 阶段 2 收尾（第一批）：**DONE**。`PlaylistPages.kt` / `AuthPage.kt` / `SearchPage.kt` 从 **143 降到 3（-97.9%）**；
  `player-ui` 五个共用组件从 16 降到 **0**。页头实现 **7 份 → 1 份**，返回按钮 **4 份 → 1 份**。
  三端编译通过，`player-ui` **29 项测试全绿**。
- 阶段 2 收尾（第二批）：**DONE**。`androidApp/ui/` 从 **466 降到 18（-96.1%）**，
  `player-ui` 非 token 文件 **0**，`webApp/Main.kt` 从 17 降到 **6**。
  **18 + 6 处残留全部是页面级布局的私有命名常量，内联圆角与内联字号为 0。**
  三端编译通过，`player-ui` **30 项测试全绿**，`:webApp:wasmJsBrowserDistribution` 出包成功。
  **`desktopApp/` 的 255 处不在范围内，需单独排期。**
- 阶段 3：模糊效果在低端机与「关闭动画」状态下正确降级，无掉帧回归。
- 全程：`player-ui` 的 `commonMain` 不新增平台专属依赖；`shared` 不引入 Compose。

## 风险

- **工具链升级是最大风险点。** CMP 1.7.1 → 1.12 跨越 5 个小版本，且 Kotlin 2.0 → 2.2 涉及 K2 迁移。**必须单独提交、单独回滚**，不要和 token 改动混在一起。
- **一次性全量替换字面量会失控。** 391 处裸 dp、22 种字号，逐页替换，每页单独提交。
- **`player-ui` 改动会影响三端。** 桌面端有音源选择 chip 等安卓没有的 UI（`DesktopShell.kt`），提取组件时要确认不会把桌面端的布局假设带进来。
- **不要把 token 层做成「万能配置」。** 目标是收敛，不是增加可配置项。每一层 token 的取值数量应少于它替代的字面量数量。
- **中文字体选型有体积代价。** 若要内嵌字体，需评估 APK 增量；中文全字库通常数 MB，建议只内嵌必要字重或改用系统字体 + 字重规范。

## 与 005 的关系

005（客户端业务模块化）会把 `TaotaoMusicApp.kt` 拆成 feature 模块。本方案的阶段 2 也要动同一批文件。

- **不互相阻塞**，但建议 005 先落 `TaotaoMusicApp.kt` 的拆分，再做阶段 2，避免同一文件被两边反复改写。
- 阶段 1（token 层）与 005 完全无冲突，可以先行。
- 若 005 先做，token 层应作为独立模块被 feature 模块依赖，依赖方向为 `feature-* → player-ui → shared`。
