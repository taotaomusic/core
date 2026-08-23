package com.taotao.music.update

import java.io.File

/** 服务端下发的一个可安装版本。 */
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
    val releaseNote: String,
)

/**
 * 服务端下发的一个可用补丁。
 *
 * [targetVersionCode] 是这个补丁**基于哪个宿主版本**生成的，必须与本机严格相等 ——
 * 补丁里的方法签名就是那份代码的，装到别的版本上会匹配不上。
 */
data class AvailablePatch(
    val patchVersion: Int,
    val targetVersionCode: Long,
    val url: String,
    val size: Long,
    val sha256: String,
    val note: String,
)

/** `bootstrap` 接口的完整返回：更新信息 + 热修复补丁 + 远程配置。 */
data class BootstrapResult(
    val release: UpdateRelease?,
    val forced: Boolean,
    val minSupportedVersionCode: Long,
    val patch: AvailablePatch?,
    val config: Map<String, String>,
    val configVersion: Long,
)

enum class UpdateStage { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

/**
 * 更新流程状态。
 *
 * [forced] 贯穿所有阶段：强制更新下即使下载失败也不能放行界面，只能重试，
 * 否则被判定为必须升级的客户端会绕过门禁继续使用旧版本。
 */
data class UpdateStatus(
    val stage: UpdateStage = UpdateStage.IDLE,
    val release: UpdateRelease? = null,
    val forced: Boolean = false,
    val progressPercent: Int = 0,
    val apk: File? = null,
    val error: String? = null,
) {
    /** 是否需要用界面拦住用户。强制更新在检查完成后的所有阶段都要拦。 */
    val blocking: Boolean
        get() = forced && stage != UpdateStage.IDLE && stage != UpdateStage.CHECKING && stage != UpdateStage.UP_TO_DATE
}
