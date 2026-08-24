package com.taotao.music.hotfix.generated

import com.taotao.music.hotfix.PatchDispatcher
import com.taotao.music.hotfix.PatchEntry

/**
 * 补丁入口。
 *
 * 类名必须固定为 `com.taotao.music.hotfix.generated.PatchEntryImpl` ——
 * 加载器按这个名字反射实例化，补丁与宿主只靠这一个约定耦合。
 *
 * ## 怎么写一个补丁
 *
 * 1. 在 [targets] 里列出要打补丁的类（全名，点号形式）。这些类必须被插过桩，
 *    也就是必须落在 `data` / `player` / `update` 包下 —— UI 层没有插桩，
 *    界面 bug 只能发整包。
 * 2. 在 `isSupport` 里认领要接管的方法键。格式是
 *    `类的内部名#方法名(参数描述符)返回描述符`。
 *    用 `./gradlew :patch:printMethodKeys` 可以列出当前 APK 里所有可用的键。
 * 3. 在 `dispatch` 里写新实现。`receiver` 是实例方法的 this（静态方法为 null），
 *    `args` 是原方法的实参，基本类型已装箱。返回值会被插桩代码按原返回类型拆箱，
 *    类型对不上会在运行时 ClassCastException。
 *
 * ## 四条不能踩的
 *
 * - **不要在 dispatch 里调用被自己接管的那个方法**。插桩的判断在方法入口，
 *   调用它会再次进到这里，无限递归直接 StackOverflow。要原逻辑就自己重写一遍。
 * - **不要 new 宿主已有的类并跨边界传递**。补丁类加载器加载的同名类与宿主的不是
 *   同一个 Class，传参会 ClassCastException。引用宿主类型只做类型声明
 *   （compileOnly 已保证不打进补丁），实例一律用传进来的那个。
 * - **不要改方法签名**。补丁是按宿主那份代码的签名生成的，签名变了匹配不上。
 * - **改完补丁要同步改源码**。补丁只是让线上先不崩，下一个整包版本里真正的修复
 *   必须在原位置也做一遍，否则升级后 bug 回归。
 *
 * ## 当前内容：问候语验证补丁
 *
 * 接管 `GreetingFormatter.greetingForHour()`：仅把深夜默认文案改为「你好」。首页的
 * Compose 结构没有变化，适合作为数据层热修补的加载、即时生效与回退验证样例。
 */
class PatchEntryImpl : PatchEntry {

    override fun targets(): List<String> = listOf("com.taotao.music.data.GreetingFormatter")

    override fun dispatcher(): PatchDispatcher = object : PatchDispatcher {
        override fun isSupport(methodKey: String): Boolean = methodKey == LABEL_KEY

        override fun dispatch(methodKey: String, receiver: Any?, args: Array<Any?>): Any? {
            check(methodKey == LABEL_KEY) { "补丁没有实现方法：$methodKey" }
            // 不能调宿主的 greetingForHour()：那会再次命中分发器造成递归。
            val hour = args.getOrNull(0) as? Int ?: return "夜深了"
            return when (hour) {
                in 5..8 -> "早上好"
                in 9..11 -> "上午好"
                in 12..13 -> "中午好"
                in 14..18 -> "下午好"
                in 19..22 -> "晚上好"
                else -> "你好"
            }
        }
    }

    private companion object {
        const val LABEL_KEY = "com/taotao/music/data/GreetingFormatter#greetingForHour(I)Ljava/lang/String;"
    }
}
