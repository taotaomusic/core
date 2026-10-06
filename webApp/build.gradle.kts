@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "taotao-share-player.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
        }
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(project(":player-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}

// wasm-opt（binaryen）用 KGP 2.2 的默认方式：按版本从 GitHub Release 下载（CI 为海外 runner，直连稳定）。
// 旧方案「禁下载 + npm 包里的 wasm-opt」依赖的 BinaryenRootExtension 在 Kotlin 2.2 已移除，
// 替代类型 BinaryenEnvSpec 是 @ExperimentalWasmDsl 内部 API、不生成脚本访问器，无法在 .kts 里引用；
// 且本地构建已被禁用，无需再为 Windows 本机保留绕行配置。
