@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.android.build.api.dsl.LibraryExtension

plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()
    // Web 分享页只复用纯 Kotlin 模型，不在共享层引入浏览器 API。
    wasmJs { browser() }
    jvmToolchain(21)
    sourceSets {
        // 共享业务规则（如歌词解析）在 commonTest 里覆盖，需要 kotlin-test 断言库。
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// 弃用的 Project.android() 访问器改 LibraryExtension 配置块（理由见 player-ui 同款注释）。
configure<LibraryExtension> { namespace = "com.taotao.music.shared"; compileSdk = 37 }
