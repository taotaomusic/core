package com.taotao.music.hotfix

import android.content.Context

/**
 * 热修复的本地状态。
 *
 * 除了记住"当前装了哪个补丁"，还承担**自愈**职责，这是整套机制敢上线的前提 ——
 * 唯一的修复通道本身就是更新通道，一个能让应用起不来的补丁如果不能自己退回去，
 * 就没有任何补救手段。
 *
 * 自愈靠一个加载尝试计数：
 *
 * - 每次加载补丁前把计数 +1 并**立刻写盘**（写在加载之前，否则加载过程直接崩就丢了）
 * - 应用平稳跑起来后清零
 * - 启动时发现计数已经不为 0，说明上一次加载后没能走到"平稳"，直接弃用该补丁
 *
 * 所以最坏情况是崩一次就自动回滚，而不是反复崩。
 *
 * ## 为什么要记「最近一次结果」
 *
 * 整条补丁链路原本只往 logcat 写日志。测试机连不上 adb，于是补丁没生效时
 * **界面上没有任何信息**：不知道是版本号不匹配、下载失败、校验不符，还是加载抛异常。
 * 真实发生过一次这样的排查，只能靠猜。所以每一步的结论都落盘，设置页直接读出来。
 */
class HotfixStore(context: Context) {
    private val preferences = context.getSharedPreferences("hotfix", Context.MODE_PRIVATE)

    /** 当前应生效的补丁版本，0 表示没有。 */
    fun patchVersion(): Int = preferences.getInt(KEY_VERSION, 0)

    /** 这个补丁是给哪个应用版本做的。宿主版本不匹配时补丁必须失效。 */
    fun targetVersionCode(): Long = preferences.getLong(KEY_TARGET, 0L)

    /**
     * 记下一个刚下载好的补丁。
     *
     * [pendingAttempt] 为真表示这次会立即加载（下载完就生效那条路径），
     * 因此把尝试计数预置为 1 —— 万一它把当前进程搞崩，下次启动就能立刻回滚。
     */
    fun install(patchVersion: Int, targetVersionCode: Long, pendingAttempt: Boolean) {
        preferences.edit()
            .putInt(KEY_VERSION, patchVersion)
            .putLong(KEY_TARGET, targetVersionCode)
            .putInt(KEY_ATTEMPTS, if (pendingAttempt) 1 else 0)
            .remove(KEY_FAILED)
            .remove(KEY_FAILED_TARGET)
            .remove(KEY_REASON)
            .apply()
    }

    /** 加载尝试次数。启动时不为 0 就说明上次没能平稳跑起来。 */
    fun attempts(): Int = preferences.getInt(KEY_ATTEMPTS, 0)

    /** 必须在真正加载**之前**调用并同步写盘，否则加载即崩时这条记录会丢。 */
    fun beginAttempt() {
        preferences.edit().putInt(KEY_ATTEMPTS, attempts() + 1).commit()
    }

    /** 应用已平稳运行，确认补丁可用。 */
    fun confirm() {
        if (attempts() == 0) return
        preferences.edit().putInt(KEY_ATTEMPTS, 0).apply()
    }

    /**
     * 弃用补丁，退回未打补丁的状态。
     *
     * 记下失败的版本号：服务端可能还在下发同一个补丁，不记住会陷入
     * 「下载 → 崩 → 回滚 → 再下载」的循环。
     *
     * 同时记下**它是给哪个宿主版本的**。补丁版本号是按宿主版本各自从 1 开始编的，
     * 所以 vc71 的 v1 和 vc72 的 v1 是两个完全不同的补丁。只记版本号的话，
     * 一个在旧宿主上失败过的 v1 会把新宿主上同名的 v1 一并拦掉 ——
     * 而 SharedPreferences 跨升级保留，于是**每次升级都会撞**，
     * 表现成"新版本装上了但补丁永远装不上，必须手动清一次失败记录"。
     */
    fun disable(patchVersion: Int, reason: String) {
        // 先取出来：同一次编辑里 KEY_TARGET 会被删掉。
        val failedTarget = targetVersionCode()
        preferences.edit()
            .remove(KEY_VERSION)
            .remove(KEY_TARGET)
            .putInt(KEY_ATTEMPTS, 0)
            .putInt(KEY_FAILED, patchVersion)
            .putLong(KEY_FAILED_TARGET, failedTarget)
            .putString(KEY_REASON, reason)
            .apply()
    }

    /**
     * 已知加载失败过的补丁版本，不再重试。
     *
     * [installedVersionCode] 用来判断这条记录还算不算数：宿主已经换版本了，
     * 旧宿主上的失败记录不该影响新宿主上同名的补丁。
     */
    fun failedPatchVersion(installedVersionCode: Long): Int {
        val failedTarget = preferences.getLong(KEY_FAILED_TARGET, 0L)
        if (failedTarget != 0L && failedTarget != installedVersionCode) return 0
        return preferences.getInt(KEY_FAILED, 0)
    }

    fun failureReason(): String? = preferences.getString(KEY_REASON, null)

    /**
     * 记下链路里最近一次发生了什么，供设置页展示。
     *
     * 每一个「不装」的分支都要调它 —— 静默返回 false 是这套机制最难排查的地方：
     * 界面上看不出区别，而 logcat 在测试机上取不到。
     */
    fun note(outcome: String) {
        preferences.edit()
            .putString(KEY_OUTCOME, outcome)
            .putLong(KEY_OUTCOME_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastOutcome(): String? = preferences.getString(KEY_OUTCOME, null)

    fun lastOutcomeAt(): Long = preferences.getLong(KEY_OUTCOME_AT, 0L)

    /**
     * 解除"这个补丁失败过"的记忆，让服务端下发的同一版本能被重新尝试。
     *
     * [failedPatchVersion] 原本没有任何清除入口：补丁一旦失败过一次就永久装不上，
     * 而用户看不到原因也没有重试手段 —— 这是个死锁。所以设置页要有一个显式出口。
     * 不清 [KEY_VERSION]：正在生效的补丁不该被这个动作弄掉。
     */
    fun forgetFailure() {
        preferences.edit()
            .remove(KEY_FAILED)
            .remove(KEY_FAILED_TARGET)
            .remove(KEY_REASON)
            .putString(KEY_OUTCOME, "已清除失败记录，下次检查更新会重新尝试")
            .putLong(KEY_OUTCOME_AT, System.currentTimeMillis())
            .apply()
    }

    /** 换账号或宿主升级后清空，避免旧状态影响判断。 */
    fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val KEY_VERSION = "patch_version"
        const val KEY_TARGET = "target_version_code"
        const val KEY_ATTEMPTS = "load_attempts"
        const val KEY_FAILED = "failed_version"
        const val KEY_FAILED_TARGET = "failed_target_version_code"
        const val KEY_REASON = "failure_reason"
        const val KEY_OUTCOME = "last_outcome"
        const val KEY_OUTCOME_AT = "last_outcome_at"
    }
}
