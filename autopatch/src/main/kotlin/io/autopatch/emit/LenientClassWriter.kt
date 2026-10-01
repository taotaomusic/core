package io.autopatch.emit

import org.objectweb.asm.ClassWriter

/**
 * 一个「算不出公共父类就退回 Object」的 ClassWriter。
 *
 * `COMPUTE_FRAMES` 需要 [getCommonSuperClass] 来合并栈帧类型，默认实现靠
 * `Class.forName` 加载两边的类求公共父类。但生成器的 classpath 里**没有宿主类**
 * （宿主类只在设备上、由宿主类加载器持有），一 load 就 ClassNotFound。
 *
 * Robust 也踩过这个坑。做法是：加载不到就保守地返回 `java/lang/Object` —— 对
 * 我们生成的补丁方法是安全的，因为方法体里对宿主类型的访问最终都会改写成反射
 * （返回 Object），栈帧上不需要精确的宿主类型。
 */
class LenientClassWriter(flags: Int) : ClassWriter(flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        return try {
            super.getCommonSuperClass(type1, type2)
        } catch (_: Throwable) {
            "java/lang/Object"
        }
    }
}
