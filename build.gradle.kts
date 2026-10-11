plugins {
    // 8.13.2：8.x 系列最新稳定（9.x 仍在 alpha）。内置 lint 以 Kotlin 2.2.10 构建，
    // 能读取 Kotlin ≤2.3 的产物 metadata；Kotlin 升 2.4 后依赖库 metadata 若超此上限，
    // 需观察 lintVitalAnalyzeRelease 是否复发告警，AGP 9.0 stable 落地后再整体上移。
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    // Kotlin 2.4.21：Miuix 组件库（top.yukonga.miuix.kmp）以 Kotlin 2.4 编译发布，
    // Kotlin 元数据只向前兼容一个小版本，消费方必须 ≥2.3，这里直接对齐 2.4。
    kotlin("android") version "2.4.21" apply false
    kotlin("multiplatform") version "2.4.21" apply false
    // 热修复补丁模块是纯 JVM 模块：它只产出一个 dex，不需要资源、清单和签名。
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    // 1.12.1：与 Kotlin 2.4 配套的 CMP 稳定版（Android 侧映射 androidx compose 1.12.1、
    // material3 1.5.0-alpha22，故各模块 compileSdk 同步抬到 36）。
    id("org.jetbrains.compose") version "1.12.1" apply false
    // Room 注解处理走 KSP2。KSP 自 2.3.0 起与 Kotlin 版本解耦、独立发版（插件不再钉
    // KGP 依赖），2.3.12 可直接配 Kotlin 2.4，不再需要 <Kotlin 版本>-<KSP 版本> 拼接。
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
