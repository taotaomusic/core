package com.taotao.music.hotfix

/**
 * 热修复状态快照，给设置页展示。
 *
 * 存在的理由很实际：补丁没生效时原本界面上**一点信息都没有** —— 版本号不匹配、
 * 下载失败、校验不符、加载抛异常，四种情况在界面上长得一模一样，而测试机连不上 adb
 * 拿不到 logcat。真实排查过一次，只能靠猜。
 *
 * @param installedVersionCode 本机版本号。补丁要求与它严格相等，不等就装不上 ——
 *   这是最常见的原因，所以放在第一位。
 * @param activePatchVersion 当前生效的补丁版本，0 表示没有。
 * @param targetVersionCode 已装补丁登记的目标版本。
 * @param failedPatchVersion 被记住"失败过"的补丁版本，0 表示没有。非 0 意味着
 *   这个版本**不会再被尝试**，需要显式清除。
 * @param failureReason 失败原因。
 * @param lastOutcome 链路最近一次的结论，人能直接读。
 * @param lastOutcomeAt 上面那条结论的时间戳，0 表示还没有过。
 */
data class HotfixDiagnostics(
    val installedVersionCode: Long,
    val activePatchVersion: Int,
    val targetVersionCode: Long,
    val failedPatchVersion: Int,
    val failureReason: String?,
    val lastOutcome: String?,
    val lastOutcomeAt: Long,
) {
    /** 是否卡在"失败过所以不再重试"的状态 —— 界面据此决定要不要显示清除入口。 */
    val isBlockedByFailure: Boolean get() = failedPatchVersion != 0

    /** 一句话概括，用作设置项右侧的值。 */
    val summary: String
        get() = when {
            activePatchVersion != 0 -> "已生效 v$activePatchVersion"
            isBlockedByFailure -> "v$failedPatchVersion 失败"
            else -> "无"
        }
}
