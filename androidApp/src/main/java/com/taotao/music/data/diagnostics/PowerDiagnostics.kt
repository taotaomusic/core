package com.taotao.music.data.diagnostics

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 一条耗电采样。
 *
 * 只在电量、充电状态、亮屏、前后台或播放状态变化时落一条，不做定时轮询；
 * 因此记录本身不会成为新的耗电来源。采样只落在应用私有目录，不上传。
 */
data class PowerSample(
    val atMillis: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val appForeground: Boolean,
    val playing: Boolean,
)

/** 当前时刻的电池、电源管理和应用资源快照，用于诊断页展示。 */
data class PowerSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val statusLabel: String,
    val pluggedLabel: String,
    val healthLabel: String,
    val temperatureC: Float?,
    val voltageMv: Int?,
    val technology: String?,
    val powerSaveMode: Boolean,
    val screenOn: Boolean,
    val deviceIdleMode: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val lowRamDevice: Boolean,
    val standbyBucket: String,
    val appUptimeMs: Long,
    val cpuTimeMs: Long,
    val pssMb: Long?,
    val availableMemMb: Long,
    val rxBytes: Long?,
    val txBytes: Long?,
    val threadCount: Int,
    val versionName: String,
    val deviceLabel: String,
    val androidLabel: String,
)

/**
 * 一段时间内的耗电汇总。
 *
 * 时长按相邻采样之间的状态归属；耗电只统计「前后两条都不在充电」的放电区间。
 * 电量是整数百分比，样本太少时只能作为趋势参考。
 */
data class PowerSummary(
    val sampleCount: Int,
    val fromMillis: Long,
    val toMillis: Long,
    val startPercent: Int,
    val endPercent: Int,
    val dischargePercent: Int,
    val foregroundMs: Long,
    val backgroundMs: Long,
    val playbackMs: Long,
    val screenOnMs: Long,
    val foregroundDischargeMs: Long,
    val backgroundDischargeMs: Long,
    val playbackDischargeMs: Long,
    val foregroundDrop: Int,
    val backgroundDrop: Int,
    val playbackDrop: Int,
) {
    val foregroundDrainPerHour: Float?
        get() = drainPerHour(foregroundDrop, foregroundDischargeMs)

    val backgroundDrainPerHour: Float?
        get() = drainPerHour(backgroundDrop, backgroundDischargeMs)

    val playbackDrainPerHour: Float?
        get() = drainPerHour(playbackDrop, playbackDischargeMs)
}

/** 按状态拆分耗电区间；样本不足或时间跨度太短时对应速率返回 null。 */
fun summarizePowerSamples(samples: List<PowerSample>): PowerSummary? {
    val ordered = samples
        .filter { it.batteryPercent in 0..100 }
        .sortedBy(PowerSample::atMillis)
    if (ordered.size < 2) return null

    var foregroundMs = 0L
    var backgroundMs = 0L
    var playbackMs = 0L
    var screenOnMs = 0L
    var foregroundDischargeMs = 0L
    var backgroundDischargeMs = 0L
    var playbackDischargeMs = 0L
    var foregroundDrop = 0
    var backgroundDrop = 0
    var playbackDrop = 0

    for (index in 0 until ordered.lastIndex) {
        val current = ordered[index]
        val next = ordered[index + 1]
        val duration = (next.atMillis - current.atMillis).coerceAtLeast(0L)
        if (current.appForeground) foregroundMs += duration else backgroundMs += duration
        if (current.playing) playbackMs += duration
        if (current.screenOn) screenOnMs += duration
        if (current.charging || next.charging) continue

        if (current.appForeground) foregroundDischargeMs += duration else backgroundDischargeMs += duration
        if (current.playing) playbackDischargeMs += duration
        val drop = current.batteryPercent - next.batteryPercent
        if (drop > 0) {
            if (current.appForeground) foregroundDrop += drop else backgroundDrop += drop
            if (current.playing) playbackDrop += drop
        }
    }

    return PowerSummary(
        sampleCount = ordered.size,
        fromMillis = ordered.first().atMillis,
        toMillis = ordered.last().atMillis,
        startPercent = ordered.first().batteryPercent,
        endPercent = ordered.last().batteryPercent,
        dischargePercent = foregroundDrop + backgroundDrop,
        foregroundMs = foregroundMs,
        backgroundMs = backgroundMs,
        playbackMs = playbackMs,
        screenOnMs = screenOnMs,
        foregroundDischargeMs = foregroundDischargeMs,
        backgroundDischargeMs = backgroundDischargeMs,
        playbackDischargeMs = playbackDischargeMs,
        foregroundDrop = foregroundDrop,
        backgroundDrop = backgroundDrop,
        playbackDrop = playbackDrop,
    )
}

