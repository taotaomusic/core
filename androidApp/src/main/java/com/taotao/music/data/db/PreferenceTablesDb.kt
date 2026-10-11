package com.taotao.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** 播放与下载音质偏好（原 playback_quality 文件）。恒定单行。 */
@Entity(tableName = "quality_preference")
data class QualityRow(
    @PrimaryKey val id: Int,
    val playback: Int,
    val download: Int,
)

/** 外观模式（原 appearance 文件）；null 即跟随系统。恒定单行。 */
@Entity(tableName = "appearance_preference")
data class AppearanceRow(
    @PrimaryKey val id: Int,
    val mode: String?,
)

/** 封面皮肤偏好（原 cover_skin 文件）；null 即默认皮肤。恒定单行。 */
@Entity(tableName = "cover_skin_preference")
data class CoverSkinRow(
    @PrimaryKey val id: Int,
    val skin: String?,
)

/** 定时关闭面板记忆（原 sleep_timer 文件）。恒定单行。 */
@Entity(tableName = "sleep_timer_preference")
data class SleepTimerRow(
    @PrimaryKey val id: Int,
    val lastMinutes: Int,
    val waitForSongEnd: Boolean,
)

/** 用户划掉的可选更新版本（原 app_update 文件）。恒定单行。 */
@Entity(tableName = "update_dismissal")
data class UpdateDismissalRow(
    @PrimaryKey val id: Int,
    val dismissedVersionCode: Long,
)

/** 匿名设备标识（原 device 文件）。恒定单行。 */
@Entity(tableName = "device_identity")
data class DeviceIdentityRow(
    @PrimaryKey val id: Int,
    val deviceId: String,
)

/** 六张单行偏好表共用的常量主键。 */
const val PREFERENCE_ROW_ID = 1

/**
 * 单行偏好表的共用 DAO：每张表只有「读单行」和「整行 upsert」两个动作，
 * 镜像策略（内存优先 + 写后落库）由各存储类经 [MirroredPreference] 实现。
 */
@Dao
interface PreferenceDao {

    @Query("SELECT * FROM quality_preference WHERE id = 1 LIMIT 1")
    fun getQuality(): QualityRow?

    @Upsert
    fun upsertQuality(row: QualityRow)

    @Query("SELECT * FROM appearance_preference WHERE id = 1 LIMIT 1")
    fun getAppearance(): AppearanceRow?

    @Upsert
    fun upsertAppearance(row: AppearanceRow)

    @Query("SELECT * FROM cover_skin_preference WHERE id = 1 LIMIT 1")
    fun getCoverSkin(): CoverSkinRow?

    @Upsert
    fun upsertCoverSkin(row: CoverSkinRow)

    @Query("SELECT * FROM sleep_timer_preference WHERE id = 1 LIMIT 1")
    fun getSleepTimer(): SleepTimerRow?

    @Upsert
    fun upsertSleepTimer(row: SleepTimerRow)

    @Query("SELECT * FROM update_dismissal WHERE id = 1 LIMIT 1")
    fun getUpdateDismissal(): UpdateDismissalRow?

    @Upsert
    fun upsertUpdateDismissal(row: UpdateDismissalRow)

    @Query("SELECT * FROM device_identity WHERE id = 1 LIMIT 1")
    fun getDeviceIdentity(): DeviceIdentityRow?

    @Upsert
    fun upsertDeviceIdentity(row: DeviceIdentityRow)
}
