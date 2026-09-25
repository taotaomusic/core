import type { Pool } from "pg";

/**
 * 顾问锁的键，任意常量，只要全库唯一即可。
 * 多个实例同时启动时用它把建表串行化。
 */
const MIGRATION_LOCK_KEY = 913_720_001;

/**
 * 全部建表语句。
 *
 * 由 [DatabaseService] 在 onModuleInit 显式调用，不做成模块导入时的副作用 ——
 * Nest 的依赖注入不保证导入时机。
 *
 * 类型选择上有三条规则，每条都对应一处会出错的代码：
 *
 * - 存 `Date.now()` 的列一律 `bigint`：毫秒时间戳约 1.7e12，超出 int4 的 21 亿上限
 * - 版本号、大小、百分比一律 `integer`：int8 会被 pg 解析成字符串（见 database.service.ts
 *   里的类型解析器注册），`apk_size` 变成字符串会让客户端的下载进度和 416 判断出错
 * - `enabled` 是 `smallint` 0/1 而**不是 boolean**：`release.service.ts` 里写的是
 *   `item.enabled === 1`（严格等于数字），`release.controller.ts` 里是真值判断，
 *   改成 boolean 会让前者恒为 false，抬高最低可用版本的守卫就永远返回 409
 */
export async function runMigrations(pool: Pool): Promise<void> {
  const client = await pool.connect();
  try {
    // PostgreSQL 的 DDL 是事务性的，整组建表要么全成要么全不成。
    // 先取顾问锁：并发执行 CREATE TABLE IF NOT EXISTS 有已知的 pg_type 唯一键冲突。
    await client.query("BEGIN");
    await client.query("SELECT pg_advisory_xact_lock($1)", [MIGRATION_LOCK_KEY]);
    await client.query(`
      CREATE TABLE IF NOT EXISTS users (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        username      text NOT NULL UNIQUE,
        password_hash text NOT NULL,
        password_salt text NOT NULL,
        created_at    timestamptz NOT NULL DEFAULT now()
      );

      -- 旧账号没有邮箱也仍可登录；新注册账号必须在插入时写入已验证邮箱。
      ALTER TABLE users ADD COLUMN IF NOT EXISTS email text;
      CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_unique
        ON users (email) WHERE email IS NOT NULL;
      ALTER TABLE users ADD COLUMN IF NOT EXISTS nickname text;
      ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url text;
      -- 禁用不是删除：保留账号与统计以便后台核查，同时鉴权查询会排除该用户并撤销刷新令牌。
      ALTER TABLE users ADD COLUMN IF NOT EXISTS disabled_at bigint;
      -- 对外 IM UID 不能复用递增主键。历史账号在第一次申请聊天会话时懒生成，避免升级迁移
      -- 一次扫描全部用户；唯一索引既挡住随机碰撞，也避免并发首次登录产生两个身份。
      ALTER TABLE users ADD COLUMN IF NOT EXISTS im_uid text;
      CREATE UNIQUE INDEX IF NOT EXISTS idx_users_im_uid_unique
        ON users (im_uid) WHERE im_uid IS NOT NULL;

      CREATE TABLE IF NOT EXISTS refresh_tokens (
        id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        user_id    integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash text NOT NULL UNIQUE,
        expires_at bigint NOT NULL,
        created_at bigint NOT NULL,
        revoked_at bigint
      );

      CREATE TABLE IF NOT EXISTS favorites (
        id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        user_id    integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source     text NOT NULL,
        song_id    text NOT NULL,
        created_at bigint NOT NULL,
        UNIQUE (user_id, source, song_id)
      );
      CREATE INDEX IF NOT EXISTS idx_favorites_user ON favorites (user_id, created_at DESC);

      -- created_at 永远表示第一次收藏时间，取消收藏和重新收藏都不能覆盖它。
      -- 取消收藏使用软删除，避免离线设备同步时丢失历史状态，也为后续增量同步保留版本依据。
      ALTER TABLE favorites ADD COLUMN IF NOT EXISTS is_favorite smallint NOT NULL DEFAULT 1;
      ALTER TABLE favorites ADD COLUMN IF NOT EXISTS favorited_at bigint;
      ALTER TABLE favorites ADD COLUMN IF NOT EXISTS updated_at bigint;
      ALTER TABLE favorites ADD COLUMN IF NOT EXISTS deleted_at bigint;
      ALTER TABLE favorites ADD COLUMN IF NOT EXISTS revision integer NOT NULL DEFAULT 1;
      ALTER TABLE favorites ADD COLUMN IF NOT EXISTS mutation_id text;
      UPDATE favorites SET favorited_at = created_at WHERE favorited_at IS NULL;
      UPDATE favorites SET updated_at = created_at WHERE updated_at IS NULL;
      ALTER TABLE favorites ALTER COLUMN favorited_at SET NOT NULL;
      ALTER TABLE favorites ALTER COLUMN updated_at SET NOT NULL;
      DO $$
      BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorites_is_favorite_check') THEN
          ALTER TABLE favorites ADD CONSTRAINT favorites_is_favorite_check CHECK (is_favorite IN (0, 1));
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'favorites_revision_check') THEN
          ALTER TABLE favorites ADD CONSTRAINT favorites_revision_check CHECK (revision > 0);
        END IF;
      END
      $$;
      CREATE INDEX IF NOT EXISTS idx_favorites_active_user
        ON favorites (user_id, favorited_at DESC) WHERE is_favorite = 1;

      -- 用户云端歌单。歌单属于账号，删除账号时级联清理；歌曲只保存稳定身份和一份
      -- 元信息快照，客户端换设备后可以先直接展示，再按 source + song_id 向音乐接口补全。
      -- position 使用唯一约束保证同一歌单内不会出现两个相同位置，所有重排都在事务中完成。
      CREATE TABLE IF NOT EXISTS playlists (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        user_id       integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        name          text NOT NULL,
        description   text NOT NULL DEFAULT '',
        cover_url     text,
        revision      integer NOT NULL DEFAULT 1 CHECK (revision > 0),
        created_at    bigint NOT NULL,
        updated_at    bigint NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_playlists_user_updated
        ON playlists (user_id, updated_at DESC, id DESC);

      CREATE TABLE IF NOT EXISTS playlist_songs (
        playlist_id    integer NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
        source         text NOT NULL,
        song_id        text NOT NULL,
        mid            text,
        title          text NOT NULL DEFAULT '',
        artist         text NOT NULL DEFAULT '',
        album          text NOT NULL DEFAULT '',
        cover_url      text,
        duration       text,
        audio_url      text,
        lyric_url      text,
        song_type      integer,
        position       integer NOT NULL CHECK (position >= 0),
        added_at       bigint NOT NULL,
        updated_at     bigint NOT NULL,
        PRIMARY KEY (playlist_id, source, song_id),
        UNIQUE (playlist_id, position)
      );
      CREATE INDEX IF NOT EXISTS idx_playlist_songs_order
        ON playlist_songs (playlist_id, position);

      -- 歌曲分享短链。只保存稳定歌曲身份和元数据快照；上游直链有时效，绝不能落库。
      -- 试听**不在服务端裁剪、也不落盘**：只把上游的完整标准音频转发出去，
      -- 「最多 60 秒」由分享页自己守（webApp 的 WebAudioController）。
      -- 所以这里没有任何音频文件字段 —— 别再往这张表加「缓存路径」。
      CREATE TABLE IF NOT EXISTS song_share (
        token            text PRIMARY KEY CHECK (token ~ '^[A-Za-z0-9_-]{8,24}$'),
        user_id          integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source           text NOT NULL CHECK (source IN ('tencent', 'netease', 'kuwo')),
        song_id          text NOT NULL,
        remote_id        text,
        mid              text,
        song_type        integer,
        title            text NOT NULL,
        artist           text NOT NULL,
        album            text NOT NULL DEFAULT '',
        cover_url        text,
        duration_seconds integer NOT NULL DEFAULT 0 CHECK (duration_seconds >= 0),
        vip              smallint NOT NULL DEFAULT 0 CHECK (vip IN (0, 1)),
        enabled          smallint NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
        access_count     integer NOT NULL DEFAULT 0 CHECK (access_count >= 0),
        created_at       bigint NOT NULL,
        updated_at       bigint NOT NULL,
        UNIQUE (user_id, source, song_id)
      );
      CREATE INDEX IF NOT EXISTS idx_song_share_lookup ON song_share (source, song_id, enabled);

      -- 旧库里的 preview_file 存的是「服务端裁出的 60 秒试听文件」文件名。裁剪取消后它恒为 NULL，
      -- 留着只会让人以为这张表还在缓存音频，因此显式删掉；新建库的建表语句里已经没有它。
      -- 顺带一句：data/share-preview 目录下遗留的文件可以手工清掉，服务端不再读它。
      ALTER TABLE song_share DROP COLUMN IF EXISTS preview_file;

      -- 放行酷我分享。上面 CREATE TABLE 里的 CHECK 只对新建库生效，已有库里的旧约束
      -- 必须显式换掉 —— 否则客户端分享一首酷我的歌会在写库时被约束拦下，表现为
      -- 一个看不懂的 502，而分享接口本身完全正常。
      --
      -- 条件换约束而不是每次启动都 DROP + ADD：后者会在启动瞬间留下一个没有约束的窗口，
      -- 且每次都全表校验一遍。这里只在旧定义的确实不含 kuwo 时才重写。
      DO $$
      BEGIN
        IF EXISTS (
          SELECT 1 FROM pg_constraint
          WHERE conname = 'song_share_source_check'
            AND pg_get_constraintdef(oid) NOT LIKE '%kuwo%'
        ) THEN
          ALTER TABLE song_share DROP CONSTRAINT song_share_source_check;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'song_share_source_check') THEN
          ALTER TABLE song_share
            ADD CONSTRAINT song_share_source_check CHECK (source IN ('tencent', 'netease', 'kuwo'));
        END IF;
      END
      $$;

      -- 播放会话由客户端生成稳定 session_id，并以累计快照重复上报。服务端只累加相对旧快照
      -- 增长的部分，所以断线重传、超时重试都不会重复增加听歌时间或播放次数。
      CREATE TABLE IF NOT EXISTS playback_sessions (
        user_id          integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        session_id       text NOT NULL,
        device_id        text NOT NULL,
        source           text NOT NULL,
        song_id          text NOT NULL,
        started_at       bigint NOT NULL,
        last_played_at   bigint NOT NULL,
        listened_ms      integer NOT NULL DEFAULT 0 CHECK (listened_ms >= 0 AND listened_ms <= 86400000),
        qualified        smallint NOT NULL DEFAULT 0 CHECK (qualified IN (0, 1)),
        completed        smallint NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
        duration_seconds integer CHECK (duration_seconds > 0),
        -- 客户端创建会话时已知的清空代际。首次写入后不可变，避免旧离线会话在稍后
        -- 收到新 revision 时重新出现在已清空的最近播放列表。
        history_revision integer NOT NULL DEFAULT 0 CHECK (history_revision >= 0),
        created_at       bigint NOT NULL,
        updated_at       bigint NOT NULL,
        PRIMARY KEY (user_id, session_id)
      );
      -- 单曲倒带日记按「用户×渠道×歌曲」查会话流水（时间窗口计数、单日峰值、播放记录），
      -- 主键 (user_id, session_id) 覆盖不到，补一条组合索引避免全表扫描。
      CREATE INDEX IF NOT EXISTS idx_playback_sessions_song
        ON playback_sessions (user_id, source, song_id, started_at DESC);

      -- 最近播放和统计都从按歌汇总读取，避免每次打开页面扫描全部会话。
      -- last_history_at 只有单次会话听满 3 秒才更新，误触后立刻切歌不会顶到列表最前。
      CREATE TABLE IF NOT EXISTS user_song_stats (
        user_id            integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        source             text NOT NULL,
        song_id            text NOT NULL,
        first_played_at    bigint NOT NULL,
        last_played_at     bigint NOT NULL,
        last_history_at    bigint,
        -- last_history_at 所属的清空代际。列表以此与 history_state.revision 比较，
        -- 不能再以客户端墙钟和清空时间戳比较。
        last_history_revision integer NOT NULL DEFAULT 0 CHECK (last_history_revision >= 0),
        play_count         integer NOT NULL DEFAULT 0 CHECK (play_count >= 0),
        completed_count    integer NOT NULL DEFAULT 0 CHECK (completed_count >= 0),
        total_listened_ms  bigint NOT NULL DEFAULT 0 CHECK (total_listened_ms >= 0),
        updated_at          bigint NOT NULL,
        PRIMARY KEY (user_id, source, song_id)
      );
      CREATE INDEX IF NOT EXISTS idx_user_song_stats_recent
        ON user_song_stats (user_id, last_history_at DESC, source, song_id)
        WHERE last_history_at IS NOT NULL;

      -- 清空最近播放只推进可见边界，累计时长和次数仍保留；离线设备补传旧会话也不会复活列表。
      CREATE TABLE IF NOT EXISTS playback_history_state (
        user_id        integer PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        cleared_before bigint NOT NULL DEFAULT 0,
        revision       integer NOT NULL DEFAULT 1 CHECK (revision > 0),
        updated_at     bigint NOT NULL,
        -- 清空请求的幂等标识。相同 marker 的响应重试必须返回同一 revision，不能再清一次。
        clear_marker   text
      );
      -- 每个 marker 都要保留一条幂等结果，不能只记 history_state 上最新的 marker。
      -- 否则设备 A 的清空响应丢失、设备 B 随后清空后，A 重试会错误地推进第三个版本。
      CREATE TABLE IF NOT EXISTS playback_history_clear_operation (
        user_id        integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        marker         text NOT NULL,
        revision       integer NOT NULL CHECK (revision > 0),
        cleared_before bigint NOT NULL,
        cleared_at     bigint NOT NULL,
        PRIMARY KEY (user_id, marker)
      );

      -- 线上旧库可能已经有上述两张表。新增字段默认第 0 代，正好表示历史上尚未发生
      -- 过可同步清空；以下回填把旧时间边界仍可见的记录迁到当前 revision，避免升级服务
      -- 端后把此前清空之后的记录误隐藏。
      ALTER TABLE playback_sessions
        ADD COLUMN IF NOT EXISTS history_revision integer NOT NULL DEFAULT 0;
      ALTER TABLE user_song_stats
        ADD COLUMN IF NOT EXISTS last_history_revision integer NOT NULL DEFAULT 0;
      ALTER TABLE playback_history_state
        ADD COLUMN IF NOT EXISTS clear_marker text;
      DO $$
      BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'playback_sessions_history_revision_check') THEN
          ALTER TABLE playback_sessions
            ADD CONSTRAINT playback_sessions_history_revision_check CHECK (history_revision >= 0);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_song_stats_last_history_revision_check') THEN
          ALTER TABLE user_song_stats
            ADD CONSTRAINT user_song_stats_last_history_revision_check CHECK (last_history_revision >= 0);
        END IF;
      END
      $$;
      UPDATE playback_sessions session
      SET history_revision = state.revision
      FROM playback_history_state state
      WHERE state.user_id = session.user_id
        AND session.history_revision = 0
        AND session.last_played_at > state.cleared_before;
      UPDATE user_song_stats stats
      SET last_history_revision = state.revision
      FROM playback_history_state state
      WHERE state.user_id = stats.user_id
        AND stats.last_history_at IS NOT NULL
        AND stats.last_history_revision = 0
        AND stats.last_history_at > state.cleared_before;

      CREATE TABLE IF NOT EXISTS app_release (
        id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel         text NOT NULL DEFAULT 'release',
        version_code    integer NOT NULL,
        version_name    text NOT NULL,
        apk_file        text NOT NULL,
        apk_size        integer NOT NULL,
        apk_sha256      text NOT NULL,
        release_note    text NOT NULL DEFAULT '',
        rollout_percent integer NOT NULL DEFAULT 0,
        min_sdk         integer NOT NULL DEFAULT 24,
        enabled         smallint NOT NULL DEFAULT 1,
        published_at    bigint NOT NULL,
        UNIQUE (channel, version_code)
      );
      CREATE INDEX IF NOT EXISTS idx_app_release_lookup
        ON app_release (channel, enabled, version_code DESC);

      CREATE TABLE IF NOT EXISTS app_channel (
        channel                    text PRIMARY KEY,
        min_supported_version_code integer NOT NULL DEFAULT 0,
        updated_at                 bigint NOT NULL
      );

      CREATE TABLE IF NOT EXISTS app_config (
        key              text PRIMARY KEY,
        value            text NOT NULL,
        min_version_code integer,
        max_version_code integer,
        updated_at       bigint NOT NULL
      );

      -- 公告独立于热更新配置：公告需要保留历史、支持上下线，不适合塞进单值键值表。
      CREATE TABLE IF NOT EXISTS app_announcement (
        id           integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        title        text NOT NULL,
        content      text NOT NULL,
        enabled      smallint NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
        published_at bigint NOT NULL,
        updated_at   bigint NOT NULL
      );
      ALTER TABLE app_announcement ADD COLUMN IF NOT EXISTS pinned smallint NOT NULL DEFAULT 0;
      CREATE INDEX IF NOT EXISTS idx_app_announcement_visible
        ON app_announcement (enabled, pinned DESC, published_at DESC);

      CREATE TABLE IF NOT EXISTS api_key (
        id      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel text NOT NULL,
        key     text NOT NULL CHECK (btrim(key) <> ''),
        quota   integer NOT NULL DEFAULT 0 CHECK (quota >= 0),
        UNIQUE (channel, key)
      );
      CREATE INDEX IF NOT EXISTS idx_api_key_available ON api_key (channel, quota DESC);

      CREATE TABLE IF NOT EXISTS image_generation_task (
        task_id        text PRIMARY KEY,
        prompt         text NOT NULL,
        consumed_quota integer NOT NULL CHECK (consumed_quota > 0),
        channel        text NOT NULL,
        state          text NOT NULL DEFAULT 'IN_PROGRESS'
                       CHECK (state IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
        completed      smallint NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
        image_url      text,
        api_key_id     integer NOT NULL REFERENCES api_key(id) ON DELETE RESTRICT
      );
      CREATE INDEX IF NOT EXISTS idx_image_generation_task_key ON image_generation_task (api_key_id);

      -- 音源账号。第三方音源在需要登录态时才给出完整音质与曲库，凭据由管理后台维护，
      -- 播放链路按 source 取用。
      --
      -- token 与 uid 是账号凭据，**只写不读**：管理接口一律只回传掩码，
      -- 明文既不进响应体也不进审计表。
      --
      -- 「enabled」按本文件的既有约定用 smallint 0/1 而不是 boolean。
      CREATE TABLE IF NOT EXISTS music_source_account (
        id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        source          text NOT NULL CHECK (source ~ '^[a-z0-9_-]{2,32}$'),
        label           text NOT NULL DEFAULT '',
        phone           text NOT NULL DEFAULT '',
        token           text NOT NULL DEFAULT '',
        uid             text NOT NULL DEFAULT '',
        enabled         smallint NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
        remark          text NOT NULL DEFAULT '',
        last_status     text NOT NULL DEFAULT 'unknown'
                        CHECK (last_status IN ('unknown', 'ok', 'invalid')),
        last_error      text NOT NULL DEFAULT '',
        -- 上一次连通性测试探测到的真实音质，例如「mp3 128kbps」。落库是为了让
        -- 管理员一眼看出「这个账号有没有真的换来更高音质」—— 上游不会因为
        -- 请求了无损就给无损，只测「通不通」是看不出来的。
        last_note       text NOT NULL DEFAULT '',
        last_checked_at bigint,
        created_at      bigint NOT NULL,
        updated_at      bigint NOT NULL
      );
      -- 同一音源下同一份登录凭据只应存在一条。uid 为空表示尚未登录的占位记录，
      -- 用部分索引跳过它，否则多条空 uid 会互相冲突。
      CREATE UNIQUE INDEX IF NOT EXISTS idx_music_source_account_identity
        ON music_source_account (source, uid) WHERE uid <> '';
      CREATE INDEX IF NOT EXISTS idx_music_source_account_enabled
        ON music_source_account (source, enabled, id);

      -- 悟空 IM 的连接凭据。消息正文和同步游标由悟空 IM 保存；本库只保存业务账号与
      -- 设备凭据的可撤销映射。悟空 IM 当前按 device_flag 管理同类设备 Token，因此一名
      -- 用户同一类设备只保留最新一份凭据，新的 Android 登录会使旧 Android 设备重连失败。
      CREATE TABLE IF NOT EXISTS im_device_session (
        user_id        integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        device_flag    integer NOT NULL,
        device_id_hash text NOT NULL,
        token_hash     text NOT NULL,
        expires_at     bigint NOT NULL,
        revoked_at     bigint,
        created_at     bigint NOT NULL,
        updated_at     bigint NOT NULL,
        PRIMARY KEY (user_id, device_flag)
      );
      CREATE INDEX IF NOT EXISTS idx_im_device_session_active
        ON im_device_session (expires_at) WHERE revoked_at IS NULL;

      -- 代码热修复补丁。与 app_release 并列而不是复用它：
      -- 补丁只对**某一个** versionCode 的宿主有效（方法签名是按那份代码生成的），
      -- 而发布记录是"版本号高于你就能装"，两者的匹配语义正好相反。
      CREATE TABLE IF NOT EXISTS app_patch (
        id                  integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel             text NOT NULL DEFAULT 'release',
        target_version_code integer NOT NULL,
        patch_version       integer NOT NULL,
        patch_file          text NOT NULL,
        patch_size          integer NOT NULL,
        patch_sha256        text NOT NULL,
        note                text NOT NULL DEFAULT '',
        rollout_percent     integer NOT NULL DEFAULT 0,
        enabled             smallint NOT NULL DEFAULT 1,
        published_at        bigint NOT NULL,
        UNIQUE (channel, target_version_code, patch_version)
      );
      CREATE INDEX IF NOT EXISTS idx_app_patch_lookup
        ON app_patch (channel, target_version_code, enabled, patch_version DESC);

      -- Windows 桌面版发布。与 Android 发布表分开，避免两端相同的 versionCode
      -- 互相覆盖；远程配置仍共用 app_config，渠道行也继续放在 app_channel。
      CREATE TABLE IF NOT EXISTS desktop_release (
        id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        channel         text NOT NULL DEFAULT 'release',
        architecture    text NOT NULL DEFAULT 'windows-x64',
        version_code    integer NOT NULL,
        version_name    text NOT NULL,
        entrypoint      text NOT NULL,
        release_note    text NOT NULL DEFAULT '',
        rollout_percent integer NOT NULL DEFAULT 0 CHECK (rollout_percent BETWEEN 0 AND 100),
        enabled         smallint NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
        published_at    bigint NOT NULL,
        UNIQUE (channel, architecture, version_code)
      );
      CREATE INDEX IF NOT EXISTS idx_desktop_release_lookup
        ON desktop_release (channel, architecture, enabled, version_code DESC);

      -- 一个桌面版本由若干可独立替换的 JAR 组成。artifact_file 使用内容哈希命名，
      -- 相同依赖跨版本只在磁盘保存一份，清单中的 path 则保留安装目录相对路径。
      CREATE TABLE IF NOT EXISTS desktop_jar (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        release_id    integer NOT NULL REFERENCES desktop_release(id) ON DELETE CASCADE,
        path          text NOT NULL,
        category      text NOT NULL DEFAULT 'app',
        artifact_file text NOT NULL,
        size          integer NOT NULL CHECK (size > 0),
        sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
        published_at  bigint NOT NULL,
        UNIQUE (release_id, path)
      );
      CREATE INDEX IF NOT EXISTS idx_desktop_jar_sha256 ON desktop_jar (sha256);

      -- JAR 的预计算二进制差分。目标版本与模块路径由 release_id + path 唯一定位；
      -- from_sha256 让客户端只在本地文件完全匹配时应用补丁，否则回退下载完整 JAR。
      CREATE TABLE IF NOT EXISTS desktop_patch (
        id                integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        release_id        integer NOT NULL REFERENCES desktop_release(id) ON DELETE CASCADE,
        from_version_code integer NOT NULL,
        path              text NOT NULL,
        from_sha256       text NOT NULL CHECK (from_sha256 ~ '^[0-9a-f]{64}$'),
        to_sha256         text NOT NULL CHECK (to_sha256 ~ '^[0-9a-f]{64}$'),
        algorithm         text NOT NULL DEFAULT 'bsdiff',
        patch_file        text NOT NULL,
        size              integer NOT NULL CHECK (size > 0),
        sha256            text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
        enabled           smallint NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
        published_at      bigint NOT NULL,
        UNIQUE (release_id, from_version_code, path, algorithm)
      );
      CREATE INDEX IF NOT EXISTS idx_desktop_patch_manifest
        ON desktop_patch (release_id, enabled, path, from_version_code DESC);
      CREATE INDEX IF NOT EXISTS idx_desktop_patch_sha256 ON desktop_patch (sha256);

      -- Android 与 Windows 共用同一条渠道记录，但强制更新下限必须分平台保存；
      -- 若共用 min_supported_version_code，单独抬高任一平台都会让另一端无包可救。
      ALTER TABLE app_channel
        ADD COLUMN IF NOT EXISTS desktop_min_supported_version_code integer NOT NULL DEFAULT 0;

      -- ========== 管理后台企业级认证 ==========

      -- 管理员账号（独立于普通 users 表）
      CREATE TABLE IF NOT EXISTS admin_users (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        username      text NOT NULL UNIQUE,
        password_hash text NOT NULL,
        password_salt text NOT NULL,
        display_name  text NOT NULL DEFAULT '',
        email         text,
        role          text NOT NULL DEFAULT 'viewer'
                      CHECK (role IN ('super_admin', 'admin', 'viewer')),
        totp_secret   text,
        totp_enabled  smallint NOT NULL DEFAULT 0 CHECK (totp_enabled IN (0, 1)),
        ip_whitelist  text,
        last_login_at bigint,
        last_login_ip text,
        disabled_at   bigint,
        created_at    timestamptz NOT NULL DEFAULT now(),
        created_by    integer REFERENCES admin_users(id) ON DELETE SET NULL
      );

      -- 首次登录是否必须改密。
      --
      -- 只有「服务启动时自动创建的默认管理员」会置 1，存量账号与 LDAP 同步
      -- 创建的账号都保持 0 —— 否则升级一次就会把所有管理员挡在改密页。
      -- smallint 0/1 是本项目既有的布尔约定（与 totp_enabled 一致）。
      ALTER TABLE admin_users
        ADD COLUMN IF NOT EXISTS must_change_password smallint NOT NULL DEFAULT 0
          CHECK (must_change_password IN (0, 1));

      -- 认证来源：local（本地口令）/ ldap（由目录接管）。
      --
      -- 用途是「目录不可达时，已由 LDAP 接管的账号禁止回落本地口令」：
      -- 否则目录判定某账号已停用/离职后，只要它在本地残留过口令哈希，
      -- 目录一挂就能用旧口令登进来。
      --
      -- 存量 LDAP 账号无法从数据反推（它们存的是随机占位哈希），只能在
      -- 下次成功 LDAP 登录时补齐标记 —— 这是一次性的迁移代价。
      ALTER TABLE admin_users
        ADD COLUMN IF NOT EXISTS auth_source text NOT NULL DEFAULT 'local'
          CHECK (auth_source IN ('local', 'ldap'));

      -- 管理员会话
      CREATE TABLE IF NOT EXISTS admin_sessions (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        admin_id      integer NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
        token_hash    text NOT NULL UNIQUE,
        expires_at    bigint NOT NULL,
        created_at    bigint NOT NULL,
        revoked_at    bigint,
        login_ip      text,
        user_agent    text
      );
      CREATE INDEX IF NOT EXISTS idx_admin_sessions_active
        ON admin_sessions (admin_id, expires_at) WHERE revoked_at IS NULL;

      -- 操作审计日志。
      --
      -- admin_id 可空且 ON DELETE SET NULL：管理员被删除后，历史审计必须保留，
      -- 只是变成「无归属」。可空也覆盖了「调用方没带出管理员身份」的边界情况，
      -- 写具体 ID 时若目标行不存在会直接撞外键。
      CREATE TABLE IF NOT EXISTS admin_audit_log (
        id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        admin_id      integer REFERENCES admin_users(id) ON DELETE SET NULL,
        action        text NOT NULL,
        target_type   text,
        target_id     text,
        detail        text,
        ip_address    text,
        user_agent    text,
        created_at    bigint NOT NULL
      );
      CREATE INDEX IF NOT EXISTS idx_admin_audit_admin
        ON admin_audit_log (admin_id, created_at DESC);
      CREATE INDEX IF NOT EXISTS idx_admin_audit_action
        ON admin_audit_log (action, created_at DESC);

      -- 上面两张表在更早的版本里建成了 NOT NULL + 无 ON DELETE 的外键，
      -- 导致「删除管理员」和「兼容身份写审计」都必然报 23503。CREATE TABLE
      -- IF NOT EXISTS 不会修正已存在的表，所以这里显式补齐，可重复执行。
      ALTER TABLE admin_audit_log ALTER COLUMN admin_id DROP NOT NULL;
      ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_admin_id_fkey;
      ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_admin_id_fkey
        FOREIGN KEY (admin_id) REFERENCES admin_users(id) ON DELETE SET NULL;
      ALTER TABLE admin_users DROP CONSTRAINT IF EXISTS admin_users_created_by_fkey;
      ALTER TABLE admin_users ADD CONSTRAINT admin_users_created_by_fkey
        FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE SET NULL;

      -- 入站开放 API Key（第三方搜歌接口用）。
      -- 与出站的 api_key 表（图片服务凭据）无关，是两套东西。
      -- 只存 sha256(key)，明文仅在创建响应里返回一次；key_prefix 供列表页回显。
      CREATE TABLE IF NOT EXISTS open_api_key (
        id           integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        name         text NOT NULL CHECK (btrim(name) <> ''),
        key_hash     text NOT NULL UNIQUE,
        key_prefix   text NOT NULL,
        enabled      smallint NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
        created_by   integer REFERENCES admin_users(id) ON DELETE SET NULL,
        created_at   bigint NOT NULL,
        last_used_at bigint,
        revoked_at   bigint
      );
      CREATE INDEX IF NOT EXISTS idx_open_api_key_enabled ON open_api_key (enabled, created_at DESC);
    `);
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}
