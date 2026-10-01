package io.autopatch.emit

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode

/**
 * 补丁入口类与运行时接口的名字。默认对齐桃桃音乐宿主；开源使用方可替换。
 */
data class EmitConfig(
    val entryClassInternalName: String = "com/taotao/music/hotfix/generated/PatchEntryImpl",
    val entryInterface: String = "com/taotao/music/hotfix/PatchEntry",
    val dispatcherInterface: String = "com/taotao/music/hotfix/PatchDispatcher",
    /**
     * 宿主包前缀（斜杠内部名）。方法体里对这些包下类型的成员访问会被改写成反射，
     * 绕开补丁类无法直连宿主私有成员的限制。默认对齐桃桃音乐。
     */
    val hostPackagePrefixes: List<String> = listOf("com/taotao/music"),
)

/** 要生成补丁的一个方法：键 + 宿主类内部名 + head 侧带指令的方法体。 */
data class PatchTarget(
    val methodKey: String,
    val ownerInternalName: String,
    val source: MethodNode,
)

/**
 * 生成补丁入口类的字节码。
 *
 * 产出一个同时实现 `PatchEntry` 与 `PatchDispatcher` 的类（`dispatcher()` 返回 `this`）：
 * - `targets()` 返回涉及的宿主类全名（点分形式，供加载器给静态字段赋值）；
 * - `isSupport(key)` 精确匹配本补丁覆盖的方法键；
 * - `dispatch(key, receiver, args)` 按键路由到 `impl$i` 静态方法（由 [MethodTransplanter] 移植）。
 */
class PatchEmitter(private val config: EmitConfig = EmitConfig()) {

    fun emit(targets: List<PatchTarget>): ByteArray {
        require(targets.isNotEmpty()) { "没有要生成的补丁方法" }

        val cw = LenientClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            config.entryClassInternalName,
            null,
            "java/lang/Object",
            arrayOf(config.entryInterface, config.dispatcherInterface),
        )

        emitDefaultCtor(cw)
        emitTargets(cw, targets.map { it.ownerInternalName }.distinct())
        emitDispatcher(cw)
        emitIsSupport(cw, targets.map { it.methodKey })
        emitDispatch(cw, targets.map { it.methodKey })

        val isHostOwner: (String) -> Boolean = { owner ->
            config.hostPackagePrefixes.any { owner.startsWith(it) }
        }
        targets.forEachIndexed { index, target ->
            MethodTransplanter.transplant(target.source, target.ownerInternalName, "impl\$$index", isHostOwner)
                .accept(cw)
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun emitDefaultCtor(cw: ClassWriter) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** List<String> targets() —— 宿主类全名（点分形式）。 */
    private fun emitTargets(cw: ClassWriter, ownersInternal: List<String>) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "targets", "()Ljava/util/List;", null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
        for (owner in ownersInternal) {
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(owner.replace('/', '.'))
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true)
            mv.visitInsn(Opcodes.POP)
        }
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** PatchDispatcher dispatcher() { return this; } */
    private fun emitDispatcher(cw: ClassWriter) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "dispatcher", "()L${config.dispatcherInterface};", null, null,
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** boolean isSupport(String key) —— 命中任一方法键即 true。 */
    private fun emitIsSupport(cw: ClassWriter, keys: List<String>) {
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "isSupport", "(Ljava/lang/String;)Z", null, null)
        mv.visitCode()
        for (key in keys) {
            val next = Label()
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitLdcInsn(key)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
            mv.visitJumpInsn(Opcodes.IFEQ, next)
            mv.visitInsn(Opcodes.ICONST_1)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitLabel(next)
        }
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /** Object dispatch(String key, Object receiver, Object[] args) —— 按键路由到 impl$i。 */
    private fun emitDispatch(cw: ClassWriter, keys: List<String>) {
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "dispatch",
            "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null,
        )
        mv.visitCode()
        keys.forEachIndexed { index, key ->
            val next = Label()
            mv.visitVarInsn(Opcodes.ALOAD, 1) // methodKey
            mv.visitLdcInsn(key)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
            mv.visitJumpInsn(Opcodes.IFEQ, next)
            mv.visitVarInsn(Opcodes.ALOAD, 2) // receiver
            mv.visitVarInsn(Opcodes.ALOAD, 3) // args
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                config.entryClassInternalName,
                "impl\$$index",
                MethodTransplanter.IMPL_DESCRIPTOR,
                false,
            )
            mv.visitInsn(Opcodes.ARETURN)
            mv.visitLabel(next)
        }
        // 没命中：抛异常，绝不静默返回错值。
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 1)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
            "(Ljava/lang/Object;)Ljava/lang/String;", false,
        )
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
            "(Ljava/lang/String;)V", false,
        )
        mv.visitInsn(Opcodes.ATHROW)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
}
