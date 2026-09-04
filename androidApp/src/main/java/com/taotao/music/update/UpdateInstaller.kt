package com.taotao.music.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 唤起系统安装器。
 *
 * Android 7 起不允许把 `file://` 交给别的应用，必须经 FileProvider 换成 `content://`
 * 并授予读权限；Android 8 起还需要用户为本应用单独开启「安装未知应用」，
 * 未授权时先引导到设置页，否则用户只会看到一个没有任何说明的系统拒绝。
 *
 * 安装包的签名由系统在安装时强制校验：与已安装应用签名不一致会被拒绝，
 * 这是防止装上被替换包的最终防线，客户端侧的 sha256 校验在下载器里完成。
 */
class UpdateInstaller(context: Context) {
    private val appContext = context.applicationContext

    /** 是否已获得安装未知应用的权限。Android 8 以下无此开关。 */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    /** 跳转到「安装未知应用」授权页。 */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 唤起安装。返回失败原因，成功时返回 null。 */
    fun install(apk: File): String? {
        if (!apk.isFile) return "安装包不存在，请重新下载"
        if (!canInstall()) {
            requestInstallPermission()
            return "请先允许桃桃音乐安装应用，然后重试"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        }.getOrElse { it.message ?: "无法唤起安装程序" }
    }
}
