package io.autopatch.core

/**
 * 热修资格配置。
 *
 * [allowedPackagePrefixes] 是宿主专属的白名单（斜杠内部名前缀，例如
 * `com/taotao/music/data`）。留空表示不按包限制 —— 让工具对开源使用方保持通用，
 * 具体范围由接入方传入。
 */
data class GateConfig(
    val allowedPackagePrefixes: List<String> = emptyList(),
)

sealed interface Verdict {
    /**
     * 可热修。
     * @param patchableKeys 需要生成补丁实现的方法键（= 变化且合规的方法）
     * @param bundledNewClasses 会随补丁 DEX 一起打包的新类（自包含，宿主没有）
     * @param warnings 不阻断但必须人工确认的点（如 inline 函数无法自动识别）
     */
    data class Eligible(
        val patchableKeys: List<String>,
        val bundledNewClasses: Set<String>,
        val warnings: List<String>,
    ) : Verdict

    /** 越过热修边界，必须发整包。[reasons] 逐条说明为什么。 */
    data class Rejected(val reasons: List<String>) : Verdict
}

/**
 * 资格闸门：把 [DiffResult] 判成「能热修」或「必须整包」。
 *
 * 这是「① 自动识别能不能热修」。规则刻意保守 —— 热修是应急动作，宁可误判成整包，
 * 也不能生成一个装上去行为诡异的补丁。
 */
object HotfixGate {

    fun evaluate(diff: DiffResult, config: GateConfig = GateConfig()): Verdict {
        val reasons = ArrayList<String>()

        // 1) 增删字段：已安装的类无法增删字段。
        for (fc in diff.fieldChanges) {
            if (fc.added.isNotEmpty()) {
                reasons += "类 ${fc.ownerInternalName} 新增了字段 ${fc.added}，热修无法给已安装的类加字段"
            }
            if (fc.removed.isNotEmpty()) {
                reasons += "类 ${fc.ownerInternalName} 删除了字段 ${fc.removed}，删字段会破坏已安装类的布局"
            }
        }

        // 2) 在已存在的类上增删方法：改不动已安装 DEX 的方法表。
        for (m in diff.addedMethods) {
            reasons += "类 ${m.ownerInternalName} 新增了方法 ${m.name}${m.descriptor}，无法注入到已安装的类"
        }
        for (m in diff.removedMethods) {
            reasons += "类 ${m.ownerInternalName} 删除了方法 ${m.name}${m.descriptor}，删方法需整包"
        }

        // 3) 删整类：字段可能仍引用它，保守拒绝。
        if (diff.removedClasses.isNotEmpty()) {
            reasons += "删除了类 ${diff.removedClasses}，需整包"
        }

        // 4) 变化的方法必须都可插桩、且落在允许的包里。
        val patchable = ArrayList<String>()
        for (m in diff.changedMethods) {
            if (!m.instrumentable) {
                reasons += "方法 ${m.key} 不可插桩（构造器/合成/接口/抽象/native），无插桩点可接管"
                continue
            }
            if (config.allowedPackagePrefixes.isNotEmpty() &&
                config.allowedPackagePrefixes.none { m.ownerInternalName.startsWith(it) }
            ) {
                reasons += "方法 ${m.key} 不在允许热修的包范围内（如触碰了 ui 层）"
                continue
            }
            patchable += m.key
        }

        if (reasons.isNotEmpty()) return Verdict.Rejected(reasons)

        if (patchable.isEmpty()) {
            return Verdict.Rejected(listOf("base 与 head 之间没有可热修的方法变化（可能只改了资源/UI/注释）"))
        }

        val warnings = ArrayList<String>()
        // 已知空缺：inline 函数无法从被调方字节码识别。调用点已把函数体内联进去，
        // 补被调方对已内联的调用点无效。识别它需要解析 Kotlin @Metadata，尚未实现。
        warnings += "尚未识别 Kotlin inline 函数：请人工确认改动的方法都不是 inline（inline 改动必须整包）"

        return Verdict.Eligible(patchable, diff.addedClasses, warnings)
    }
}
