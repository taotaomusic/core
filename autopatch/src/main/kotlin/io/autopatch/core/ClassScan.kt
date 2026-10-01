package io.autopatch.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.security.MessageDigest

/** 一个方法的扫描结果：键、access、以及一个对「实现是否变化」稳定的指纹。 */
data class MethodInfo(
    val key: String,
    val name: String,
    val descriptor: String,
    val access: Int,
    val instrumentable: Boolean,
    /** 指纹只覆盖方法体语义，忽略行号与栈帧图，改缩进/挪行不算变化。 */
    val fingerprint: String,
)

/** 一个类的扫描结果。字段集合用于「是否新增字段」的闸门判断。 */
data class ScannedClass(
    val internalName: String,
    val isInterface: Boolean,
    val fields: Set<String>, // "name:descriptor"
    val methods: Map<String, MethodInfo>, // key -> info
)

/** 一批类的扫描结果，按内部名索引。 */
data class Scan(val classes: Map<String, ScannedClass>)

/**
 * 扫描一个目录下的所有 `.class`，产出 [Scan]。
 *
 * 典型用法是分别扫 base（线上版本编译产物）和 head 两份 `build/tmp/kotlin-classes/release`，
 * 交给 [io.autopatch.core.diff] 求差集。
 */
object ClassScanner {

    fun scanDir(dir: File): Scan {
        require(dir.isDirectory) { "不是目录：$dir" }
        val classes = LinkedHashMap<String, ScannedClass>()
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
                val scanned = scanBytes(file.readBytes())
                classes[scanned.internalName] = scanned
            }
        return Scan(classes)
    }

    fun scanBytes(bytes: ByteArray): ScannedClass {
        val node = ClassNode()
        // SKIP_DEBUG：丢掉行号与本地变量表，指纹本就不该受它们影响。
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val isInterface = node.access and Opcodes.ACC_INTERFACE != 0
        val fields = node.fields.map { "${it.name}:${it.desc}" }.toSet()
        val methods = LinkedHashMap<String, MethodInfo>()
        for (method in node.methods) {
            val key = MethodKey.of(node.name, method)
            methods[key] = MethodInfo(
                key = key,
                name = method.name,
                descriptor = method.desc,
                access = method.access,
                instrumentable = Instrumentable.isInstrumentable(isInterface, method.access, method.name),
                fingerprint = fingerprintOf(method),
            )
        }
        // ClassNode.name 就是内部名（斜杠形式）。
        return ScannedClass(node.name, isInterface, fields, methods)
    }

    /**
     * 方法体指纹。
     *
     * 只序列化「指令语义」：opcode + 关键操作数（字段/方法引用、常量、类型）。
     * 跳过 [LineNumberNode] 与 [FrameNode] —— 它们随排版和编译器心情变化，
     * 算进去会把「只是挪了几行」误判成「方法改了」，触发无谓的整包判定。
     */
    private fun fingerprintOf(method: MethodNode): String {
        val sb = StringBuilder()
        sb.append(method.access).append('|').append(method.desc).append('\n')
        var insn: AbstractInsnNode? = method.instructions.first
        while (insn != null) {
            when (insn) {
                is LineNumberNode, is FrameNode -> {} // 忽略
                is FieldInsnNode -> sb.append(insn.opcode).append(' ')
                    .append(insn.owner).append('.').append(insn.name).append(':').append(insn.desc)
                is MethodInsnNode -> sb.append(insn.opcode).append(' ')
                    .append(insn.owner).append('.').append(insn.name).append(insn.desc)
                is InvokeDynamicInsnNode -> sb.append("indy ").append(insn.name).append(insn.desc)
                is LdcInsnNode -> sb.append("ldc ").append(insn.cst)
                is TypeInsnNode -> sb.append(insn.opcode).append(' ').append(insn.desc)
                is IntInsnNode -> sb.append(insn.opcode).append(' ').append(insn.operand)
                is IincInsnNode -> sb.append("iinc ").append(insn.`var`).append(' ').append(insn.incr)
                is MultiANewArrayInsnNode -> sb.append("multianew ").append(insn.desc).append(insn.dims)
                else -> if (insn.opcode >= 0) sb.append(insn.opcode)
            }
            if (insn !is LineNumberNode && insn !is FrameNode) sb.append('\n')
            insn = insn.next
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
