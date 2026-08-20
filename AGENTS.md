# 桃桃音乐项目开发规范

## 项目结构

桃桃音乐是一个 Kotlin Multiplatform 音乐播放器，目前优先支持 Android。

- `androidApp/`：Android 应用入口、Jetpack Compose 页面、Android 资源和平台能力。
- `shared/`：跨平台共享的数据模型、业务状态和未来的播放领域逻辑，代码放在 `src/commonMain/`。
- 根目录 Gradle 文件：定义 `:androidApp` 和 `:shared` 模块。
- `build/` 目录：构建生成物，只读，不手工修改。
- `server/`：Node.js + TypeScript 无状态音乐接口适配服务；服务源码必须保持可读，不得提交压缩后的源码。
- 外部参考仓库不得放在项目根目录；临时参考代码使用项目外目录，交付前清理无关仓库。
- 新增 Kotlin 代码必须放在已有的 `com.taotao.music` 包层级下。

## 文档语言

项目内置文档、代码注释、模块说明、用户提示和构建说明统一使用简体中文。必要的技术名词可以保留英文原名，并在首次出现时补充中文说明。

## 模块化与组件复用

- Activity 只负责 Android 生命周期和 Compose 入口，不承载页面布局或业务逻辑。
- 页面按职责拆分为独立文件和组件，保持 Composable 小而清晰、状态驱动。
- 通用视觉组件必须抽取并复用，例如歌曲列表项、专辑封面、迷你播放器、按钮和主题。
- 跨平台的数据模型和业务规则放入 `shared`，Android 专属 UI 和平台集成放入 `androidApp`。
- 新功能优先扩展现有组件和状态模型，禁止复制粘贴同类 UI；如果组件需要两个以上页面使用，应抽取为公共组件。
- 组件的参数、回调和状态边界要明确，尽量使用单向数据流，避免在组件内部隐藏全局状态。

## 源码可读性

- 生产源码不得压缩、混淆或以单行形式提交；必须保持正常缩进、换行、命名和必要注释。
- Release 构建当前保持 `isMinifyEnabled = false`，确保交付版本可调试、可追踪。
- 使用 Kotlin 官方风格：四个空格缩进，类型和 Composable 使用 `UpperCamelCase`，函数和属性使用 `lowerCamelCase`。
- 公共组件、共享模型和非直观的状态转换必须添加简体中文说明。
- 修改后使用 Android Studio Kotlin formatter 格式化代码。
- TypeScript 源码也必须保持正常换行、缩进和模块职责边界；压缩仅允许发生在构建产物中。

## 构建与运行

在项目根目录执行：

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:installDebug
.\gradlew.bat :androidApp:assembleRelease
.\gradlew.bat build
```

当前要求使用 JDK 21 和项目自带 Gradle Wrapper。生产 APK 输出在 `androidApp/build/outputs/apk/release/`。

## 测试规范

新增共享逻辑时，在 `shared/src/commonTest/` 添加 `*Test.kt`；Android 单元测试放在 `androidApp/src/test/`，设备测试放在 `androidApp/src/androidTest/`。

可使用以下命令验证：

```powershell
.\gradlew.bat :shared:allTests
.\gradlew.bat :androidApp:testDebugUnitTest
```

## 安全与签名

- 不得提交密码、API Key、机器专属 SDK 路径或其他敏感信息。
- `local.properties` 只保存在本机，用于 SDK 路径和本地发布签名配置，不得提交到版本库。
- 发布签名文件必须妥善备份；签名密码不得写入公共源码、README 或聊天记录以外的仓库文档。
- 发布前检查 `validateSigningRelease`，并使用 `apksigner verify` 验证 APK 签名。

## 提交与交付

提交信息使用简洁的祈使句，可带模块范围，例如 `ui: 增加播放器控制栏`。交付 UI 变更时说明影响模块、验证命令和 APK 输出路径；如果有界面变化，应附模拟器截图或录屏。

## 验收优先级

- 默认以签名 Release APK 生成为主要验收标准，修改完成后优先执行 `.\gradlew.bat :androidApp:assembleRelease`。
- 仅在定位编译问题、快速验证局部改动或 Release 构建受阻时，单独执行 Kotlin 编译测试；单独编译不能替代 APK 交付。
- 交付时必须提供最新 Release APK 的绝对路径、版本号和构建结果。
