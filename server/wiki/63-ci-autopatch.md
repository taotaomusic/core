# autopatch 热修补丁自动生成器

[返回文档中心](README.md)

最后更新:2026-10-11

`autopatch/` 是 Android **方法级热修复**的补丁自动生成器:输入修复前后的两份编译产物,自动判定这次改动能不能热修,能修就直接生成补丁入口类。它回答两个问题——「这次改动敢不敢走补丁」和「补丁类怎么写」;补丁的打包、登记与下发仍走既有人工流程。热修的整体设计与下发链路见 [HOT_UPDATE.md](../../HOT_UPDATE.md) 与 [61-release-android.md](61-release-android.md) §7;本文只讲生成器本身与它在 CI 里的位置。

## 1. 定位:只做「生成器」这一半

项目里的方法级热修分三块,autopatch 只负责第三块:

| 环节 | 位置 | 职责 |
| --- | --- | --- |
| 编译期插桩 | `build-logic/` 的 `com.taotao.hotfix` 插件(`HotfixClassVisitor`) | 用 AGP 8 Instrumentation API 给每个可插桩方法开头插「有补丁就交给补丁并返回」的分支 |
| 运行时 | `androidApp/src/main/java/com/taotao/music/hotfix/`(`PatchDispatcher` / `PatchEntry` / `HotfixLoader`) | `DexClassLoader` 加载补丁 DEX,把分发器赋给宿主类的静态字段 |
| **补丁生成** | **`autopatch/`**(本文) | 扫描 base/head 差异 → 判定能否热修 → 生成 `PatchEntryImpl` 字节码 |

这是独立 JVM 工具项目:自带 `settings.gradle.kts`(`rootProject.name = "autopatch"`,不被主仓 root 构建收编,可整目录拎出去开源),依赖只有 ASM 9.7(大版本对齐主仓 build-logic)与 Kotlin/JVM 21,**纯 ASM + JDK,不需要 Android SDK**。模型与美团 Robust 同路(编译期插桩 + 运行时方法分发),再造的原因与一个关键简化——**宿主不混淆**(反射键天然稳定)——见 `autopatch/README.md`。注意「不混淆」在 2026-10 起不再是「R8 整个关掉」:Release 已启用 R8 收缩(`androidApp/proguard-rules.pro` 显式 `-dontobfuscate` + `-dontoptimize` + 插桩三包整包 keep,与插桩共存的边界见 90 篇 §2),方法键与反射键的稳定性由规则保证,工具链的输入仍是未混淆产物,不需要 mapping。

生成产物如何进入下发链路:

```text
autopatch 生成 .class
  → 宿主 :patch:buildPatch 用 d8 打成补丁 DEX(zip 成 patch-<n>.apk)
  → POST /app/admin/patches 登记(绑定 targetVersionCode,见 61 篇 §7)
  → app_patch 表(见 42-database-tables-release.md §5)
  → 客户端经 /app/patch/{targetVersionCode}/{patchVersion} 下载
```

补丁与整包的关系是兜底:有整包更新时服务端不下发补丁(见 [RELEASE.md](../../RELEASE.md) §一)。

**与手写补丁的关系**:`patch/` 模块里现在还躺着手写的 `PatchEntryImpl.kt`(按 [RELEASE.md](../../RELEASE.md) §五「写补丁」一步人工编写)。生成器产出的类与手写版遵循**同一个契约**——入口类全名固定为 `PatchEntry.ENTRY_CLASS`(`com.taotao.music.hotfix.generated.PatchEntryImpl`),加载器按这个名字反射实例化,宿主与补丁只靠这一个约定耦合。autopatch 做的事,本质是把「人肉对照两份产物写 `isSupport` / `dispatch`」自动化,并让闸门替人判断该不该写。

## 2. 对齐命门:方法键

生成器算出的方法键必须与插桩器写进字节码的**逐字节一致**——运行时 `PatchDispatcher.isSupport(key)` 拿的就是插桩时的那个字符串,差一个字符补丁永远不会被命中:

