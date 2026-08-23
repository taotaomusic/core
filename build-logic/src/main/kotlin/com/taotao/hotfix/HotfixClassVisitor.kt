package com.taotao.hotfix

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** 分发器接口与静态字段的内部名，必须与运行时那份一致。 */
private const val DISPATCHER_TYPE = "com/taotao/music/hotfix/PatchDispatcher"
private const val DISPATCHER_FIELD = "\$\$patchDispatcher"
private const val DISPATCHER_DESC = "L$DISPATCHER_TYPE;"

/**
 * 给一个类插桩。
 *
 * 做两件事：
 *
 * 1. 加一个 `public static PatchDispatcher $$patchDispatcher;` 字段，运行时由加载器赋值。
 * 2. 在每个可插桩方法开头插入等价于下面这段的字节码：
 *
 * ```
 * if ($$patchDispatcher != null && $$patchDispatcher.isSupport(key)) {
 *     return (T) $$patchDispatcher.dispatch(key, this, new Object[]{ 参数... });
 * }
 * ```
 *
 * 只在开头插一段分支，不动原方法体 —— 所以没有补丁时的开销就是一次静态字段读 +
 * 一次 null 比较，可以忽略。
 */
class HotfixClassVisitor(
    delegate: ClassVisitor,
    className: String,
) : ClassVisitor(Opcodes.ASM9, delegate) {

    /** 本类的内部名（斜杠形式），GETSTATIC 的 owner 要用它。 */
    private val owner = className.replace('.', '/')

    private var isInterface = false

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        isInterface = access and Opcodes.ACC_INTERFACE != 0
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val original = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (!shouldInstrument(access, name)) return original
        return DispatchInserter(original, access, descriptor, owner, "$owner#$name$descriptor")
    }

    override fun visitEnd() {
        if (!isInterface) {
            // 字段必须是 public static：加载器跨类加载器反射赋值，private 在部分 ROM 上
            // 会被 setAccessible 限制拦住。
            cv.visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
                DISPATCHER_FIELD,
                DISPATCHER_DESC,
                null,
                null,
            ).visitEnd()
        }
        super.visitEnd()
    }

    private fun shouldInstrument(access: Int, name: String): Boolean {
        // 接口方法、抽象方法、native 方法没有方法体，无处可插。
        if (isInterface) return false
        if (access and Opcodes.ACC_ABSTRACT != 0) return false
        if (access and Opcodes.ACC_NATIVE != 0) return false
        // 构造器不能插：提前 return 会跳过 super() 调用，字节码校验直接不过。
        if (name == "<init>") return false
        // 静态初始化块同理，而且它跑在类加载期，那时补丁可能还没挂上。
        if (name == "<clinit>") return false
        // 合成方法（桥接、访问器、lambda 实现）名字会随编辑漂移，插了对不上。
        if (access and Opcodes.ACC_SYNTHETIC != 0) return false
        if (name.contains('$')) return false
        return true
    }
}

/**
 * 在方法开头插入分发判断。
 *
 * 关键点：所有插入的指令都必须在 `visitCode` 里、也就是原方法体的第一条指令**之前**发出，
 * 且分支跳过的目标标签放在原方法体开头，这样原方法体一个字节都不用改。
 */
private class DispatchInserter(
    delegate: MethodVisitor,
    private val access: Int,
    private val descriptor: String,
    private val owner: String,
    private val methodKey: String,
) : MethodVisitor(Opcodes.ASM9, delegate) {

    override fun visitCode() {
        super.visitCode()
        val isStatic = access and Opcodes.ACC_STATIC != 0
        val skip = org.objectweb.asm.Label()

        // if ($$patchDispatcher == null) goto skip
        loadDispatcher()
        mv.visitJumpInsn(Opcodes.IFNULL, skip)

        // if (!$$patchDispatcher.isSupport(key)) goto skip
        loadDispatcher()
        mv.visitLdcInsn(methodKey)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DISPATCHER_TYPE, "isSupport", "(Ljava/lang/String;)Z", true)
        mv.visitJumpInsn(Opcodes.IFEQ, skip)

        // return 拆箱($$patchDispatcher.dispatch(key, this, new Object[]{...}))
        loadDispatcher()
        mv.visitLdcInsn(methodKey)
        if (isStatic) mv.visitInsn(Opcodes.ACONST_NULL) else mv.visitVarInsn(Opcodes.ALOAD, 0)
        packArguments(isStatic)
        mv.visitMethodInsn(
            Opcodes.INVOKEINTERFACE,
            DISPATCHER_TYPE,
            "dispatch",
            "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            true,
        )
        emitReturn()

        mv.visitLabel(skip)
    }

    private fun loadDispatcher() {
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, DISPATCHER_FIELD, DISPATCHER_DESC)
    }

    /** 把实参装进 Object[]，基本类型逐个装箱。 */
    private fun packArguments(isStatic: Boolean) {
        val types = Type.getArgumentTypes(descriptor)
        mv.visitLdcInsn(types.size)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        var slot = if (isStatic) 0 else 1
        types.forEachIndexed { index, type ->
            mv.visitInsn(Opcodes.DUP)
            mv.visitLdcInsn(index)
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot)
            box(type)
            mv.visitInsn(Opcodes.AASTORE)
            slot += type.size
        }
    }

    private fun box(type: Type) {
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
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            boxed,
            "valueOf",
            "(${type.descriptor})L$boxed;",
            false,
        )
    }

    /** 按原方法的返回类型拆箱并返回。void 时把 dispatch 的返回值丢掉。 */
    private fun emitReturn() {
        val returnType = Type.getReturnType(descriptor)
        when (returnType.sort) {
            Type.VOID -> {
                mv.visitInsn(Opcodes.POP)
                mv.visitInsn(Opcodes.RETURN)
            }
            Type.BOOLEAN -> unbox("java/lang/Boolean", "booleanValue", "()Z", Opcodes.IRETURN)
            Type.CHAR -> unbox("java/lang/Character", "charValue", "()C", Opcodes.IRETURN)
            Type.BYTE -> unbox("java/lang/Byte", "byteValue", "()B", Opcodes.IRETURN)
            Type.SHORT -> unbox("java/lang/Short", "shortValue", "()S", Opcodes.IRETURN)
            Type.INT -> unbox("java/lang/Integer", "intValue", "()I", Opcodes.IRETURN)
            Type.FLOAT -> unbox("java/lang/Float", "floatValue", "()F", Opcodes.FRETURN)
            Type.LONG -> unbox("java/lang/Long", "longValue", "()J", Opcodes.LRETURN)
            Type.DOUBLE -> unbox("java/lang/Double", "doubleValue", "()D", Opcodes.DRETURN)
            else -> {
                // 引用类型：dispatch 返回 Object，强转回声明类型。
                mv.visitTypeInsn(Opcodes.CHECKCAST, returnType.internalName)
                mv.visitInsn(Opcodes.ARETURN)
            }
        }
    }

    private fun unbox(boxed: String, method: String, methodDescriptor: String, returnOpcode: Int) {
        mv.visitTypeInsn(Opcodes.CHECKCAST, boxed)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxed, method, methodDescriptor, false)
        mv.visitInsn(returnOpcode)
    }
}
