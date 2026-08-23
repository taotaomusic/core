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
     */
    fun disable(patchVersion: Int, reason: String) {
        preferences.edit()
            .remove(KEY_VERSION)
            .remove(KEY_TARGET)
            .putInt(KEY_ATTEMPTS, 0)
            .putInt(KEY_FAILED, patchVersion)
            .putString(KEY_REASON, reason)
            .apply()
    }

    /** 已知加载失败过的补丁版本，不再重试。 */
    fun failedPatchVersion(): Int = preferences.getInt(KEY_FAILED, 0)

    fun failureReason(): String? = preferences.getString(KEY_REASON, null)

    /** 换账号或宿主升级后清空，避免旧状态影响判断。 */
    fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val KEY_VERSION = "patch_version"
        const val KEY_TARGET = "target_version_code"
        const val KEY_ATTEMPTS = "load_attempts"
        const val KEY_FAILED = "failed_version"
        const val KEY_REASON = "failure_reason"
    }
}