```text
<owner 内部名>#<方法名><descriptor>
例:com/taotao/music/data/im/WukongImClient#mergeSyncedConversations(Lorg/json/JSONArray;)I
```

`core/MethodKey.kt` 定义键格式(owner 用斜杠内部名),同文件里的 `Instrumentable` 对象**一字不差地复刻**插桩器 `HotfixClassVisitor.shouldInstrument` 的判定,分两层:类级——接口、抽象、native、内部名含 `$` 的类整类排除(协程状态机 / `WhenMappings` / lambda 类的变化不生成「加载即拒」的死补丁);方法级——排除 `<init>`、`<clinit>`、合成方法(`ACC_SYNTHETIC`)与名字含 `$` 的方法。两边任何一条规则漂移,都会出现「生成器认为能修、运行时却没有插桩点」这种最难排查的错位,所以改任一侧都必须同步核对另一侧。

另有一个**范围前提**:插桩插件只作用于 `data` / `player` / `update` 包(Compose 可组合函数依赖 Group 调用严格配对,方法开头插提前 return 会破坏组结构,UI 层刻意不插)。生成器的包白名单由 `--allow` 传入,**未传时默认就是插桩三包**(带尾斜杠,与 `HotfixInstrumentationPlugin.includedPackages` 逐字对齐,两侧注释互指防漂移)——历史上「留空 = 不按包限制」的口径已废弃,它会放行从未插桩的包(如 ui 层)改出的方法,生成加载即拒的死补丁。

顺带说明插桩的形态与代价:`HotfixClassVisitor` 给每个类加一个 `public static volatile PatchDispatcher $$patchDispatcher` 字段(ACC_VOLATILE——反射写与插桩代码的 GETSTATIC 读端建立 happens-before,补丁挂上后各线程立即可见,2026-10 审计补;运行时由加载器跨类加载器反射赋值),并在每个可插桩方法开头插等价于下面这段的分支:

```java
if ($$patchDispatcher != null && $$patchDispatcher.isSupport(key)) {
    return (T) $$patchDispatcher.dispatch(key, this, new Object[]{ 参数... });
}
```

只插开头分支、不动原方法体——没有补丁时开销就是一次静态字段读加一次 null 比较,可以忽略;这也是它避开 Tinker 那类「改 dexElements」方案全部坑(DEX 只读、vdex 异常、必须重启)的原因。

## 3. 管线:扫描 → diff → 闸门 → 生成

```text
① 扫描   ClassScanner.scanDir(base / head)   → 每个方法算「忽略行号与栈帧」的语义指纹
② diff   diff(base, head)                    → 方法 增/删/改、字段增删、类增删
③ 闸门   HotfixGate.evaluate(diff, config)   → Eligible(可热修方法键) | Rejected(必须整包,逐条原因)
④ 生成   PatchEmitter                        → 补丁入口类 + Refl 支撑类的 .class
⑤ 打包   (交给宿主 :patch:buildPatch)         → d8 打成补丁 DEX
```

