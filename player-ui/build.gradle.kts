@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.library")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget()
    wasmJs { browser() }
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            // Miuix 组件库（上游 compose-miuix-ui）。0.9.4 以 Kotlin 2.4.20 + CMP
            // foundation 1.12.0-alpha03 编译，与本项目 Kotlin 2.4.21 + CMP 1.12.1 的
            // 消费环境配对（PR #26 已把工具链抬到该档位：KLIB ABI 2.4 无小版本容忍，
            // wasm 侧倒逼全链升级）。主题桥见 theme/MiuixBridge.kt；组件按页渐进采用。
            implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
            implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.taotao.music.playerui"
    compileSdk = 37
}
