// 插桩插件的独立构建。用 includeBuild 引入，不必发布到任何仓库。
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "build-logic"
