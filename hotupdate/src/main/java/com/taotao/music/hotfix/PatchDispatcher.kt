package com.taotao.music.hotfix

/**
 * 补丁分发接口。
 *
 * 这是整套热修复的核心约定，思路取自 Robust：**编译期**在每个可热修方法的开头插一段
 * 「如果有补丁就交给补丁执行并直接返回」的判断，运行时把补丁 DEX 里的实现赋给
 * 目标类的静态字段。方法体本身不被替换，所以：
 *
 * - 不需要动 `dexElements`，用普通 `DexClassLoader` 就够 —— Android 14 起 DEX 必须只读、
 *   Android 16 上 vdex 生成异常这些坑全都不沾（Tinker 正是卡在这里）
 * - **不用重启应用**，赋值完下一次调用就走补丁
 * - 补丁只包含改动的方法，几十 KB 量级，而不是整包 12.6 MB 的 DEX
 *
 * 代价是 UI 层不能插桩：Compose 的可组合函数依赖 `startRestartGroup` / `endRestartGroup`
 * 严格配对，在函数开头插一条提前 return 会破坏组结构。所以插桩范围限定在
 * `data` / `player` / `update` / `model` 这些纯逻辑包，界面 bug 仍然只能发整包。
 *
 * ## 补丁作者须知
 *
 * - 异常边界：插桩的分发判断位于原方法体的第一条指令**之前**，在原方法任何 try 块的
 *   覆盖范围之外。补丁 `dispatch`（及其实现）抛出的异常会直接向调用方传播，**不会**
 *   被原方法自己的 catch 接住 —— 「原方法包了 try 不会崩」的直觉在打上补丁后失效，
 *   可预期的异常必须在补丁实现里自行兜住。
 * - 线程可见性：分发器字段是 volatile，加载器写入后各线程的下一次方法调用立即可见补丁；
 *   但补丁实现内部若有延迟初始化（懒加载单例之类）仍需自带同步 —— volatile 只保证
 *   分发器字段本身的可见性，不延伸到补丁自己的状态。
 */
interface PatchDispatcher {
    /**
     * 这个方法有没有补丁。
     *
     * [methodKey] 由插桩阶段生成，格式为 `类的全名#方法名(描述符)`，保证同名重载不会撞。
     */
    fun isSupport(methodKey: String): Boolean

    /**
     * 执行补丁实现。
     *
     * [receiver] 是实例方法的 this，静态方法传 null；[args] 是原方法的实参。
     * 返回值由插桩代码按原方法的返回类型拆箱；原方法返回 void 时返回值被忽略。
     */
    fun dispatch(methodKey: String, receiver: Any?, args: Array<Any?>): Any?
}
