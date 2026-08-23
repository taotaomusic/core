pluginManagement {
    // 插桩插件放在 build-logic 里，用 includeBuild 引进来 —— 不发布到仓库也能被 :androidApp 应用。
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "TaotaoMusic"
include(":shared", ":androidApp")
