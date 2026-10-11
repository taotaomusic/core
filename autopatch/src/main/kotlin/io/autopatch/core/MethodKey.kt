package io.autopatch.core

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodNode

/**
 * 方法键：热修全链路的对齐命门。
 *
 * 这里算出的键必须与插桩器 `HotfixClassVisitor.visitMethod` 里
 * `"$owner#$name$descriptor"` **逐字节一致** —— 运行时 `PatchDispatcher.isSupport(key)`
 * 拿的就是插桩时写进字节码的那个字符串。差一个字符补丁就永远不会被命中。
 *
 * 形如：`com/taotao/music/data/im/WukongImClient#mergeSyncedConversations(Lorg/json/JSONArray;)I`
 * - owner 是内部名（斜杠形式），不是点分形式；
 * - name 与 descriptor 之间没有分隔符，descriptor 直接拼在方法名后。
 */
object MethodKey {

    /** owner 用内部名（斜杠形式）。传点分形式会算错键。 */
    fun of(ownerInternalName: String, method: MethodNode): String =
        "$ownerInternalName#${method.name}${method.desc}"

    fun of(ownerInternalName: String, name: String, descriptor: String): String =
        "$ownerInternalName#$name$descriptor"
}

/**
 * 可插桩判定：复刻插桩器的**两层**规则。
 *
 * - 类级：`HotfixClassVisitorFactory.isInstrumentable` 按类内部名含 `$` 整类跳过
 *   （Kotlin 合成类：lambda、`WhenMappings`、协程状态机等），这里补齐同一条；
 * - 方法级：`HotfixClassVisitor.shouldInstrument` 的接口/抽象/native/构造器/合成规则。
 *
 * 只有插过桩的方法才可能被补丁接管；生成器判定「能否热修」的第一道闸门就是它。
 * 任何一条规则和插桩器不一致，都会让「生成器认为能修、运行时却没有插桩点」这种
 * 最难排查的错位发生（类级漏判的典型后果：状态机类被闸门放行，而目标类根本没有
 * 分发器字段，补丁整包加载失败）。改这里时必须同步核对插桩器的两层。
 */
object Instrumentable {

    /**
     * @param classInternalName 所在类的内部名（斜杠形式），用于类级排除
     * @param isInterface 声明该方法的类是否是接口
     * @param access 方法的 access flags
     * @param name 方法名
     */
    fun isInstrumentable(classInternalName: String, isInterface: Boolean, access: Int, name: String): Boolean {
        // 类内部名含 `$` 的是 Kotlin 生成的合成类（lambda、WhenMappings、协程状态机等），
        // 插桩工厂在类级就把它们排除了；这里必须同样排除，否则闸门会给「运行时没有
        // 分发器字段」的类生成补丁。方法名自身的 `$` 判定在下面方法级规则里另有一条。
        if (classInternalName.contains('$')) return false
        // 接口 / 抽象 / native 没有方法体，无处插桩。
        if (isInterface) return false
        if (access and Opcodes.ACC_ABSTRACT != 0) return false
        if (access and Opcodes.ACC_NATIVE != 0) return false
        // 构造器：提前 return 会跳过 super()，字节码校验不过。
        if (name == "<init>") return false
        // 静态初始化块跑在类加载期，那时补丁还没挂上。
        if (name == "<clinit>") return false
        // 合成方法（桥接、访问器、lambda 实现）名字随编辑漂移，插了对不上。
        if (access and Opcodes.ACC_SYNTHETIC != 0) return false
        if (name.contains('$')) return false
        return true
    }
}
