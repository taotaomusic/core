plugins {
    // 8.13.2：内置 lint 以 Kotlin 2.2.10 构建，能读取 Kotlin 2.2 编译产物的 metadata，
    // 修复 lintVitalAnalyzeRelease 上成批的 "metadata 2.2.0, expected 2.0.0" 噪音。
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    // Kotlin 2.2.0：与 Compose Multiplatform 1.9.x 的要求对齐（1.8 起 web klib 需要 Kotlin 2.1+）。
    kotlin("android") version "2.2.0" apply false
    kotlin("multiplatform") version "2.2.0" apply false
    // 热修复补丁模块是纯 JVM 模块：它只产出一个 dex，不需要资源、清单和签名。
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.compose") version "2.2.0" apply false
    // 1.9.1：修复 wasm 高 DPR（手机屏）下指针/触摸输入完全失效的问题（1.8.0 大修 Web 输入处理）。
    id("org.jetbrains.compose") version "1.9.1" apply false
    // Room 注解处理走 KSP2；版本号格式是 <Kotlin 版本>-<KSP 版本>，必须与上面的 Kotlin 2.2.0 配套。
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
