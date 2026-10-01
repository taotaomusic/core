# autopatch

> Android 方法级热修复的**补丁自动生成器**。诊断 base→head 两次构建的差异，判定能否热修，并（后续）自动生成可下发的补丁 DEX。

这是一个独立的 JVM 工具项目（自带 `settings.gradle.kts`，可整目录拎出去开源），
面向「编译期插桩 + 运行时方法分发」这一路热修方案（与美团 Robust 同模型）。

## 为什么再造一个

现有开源方案里，方法级热修只有 Robust 是同模型，但：

- Robust 的**插桩**用的老 `Transform` API 在 AGP 8 已被删除；
- 它的**补丁生成器**基于 Javassist，为「有混淆」的通用场景设计，反射改写复杂且脆弱。

本项目只做**生成器**这一半（插桩与运行时由接入方提供），并利用一个关键简化：
**目标宿主不混淆**（`isMinifyEnabled = false`）时，反射键天然稳定，Robust 一半的复杂度消失。

## 全链路的对齐命门：方法键

生成器算出的方法键必须与插桩器写进字节码的**逐字节一致**：

```
<owner 内部名>#<方法名><descriptor>
例：com/taotao/music/data/im/WukongImClient#mergeSyncedConversations(Lorg/json/JSONArray;)I
```

见 `core/MethodKey.kt`。可插桩判定 `core/Instrumentable` 复刻插桩器的 `shouldInstrument`
（排除 接口/抽象/native/构造器/`<clinit>`/合成/含 `$` 的方法）。

## 管线

```
① 扫描   ClassScanner.scanDir(base) / (head)   → 每个方法算「忽略行号与栈帧」的语义指纹
② diff    diff(base, head)                      → 变化/新增/删除的方法、字段、类
③ 闸门    HotfixGate.evaluate(diff, config)     → Eligible(可热修方法键) | Rejected(必须整包，逐条原因)
④ 生成    （待实现）                              → 为每个方法键生成补丁实现 + PatchEntryImpl
⑤ 打包    （交给宿主的 :patch:buildPatch）        → d8 打成补丁 DEX
```

①②③ 就是「**自动识别能不能热修**」，已完成且有测试覆盖。

## 当前状态

- [x] 方法键契约 + 可插桩判定（`core/MethodKey.kt`）
- [x] 类扫描与语义指纹（`core/ClassScan.kt`）
- [x] 差集计算（`core/Diff.kt`）
- [x] 资格闸门（`core/Gate.kt`）
- [x] 全链路单测（`src/test/.../DiffPipelineTest.kt`）
- [ ] 补丁实现生成（ASM 生成 dispatcher 可调的静态方法，实例访问走反射）
- [ ] `PatchEntryImpl` 自动生成
- [ ] CLI / Gradle 任务封装
- [ ] 云端 `workflow_dispatch` 集成

## 已知空缺

- **Kotlin `inline` 函数无法从被调方字节码识别**：调用点已内联，补被调方无效。识别它需要
  解析 Kotlin `@Metadata`，尚未实现；闸门目前以 warning 提示人工确认。
- `suspend` / `$default` 合成方法 / 内部类访问等 Kotlin 特例的生成侧处理待 ④ 阶段落地。

## 构建

```bash
# 在主仓里借用 root 的 gradle wrapper（本项目暂无独立 wrapper）
./gradlew.bat -p autopatch test
```
