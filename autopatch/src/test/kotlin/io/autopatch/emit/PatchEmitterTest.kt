package io.autopatch.emit

import io.autopatch.testhost.Calc
import io.autopatch.testhost.PatchEntry
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端：读真实编译产物（或手工构造的方法体）→ 移植方法体 → 生成补丁类 → 在 JVM 上加载并调用 dispatch。
 * 验证移植后的行为与直接调用宿主方法一致，覆盖 public 成员的直连访问与宿主包成员的反射改写。
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

    @Test
    fun `宿主静态字段 读写 改写成反射后跑通`() {
        StaticHost.counter = 7
        val key = "$STATIC_HOST#bump()I"
        val hostConfig = config.copy(hostPackagePrefixes = listOf("io/autopatch/emit"))
        val bytes = PatchEmitter(hostConfig).emit(
            listOf(PatchTarget(key, STATIC_HOST, staticReadWriteBody())),
        )
        val entry = BytesLoader(javaClass.classLoader)
            .define("io.autopatch.gen.PatchEntryImpl", bytes)
            .getDeclaredConstructor().newInstance() as PatchEntry

        // bump 的方法体是 counter = counter + 1 并返回新值：GETSTATIC/PUTSTATIC 都被改写成
        // Refl 反射。应返回 7+1，且宿主类上的静态字段确实被写入。
        val result = entry.dispatcher().dispatch(key, null, arrayOf<Any?>())
        assertEquals(8, result)
        assertEquals(8, StaticHost.counter)
    }

    @Test
    fun `宿主静态引用字段 读写 改写成反射后跑通`() {
        StaticHost.label = "init"
        val key = "$STATIC_HOST#relabel()Ljava/lang/String;"
        val hostConfig = config.copy(hostPackagePrefixes = listOf("io/autopatch/emit"))
        val bytes = PatchEmitter(hostConfig).emit(
            listOf(PatchTarget(key, STATIC_HOST, staticWriteReadBody())),
        )
        val entry = BytesLoader(javaClass.classLoader)
            .define("io.autopatch.gen.PatchEntryImpl", bytes)
            .getDeclaredConstructor().newInstance() as PatchEntry

        // 写引用型静态字段（装箱直通）+ 读回（CHECKCAST 收口），PUTSTATIC 失败会读到旧值。
        val result = entry.dispatcher().dispatch(key, null, arrayOf<Any?>())
        assertEquals("patched", result)
        assertEquals("patched", StaticHost.label)
    }

    /** 手工构造的 head 方法体：counter = counter + 1; return counter;（GETSTATIC 与 PUTSTATIC 同段直线代码）。 */
    private fun staticReadWriteBody(): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "bump", "()I", null, null).apply {
            visitFieldInsn(Opcodes.GETSTATIC, STATIC_HOST, "counter", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitInsn(Opcodes.DUP)
            visitFieldInsn(Opcodes.PUTSTATIC, STATIC_HOST, "counter", "I")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(2, 0)
        }

    /** 手工构造的 head 方法体：label = "patched"; return label;（引用类型的 PUTSTATIC + GETSTATIC）。 */
    private fun staticWriteReadBody(): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "relabel", "()Ljava/lang/String;", null, null).apply {
            visitLdcInsn("patched")
            visitFieldInsn(Opcodes.PUTSTATIC, STATIC_HOST, "label", "Ljava/lang/String;")
            visitFieldInsn(Opcodes.GETSTATIC, STATIC_HOST, "label", "Ljava/lang/String;")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
        }
}

/** 静态字段读写用例的宿主类内部名。 */
private const val STATIC_HOST = "io/autopatch/emit/StaticHost"

/**
 * 测试宿主：补丁方法体读写的静态字段都挂在它上面。
 *
 * companion 里标 @JvmField 的属性才会编译成本类上的 public static 字段（不标注则字段落在
 * Companion 实例上，GETSTATIC/PUTSTATIC 就没有可指向的宿主静态字段）。
 */
class StaticHost {
    companion object {
        @JvmField var counter: Int = 7

        @JvmField var label: String = "init"
    }
}
