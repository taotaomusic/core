pluginManagement {
    // 插桩插件放在 build-logic 里，用 includeBuild 引进来 —— 不发布到仓库也能被 :androidApp 应用。
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    // Kotlin/Wasm 的生产优化任务会按目标平台动态注册 Node/Binaryen 分发仓库。
    // FAIL_ON_PROJECT_REPOS 会在任务图解析阶段直接拒绝这些工具链仓库。
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        // 悟空 IM Android SDK 通过 JitPack 发布。
        maven("https://jitpack.io")
    }
}
rootProject.name = "TaotaoMusic"
include(":shared", ":player-ui", ":androidApp", ":patch", ":desktopApp", ":desktopLauncher", ":desktopUpdater", ":webApp")
