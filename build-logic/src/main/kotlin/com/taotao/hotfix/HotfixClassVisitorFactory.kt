package com.taotao.hotfix

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

interface HotfixParameters : InstrumentationParameters {
    @get:Input
    val includedPackages: ListProperty<String>

    @get:Input
    val enabled: Property<Boolean>
}

/**
 * 决定哪些类要插桩。
 *
 * 排除规则比"包前缀"更细。[isInstrumentable] 里那几条不是"没必要"而是"不能插"，
 * 插了会直接崩或者永远不生效，理由逐条写在旁边。
 */
abstract class HotfixClassVisitorFactory : AsmClassVisitorFactory<HotfixParameters> {

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        HotfixClassVisitor(nextClassVisitor, classContext.currentClassData.className)

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (!parameters.get().enabled.get()) return false
        val internalName = classData.className.replace('.', '/')

        // 热修复自身的运行时不能插桩：加载补丁的代码若被补丁改坏，就没法自愈了。
        if (internalName.startsWith("com/taotao/music/hotfix/")) return false
        // Application 在补丁挂上之前就已经被 ART 加载完，插了也不会生效。
        if (internalName == "com/taotao/music/TaotaoApplication") return false

        if (parameters.get().includedPackages.get().none { internalName.startsWith(it) }) return false

        // Kotlin 生成的合成类：lambda、`WhenMappings`、`$DefaultImpls` 等。
        // 名字随任何一次编辑漂移，插桩后补丁里也对不上，是纯粹的负担。
        if (internalName.contains('$')) return false
        return true
    }
}
