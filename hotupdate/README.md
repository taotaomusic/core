# hotupdate —— 可复用的 Kotlin 热更新模块

整包更新 + 远程配置（`com.taotao.music.update` 包）与 DEX 热修运行时（`com.taotao.music.hotfix` 包），
已从 androidApp 拆成独立 Android library 模块，**任何 Kotlin 客户端都可以直接接入**。

## 30 秒接入（其他 Kotlin 客户端）

### 1. 依赖模块

```kotlin
// settings.gradle.kts
include(":hotupdate")

// app 的 build.gradle.kts
dependencies {
    implementation(project(":hotupdate"))
}
```

### 2. 实现宿主契约（模块唯一需要你写的东西）

```kotlin
class MyUpdateHost(context: Context) : UpdateHost {
    override val endpoint = "https://music.xydaigua.cn"   // 你的服务端
    override val userAgent = "MyApp/1.0"
    override val config: UpdateConfigStore = MyConfigStore(context) // 远程配置缓存（可先用内存实现）
    override fun currentToken(): String? = mySession.token        // 未登录返回 null 即可
    override fun deviceId(): String = myDeviceId                  // 首次生成后必须稳定
    override fun dismissedVersionCode(): Long = myPrefs.dismissedVersion
    override fun commitDismissed(versionCode: Long) { myPrefs.dismissedVersion = versionCode }
}
```

`UpdateConfigStore` 最小实现（生产建议落盘，参考 androidApp 的 `RemoteConfigStore`）：

```kotlin
class MemoryConfigStore : UpdateConfigStore {
    private var values: Map<String, String> = emptyMap()
    override fun save(values: Map<String, String>, version: Long) { this.values = values }
    override fun text(key: String, default: String) = values[key] ?: default
    override fun boolean(key: String, default: Boolean) = values[key]?.toBooleanStrictOrNull() ?: default
    override fun int(key: String, default: Int) = values[key]?.toIntOrNull() ?: default
    override fun clear() { values = emptyMap() }
}
```

### 3. Application.onCreate 里两行启用

```kotlin
class MyApp : Application() {
    lateinit var hotUpdate: HotUpdateSession
        private set
    var activePatchVersion: Int = 0
        private set

    override fun onCreate() {
        super.onCreate()
        // ① 尽早加载已安装的补丁（晚于第一次业务调用就白打了）
        activePatchVersion = HotUpdate.installPatches(this)
        // ② 创建更新管理器；initialPatchVersion 必须传 ① 的返回值
        hotUpdate = HotUpdate.create(this, MyUpdateHost(this), initialPatchVersion = activePatchVersion)
        // ③ 启动后检查一次（静默失败不打扰用户）
        hotUpdate.launchCheck()
    }
}
```

### 4. 平稳运行后确认补丁可用（自愈机制的另一半）

```kotlin
// 应用正常跑起来之后（例如启动 5 秒后）调用，不要在 onCreate 里立刻调：
myApp.hotUpdate.updateManager.confirmPatch(myApp.activePatchVersion)
```

### 5. 界面读状态（可选）

`UpdateManager` 的 `status` / `manualResult` / `activePatchVersion` 是 Compose 状态，
Compose 客户端直接观察即可；强制更新门禁 UI 参考桃桃主 App 的 `ui/update/UpdateGate.kt`
（该 UI 属于宿主，模块不绑定任何界面实现）。

## 快速调用速查

| 想做什么 | 调用 |
| --- | --- |
| 启动期装补丁 | `HotUpdate.installPatches(context): Int` |
| 创建管理器 | `HotUpdate.create(context, host, initialPatchVersion)` |
| 检查更新（后台静默） | `updateManager.check()` 或 `session.launchCheck()` |
| 手动检查（结果可见） | `updateManager.check(manual = true)` |
| 下载 | `updateManager.download()` |
| 安装整包 | `updateManager.install()` |
| 响应头提示触发重查 | `updateManager.onLatestVersionHint(latestVersionCode)` |
| 补丁提示触发重查 | `updateManager.onLatestPatchHint(latestPatchVersion)` |
| 确认补丁可用（自愈） | `updateManager.confirmPatch(activeVersion)` |
| 忽略本次可选更新 | `updateManager.dismiss()` |
| 失败重试 | `updateManager.retry()` |
| 读远程配置 | `updateManager.remoteConfig.text/boolean/int(key, default)` |
| 非 Compose 订阅状态 | `updateManager.statusFlow`（StateFlow）与 `updateManager.events`（SharedFlow） |

