plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    // Kotlin 2.2.0：与 Compose Multiplatform 1.9.x 的要求对齐（1.8 起 web klib 需要 Kotlin 2.1+）。
    kotlin("android") version "2.2.0" apply false
    kotlin("multiplatform") version "2.2.0" apply false
    // 热修复补丁模块是纯 JVM 模块：它只产出一个 dex，不需要资源、清单和签名。
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.compose") version "2.2.0" apply false
    // 1.9.1：修复 wasm 高 DPR（手机屏）下指针/触摸输入完全失效的问题（1.8.0 大修 Web 输入处理）。
    id("org.jetbrains.compose") version "1.9.1" apply false
}