- **① `core/ClassScan.kt`**:ASM tree API 读 `.class`(`SKIP_DEBUG | SKIP_FRAMES`),对每个方法体序列化「指令语义」——opcode 加关键操作数(字段/方法引用、常量、类型),indy 记到 **bootstrap 方法与参数**、switch 记**全部分支目标**(标签按首次出现编号引用,普通标签不进指纹),**跳过行号与栈帧节点**后取 SHA-256 作指纹——「只改 lambda 捕获 / 分支重排」不再漏检;改缩进、挪行不会误判成变化。扫描期顺带收集**不可移植事实**(`MethodInfo.portabilityIssues`:super 调用、NEW 宿主类型、宿主 `<init>`),交给闸门结构化拒绝。
- **② `core/Diff.kt`**:支持**多目录合并扫描**(`--base` / `--head` 逗号分隔多个模块产物目录,同一内部名不许出现在两个目录里)——androidApp 与 `:shared` 的 kotlin-classes 各传一份,shared 的类不传进来就进不了 diff;其变化会被闸门按「不在插桩范围」结构化拒绝,**半新半旧的补丁不再可能静默生成**。方法分 `CHANGED` / `ADDED` / `REMOVED`,字段与整类的增删单独记账交给闸门。
- **③ `core/Gate.kt`**:规则刻意保守(热修是应急动作,宁可误判成整包)。以下任一条命中即 `Rejected`:新增/删除字段(已安装的类无法改字段布局);在已有类上新增或删除方法(改不动已安装 DEX 的方法表);删除整类;变化的方法不可插桩(构造器/合成/接口/抽象/native,含 `$` 类);变化的方法不在 `--allow` 包白名单;**补丁方法体含不可移植结构**——super 调用(`INVOKESPECIAL` owner≠本类,反射改写会虚分发到最派生实现导致无限递归)、在补丁里 `new` 宿主类型、调宿主 `<init>`,扫描期收集、此处结构化拒绝并说明需整包(不再等发射阶段裸抛 `UnsupportedOperationException`);或根本没有方法级变化(「可能只改了资源/UI/注释」)。通过时返回可热修方法键、随补丁打包的新类,以及一条**不阻断的警告**:Kotlin `inline` 函数无法从被调方字节码识别(调用点已内联,补被调方无效),需人工确认。
- **④ `emit/`**:`PatchEmitter` 生成同时实现宿主 `PatchEntry` 与 `PatchDispatcher` 两个接口的 `com/taotao/music/hotfix/generated/PatchEntryImpl`(`targets()` 报宿主类全名、`isSupport(key)` 精确匹配、`dispatch(key, receiver, args)` 路由到 `impl$i` 静态方法,未命中抛 `IllegalStateException` 而不是静默返回错值)。`MethodTransplanter` 把 head 侧方法体移植进 `impl$i`:`(receiver, args) -> Object` 定长签名,局部变量槽整体 +2、重建原始帧(强转 receiver、拆箱参数)、返回值装箱。方法体里对**宿主包成员**(默认前缀 `com/taotao/music`)的取字段/存字段/方法调用被 `HostAccessRewriter` 改写成 `io/autopatch/runtime/Refl` 的反射调用——补丁类与宿主类是两套 `Class`,直连访问私有成员会抛 `IllegalAccessError`,统一走反射最简单也总是正确。宿主**静态字段**访问(`GETSTATIC`/`PUTSTATIC`)已支持:`Refl.getStatic/setStatic` 沿继承链与接口查找静态字段(companion / 顶层常量编译在接口上的情况可命中),与闸门契约互认——静态字段访问不再被拒绝;仍不可为的(`new` 宿主类型、super 调用)已前移到闸门拒绝。`LenientClassWriter` 处理「生成器 classpath 上没有宿主类」:`getCommonSuperClass` 加载失败就退回 `java/lang/Object`(Robust 踩过同一个坑)。

产物共两个文件:`PatchEntryImpl.class` 与 `io/autopatch/runtime/Refl.class`(自包含反射支撑类,必须随补丁 DEX 一起打包)。d8 打包不在 autopatch 职责内——`:patch:buildPatch`(见 `patch/build.gradle.kts`)用 SDK 自带 d8 打 `classes.dex` 并压成 `patch-<patchVersion>.apk`;打包后要核对补丁包里没有混入宿主类(见 [RELEASE.md](../../RELEASE.md) §五)。

## 4. CLI 与测试

`cli/Main.kt` 用法:

```bash
autopatch --base <baseClassesDir[,baseClassesDir2...]> --head <headClassesDir[,headClassesDir2...]> \
          --out <outDir> [--host com/taotao/music] [--allow com/taotao/music/data,com/taotao/music/player]
```

- `--base` / `--head` 是两份编译产物目录(release 变体的 kotlin-classes,支持**逗号分隔多个模块目录**:androidApp 与 `:shared` 各一份,shared 的类不传就进不了 diff;base = 线上那个版本,head = 修好之后);**不收 APK、也不收 mapping**,宿主不混淆所以不需要 mapping。CI 的 client-android job 会归档这两份 kotlin-classes(保留 30 天),生成补丁的 base 侧输入不再随下次构建丢失。
- `--host` 传宿主包前缀(反射改写范围),`--allow` 传允许热修的包白名单(**未传默认取插桩三包** data / player / update,带尾斜杠与插桩插件逐字对齐;不要再依赖旧的「留空 = 不限制」行为)。
- 判定必须整包时**以退出码 2** 结束并逐条打印原因;成功则打印方法键清单与产物路径,外加需人工确认的警告。

