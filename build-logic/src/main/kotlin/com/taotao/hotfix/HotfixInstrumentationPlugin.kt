package com.taotao.hotfix

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * 热修复插桩插件。
 *
 * 用 AGP 8 的 Instrumentation API（`AsmClassVisitorFactory`），**不是**已被移除的
 * Transform API —— Robust 与 Tinker 的 Gradle 插件都还停留在 Transform 上，
 * 那正是它们没法直接用在这个项目（AGP 8.7）的根本原因。
 *
 * 只对**业务逻辑包**插桩，界面层刻意不碰：Compose 的可组合函数依赖
 * `startRestartGroup` / `endRestartGroup` 严格配对，在函数开头插一条提前 return
 * 会破坏组结构。所以界面 bug 仍然只能发整包，逻辑 bug 才能热修 ——
 * 这也正好是"小逻辑热更新、大版本发包"的边界。
 */
class HotfixInstrumentationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("hotfix", HotfixExtension::class.java).apply {
            includedPackages.convention(
                listOf(
                    "com/taotao/music/data/",
                    "com/taotao/music/player/",
                    "com/taotao/music/update/",
                ),
            )
            enabled.convention(true)
        }

        val components = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: error("com.taotao.hotfix 必须应用在 Android 模块上")

        components.onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                HotfixClassVisitorFactory::class.java,
                // 只改本模块的类。用 ALL 会把 Compose runtime、media3 一起插一遍，
                // 既无意义又会显著拖慢构建。
                InstrumentationScope.PROJECT,
            ) { params ->
                params.includedPackages.set(extension.includedPackages)
                params.enabled.set(extension.enabled)
            }
            // 插桩加了分支，栈帧必须重算，否则 dex 阶段报 VerifyError。
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
            )
        }
    }
}
