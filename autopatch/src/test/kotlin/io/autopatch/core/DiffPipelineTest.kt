package io.autopatch.core

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用 ASM 直接合成两版类字节码来驱动 扫描→diff→闸门 全链路，
 * 不依赖真实编译器，保证测试快且可控。
 */
class DiffPipelineTest {

    /** 造一个类：internalName，可带若干「返回常量 int」的方法和字段。 */
    private fun clazz(
        internalName: String,
        isInterface: Boolean = false,
        methods: List<Triple<String, Int, Int>> = emptyList(), // name, access, 返回的常量
        fields: List<Pair<String, String>> = emptyList(),      // name, desc
    ): ByteArray {
        val cw = ClassWriter(0)
        val access = Opcodes.ACC_PUBLIC or if (isInterface) Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT else 0
        cw.visit(Opcodes.V21, access, internalName, null, "java/lang/Object", null)
        for ((name, desc) in fields) {
            cw.visitField(Opcodes.ACC_PRIVATE, name, desc, null, null).visitEnd()
        }
        for ((name, mAccess, constant) in methods) {
            val mv = cw.visitMethod(mAccess, name, "()I", null, null)
            mv.visitCode()
            mv.visitLdcInsn(constant)
            mv.visitInsn(Opcodes.IRETURN)
            mv.visitMaxs(1, 1)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun scanOf(vararg classes: ByteArray): Scan =
        Scan(classes.associate { val s = ClassScanner.scanBytes(it); s.internalName to s })

    @Test
    fun `方法体变化被识别为可热修`() {
        val base = scanOf(clazz("com/app/data/Foo", methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 1))))
        val head = scanOf(clazz("com/app/data/Foo", methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 2))))

        val d = diff(base, head)
        assertEquals(1, d.changedMethods.size)
        assertEquals("com/app/data/Foo#bar()I", d.changedMethods.single().key)

        val verdict = HotfixGate.evaluate(d, GateConfig(allowedPackagePrefixes = listOf("com/app/data")))
        assertTrue(verdict is Verdict.Eligible)
        assertEquals(listOf("com/app/data/Foo#bar()I"), (verdict as Verdict.Eligible).patchableKeys)
    }

    @Test
    fun `只挪行不改语义不算变化`() {
        val bytes = clazz("com/app/data/Foo", methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 1)))
        val d = diff(scanOf(bytes), scanOf(bytes))
        assertTrue(d.methodChanges.isEmpty())
    }

    @Test
    fun `新增字段被判定为必须整包`() {
        val base = scanOf(clazz("com/app/data/Foo", methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 1))))
        val head = scanOf(
            clazz(
                "com/app/data/Foo",
                methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 2)),
                fields = listOf("cache" to "Ljava/lang/String;"),
            ),
        )
        val verdict = HotfixGate.evaluate(diff(base, head))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reasons.any { it.contains("新增了字段") })
    }

    @Test
    fun `包范围外的改动被拒绝`() {
        val base = scanOf(clazz("com/app/ui/Screen", methods = listOf(Triple("render", Opcodes.ACC_PUBLIC, 1))))
        val head = scanOf(clazz("com/app/ui/Screen", methods = listOf(Triple("render", Opcodes.ACC_PUBLIC, 2))))
        val verdict = HotfixGate.evaluate(diff(base, head), GateConfig(allowedPackagePrefixes = listOf("com/app/data")))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reasons.any { it.contains("包范围") })
    }

    @Test
    fun `构造器变化不可接管`() {
        val base = scanOf(clazz("com/app/data/Foo", methods = listOf(Triple("<init>", Opcodes.ACC_PUBLIC, 1))))
        val head = scanOf(clazz("com/app/data/Foo", methods = listOf(Triple("<init>", Opcodes.ACC_PUBLIC, 2))))
        val verdict = HotfixGate.evaluate(diff(base, head))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reasons.any { it.contains("不可插桩") })
    }
}
