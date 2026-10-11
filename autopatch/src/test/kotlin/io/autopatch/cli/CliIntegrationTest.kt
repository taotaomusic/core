package io.autopatch.cli

import io.autopatch.core.GateConfig
import io.autopatch.emit.EmitConfig
import io.autopatch.testhost.Calc
import io.autopatch.testhost.PatchEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端走 CLI：从磁盘上的多份 class 目录，跑 扫描→diff→闸门→生成，
 * 再加载产物 PatchEntryImpl 调用 dispatch 验证行为。
 */
class CliIntegrationTest {

    private fun calcBytes(): ByteArray =
        Calc::class.java.getResourceAsStream("/io/autopatch/testhost/Calc.class")!!.readBytes()

    /** 把 Calc 的 add 方法体换成「返回常量 0」，造出一个只有 add 不同的 base 版本。 */
    private fun calcWithStubbedAdd(): ByteArray {
        val node = ClassNode()
        ClassReader(calcBytes()).accept(node, 0)
        val add = node.methods.single { it.name == "add" && it.desc == "(I)I" }
        add.instructions = InsnList().apply {
            add(InsnNode(Opcodes.ICONST_0))
            add(InsnNode(Opcodes.IRETURN))
        }
        add.tryCatchBlocks?.clear()
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        node.accept(cw)
        return cw.toByteArray()
    }

    /** 合成一个只含「返回常量 int」方法的类，模拟 shared 这类独立模块的编译产物。 */
    private fun constClass(internalName: String, methodName: String, value: Int): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null)
        mv.visitCode()
        mv.visitLdcInsn(value)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeClass(dir: File, internalName: String, bytes: ByteArray) {
        val f = File(dir, "$internalName.class")
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }

    @Test
    fun `从磁盘多份产物生成补丁并跑通`() {
        val root = createTempDirectory("autopatch-it").toFile()
        val baseDir = File(root, "base").apply { mkdirs() }
        val headDir = File(root, "head").apply { mkdirs() }
        val outDir = File(root, "out")

        writeClass(baseDir, "io/autopatch/testhost/Calc", calcWithStubbedAdd())
        writeClass(headDir, "io/autopatch/testhost/Calc", calcBytes())

        val result = AutopatchCli.run(
            CliRequest(
                baseDirs = listOf(baseDir),
                headDirs = listOf(headDir),
                outDir = outDir,
                gateConfig = GateConfig(allowedPackagePrefixes = listOf("io/autopatch/testhost")),
                emitConfig = EmitConfig(
                    entryClassInternalName = "io/autopatch/gen/PatchEntryImpl",
                    entryInterface = "io/autopatch/testhost/PatchEntry",
                    dispatcherInterface = "io/autopatch/testhost/PatchDispatcher",
                ),
            ),
        )

        assertTrue(result is CliResult.Generated, "应判定可热修，实际：$result")
        result as CliResult.Generated
        assertEquals(listOf("io/autopatch/testhost/Calc#add(I)I"), result.patchableKeys)
        assertTrue(result.writtenFiles.any { it.name == "PatchEntryImpl.class" })
        assertTrue(result.writtenFiles.any { it.name == "Refl.class" })

        // 加载产物并调用：head 的 add 是 base+x+1，Calc(10).add(5) → 16。
        val entryBytes = File(outDir, "io/autopatch/gen/PatchEntryImpl.class").readBytes()
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(n: String, b: ByteArray): Class<*> = defineClass(n, b, 0, b.size)
        }
        val entry = loader.define("io.autopatch.gen.PatchEntryImpl", entryBytes)
            .getDeclaredConstructor().newInstance() as PatchEntry
        val value = entry.dispatcher().dispatch("io/autopatch/testhost/Calc#add(I)I", Calc(10), arrayOf<Any?>(5))
        assertEquals(16, value)
    }

    @Test
    fun `无变化时判定必须整包`() {
        val root = createTempDirectory("autopatch-it2").toFile()
        val baseDir = File(root, "base").apply { mkdirs() }
        val headDir = File(root, "head").apply { mkdirs() }

        writeClass(baseDir, "io/autopatch/testhost/Calc", calcBytes())
        writeClass(headDir, "io/autopatch/testhost/Calc", calcBytes())

        val result = AutopatchCli.run(
            CliRequest(listOf(baseDir), listOf(headDir), File(root, "out"), GateConfig(), EmitConfig()),
        )
        assertTrue(result is CliResult.Rejected)
    }

    @Test
    fun `第二个目录里的类变化被拒绝且原因含包范围`() {
        // shared 这类独立模块的类必须随第二份目录进 diff：它改了方法却不在插桩范围
        // （allowedPackagePrefixes）内时，闸门要按「包范围」整包拒绝 —— 否则半新半旧
        // 的补丁会静默生成，运行时经父加载器拿到的还是宿主里的旧 shared 类。
        val root = createTempDirectory("autopatch-it3").toFile()
        val baseApp = File(root, "base-app").apply { mkdirs() }
        val baseShared = File(root, "base-shared").apply { mkdirs() }
        val headApp = File(root, "head-app").apply { mkdirs() }
        val headShared = File(root, "head-shared").apply { mkdirs() }

        writeClass(baseApp, "io/autopatch/testhost/Calc", calcBytes())
        writeClass(headApp, "io/autopatch/testhost/Calc", calcBytes())
        writeClass(baseShared, "io/autopatch/shared/Repo", constClass("io/autopatch/shared/Repo", "load", 1))
        writeClass(headShared, "io/autopatch/shared/Repo", constClass("io/autopatch/shared/Repo", "load", 2))

        val result = AutopatchCli.run(
            CliRequest(
                baseDirs = listOf(baseApp, baseShared),
                headDirs = listOf(headApp, headShared),
                outDir = File(root, "out"),
                gateConfig = GateConfig(allowedPackagePrefixes = listOf("io/autopatch/testhost")),
                emitConfig = EmitConfig(
                    entryClassInternalName = "io/autopatch/gen/PatchEntryImpl",
                    entryInterface = "io/autopatch/testhost/PatchEntry",
                    dispatcherInterface = "io/autopatch/testhost/PatchDispatcher",
                ),
            ),
        )

        assertTrue(result is CliResult.Rejected, "包范围外的 shared 改动应判定必须整包，实际：$result")
        assertTrue(
            (result as CliResult.Rejected).reasons.any {
                it.contains("包范围") && it.contains("io/autopatch/shared/Repo")
            },
            "拒绝原因应指明包范围与越界的类，实际：${result.reasons}",
        )
    }
}
