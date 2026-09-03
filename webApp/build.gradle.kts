@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.targets.js.binaryen.BinaryenRootExtension

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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            // 与 Kotlin 2.0.21 内置版本保持一致，避免生产构建依赖 GitHub Release 直连。
            implementation(npm("binaryen", "118.0.0"))
        }
    }
}

rootProject.extensions.configure<BinaryenRootExtension> {
    download = false
    command = rootProject.layout.projectDirectory
        .file(
            if (System.getProperty("os.name").startsWith("Windows")) {
                "build/js/node_modules/.bin/wasm-opt.cmd"
            } else {
                "build/js/node_modules/.bin/wasm-opt"
            },
        )
        .asFile
        .absolutePath
}
