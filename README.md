# 桃桃音乐

桃桃音乐是一个使用 Kotlin Multiplatform 构建的音乐播放器项目，当前先实现 Android 版本，后续复用 `shared` 模块扩展 iOS。

## 当前功能

- 推荐首页、分类标签和每日推荐卡片
- 最近播放列表
- 歌曲选择、播放/暂停状态和迷你播放器
- 可复用的专辑封面、歌曲列表项和播放器组件
- Release 版本使用本地发布签名，关闭代码压缩与混淆

## 模块说明

- `shared`：跨平台歌曲模型和共享业务逻辑。
- `androidApp`：Android 入口、Compose UI、主题和平台资源。
- `androidApp/src/main/java/com/taotao/music/ui`：页面和可复用 UI 组件。

## 打开与运行

使用 Android Studio 打开 `F:/music`，等待 Gradle 同步后运行 `androidApp`。

也可以在项目根目录执行：

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:assembleRelease
```

生产 APK 输出路径：

`androidApp/build/outputs/apk/release/androidApp-release.apk`

完整开发约定请阅读 [AGENTS.md](AGENTS.md)。

## 版本号

版本号保存在 `version.properties`。执行 `assembleDebug` 或 `assembleRelease` 成功后，Gradle 会自动调用 `tools/Update-Version.ps1`，递增 `versionCode` 和补丁版本号。也可以手动执行：

```powershell
.\tools\Update-Version.ps1
```
