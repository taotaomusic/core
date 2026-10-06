@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec

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
            // 与 Kotlin 2.0.21 内置版本保持一致，避免生产构建依赖 GitHub Release 直连。
            implementation(npm("binaryen", "118.0.0"))
        }
    }
}

// Kotlin 2.2 起 BinaryenRootExtension 改名为 BinaryenEnvSpec，属性迁移到 Provider API。
// download = false：不走 GitHub Release 直连下载，改用 npm 包里的 wasm-opt 二进制。
rootProject.extensions.configure<BinaryenEnvSpec> {
    download.set(false)
    command.set(
        rootProject.layout.projectDirectory
            .file(
                if (System.getProperty("os.name").startsWith("Windows")) {
                    "build/js/node_modules/.bin/wasm-opt.cmd"
                } else {
                    "build/js/node_modules/.bin/wasm-opt"
                },
            )
            .asFile
            .absolutePath
    )
}
