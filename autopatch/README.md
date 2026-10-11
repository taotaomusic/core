# autopatch

> Android 方法级热修复的**补丁自动生成器**。诊断 base→head 两次构建的差异，判定能否热修，并自动生成可下发的补丁 DEX。

这是一个独立的 JVM 工具项目（自带 `settings.gradle.kts`，可整目录拎出去开源），
面向「编译期插桩 + 运行时方法分发」这一路热修方案（与美团 Robust 同模型）。
插桩与运行时由接入方提供（桃桃音乐侧：`build-logic` 的 `com.taotao.hotfix` 插件 +
`androidApp` 的 `com.taotao.music.hotfix` 运行时）。

## 为什么再造一个

现有开源方案里，方法级热修只有 Robust 是同模型，但：

- Robust 的**插桩**用的老 `Transform` API 在 AGP 8 已被删除；
- 它的**补丁生成器**基于 Javassist，为「有混淆」的通用场景设计，反射改写复杂且脆弱。

本项目利用一个关键简化：**目标宿主不做混淆**（R8 保留 `-dontobfuscate`，见主仓
`androidApp/proguard-rules.pro`）时，方法键天然稳定，Robust 一半的复杂度消失。

## 全链路的对齐命门：方法键

生成器算出的方法键必须与插桩器写进字节码的**逐字节一致**：

```
<owner 内部名>#<方法名><descriptor>
例：com/taotao/music/data/im/WukongImClient#mergeSyncedConversations(Lorg/json/JSONArray;)I
```

见 `core/MethodKey.kt`。可插桩判定 `core/Instrumentable` 复刻插桩器的**两层**规则：
类级（内部名含 `$` 的合成类整类排除、`TaotaoApplication`、hotfix 运行时包）与方法级
（接口/抽象/native/构造器/`<clinit>`/合成方法）。

## 管线

```
① 扫描   ClassScanner.scanDirs(baseDirs) / (headDirs)  → 每个方法算「忽略行号与栈帧」的语义指纹，
                                                          并收集不可移植结构（super 调用、new 宿主类型等）
② diff    diff(base, head)                              → 变化/新增/删除的方法、字段、类
③ 闸门    HotfixGate.evaluate(diff, config)             → Eligible(可热修方法键) | Rejected(必须整包，逐条原因)
④ 生成    PatchEmitter.emit(...)                        → 补丁入口类（impl$i 静态方法 + Refl 反射改写）
⑤ 打包    （交给宿主的 :patch:buildPatch）               → d8 打成补丁 DEX
```

①②③ 是「**自动识别能不能热修**」；④ 已实现：实例/静态成员访问统一改写成
`io.autopatch.runtime.Refl` 的反射（随补丁 DEX 下发），唯一不支持的是 `new` 宿主类型
（闸门在 ③ 就会拒绝这类改动）。

多模块宿主（androidApp + shared）把各自 `build/tmp/kotlin-classes/release` 一起传入：
**shared 的类不进 diff，其改动就会静默丢失**；同一内部名出现在两个目录会在扫描期报错。

## 当前状态

- [x] 方法键契约 + 可插桩判定（`core/MethodKey.kt`）
- [x] 类扫描与语义指纹（`core/ClassScan.kt`，含 indy bootstrap 参数与 switch 分支目标）
- [x] 差集计算（`core/Diff.kt`）
- [x] 资格闸门（`core/Gate.kt`，不可移植结构结构化拒绝）
- [x] 补丁实现生成（`emit/MethodTransplanter` + `emit/HostAccessRewriter` + `runtime/Refl`）
- [x] 补丁入口类生成（`emit/PatchEmitter`）
- [x] CLI（`cli/Main.kt` + `cli/AutopatchCli.kt`，多目录扫描）
- [x] 全链路单测（`src/test/...`）
- [ ] 云端 `workflow_dispatch` 集成

## 已知空缺

- **Kotlin `inline` 函数无法从被调方字节码识别**：调用点已内联，补被调方无效。识别它需要
  解析 Kotlin `@Metadata`，尚未实现；闸门目前以 warning 提示人工确认。
- `suspend` 函数仅部分支持：状态机类是合成类（不可插桩），挂起点结构变化的改动会被
  闸门按「不可插桩」整包拒绝；挂起点不变的纯逻辑改动可以热修。
- 接口 default 方法不可热修（接口不插桩）。
- 补丁实现抛出的异常在原方法 try 块覆盖范围之外传播，作者需自行兜底（见宿主
  `PatchDispatcher` 的作者须知）。

## 构建

```bash
# 在主仓里借用 root 的 gradle wrapper（本项目暂无独立 wrapper）
./gradlew.bat -p autopatch test
```

## CLI 用法

```bash
autopatch --base <androidApp的kotlin-classes>,<shared的kotlin-classes> \
          --head <修好后的androidApp>,<修好后的shared> \
          --out <outDir> [--host com/taotao/music] \
          [--allow com/taotao/music/data/,com/taotao/music/player/,com/taotao/music/update/]
```

- `--base` / `--head` 支持逗号分隔多目录；同一内部名不允许出现在两个目录里。
- `--allow` 未传时默认取插桩三包（**必须与 build-logic 的
  `HotfixInstrumentationPlugin.includedPackages` 保持一致，改一边同步另一边**）。
