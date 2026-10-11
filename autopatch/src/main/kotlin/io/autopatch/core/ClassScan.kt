package io.autopatch.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.security.MessageDigest

/**
 * 宿主包前缀（斜杠内部名），与 emit 层 `EmitConfig.hostPackagePrefixes` 的默认值一致。
 * 扫描期只用来判断「引用的 owner 是否落在宿主包里」，不需要做成配置。
 */
private const val HOST_PACKAGE_PREFIX = "com/taotao/music"

/** 一个方法的扫描结果：键、access、以及一个对「实现是否变化」稳定的指纹。 */
data class MethodInfo(
    val key: String,
    val name: String,
    val descriptor: String,
    val access: Int,
    val instrumentable: Boolean,
    /** 指纹只覆盖方法体语义，忽略行号与栈帧图，改缩进/挪行不算变化。 */
    val fingerprint: String,
    /**
     * 方法体里「结构上无法安全移植进补丁类」的事实（人类可读），空列表 = 可移植。
     * 扫描期就收集齐，让闸门据以整包拒绝，而不是等发射阶段改写失败才暴露。
     */
    val portabilityIssues: List<String> = emptyList(),
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
 * 交给 [io.autopatch.core.diff] 求差集；多模块产物用 [scanDirs] 合并后同样走 diff。
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

    /**
     * 扫描多个目录并合并成一个 [Scan]。
     *
     * 多目录用于把 `shared` 这类独立模块的编译产物一起纳入 diff —— 漏掉它们，
     * 其改动会被误判成「没有方法变化」，运行时经父加载器拿到的还是宿主里的旧类。
     * 同一内部名出现在两个目录时直接抛错：那意味着目录重叠或混入了同一模块的
     * 旧产物，静默合并会让 diff 结果取决于目录遍历顺序。
     */
    fun scanDirs(dirs: List<File>): Scan {
        val classes = LinkedHashMap<String, ScannedClass>()
        val ownerOf = HashMap<String, File>()
        for (dir in dirs) {
            for ((internalName, scanned) in scanDir(dir).classes) {
                val firstDir = ownerOf.put(internalName, dir)
                require(firstDir == null) {
                    "内部名 $internalName 同时出现在扫描目录 $firstDir 和 $dir，无法合并成一份扫描结果；" +
                        "请检查目录是否重叠或混入了同一模块的旧产物"
                }
                classes[internalName] = scanned
            }
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
                instrumentable = Instrumentable.isInstrumentable(
                    node.name, isInterface, method.access, method.name,
                ),
                fingerprint = fingerprintOf(method),
                portabilityIssues = portabilityIssuesOf(method, node.name),
            )
        }
        // ClassNode.name 就是内部名（斜杠形式）。
        return ScannedClass(node.name, isInterface, fields, methods)
    }

    /**
     * 方法体指纹。
     *
     * 只序列化「指令语义」：opcode + 关键操作数（字段/方法引用、常量、类型），
     * 以及 indy 的 bootstrap 方法与参数、switch 的全部分支目标 —— 后两者恰是
     * 「只改 lambda 捕获 / 只重排分支」时唯一变化的字节码部位，漏记就会漏检
     * （补丁键不命中，静默走旧逻辑）。switch 目标用 LabelNode 的首次出现序号
     * 引用；普通 LabelNode 本身不进指纹 —— 它随排版漂移，算进去会把「只是挪了
     * 几行」误判成「方法改了」，触发无谓的整包判定。
     * 跳过 [LineNumberNode] 与 [FrameNode] —— 它们随排版和编译器心情变化，
     * 算进去同样违背上面的设计。
     */
    private fun fingerprintOf(method: MethodNode): String {
        // 先按首次出现顺序给所有 LabelNode 编号：switch 的分支目标就是 LabelNode，
        // 序号是它跨两版字节码唯一稳定的引用方式。编号表只服务于 switch 目标的
        // 序列化，普通 LabelNode 并不据此进指纹。
        val labelIds = HashMap<LabelNode, Int>()
        var cur: AbstractInsnNode? = method.instructions.first
        var nextLabelId = 0
        while (cur != null) {
            if (cur is LabelNode) labelIds[cur] = nextLabelId++
            cur = cur.next
        }

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
                is InvokeDynamicInsnNode -> {
                    // lambda 捕获列表的变化只体现在 bootstrap 方法与参数里。
                    sb.append("indy ").append(insn.name).append(insn.desc)
                        .append(" bsm=").append(handleText(insn.bsm))
                    for (arg in insn.bsmArgs) sb.append(' ').append(constantText(arg))
                }
                is TableSwitchInsnNode -> {
                    sb.append("tableswitch ").append(insn.min).append('..').append(insn.max)
                        .append(" dflt=").append(labelId(labelIds, insn.dflt))
                    for (target in insn.targets) sb.append(' ').append(labelId(labelIds, target))
                }
                is LookupSwitchInsnNode -> {
                    sb.append("lookupswitch dflt=").append(labelId(labelIds, insn.dflt))
                    for (key in insn.keys) sb.append(' ').append(key)
                    for (target in insn.targets) sb.append(' ').append(labelId(labelIds, target))
                }
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

    /** switch 目标标签的稳定序号；查不到说明指令表与 switch 操作数不一致，属于字节码结构异常。 */
    private fun labelId(labelIds: Map<LabelNode, Int>, label: LabelNode): Int =
        requireNotNull(labelIds[label]) { "switch 分支目标标签不在方法指令列表里（字节码结构异常）" }

    /** bootstrap 常量序列化：Handle 展开成成员引用（含 itf，接口与否影响调用语义），Type 取 descriptor。 */
    private fun constantText(value: Any?): String = when (value) {
        null -> "null"
        is Handle -> handleText(value)
        is Type -> "type:${value.descriptor}"
        else -> value.toString()
    }

    private fun handleText(handle: Handle): String =
        "handle:${handle.owner}.${handle.name}${handle.desc}" + if (handle.itf) ";itf" else ""

    /**
     * 收集方法体里「结构上无法安全移植进补丁类」的指令事实（同一类事实只记一条）。
     *
     * 这些模式在发射阶段要么被硬拒（NEW / `<init>` 宿主类型）、要么被改写成会出错的
     * 反射调用（super 调用经反射会从 receiver 具体类沿继承链找最派生实现再虚分发，
     * 补丁方法自身 override 同一方法时无限递归），必须提前到闸门层整包拒绝。
     *
     * 注意：GETSTATIC / PUTSTATIC 不在此列 —— 宿主静态字段访问由发射层反射支持，不能拒。
     */
    private fun portabilityIssuesOf(method: MethodNode, classInternalName: String): List<String> {
        val issues = LinkedHashSet<String>()
        var insn: AbstractInsnNode? = method.instructions.first
        while (insn != null) {
            when (insn) {
                is MethodInsnNode -> when {
                    // super.foo() 这类调用：owner 不是本类且不是构造器。
                    insn.opcode == Opcodes.INVOKESPECIAL && insn.name != "<init>" &&
                        insn.owner != classInternalName ->
                        issues += "super 调用无法安全移植：反射会虚分发到最派生实现导致递归"
                    // new 宿主类型（<init> 调用只会以 INVOKESPECIAL 出现，按名字判定即可）。
                    insn.name == "<init>" && insn.owner.startsWith(HOST_PACKAGE_PREFIX) ->
                        issues += "补丁里构造宿主类型暂不支持"
                }

                is TypeInsnNode ->
                    if (insn.opcode == Opcodes.NEW && insn.desc.startsWith(HOST_PACKAGE_PREFIX)) {
                        issues += "补丁里构造宿主类型暂不支持"
                    }
            }
            insn = insn.next
        }
        return issues.toList()
    }
}
