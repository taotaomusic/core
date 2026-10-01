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
 * 端到端走 CLI：从磁盘上的两份 class 目录，跑 扫描→diff→闸门→生成，
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

    private fun writeClass(dir: File, internalName: String, bytes: ByteArray) {
        val f = File(dir, "$internalName.class")
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
    }

    @Test
    fun `从磁盘两份产物生成补丁并跑通`() {
        val root = createTempDirectory("autopatch-it").toFile()
        val baseDir = File(root, "base").apply { mkdirs() }
        val headDir = File(root, "head").apply { mkdirs() }
        val outDir = File(root, "out")

        writeClass(baseDir, "io/autopatch/testhost/Calc", calcWithStubbedAdd())
        writeClass(headDir, "io/autopatch/testhost/Calc", calcBytes())

        val result = AutopatchCli.run(
            CliRequest(
                baseDir = baseDir,
                headDir = headDir,
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
            CliRequest(baseDir, headDir, File(root, "out"), GateConfig(), EmitConfig()),
        )
        assertTrue(result is CliResult.Rejected)
    }
}
