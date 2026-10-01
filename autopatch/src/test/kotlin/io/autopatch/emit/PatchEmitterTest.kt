package io.autopatch.emit

import io.autopatch.testhost.Calc
import io.autopatch.testhost.PatchEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端：读真实编译产物 → 移植方法体 → 生成补丁类 → 在 JVM 上加载并调用 dispatch。
 * 验证移植后的行为与直接调用宿主方法一致（本轮覆盖 public 成员的直连访问）。
 */
class PatchEmitterTest {

    private val config = EmitConfig(
        entryClassInternalName = "io/autopatch/gen/PatchEntryImpl",
        entryInterface = "io/autopatch/testhost/PatchEntry",
        dispatcherInterface = "io/autopatch/testhost/PatchDispatcher",
    )

    private fun methodNodeOf(owner: Class<*>, name: String): MethodNode {
        val bytes = owner.getResourceAsStream("/${owner.name.replace('.', '/')}.class")!!.readBytes()
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        return node.methods.single { it.name == name }
    }

    private class BytesLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(binaryName: String, bytes: ByteArray): Class<*> =
            defineClass(binaryName, bytes, 0, bytes.size)
    }

    private fun buildEntry(vararg targets: PatchTarget): PatchEntry {
        val bytes = PatchEmitter(config).emit(targets.toList())
        val loader = BytesLoader(javaClass.classLoader)
        val cls = loader.define("io.autopatch.gen.PatchEntryImpl", bytes)
        return cls.getDeclaredConstructor().newInstance() as PatchEntry
    }

    @Test
    fun `实例方法 基本类型 移植后行为一致`() {
        val key = "io/autopatch/testhost/Calc#add(I)I"
        val entry = buildEntry(PatchTarget(key, "io/autopatch/testhost/Calc", methodNodeOf(Calc::class.java, "add")))

        assertEquals(listOf("io.autopatch.testhost.Calc"), entry.targets())
        val dispatcher = entry.dispatcher()
        assertTrue(dispatcher.isSupport(key))
        assertTrue(!dispatcher.isSupport("io/autopatch/testhost/Calc#missing()V"))

        // base=10, x=5 → 10+5+1 = 16
        val result = dispatcher.dispatch(key, Calc(10), arrayOf<Any?>(5))
        assertEquals(16, result)
    }

    @Test
    fun `私有字段与私有方法访问改写成反射后跑通`() {
        val key = "io/autopatch/testhost/Secret#compute(I)I"
        val hostConfig = config.copy(hostPackagePrefixes = listOf("io/autopatch/testhost"))
        val bytes = PatchEmitter(hostConfig).emit(
            listOf(PatchTarget(key, "io/autopatch/testhost/Secret", methodNodeOf(io.autopatch.testhost.Secret::class.java, "compute"))),
        )
        val entry = BytesLoader(javaClass.classLoader)
            .define("io.autopatch.gen.PatchEntryImpl", bytes)
            .getDeclaredConstructor().newInstance() as PatchEntry

        // seed=10, x=4 → salt(4)=12 + 10 = 22。直连访问会 IllegalAccessError，反射改写后应得 22。
        val result = entry.dispatcher().dispatch(key, io.autopatch.testhost.Secret(10), arrayOf<Any?>(4))
        assertEquals(22, result)
    }

    @Test
    fun `静态方法 引用类型 移植后行为一致`() {
        val key = "io/autopatch/testhost/Calc#tag(Ljava/lang/String;)Ljava/lang/String;"
        val entry = buildEntry(PatchTarget(key, "io/autopatch/testhost/Calc", methodNodeOf(Calc::class.java, "tag")))

        val result = entry.dispatcher().dispatch(key, null, arrayOf<Any?>("hi"))
        assertEquals("tag:hi", result)
    }
}
