plugins {
    // 9.4.1：接 Miuix 组件库的必然要求——Miuix 0.9.x 的 wasm klib 是 Kotlin 2.4 ABI
    // （KLIB 无元数据的小版本容忍），倒逼 Kotlin 2.4 → CMP 1.12 → androidx compose 1.12.1
    // （AAR metadata 硬性要求 compileSdk 37 + AGP 9.1+）→ Gradle 9 的整条链。
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    // Kotlin 2.4.21：Miuix klib 同版本 ABI + CMP 1.12.x 配套。KGP 插件体系暂时保留
    // （gradle.properties 的 android.builtInKotlin=false，AGP 10 前迁移到内置 Kotlin）。
    kotlin("android") version "2.4.21" apply false
    kotlin("multiplatform") version "2.4.21" apply false
    // 热修复补丁模块是纯 JVM 模块：它只产出一个 dex，不需要资源、清单和签名。
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.compose") version "2.4.21" apply false
    // 1.12.1：Kotlin 2.4 官方配对（Android 侧映射 androidx compose 1.12.1 / material3 1.5.0-alpha22）。
    id("org.jetbrains.compose") version "1.12.1" apply false
    // Room 注解处理走 KSP2。KSP 自 2.3.0 起与 Kotlin 版本解耦、独立发版（插件不再钉
    // KGP 依赖），2.3.12 可直接配 Kotlin 2.3/2.4，不再需要 <Kotlin 版本>-<KSP 版本> 拼接。
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
