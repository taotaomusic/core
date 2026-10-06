# 依赖安全审计：79 个 Dependabot 告警的可达性判定

日期：2026-10-06 ｜ 仓库：`taotaomusic/core` ｜ 处置：全部 79 条按 `not_used` 关闭

## 结论先行

**79 个告警中，可达（能进入分发产物）的为 0 个。** 修复动作不是"升级依赖"，
而是**降低可达性认知成本** —— 把这些告警标记为不可达并固化判定依据，
避免每次审计都重新推导一遍。

| 清单文件 | 数量 | 归属 | 可达性 |
|---|---:|---|---|
| `settings.gradle.kts` | 27 | AGP 模拟器测试平台 + Gradle plugin classpath | ❌ 不进 APK |
| `kotlin-js-store/package-lock.json` | 51 | Kotlin/Wasm 编译期 npm 工具链（全 `dev: true`） | ❌ 只在构建机 |
| `desktop/src-tauri/Cargo.lock` | 1 | Tauri 的 Linux/GTK 目标依赖（`glib`） | ❌ Windows 构建不参与 |

严重级别分布：critical 4、high 27、medium 44、low 4。

## 为什么"直接升版本"是错的

Dependabot 报告挂在 `settings.gradle.kts` 上，但**该文件里没有任何 `dependencies { }` 块** ——
它只有 `pluginManagement` 和 `dependencyResolutionManagement` 的仓库配置。
Dependabot 是把整个 Gradle 工程解析出的所有 classpath 都当"依赖清单"上报，
于是构建期工具链混进了运行时依赖的报告里。

### 判据：只认 `releaseRuntimeClasspath`

决定 APK 内容的 configuration 只有一个。实测：

```
gradlew.bat :androidApp:dependencies --configuration releaseRuntimeClasspath -q
  → netty: 0 处    bouncycastle: 0 处    protobuf: 0 处
    jdom: 0 处     jose4j: 0 处          commons-io: 0 处
```

而这些包在别的 configuration 里确实存在，来源已定位：

- **`io.grpc:grpc-netty:1.57.0` → netty 4.1.93.Final**
  来自 `_internal-unified-test-platform-android-test-plugin-host-emulator-control`
  → `com.android.tools.utp:android-test-plugin-host-emulator-control:31.7.3`
  —— 这是 **AGP 的 Android 模拟器测试平台内部 classpath**，gradle 自己的工具。
- **`protobuf-java` / `commons-io` / `guava`** 同上，都在这个 `_internal-*` configuration 下。
- **`bouncycastle` / `jdom2` / `jose4j`** 在 `:androidApp` 依赖树里完全不出现，
  只存在于 Gradle **最外层 plugin classpath**。

### 为什么"强制升级"修不动

把这些包钉到 `first_patched_version` 需要作用于 **构建期 classpath**，
而 `dependencyResolutionManagement`（`settings.gradle.kts`）管的是**项目依赖**，
两者不是同一套解析。强行用 `resolutionStrategy` 去动 AGP 自己的内部 classpath
轻则无效、重则弄坏 AGP。

**关键判断：即使升级成功，对 Windows/Android 产物也是零收益** —— 因为它们压根不进产物。

## 三份清单的逐一判定

### 1. `settings.gradle.kts`（27 条）

依据：`releaseRuntimeClasspath` 中不出现（见上）。
处置理由：属构建机本地工具链，不随应用分发，无法被远程利用。

### 2. `kotlin-js-store/package-lock.json`（51 条）

这是 **Kotlin/Wasm 编译期由 Kotlin Gradle 插件自动生成并回写的 lockfile**。
git 历史可见它由 CI 生成：

```
962dac7 build: CI 刷新 kotlin-js-store 锁文件（Linux 侧 npm 解析）[skip ci]
07ea679 build: 同步 kotlin-js-store 锁文件（CI wasm 构建过期报错）[full]
```

其中告警包**全部标记 `dev: true`**：

| 包 | 版本 | dev |
|---|---|:-:|
| `webpack` / `webpack-dev-server` | — | ✓ |
| `serialize-javascript` | 6.0.2 | ✓ |
| `source-map-js` | 1.2.1 | ✓ |
| `proxy-addr` | 2.0.7 | ✓ |
| `compression` | 1.8.1 | ✓ |
| `node-forge` | 1.4.0 | ✓ |
| `uuid` | — | ✓ |
| `brace-expansion` | — | ✓ |

⚠️ **`node-forge` 的告警没有修复版本**（影响 `<= 1.4.0` 全部版本）。
这也是不能靠升级解决的一类 —— 只能靠"它进不了产物"来消解风险。

**注意**：该 lockfile 手改无效，下次构建会被 Kotlin 插件覆盖。

### 3. `desktop/src-tauri/Cargo.lock`（1 条：`glib 0.18.5`）

依赖链：`glib` ← `gio-sys` ← **`gtk 0.18.2`** ← Tauri 的 `cfg(linux)` 目标依赖。

本项目桌面端（`desktop/`）**仅构建 Windows 目标**（`Cargo.toml` 的
`[target.'cfg(windows)'.dependencies]` 用 `winreg` 读注册表 MachineGuid），
Linux/GTK 依赖不参与编译链接，只是 lockfile 跨平台统一下载了元数据。

## 复现方式

新增 `tools/audit-deps.mjs`，把上面三条判据固化成可执行脚本：

```bash
# 导出依赖树（唯一需要的 Gradle 调用，不触发编译）
gradlew.bat :androidApp:dependencies --configuration releaseRuntimeClasspath -q > .gradle-release-runtime.txt

# 审计
node tools/audit-deps.mjs
```

输出三条判据的结论，退出码 0 = 全部不可达，1 = 存在可达告警需修复。
可用于**后续每次 Dependabot 告警批量到达时先跑一遍**，避免重复推导。

## 未处理的风险（诚实记录）

1. **构建机本身仍是攻击面**。这些包的漏洞在"构建机被投毒"场景下仍有意义
   （如 `webpack-dev-server` 的 source code exposure、`serialize-javascript` 的 RCE）。
   缓解措施是构建机不做交互式浏览、只跑 CI —— 但这属于运维层面，不是依赖升级能解决的。
2. **`node-forge` 无修复版本**，一旦将来有包把它引到生产链路，必须立刻替换而非升级。
3. **Dependabot 会在重新扫描时把同类告警再次上报**（本次操作期间就新出现了 12 条）。
   处置完毕后需复查 `state=open` 是否为 0。
