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
 * ## 当前内容：一个可验证生效的样例补丁
 *
 * 接管 `AppearanceMode.getLabel()`，在外观选项的文案后面加一个标记。
 * 装上补丁后进「我的 → 设置 → 外观」，三个选项会变成「跟随系统 · 补丁已生效」这样，
 * 一眼就能确认热修复真的跑起来了；而且它只改一个字符串，改错了也不会影响功能。
 *
 * 正式发补丁时把 [targets] / isSupport / dispatch 换成真实的修复内容。
 */
class PatchEntryImpl : PatchEntry {

    override fun targets(): List<String> = listOf("com.taotao.music.data.AppearanceMode")

    override fun dispatcher(): PatchDispatcher = object : PatchDispatcher {
        override fun isSupport(methodKey: String): Boolean = methodKey == LABEL_KEY

        override fun dispatch(methodKey: String, receiver: Any?, args: Array<Any?>): Any? {
            check(methodKey == LABEL_KEY) { "补丁没有实现方法：$methodKey" }
            // 不能调 receiver.getLabel()：那会再次命中插桩的判断，无限递归。
            // 枚举常量名不受补丁影响，按它自己算出文案。
            val name = (receiver as? Enum<*>)?.name
            val label = when (name) {
                "FOLLOW_SYSTEM" -> "跟随系统"
                "LIGHT" -> "浅色"
                "DARK" -> "深色"
                else -> name ?: "未知"
            }
            return "$label · 补丁已生效"
        }
    }

    private companion object {
        const val LABEL_KEY = "com/taotao/music/data/AppearanceMode#getLabel()Ljava/lang/String;"
    }
}
