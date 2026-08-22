package com.taotao.music.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条崩溃记录：文件名用于排序和展示，正文是完整堆栈。 */
data class CrashLog(val name: String, val content: String)

/**
 * 崩溃日志落盘。
 *
 * 测试设备无法连接 adb 时，未捕获异常的堆栈会随进程一起消失，只能靠猜。
 * 这里在进程启动时接管默认异常处理器，把堆栈写进应用私有目录，
 * 供「我的」页面直接查看和复制；写完仍然交回系统原处理器，不改变崩溃行为。
 *
 * 日志只包含异常堆栈和机型信息，不含账号、密码或令牌。
 */
class CrashReporter(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, DIRECTORY)

    /** 注册未捕获异常处理器，应在 Application.onCreate 中调用一次。 */
    fun install() {
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(thread, error) }
            // 交回系统处理器，保持「弹出崩溃对话框并结束进程」的原有行为。
            system?.uncaughtException(thread, error)
        }
    }

    /** 按时间倒序读取已保存的崩溃日志，最新的在前。 */
    fun logs(): List<CrashLog> = directory.listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending(File::lastModified)
        ?.mapNotNull { file -> runCatching { CrashLog(file.name, file.readText()) }.getOrNull() }
        .orEmpty()

    fun clear() {
        directory.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun write(thread: Thread, error: Throwable) {
        directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use(error::printStackTrace)
        }.toString()
        val content = buildString {
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("版本：${versionName()}")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}）")
            appendLine("线程：${thread.name}")
            appendLine()
            append(stackTrace)
        }
        File(directory, "crash-$stamp.txt").writeText(content)
        trim()
    }

    /** 只保留最近若干条，避免日志无限增长占用存储。 */
    private fun trim() {
        directory.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun versionName(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    private companion object {
        const val DIRECTORY = "crash"
        const val MAX_FILES = 10
    }
}
