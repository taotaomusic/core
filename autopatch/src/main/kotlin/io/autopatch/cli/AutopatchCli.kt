package io.autopatch.cli

import io.autopatch.core.ClassScanner
import io.autopatch.core.GateConfig
import io.autopatch.core.HotfixGate
import io.autopatch.core.Verdict
import io.autopatch.core.diff
import io.autopatch.emit.EmitConfig
import io.autopatch.emit.PatchEmitter
import io.autopatch.emit.PatchTarget
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.File

/**
 * 一次补丁生成的输入。
 *
 * [baseDirs] / [headDirs] 是若干份编译产物目录（如 `androidApp` 与 `shared` 各自的
 * `build/tmp/kotlin-classes/release`）：base = 线上那个版本，head = 修好之后。
 * 必须把 shared 这类独立模块的目录一起传入 —— 漏掉的话其改动对 diff 不可见，
 * 补丁会放行「方法体半新半旧」的类（补丁类走新实现、经父加载器拿到的同模块
 * 旧类走旧实现），且无任何告警。
 */
data class CliRequest(
    val baseDirs: List<File>,
    val headDirs: List<File>,
    val outDir: File,
    val gateConfig: GateConfig,
    val emitConfig: EmitConfig,
)

sealed interface CliResult {
    /** 判定必须整包，逐条原因。 */
    data class Rejected(val reasons: List<String>) : CliResult

    /** 生成成功。[patchableKeys] 是被写进补丁的方法键；[writtenFiles] 是产物路径。 */
    data class Generated(
        val patchableKeys: List<String>,
        val writtenFiles: List<File>,
        val warnings: List<String>,
    ) : CliResult
}

/**
 * 端到端：扫描 → diff → 闸门 → 生成补丁类 + 打出 Refl 支撑类。
 *
 * 这一层不碰 d8/打包（那步交给宿主的 `:patch:buildPatch`），只产出 `.class`。
 */
object AutopatchCli {

    fun run(request: CliRequest): CliResult {
        val base = ClassScanner.scanDirs(request.baseDirs)
        val head = ClassScanner.scanDirs(request.headDirs)
        val d = diff(base, head)

        val verdict = HotfixGate.evaluate(d, request.gateConfig)
        if (verdict is Verdict.Rejected) return CliResult.Rejected(verdict.reasons)
        verdict as Verdict.Eligible

        // 为每个可热修方法键，从 head 产物里取带指令的 MethodNode。
        val targets = verdict.patchableKeys.map { key -> toPatchTarget(key, request.headDirs) }

        val entryBytes = PatchEmitter(request.emitConfig).emit(targets)

        request.outDir.mkdirs()
        val written = ArrayList<File>()

        val entryPath = File(request.outDir, "${request.emitConfig.entryClassInternalName}.class")
        entryPath.parentFile.mkdirs()
        entryPath.writeBytes(entryBytes)
        written += entryPath

        // Refl 是自包含支撑类，必须随补丁 DEX 一起打包。
        val reflPath = File(request.outDir, "io/autopatch/runtime/Refl.class")
        reflPath.parentFile.mkdirs()
        reflPath.writeBytes(reflClassBytes())
        written += reflPath

        return CliResult.Generated(verdict.patchableKeys, written, verdict.warnings)
    }

    /** 键形如 `owner#name(desc)ret`，据此定位 head 里的类文件与方法。 */
    private fun toPatchTarget(key: String, headDirs: List<File>): PatchTarget {
        val hash = key.indexOf('#')
        require(hash > 0) { "非法方法键：$key" }
        val owner = key.substring(0, hash)
        val methodPart = key.substring(hash + 1)
        val paren = methodPart.indexOf('(')
        require(paren > 0) { "非法方法键（缺描述符）：$key" }
        val name = methodPart.substring(0, paren)
        val desc = methodPart.substring(paren)

        // scanDirs 已保证同一内部名只属于一个目录，命中第一个即唯一。
        val classFile = headDirs.asSequence()
            .map { File(it, "$owner.class") }
            .firstOrNull { it.isFile }
        requireNotNull(classFile) {
            "head 里找不到类文件：$owner.class（查找过的目录：${headDirs.joinToString("、")}）"
        }
        val node = ClassNode()
        ClassReader(classFile.readBytes()).accept(node, 0)
        val method: MethodNode = node.methods.single { it.name == name && it.desc == desc }
        return PatchTarget(key, owner, method)
    }

    private fun reflClassBytes(): ByteArray =
        AutopatchCli::class.java.getResourceAsStream("/io/autopatch/runtime/Refl.class")
            ?.readBytes()
            ?: error("classpath 里找不到 Refl.class —— 生成器构建产物异常")
}