测试覆盖全链路:`core/DiffPipelineTest.kt`(方法体变化识别、挪行不误判、新增字段拒绝、包外拒绝、构造器不可接管)、`emit/PatchEmitterTest.kt`、`cli/CliIntegrationTest.kt`(从磁盘两份产物端到端走 扫描→diff→闸门→生成)。本地跑法(借根 wrapper,autopatch 暂无独立 wrapper):

```bash
./gradlew.bat -p autopatch test
```

## 5. CI:autopatch job

根 `.github/workflows/ci.yml` 的 `autopatch` job(路径过滤开关定义在 `changes` job):

- **触发**:`changes` 的 `dorny/paths-filter` 命中 `autopatch/**`,或提交信息含 `[full]` 全量构建。只动 `autopatch/` 目录的提交不会连带构建后端或客户端。
- **runner**:ubuntu-latest + Temurin 21;因为纯 JVM 工程不需要 Android SDK,比客户端 job 轻得多。
- **动作**:只有一步 `./gradlew -p autopatch test`(`--console=plain --no-daemon`,`-p` 指向 autopatch 自己的构建根,借用根目录的 Gradle wrapper)——CI 里跑的是**生成器自身的测试**,不是用真实 APK 生成补丁。
- **依赖与顺序**:`needs: [changes, purge]`,与 crypto 等 job 一样等 `purge` 完成(`purge` 本身通常秒过,只在提交信息含 `[purge]` 时才真的删 Release)。
- **产物**:仅测试报告 artifact `autopatch-test-report`(`autopatch/build/reports/tests/test/`,`if: always()`,`if-no-files-found: ignore`)。**不发任何 Release**——补丁的生成、打包、登记与放量都是发版时的本地人工流程,走 [RELEASE.md](../../RELEASE.md) §五与 [61-release-android.md](61-release-android.md) §7,CI 不参与。

## 6. 边界:什么适合补丁,什么必须整包

| 改动 | 结论 |
| --- | --- |
| `data` / `player` / `update` 包内**已有方法的方法体逻辑变化** | 可热修(前提是该方法被插过桩) |
| 新增/删除字段、在已有类上增删方法、删除类 | 必须整包(闸门直接拒绝) |
| 构造器、合成方法(桥接/访问器/lambda)、接口、抽象、native 方法的变化 | 必须整包(无插桩点可接管) |
| UI/Compose 层(界面、页面、组件) | 必须整包(插桩范围刻意不含 UI) |
| Kotlin `inline` 函数改动 | 必须整包;工具目前只给警告,需人工确认 |
| 资源、清单、依赖、gradle 配置 | 必须整包(方法指纹层面根本不存在差异,闸门以「没有可热修的方法变化」拒绝) |

两条人工纪律来自 [RELEASE.md](../../RELEASE.md) §五,与生成器互补:补丁只救急,同一个修复必须在源码原位置再做一遍进下一个整包;补丁按方法签名匹配,不要改方法签名。

## 7. 相关篇目

- 插桩与运行时:安卓端章节 [90-client-android.md](90-client-android.md) §8;插桩源码 `build-logic/src/main/kotlin/com/taotao/hotfix/`。
- 下发链路与登记:`app_patch` 表见 [42-database-tables-release.md](42-database-tables-release.md) §5;补丁下载契约见 [61-release-android.md](61-release-android.md) §1、登记放量见同篇 §7。
- 设计动机与历史决策:[HOT_UPDATE.md](../../HOT_UPDATE.md)、[RELEASE.md](../../RELEASE.md)。
- CI 总览:[62-ci-cloud-build.md](62-ci-cloud-build.md) §2 的 job 一览表含本 job。