/** 耗电日志单条最短统计时长，低于半小时的样本不计算每小时速率。 */
private const val MIN_RATE_WINDOW_MS = 30 * 60_000L

private fun drainPerHour(drop: Int, dischargeMs: Long): Float? {
    if (drop <= 0 || dischargeMs < MIN_RATE_WINDOW_MS) return null
    return drop * 3_600_000f / dischargeMs
}

/**
 * 应用内耗电诊断采集器。
 *
 * Android 不允许普通应用读取其它应用的耗电排行，这里只记录桃桃音乐自身的
 * 电量、亮屏、前后台和播放状态，用于回答「后台是否在异常耗电」。
 *
 * 采集由系统广播驱动，不持有唤醒锁，也不启动后台服务或定时器。
 */
class PowerDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val directory = File(appContext.filesDir, DIRECTORY)
    private val file = File(directory, FILE_NAME)
    private val fileLock = Any()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "power-diagnostics").apply { isDaemon = true }
    }
    private val appVersionName: String by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "未知"
        }.getOrDefault("未知")
    }

    @Volatile
    private var appForeground = false

    @Volatile
    private var screenOn = powerManager?.isInteractive == true

    @Volatile
    private var playing = false

    @Volatile
    private var lastBattery = BatteryRead(-1, false)

    private var lastSignature: String? = null
    private var lastRecordedAt = 0L
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateBattery(intent)
                    record(force = true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    record()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    record()
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> record(force = true)
            }
        }
    }

    /** 注册系统广播；应在 Application.onCreate 中调用一次。 */
    fun install() {
        if (receiverRegistered) return
        screenOn = powerManager?.isInteractive ?: screenOn
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val registration = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        receiverRegistered = registration.isSuccess
        val sticky = registration.getOrNull()
        sticky?.let(::updateBattery)
        // 个别 ROM 在带 flag 注册时不回传 sticky Intent，补读一次电量，保证首条样本能落盘。
        if (lastBattery.percent < 0) readBatteryIntent()?.let(::updateBattery)
        record(force = true)
    }

    /** 应用回到前台。 */
    fun onAppForeground() {
        appForeground = true
        refreshScreenState()
        record()
    }

    /** 应用退到后台；后台播放时 [playing] 仍会单独记录。 */
    fun onAppBackground() {
        appForeground = false
        refreshScreenState()
        record()
    }

    /** 播放状态变化；由 AudioPlayer 的状态回调驱动，后台暂停也能记录。 */
    fun onPlaybackChanged(playing: Boolean) {
        this.playing = playing
        refreshScreenState()
        record()
    }

    /** 当前时刻的系统与应用资源快照。 */
    fun snapshot(): PowerSnapshot {
        val batteryIntent = readBatteryIntent()
        val battery = batteryIntent?.let(::readBattery) ?: lastBattery
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        val technology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }
        val memoryInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val systemMemory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val uid = Process.myUid()

        return PowerSnapshot(
            batteryPercent = battery.percent,
            charging = battery.charging,
            statusLabel = batteryStatusLabel(status),
            pluggedLabel = pluggedLabel(plugged),
            healthLabel = batteryHealthLabel(health),
            temperatureC = temperature,
            voltageMv = voltage,
            technology = technology,
            powerSaveMode = powerManager?.isPowerSaveMode == true,
            screenOn = powerManager?.isInteractive ?: screenOn,
            deviceIdleMode = powerManager?.isDeviceIdleMode == true,
            ignoringBatteryOptimizations = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activityManager?.isBackgroundRestricted == true
            } else {
                false
            },
            lowRamDevice = activityManager?.isLowRamDevice == true,
            standbyBucket = standbyBucketLabel(),
            appUptimeMs = appUptimeMs(),
            cpuTimeMs = Process.getElapsedCpuTime(),
            pssMb = memoryInfo.totalPss.takeIf { it > 0 }?.let { it / 1024L },
            availableMemMb = systemMemory.availMem / (1024L * 1024L),
            rxBytes = TrafficStatsCompat.uidRxBytes(uid),
            txBytes = TrafficStatsCompat.uidTxBytes(uid),
            threadCount = Thread.activeCount(),
            versionName = appVersionName,
            deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidLabel = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        )
    }

    /** 读取最近 [hours] 小时的历史采样，按时间正序。 */
    fun history(hours: Int = 24): List<PowerSample> {
        val since = System.currentTimeMillis() - hours.coerceAtLeast(1) * 3_600_000L
        if (!file.exists()) return emptyList()
        return synchronized(fileLock) {
            runCatching {
                file.readLines()
                    .mapNotNull(::parseSample)
                    .filter { it.atMillis >= since }
                    .sortedBy(PowerSample::atMillis)
            }.getOrDefault(emptyList())
        }
    }

    /** 清空历史；下一次状态变化会重新开始记录。 */
    fun clear() {
        lastSignature = null
        lastRecordedAt = 0L
        // 交给同一个写线程删除，避免把排队中的旧样本追加到刚清空的文件后面。
        writer.execute {
            synchronized(fileLock) {
                runCatching { file.delete() }
            }
        }
    }

    private fun record(force: Boolean = false) {
        val battery = lastBattery
        if (battery.percent < 0) return
        val now = System.currentTimeMillis()
        val signature = buildString {
            append(battery.percent)
            append('|').append(battery.charging)
            append('|').append(screenOn)
            append('|').append(appForeground)
            append('|').append(playing)
        }
        if (!force && signature == lastSignature && now - lastRecordedAt < 60_000L) return
        lastSignature = signature
        lastRecordedAt = now
        val sample = PowerSample(
            atMillis = now,
            batteryPercent = battery.percent,
            charging = battery.charging,
            screenOn = screenOn,
            appForeground = appForeground,
            playing = playing,
        )
        val line = JSONObject().apply {
            put("at", sample.atMillis)
            put("battery", sample.batteryPercent)
            put("charging", sample.charging)
            put("screen", sample.screenOn)
            put("foreground", sample.appForeground)
            put("playing", sample.playing)
        }.toString()
        writer.execute {
            synchronized(fileLock) {
                runCatching {
                    directory.mkdirs()
                    file.appendText(line + "\n", Charsets.UTF_8)
                    trimIfNeeded()
                }
            }
        }
    }

    /** 只按文件大小裁剪；正常使用一天几十条，距离上限很远。 */
    private fun trimIfNeeded() {
        if (file.length() <= MAX_FILE_BYTES) return
        val lines = file.readLines()
        if (lines.size <= MAX_SAMPLES) return
        file.writeText(lines.takeLast(MAX_SAMPLES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun updateBattery(intent: Intent) {
        lastBattery = readBattery(intent)
    }

    /** 亮屏广播在应用被缓存时可能延迟，前后台/播放变化时补读一次系统状态。 */
    private fun refreshScreenState() {
        screenOn = powerManager?.isInteractive ?: screenOn
    }

    private fun readBatteryIntent(): Intent? = runCatching {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun readBattery(intent: Intent): BatteryRead {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return BatteryRead(
            percent = percent.coerceIn(-1, 100),
            charging = plugged != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun standbyBucketLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "需要 Android 9+"
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return "未知"
        val bucket = runCatching { manager.appStandbyBucket }.getOrNull() ?: return "未知"
        return when (bucket) {
            // EXEMPTED / NEVER 是系统内部常量，公开 SDK 没导出；数值沿用 Android 官方分档。
            5 -> "豁免"
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "活跃"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "工作集"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "经常使用"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "很少使用"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "受限"
            50 -> "从未使用"
            else -> "未知（$bucket）"
        }
    }

    private fun appUptimeMs(): Long {
        val startedAt = Process.getStartElapsedRealtime()
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private fun parseSample(line: String): PowerSample? = runCatching {
        val json = JSONObject(line)
        PowerSample(
            atMillis = json.getLong("at"),
            batteryPercent = json.getInt("battery"),
            charging = json.getBoolean("charging"),
            screenOn = json.getBoolean("screen"),
            appForeground = json.getBoolean("foreground"),
            playing = json.getBoolean("playing"),
        )
    }.getOrNull()

    private data class BatteryRead(val percent: Int, val charging: Boolean)

    private companion object {
        const val DIRECTORY = "diagnostics"
        const val FILE_NAME = "power-samples.jsonl"
        const val MAX_FILE_BYTES = 512 * 1024L
        const val MAX_SAMPLES = 2_000
    }
}

/** 集中封装 TrafficStats，避免诊断页在每个取值点重复判空。 */
private object TrafficStatsCompat {
    fun uidRxBytes(uid: Int): Long? = android.net.TrafficStats.getUidRxBytes(uid)
        .takeIf { it != android.net.TrafficStats.UNSUPPORTED.toLong() }

    fun uidTxBytes(uid: Int): Long? = android.net.TrafficStats.getUidTxBytes(uid)
        .takeIf { it != android.net.TrafficStats.UNSUPPORTED.toLong() }
}

private fun batteryStatusLabel(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
    BatteryManager.BATTERY_STATUS_FULL -> "已充满"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
    else -> "未知"
}

private fun pluggedLabel(plugged: Int): String = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "交流充电器"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
    else -> "未连接"
}

private fun batteryHealthLabel(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
    BatteryManager.BATTERY_HEALTH_DEAD -> "失效"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
    BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "未知故障"
    else -> "未知"
}
