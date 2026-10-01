package io.autopatch.emit

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * 把方法体里对**宿主包成员**的字节码访问改写成调用 [io.autopatch.runtime.Refl] 的反射。
 *
 * 补丁类与宿主类是两套 Class 对象，直连访问宿主私有成员会抛 `IllegalAccessError`。
 * 统一走反射（不区分 public/private）最简单也总是正确 —— 热修低频，反射开销可忽略。
 *
 * 用「溢出到局部变量槽」的策略处理栈：先把栈上的操作数逐个存进 scratch 槽，再按
 * `Refl.*` 需要的顺序重新组织，避免在栈上做易错的 SWAP/DUP 体操（尤其是 long/double
 * 这种双字值）。scratch 槽从 [scratchBase] 起，各改写点复用同一段（点内直线代码、
 * 无跳转，不会与栈帧计算冲突）。
 */
object HostAccessRewriter {

    private const val REFL = "io/autopatch/runtime/Refl"

    /** getfield：栈顶已是 objectref。追加 owner/name 常量并调 Refl.getField，再按字段类型收口。 */
    fun getField(out: InsnList, owner: String, name: String, desc: String) {
        out.add(LdcInsnNode(owner))
        out.add(LdcInsnNode(name))
        out.add(MethodInsnNode(Opcodes.INVOKESTATIC, REFL, "getField",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false))
        coerceResult(out, Type.getType(desc))
    }

    /** putfield：栈为 [objectref, value]。先把 value 装箱存 scratch，再按 (target,owner,name,value) 调用。 */
    fun putField(out: InsnList, owner: String, name: String, desc: String, scratchBase: Int) {
        box(out, Type.getType(desc))
        out.add(VarInsnNode(Opcodes.ASTORE, scratchBase)) // value → scratch
        // 栈剩 [objectref]
        out.add(LdcInsnNode(owner))
        out.add(LdcInsnNode(name))
        out.add(VarInsnNode(Opcodes.ALOAD, scratchBase))
        out.add(MethodInsnNode(Opcodes.INVOKESTATIC, REFL, "setField",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", false))
    }

    /**
     * 方法调用改写。opcode ∈ {INVOKEVIRTUAL, INVOKEINTERFACE, INVOKESPECIAL, INVOKESTATIC}。
     * 实例调用栈为 [objectref, args...]，静态为 [args...]。
     */
    fun invoke(out: InsnList, opcode: Int, owner: String, name: String, desc: String, scratchBase: Int) {
        val isStatic = opcode == Opcodes.INVOKESTATIC
        val argTypes = Type.getArgumentTypes(desc)
        val n = argTypes.size

        // 1) 从栈顶逆序把实参装箱后存进 scratch[1..n]（scratch[0] 留给 target）。
        for (i in n - 1 downTo 0) {
            box(out, argTypes[i])
            out.add(VarInsnNode(Opcodes.ASTORE, scratchBase + 1 + i))
        }
        // 2) 实例调用此刻栈顶是 objectref，存 scratch[0]；静态无 target。
        if (!isStatic) out.add(VarInsnNode(Opcodes.ASTORE, scratchBase))

        // 3) 组织调用参数。
        if (isStatic) {
            out.add(LdcInsnNode(owner))
            out.add(LdcInsnNode(name))
            buildParamDescArray(out, argTypes)
            buildArgArray(out, n, scratchBase + 1)
            out.add(MethodInsnNode(Opcodes.INVOKESTATIC, REFL, "invokeStatic",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false))
        } else {
            out.add(VarInsnNode(Opcodes.ALOAD, scratchBase)) // target
            out.add(LdcInsnNode(owner))
            out.add(LdcInsnNode(name))
            buildParamDescArray(out, argTypes)
            buildArgArray(out, n, scratchBase + 1)
            out.add(MethodInsnNode(Opcodes.INVOKESTATIC, REFL, "invoke",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false))
        }
        coerceResult(out, Type.getReturnType(desc))
    }

    /** new Object[n]{ scratch[base], scratch[base+1], ... }。 */
    private fun buildArgArray(out: InsnList, n: Int, base: Int) {
        out.add(intConst(n))
        out.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        for (i in 0 until n) {
            out.add(InsnNode(Opcodes.DUP))
            out.add(intConst(i))
            out.add(VarInsnNode(Opcodes.ALOAD, base + i))
            out.add(InsnNode(Opcodes.AASTORE))
        }
    }

    /** new String[]{ "I", "Ljava/lang/String;", ... }，给 Refl 精确匹配重载用。 */
    private fun buildParamDescArray(out: InsnList, argTypes: Array<Type>) {
        out.add(intConst(argTypes.size))
        out.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"))
        argTypes.forEachIndexed { i, t ->
            out.add(InsnNode(Opcodes.DUP))
            out.add(intConst(i))
            out.add(LdcInsnNode(t.descriptor))
            out.add(InsnNode(Opcodes.AASTORE))
        }
    }

    /** Refl.* 返回 Object：void 丢弃，基本类型拆箱，引用类型 CHECKCAST。 */
    private fun coerceResult(out: InsnList, type: Type) {
        when (type.sort) {
            Type.VOID -> out.add(InsnNode(Opcodes.POP))
            Type.BOOLEAN -> unbox(out, "java/lang/Boolean", "booleanValue", "()Z")
            Type.CHAR -> unbox(out, "java/lang/Character", "charValue", "()C")
            Type.BYTE -> unbox(out, "java/lang/Byte", "byteValue", "()B")
            Type.SHORT -> unbox(out, "java/lang/Short", "shortValue", "()S")
            Type.INT -> unbox(out, "java/lang/Integer", "intValue", "()I")
            Type.FLOAT -> unbox(out, "java/lang/Float", "floatValue", "()F")
            Type.LONG -> unbox(out, "java/lang/Long", "longValue", "()J")
            Type.DOUBLE -> unbox(out, "java/lang/Double", "doubleValue", "()D")
            else -> out.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
        }
    }

    private fun unbox(out: InsnList, boxed: String, method: String, desc: String) {
        out.add(TypeInsnNode(Opcodes.CHECKCAST, boxed))
        out.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed, method, desc, false))
    }

    private fun box(out: InsnList, type: Type) {
        val boxed = when (type.sort) {
            Type.BOOLEAN -> "java/lang/Boolean"
            Type.CHAR -> "java/lang/Character"
            Type.BYTE -> "java/lang/Byte"
            Type.SHORT -> "java/lang/Short"
            Type.INT -> "java/lang/Integer"
            Type.FLOAT -> "java/lang/Float"
            Type.LONG -> "java/lang/Long"
            Type.DOUBLE -> "java/lang/Double"
            else -> return
        }
        out.add(MethodInsnNode(Opcodes.INVOKESTATIC, boxed, "valueOf", "(${type.descriptor})L$boxed;", false))
    }

    private fun intConst(value: Int) = when {
        value in -1..5 -> InsnNode(Opcodes.ICONST_0 + value)
        value in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
        value in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, value)
        else -> LdcInsnNode(value)
    }
}
