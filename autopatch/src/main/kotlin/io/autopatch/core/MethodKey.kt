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
 * 可插桩判定：与 `HotfixClassVisitor.shouldInstrument` 一字不差地复刻。
 *
 * 只有插过桩的方法才可能被补丁接管；生成器判定「能否热修」的第一道闸门就是它。
 * 任何一条规则和插桩器不一致，都会让「生成器认为能修、运行时却没有插桩点」这种
 * 最难排查的错位发生。改这里时必须同步核对插桩器。
 */
object Instrumentable {

    /**
     * @param isInterface 声明该方法的类是否是接口
     * @param access 方法的 access flags
     * @param name 方法名
     */
    fun isInstrumentable(isInterface: Boolean, access: Int, name: String): Boolean {
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
