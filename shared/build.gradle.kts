@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()
    // Windows 桌面端复用纯 Kotlin 的歌曲模型、音质枚举和歌词解析器。
    jvm("desktop")
    // Web 分享页只复用纯 Kotlin 模型，不在共享层引入浏览器 API。
    wasmJs { browser() }
    jvmToolchain(21)
    sourceSets {
        // 共享业务规则（如歌词解析）在 commonTest 里覆盖，需要 kotlin-test 断言库。
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android { namespace = "com.taotao.music.shared"; compileSdk = 35 }
