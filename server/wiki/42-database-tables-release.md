# 数据表:发布、公告与桌面发布

[返回文档中心](README.md)

最后更新:2026-09-27

Android 发布、桌面发布与公告域的表。发布操作流程见 [61-release-android.md](61-release-android.md) 与 [52-feature-desktop-release.md](52-feature-desktop-release.md)。

## 1. `app_release`

保存 APK 文件名、大小、sha256、灰度比例、最低 SDK 和发布状态。

- `enabled` 是 `smallint` 0/1,**不能改成 PostgreSQL boolean**(全库布尔约定,见 [40-database-overview.md](40-database-overview.md))。
- `UNIQUE (channel, version_code)` 用于登记同版本时更新元数据。

## 2. `app_channel`

保存每个发布渠道的 Android `min_supported_version_code`、桌面 `desktop_min_supported_version_code` 和更新时间。

- 两端最低版本**独立判断**,不能混用:Android 用 `min_supported_version_code`,桌面用 `desktop_min_supported_version_code`。
- 抬高一端不会改变另一端。

## 3. `app_config`

保存按客户端版本范围生效的远程配置,随 `/app/bootstrap` 下发。

## 4. `app_announcement`

保存公告标题、正文、启用状态、置顶状态和发布时间。

- `enabled`/`pinned` 是 `smallint` 0/1。
- `idx_app_announcement_visible` 支持公开接口按置顶和发布时间读取。
- 置顶切换由 Repository 的**顾问锁**保证同一时间只有一条置顶公告。

## 5. `app_patch`

Android 补丁按 `(channel, target_version_code, patch_version)` 唯一,保存文件大小、sha256、灰度比例、启用状态和说明。

- 补丁只对指定宿主 `target_version_code` 有效,**不能当成「高版本 APK」处理**。
- 发布与放量流程见 [61-release-android.md](61-release-android.md)。

## 6. `desktop_release`、`desktop_jar`、`desktop_patch`

- `desktop_release` 以 `(channel, architecture, version_code)` 唯一,保存入口、灰度、启用和说明。
- `desktop_jar` 保存安装目录相对路径、分类、内容寻址对象名、大小和 sha256;同一发布内路径唯一。
- `desktop_patch` 保存来源版本、路径、源/目标 sha256、算法(`courgette`/`bsdiff`)、对象、大小和启用状态;`(release_id, from_version_code, path, algorithm)` 唯一。

要点:

- 桌面发布与 Android 发布**不能共用 versionCode 表或最低版本字段**;两者共享管理员会话、灰度哈希和 `app_config`。
- `desktop_jar`/`desktop_patch` 随 `desktop_release` 级联删除,但**磁盘内容寻址文件不会自动回收**(旧客户端可能仍在下载),清理流程见 [52-feature-desktop-release.md](52-feature-desktop-release.md)。
- 差分按 `fromSha256` 匹配上一版本同路径文件;sha256 不匹配时客户端只能拿完整模块。
