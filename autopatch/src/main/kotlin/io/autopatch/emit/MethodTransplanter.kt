package io.autopatch.emit

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * 把 head 侧一个方法的方法体「移植」进补丁类的一个静态方法。
 *
 * 生成的静态方法签名固定为 `(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;`
 * ——即 `(methodKey 已在 dispatch 里判过, receiver, args) -> result`，这里只收 receiver 与 args。
 *
 * 移植做三件事：
 * 1. **重排局部变量槽**：原方法体按 `this=0, 参数=1..` 的槽位读写；补丁静态方法自己的
 *    两个参数（receiver、args）占了低槽，所以原方法的所有槽整体 +[SLOT_OFFSET]。
 * 2. **重建原始栈帧**：把 receiver 强转回宿主类型存进原 this 槽，把 args[i] 拆箱/强转成
 *    原参数类型存进对应槽，这样移植过来的指令一个字节都不用改就能读到它们。
 * 3. **改写返回**：原方法的 `?RETURN` 一律改成「装箱后 ARETURN」；`void` 返回 null。
 *
 * ⚠️ 本轮是「直连」移植：方法体里对宿主成员的访问保持原样的 `getfield`/`invokevirtual`。
 * 对 public/internal 成员没问题（补丁类加载器以宿主为父，引用能链接到宿主那份类）；
 * 但访问**私有**成员会在运行时抛 `IllegalAccessError`。私有访问改写成反射是下一步。
 */
object MethodTransplanter {

    const val SLOT_OFFSET = 2 // receiver@0, args@1

    /** 生成的静态方法描述符：(receiver, args) -> Object。 */
    const val IMPL_DESCRIPTOR = "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"

    /**
     * @param source head 侧带指令的 MethodNode（读取时不要 SKIP_CODE；FrameNode 会被丢弃，靠 COMPUTE_FRAMES 重算）
     * @param ownerInternalName 宿主类内部名，用于把 receiver 强转回去
     * @param implName 生成的静态方法名，例如 `impl$0`
     * @param isHostOwner 判断某个引用的 owner 是否是「宿主包」——是则其成员访问改写成反射
     */
    fun transplant(
        source: MethodNode,
        ownerInternalName: String,
        implName: String,
        isHostOwner: (String) -> Boolean = { false },
    ): MethodNode {
        val isStatic = source.access and Opcodes.ACC_STATIC != 0
        val argTypes = Type.getArgumentTypes(source.desc)
        val returnType = Type.getReturnType(source.desc)

        val target = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            implName,
            IMPL_DESCRIPTOR,
            null,
            null,
        )
        val out = target.instructions

        // 1) 重建原始帧 --------------------------------------------------------
        if (!isStatic) {
            // this: receiver 强转宿主类型 → 原槽 0（偏移后 = SLOT_OFFSET）
            out.add(varInsn(Opcodes.ALOAD, 0))
            out.add(TypeInsnNode(Opcodes.CHECKCAST, ownerInternalName))
            out.add(varInsn(Opcodes.ASTORE, SLOT_OFFSET))
        }
        var origSlot = if (isStatic) 0 else 1
        argTypes.forEachIndexed { index, type ->
            out.add(varInsn(Opcodes.ALOAD, 1)) // args
            out.add(intConst(index))
            out.add(InsnNode(Opcodes.AALOAD))
            unboxOrCast(out, type)
            out.add(varInsn(type.getOpcode(Opcodes.ISTORE), origSlot + SLOT_OFFSET))
            origSlot += type.size
        }

        // 2) 移植方法体 --------------------------------------------------------
        val labelMap = HashMap<LabelNode, LabelNode>()
        var insn: AbstractInsnNode? = source.instructions.first
        while (insn != null) {
            if (insn is LabelNode) labelMap[insn] = LabelNode()
            insn = insn.next
        }

        // scratch 槽从原方法局部变量之上起，供反射改写溢出操作数用。
        val scratchBase = SLOT_OFFSET + source.maxLocals

