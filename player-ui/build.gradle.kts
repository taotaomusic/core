@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.android.build.api.dsl.LibraryExtension

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
            // compose.* 快捷访问器在 CMP 1.12 已整体 @Deprecated("Specify dependency directly")，
            // 按官方 replaceWith 换成显式坐标。版本必须与插件 1.12.1 发布物 ComposeBuildConfig
            // 注入的值一致（composeDependency 是"坐标:版本"字符串拼接，插件不提供 BOM，
            // 无版本写法无法解析）：runtime/foundation = 1.12.1；material3 = 1.9.0
            // （Miuix klib 传递依赖的 material3 1.12.0-alpha03 更高，Gradle 冲突消解仍选它，
            // 解析结果与改前一致）。
            implementation(project(":shared"))
            implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            // icons-extended 上游钉在 1.7.3 不再更新（K1 编译产物），官方建议维持该版本
            // 或迁移 Material Symbols；本项目仍需全套图标，保持 1.7.3。
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            // Miuix 组件库（上游 compose-miuix-ui）。0.9.4 以 Kotlin 2.4.20 + CMP
            // foundation 1.12.0-alpha03 编译，与本项目 Kotlin 2.4.21 + CMP 1.12.1 的
            // 消费环境配对（PR #26 已把工具链抬到该档位：KLIB ABI 2.4 无小版本容忍，
            // wasm 侧倒逼全链升级）。主题桥见 theme/MiuixBridge.kt；组件按页渐进采用。
            // 用 api 而非 implementation：组件库是本共享 UI 层对外契约的一部分，
            // androidApp 等平台层的页面也要直接采用 Miuix 组件（2026-11 起）。
            api("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
            api("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Project.android(configure: Action<BaseExtension>) 访问器已弃用（AGP 建议 com.android.build.api.dsl），
// KMP 的 androidTarget() 在 newDsl=false 过渡模式下也能吃 LibraryExtension 配置块。
configure<LibraryExtension> {
    namespace = "com.taotao.music.playerui"
    compileSdk = 37
}
