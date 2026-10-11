package io.autopatch.core

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
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

    /**
     * 造一个类：方法体由调用方定制（[clazz] 只会「返回常量」），
     * 覆盖 super 调用、字段访问这类需要真实指令序列的场景。
     * COMPUTE_MAXS 由 ASM 自动算 maxs，body 里不必手写。
     */
    private fun clazzWithBodies(
        internalName: String,
        superName: String = "java/lang/Object",
        methods: List<Triple<String, String, (MethodVisitor) -> Unit>>, // name, desc, body
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null)
        for ((name, desc, body) in methods) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null)
            mv.visitCode()
            body(mv)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

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

    @Test
    fun `super 调用的方法变化被判定为不可移植`() {
        // Child#foo 调 super.bar()：移植后 Refl 会从 receiver 具体类沿继承链找最派生实现，
        // 而最派生实现正是补丁方法自己，虚分发回去就无限递归 —— 必须整包。
        fun superBar(mv: MethodVisitor) {
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/app/data/Base", "bar", "()I", false)
        }
        val base = scanOf(
            clazzWithBodies(
                "com/app/data/Child",
                superName = "com/app/data/Base",
                methods = listOf(
                    Triple("foo", "()I") { mv ->
                        superBar(mv)
                        mv.visitInsn(Opcodes.IRETURN)
                    },
                ),
            ),
        )
        val head = scanOf(
            clazzWithBodies(
                "com/app/data/Child",
                superName = "com/app/data/Base",
                methods = listOf(
                    Triple("foo", "()I") { mv ->
                        superBar(mv)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.IRETURN)
                    },
                ),
            ),
        )

        val verdict = HotfixGate.evaluate(diff(base, head), GateConfig(allowedPackagePrefixes = listOf("com/app/data")))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reasons.any { it.contains("不可移植") && it.contains("递归") })
    }

    @Test
    fun `类名含美元符的类不可插桩`() {
        // Kotlin 合成类（WhenMappings / lambda / 协程状态机）在插桩工厂是类级排除的，
        // 闸门必须同口径拒绝，否则补丁会发给一个没有分发器字段的目标类。
        val base = scanOf(clazz("com/app/data/Foo\$WhenMappings", methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 1))))
        val head = scanOf(clazz("com/app/data/Foo\$WhenMappings", methods = listOf(Triple("bar", Opcodes.ACC_PUBLIC, 2))))
        val verdict = HotfixGate.evaluate(diff(base, head), GateConfig(allowedPackagePrefixes = listOf("com/app/data")))
        assertTrue(verdict is Verdict.Rejected)
        assertTrue((verdict as Verdict.Rejected).reasons.any { it.contains("不可插桩") })
    }

    @Test
    fun `本类私有调用的方法变化仍可热修`() {
        // INVOKESPECIAL owner == 本类是同类私有调用，不是 super 调用，不得误伤。
        val base = scanOf(
            clazzWithBodies(
                "com/app/data/Foo",
                methods = listOf(
                    Triple("bar", "()I") { mv ->
                        mv.visitVarInsn(Opcodes.ALOAD, 0)
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/app/data/Foo", "secret", "()I", false)
                        mv.visitInsn(Opcodes.IRETURN)
                    },
                ),
            ),
        )
        val head = scanOf(
            clazzWithBodies(
                "com/app/data/Foo",
                methods = listOf(
                    Triple("bar", "()I") { mv ->
                        mv.visitVarInsn(Opcodes.ALOAD, 0)
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/app/data/Foo", "secret", "()I", false)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.IRETURN)
                    },
                ),
            ),
        )

        val verdict = HotfixGate.evaluate(diff(base, head), GateConfig(allowedPackagePrefixes = listOf("com/app/data")))
        assertTrue(verdict is Verdict.Eligible)
        assertEquals(listOf("com/app/data/Foo#bar()I"), (verdict as Verdict.Eligible).patchableKeys)
    }

    @Test
    fun `宿主静态字段读取不触发拒绝`() {
        // GETSTATIC 宿主字段由发射层改写成反射支持，闸门不能拒绝（与 emit 层的契约）。
        val base = scanOf(
            clazzWithBodies(
                "com/taotao/music/data/Foo",
                methods = listOf(
                    Triple("bar", "()I") { mv ->
                        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/taotao/music/data/Config", "flag", "I")
                        mv.visitInsn(Opcodes.IRETURN)
                    },
                ),
            ),
        )
        val head = scanOf(
            clazzWithBodies(
                "com/taotao/music/data/Foo",
                methods = listOf(
                    Triple("bar", "()I") { mv ->
                        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/taotao/music/data/Config", "flag", "I")
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.IRETURN)
                    },
                ),
            ),
        )

        val verdict =
            HotfixGate.evaluate(diff(base, head), GateConfig(allowedPackagePrefixes = listOf("com/taotao/music")))
        assertTrue(verdict is Verdict.Eligible)
        assertEquals(listOf("com/taotao/music/data/Foo#bar()I"), (verdict as Verdict.Eligible).patchableKeys)
    }
}