        insn = source.instructions.first
        while (insn != null) {
            val node = insn
            when {
                node is LineNumberNode || node is FrameNode -> {} // 丢弃
                node.opcode in Opcodes.IRETURN..Opcodes.RETURN -> emitReturn(out, node.opcode, returnType)

                node is org.objectweb.asm.tree.FieldInsnNode && isHostOwner(node.owner) -> when (node.opcode) {
                    Opcodes.GETFIELD -> HostAccessRewriter.getField(out, node.owner, node.name, node.desc)
                    Opcodes.PUTFIELD -> HostAccessRewriter.putField(out, node.owner, node.name, node.desc, scratchBase)
                    else -> throw UnsupportedOperationException(
                        "暂不支持改写宿主静态字段访问：${node.owner}.${node.name}（GETSTATIC/PUTSTATIC）",
                    )
                }

                node is org.objectweb.asm.tree.MethodInsnNode && isHostOwner(node.owner) -> {
                    if (node.name == "<init>") {
                        throw UnsupportedOperationException(
                            "暂不支持在补丁里 new 宿主类型：${node.owner}（NEW + <init>）",
                        )
                    }
                    HostAccessRewriter.invoke(out, node.opcode, node.owner, node.name, node.desc, scratchBase)
                }

                else -> {
                    val cloned = node.clone(labelMap)
                    shiftLocals(cloned)
                    out.add(cloned)
                }
            }
            insn = node.next
        }

        // try-catch 块的标签也要走 labelMap 重映射。
        source.tryCatchBlocks?.forEach { tcb ->
            target.tryCatchBlocks.add(
                org.objectweb.asm.tree.TryCatchBlockNode(
                    labelMap.getValue(tcb.start),
                    labelMap.getValue(tcb.end),
                    labelMap.getValue(tcb.handler),
                    tcb.type,
                ),
            )
        }

        return target
    }

    /** 局部变量槽整体 +SLOT_OFFSET（原方法体假设自己独占低槽）。 */
    private fun shiftLocals(insn: AbstractInsnNode) {
        when (insn) {
            is org.objectweb.asm.tree.VarInsnNode -> insn.`var` += SLOT_OFFSET
            is org.objectweb.asm.tree.IincInsnNode -> insn.`var` += SLOT_OFFSET
        }
    }

    /** 把栈顶（原方法的返回值）装箱后 ARETURN；void 返回 null。 */
    private fun emitReturn(out: InsnList, opcode: Int, returnType: Type) {
        when (opcode) {
            Opcodes.RETURN -> out.add(InsnNode(Opcodes.ACONST_NULL))
            Opcodes.ARETURN -> {} // 已是引用
            else -> box(out, returnType) // 基本类型
        }
        out.add(InsnNode(Opcodes.ARETURN))
    }

    /** args[i]（Object）→ 目标类型：基本类型拆箱，引用类型 CHECKCAST。 */
    private fun unboxOrCast(out: InsnList, type: Type) {
        val (boxed, method, desc) = when (type.sort) {
            Type.BOOLEAN -> Triple("java/lang/Boolean", "booleanValue", "()Z")
            Type.CHAR -> Triple("java/lang/Character", "charValue", "()C")
            Type.BYTE -> Triple("java/lang/Byte", "byteValue", "()B")
            Type.SHORT -> Triple("java/lang/Short", "shortValue", "()S")
            Type.INT -> Triple("java/lang/Integer", "intValue", "()I")
            Type.FLOAT -> Triple("java/lang/Float", "floatValue", "()F")
            Type.LONG -> Triple("java/lang/Long", "longValue", "()J")
            Type.DOUBLE -> Triple("java/lang/Double", "doubleValue", "()D")
            else -> {
                out.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
                return
            }
        }
        out.add(TypeInsnNode(Opcodes.CHECKCAST, boxed))
        out.add(org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed, method, desc, false))
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
        out.add(
            org.objectweb.asm.tree.MethodInsnNode(
                Opcodes.INVOKESTATIC, boxed, "valueOf", "(${type.descriptor})L$boxed;", false,
            ),
        )
    }

    private fun varInsn(opcode: Int, slot: Int) = org.objectweb.asm.tree.VarInsnNode(opcode, slot)

    private fun intConst(value: Int): AbstractInsnNode = when {
        value in -1..5 -> InsnNode(Opcodes.ICONST_0 + value)
        value in Byte.MIN_VALUE..Byte.MAX_VALUE -> org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value)
        value in Short.MIN_VALUE..Short.MAX_VALUE -> org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value)
        else -> org.objectweb.asm.tree.LdcInsnNode(value)
    }
}
