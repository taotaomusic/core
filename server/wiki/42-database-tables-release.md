# 数据表:发布与公告

[返回文档中心](README.md)

最后更新:2026-10-01

Android 发布与公告域的表。发布操作流程见 [61-release-android.md](61-release-android.md)。

## 1. `app_release`

保存 APK 外链/文件名、大小、sha256、灰度比例、最低 SDK 和发布状态。

- `enabled` 是 `smallint` 0/1,**不能改成 PostgreSQL boolean**(全库布尔约定,见 [40-database-overview.md](40-database-overview.md))。
- `UNIQUE (channel, version_code)` 用于登记同版本时更新元数据。
- `apk_url`(2026-10-01 起构件上云):存发布构件外链(带 `DOWNLOAD_PROXY_PREFIX` 前缀的 GitHub Release 代理直链),为空回落本机 `/app/apk` 的 `apk_file`;`apk_file` 已放宽为可空,历史记录兼容,自然淘汰后再删。

## 2. `app_channel`

保存每个发布渠道的 Android `min_supported_version_code` 和更新时间。

- 抬高最低版本只影响该渠道的 Android 客户端。

## 3. `app_config`

保存按客户端版本范围生效的远程配置,随 `/app/bootstrap` 下发。

## 4. `app_announcement`

保存公告标题、正文、启用状态、置顶状态和发布时间。

- `enabled`/`pinned` 是 `smallint` 0/1。
- `idx_app_announcement_visible` 支持公开接口按置顶和发布时间读取。
- 置顶切换由 Repository 的**顾问锁**保证同一时间只有一条置顶公告。

## 5. `app_patch`

Android 补丁按 `(channel, target_version_code, patch_version)` 唯一,保存外链/文件名、文件大小、sha256、灰度比例、启用状态和说明。

- 补丁只对指定宿主 `target_version_code` 有效,**不能当成「高版本 APK」处理**。
- `patch_url`(构件上云):存补丁外链,为空回落本机 `/app/patch` 的 `patch_file`(`patch_file` 已放宽为可空)。
- 发布与放量流程见 [61-release-android.md](61-release-android.md)。