## 非 Compose 客户端接入（StateFlow）

界面不是 Compose（普通 View、桌面桥接、后台 Service）时，不用引 Compose 也能拿到同样的状态：

```kotlin
val manager = hotUpdate.updateManager

// 状态流：与 Compose status 属性同源，StateFlow 去重相同值
scope.launch {
    manager.statusFlow.collect { status ->
        when (status.stage) {
            UpdateStage.AVAILABLE -> showUpdateDialog(status.release)
            UpdateStage.DOWNLOADING -> updateProgress(status.progressPercent)
            UpdateStage.FAILED -> showToast(status.error)
            else -> Unit
        }
    }
}

// 事件流：手动检查结果（与 manualResult 同源），新订阅者重放最近 1 条
scope.launch {
    manager.events.collect { event ->
        if (event is HotUpdateEvent.ManualResult) showToast(event.message)
    }
}
```

宿主经 `HotUpdate.create(..., scope = 自己的作用域)` 传入作用域可统一生命周期。

## 服务端接口（配套 Node SDK 见 `../hotupdate-node/`）

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/app/bootstrap` | 更新 + 补丁 + 远程配置一次取回（公开，令牌可选） |
| GET | `/api/v1/app/apk/:versionCode` | 下载整包 |
| GET | `/api/v1/app/patch/:targetVersionCode/:patchVersion` | 下载补丁 |
| POST | `/api/v1/app/admin/*` | 发布登记 / 放量 / 补丁 / 远程配置（管理员会话） |

## 不可破坏的约定

1. **包名 `com.taotao.music.update` / `com.taotao.music.hotfix` 不能改**：
   插桩白名单（build-logic）、R8 keep（consumer-rules.pro）与补丁方法键（「类名#方法名#签名」）
   都以这两个包前缀绑定，改名即全体系失配。
2. **模块不依赖宿主任何类型**：令牌、设备号、远程配置与划掉记录全部经 `UpdateHost` 注入，
   依赖方向永远 app -> 模块。
3. **补丁与宿主版本严格绑定**：`AvailablePatch.targetVersionCode` 必须与本机 versionCode
   相等，补丁方法签名是按那一份代码生成的。
4. **令牌过期也要照发**：检查更新用 `currentToken()`（不续期），服务端对过期令牌降级为
   按设备号分桶，绝不能因 401 断掉更新通道。

## 模块内文件

| 文件 | 职责 |
| --- | --- |
| `update/HotUpdate.kt` | 一站式门面：installPatches + create + 会话 |
| `update/UpdateHost.kt` | 宿主依赖契约 + 远程配置契约 |
| `update/UpdateManager.kt` | 检查/下载/安装编排，Compose 状态暴露 |
| `update/AppUpdateApi.kt` | `/app/bootstrap` 客户端（HttpURLConnection） |
| `update/UpdateDownloader.kt` | 分片下载 + sha256 校验 + 断点续传 |
| `update/UpdateInstaller.kt` | FileProvider 唤起系统安装器 |
| `update/UpdateModels.kt` | UpdateRelease / AvailablePatch / UpdateStatus 模型 |
| `hotfix/HotfixLoader.kt` | DexClassLoader 独立加载器 + 自愈回滚 |
| `hotfix/HotfixInstaller.kt` | 补丁下载落盘与安装 |
| `hotfix/HotfixStore.kt` | 补丁版本/尝试计数/备注的本地存储 |
| `hotfix/PatchDispatcher.kt` / `PatchEntry.kt` | 插桩分发的运行时半边 |
| `hotfix/HotfixDiagnostics.kt` | 补丁诊断信息 |
