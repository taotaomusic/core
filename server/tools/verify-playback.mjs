// 最近播放后端的独立契约验证。
//
// 必须在独立验证库和验证实例上执行：
//   $env:DATABASE_URL="postgres://.../music_verify"
//   node tools/verify-playback.mjs http://127.0.0.1:4720 verify-token-secret
import "dotenv/config";
import { createHmac, randomBytes } from "node:crypto";
import { Client } from "pg";

const base = (process.argv[2] ?? "http://127.0.0.1:4720").replace(/\/+$/, "");
const authSecret = process.argv[3] ?? "0123456789012345678901234567890123456789";
const databaseUrl = process.env.DATABASE_URL;
if (!databaseUrl) throw new Error("缺少 DATABASE_URL");
const databaseName = new URL(databaseUrl).pathname.replace(/^\//, "");
if (!/verify|test/i.test(databaseName)) throw new Error(`拒绝写入非验证库「${databaseName}」`);

let passed = 0;
let failed = 0;
function check(label, condition, detail = "") {
  if (condition) {
    passed++;
    console.log(`  ✓ ${label}`);
  } else {
    failed++;
    console.log(`  ✗ ${label}${detail ? `  →  ${detail}` : ""}`);
  }
}

const db = new Client({ connectionString: databaseUrl });
await db.connect();
let userId;
try {
  const suffix = randomBytes(4).toString("hex");
  const inserted = await db.query(
    `INSERT INTO users (username, email, password_hash, password_salt)
     VALUES ($1, $2, 'unused', 'unused') RETURNING id`,
    [`playback_${suffix}`, `playback_${suffix}@qq.com`],
  );
  userId = inserted.rows[0].id;
  const nowSeconds = Math.floor(Date.now() / 1_000);
  const payload = Buffer.from(
    JSON.stringify({ sub: userId, iat: nowSeconds, exp: nowSeconds + 900, typ: "access" }),
  ).toString("base64url");
  const signature = createHmac("sha256", authSecret).update(payload).digest("base64url");
  const headers = { authorization: `Bearer ${payload}.${signature}`, "content-type": "application/json" };
  const startedAt = Date.now() - 60_000;

  const report = (body) => fetch(`${base}/api/v1/playback/sessions`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  const baseSession = {
    sessionId: `session-${suffix}-1`,
    deviceId: `device-${suffix}`,
    source: "tencent",
    songId: "97773",
    startedAt,
    lastPlayedAt: startedAt + 2_000,
    listenedMs: 2_000,
    completed: false,
    durationSeconds: 180,
  };

  const unauthorized = await fetch(`${base}/api/v1/playback/recent`);
  check("最近播放接口受访问令牌保护", unauthorized.status === 401, `实际 ${unauthorized.status}`);

  const short = await report(baseSession);
  check("不足 3 秒的会话可以保存", short.status >= 200 && short.status < 300, `实际 ${short.status}`);
  let recent = await (await fetch(`${base}/api/v1/playback/recent`, { headers })).json();
  check("不足 3 秒不会进入最近播放", Array.isArray(recent.data) && recent.data.length === 0, JSON.stringify(recent.data));

  const qualifiedBody = { ...baseSession, lastPlayedAt: startedAt + 35_000, listenedMs: 35_000 };
  const qualified = await (await report(qualifiedBody)).json();
  check("听满 30 秒后会话记为有效播放", qualified.data?.qualified === true, JSON.stringify(qualified));
  await report(qualifiedBody);
  let stats = await (await fetch(`${base}/api/v1/playback/stats`, { headers })).json();
  check("重复快照不重复累计时长", stats.data?.totalListenedMs === 35_000, JSON.stringify(stats.data));
  check("重复快照不重复累计次数", stats.data?.playCount === 1, JSON.stringify(stats.data));

  const completed = await (await report({ ...qualifiedBody, completed: true })).json();
  check("完整播放状态可以后补且只计一次", completed.data?.completed === true, JSON.stringify(completed));
  await report({ ...qualifiedBody, completed: true });
  stats = await (await fetch(`${base}/api/v1/playback/stats`, { headers })).json();
  check("重复完成快照不重复累计", stats.data?.completedCount === 1, JSON.stringify(stats.data));

  const secondStartedAt = Date.now() - 10_000;
  await report({
    ...baseSession,
    sessionId: `session-${suffix}-2`,
    songId: "105648974",
    startedAt: secondStartedAt,
    lastPlayedAt: secondStartedAt + 4_000,
    listenedMs: 4_000,
  });
  recent = await (await fetch(`${base}/api/v1/playback/recent?limit=1`, { headers })).json();
  check("最近播放按有效时间倒序且支持 limit", recent.data?.length === 1 && recent.data[0].songId === "105648974", JSON.stringify(recent.data));

  const conflict = await report({ ...qualifiedBody, songId: "different-song" });
  check("同一 sessionId 不能改绑其它歌曲", conflict.status === 409, `实际 ${conflict.status}`);

  const concurrentStartedAt = Date.now() - 40_000;
  const concurrentBody = {
    ...baseSession,
    sessionId: `session-${suffix}-concurrent`,
    songId: "concurrent-song",
    startedAt: concurrentStartedAt,
    lastPlayedAt: concurrentStartedAt + 30_000,
    listenedMs: 30_000,
  };
  await Promise.all([report(concurrentBody), report(concurrentBody)]);
  stats = await (await fetch(`${base}/api/v1/playback/stats`, { headers })).json();
  check("并发重传同一会话不重复累计", stats.data?.totalListenedMs === 69_000 && stats.data?.playCount === 2, JSON.stringify(stats.data));

  const shortSongStartedAt = Date.now() - 15_000;
  const shortSong = await (await report({
    ...baseSession,
    sessionId: `session-${suffix}-short-song`,
    songId: "short-song",
    startedAt: shortSongStartedAt,
    lastPlayedAt: shortSongStartedAt + 10_000,
    listenedMs: 10_000,
    durationSeconds: 20,
  })).json();
  check("短歌曲听满一半即计一次播放", shortSong.data?.qualified === true, JSON.stringify(shortSong));

  const bulkBase = Date.now() - 10_000;
  await db.query(
    `INSERT INTO user_song_stats
     (user_id, source, song_id, first_played_at, last_played_at, last_history_at,
        play_count, completed_count, total_listened_ms, updated_at)
     SELECT $1, 'tencent', 'bulk-' || value,
            $2::bigint + value, $2::bigint + value, $2::bigint + value, 0, 0, 0, $2::bigint
     FROM generate_series(1, 510) value`,
    [userId, bulkBase],
  );
  const capped = await (await fetch(`${base}/api/v1/playback/recent?limit=9999`, { headers })).json();
  check("最近播放默认及最多返回 500 条", capped.data?.length === 500, `实际 ${capped.data?.length}`);

  const clearMarker = `clear-${suffix}`;
  const cleared = await fetch(`${base}/api/v1/playback/recent?marker=${clearMarker}`, { method: "DELETE", headers });
  check("清空最近播放返回成功", cleared.status >= 200 && cleared.status < 300, `实际 ${cleared.status}`);
  recent = await (await fetch(`${base}/api/v1/playback/recent`, { headers })).json();
  stats = await (await fetch(`${base}/api/v1/playback/stats`, { headers })).json();
  check("清空后最近播放为空", recent.data?.length === 0, JSON.stringify(recent.data));
  const historyState = await (await fetch(`${base}/api/v1/playback/recent/state`, { headers })).json();
  const clearedRevision = historyState.data?.revision;
  check(
    "清空状态含服务端时间与递增版本",
    Number.isInteger(clearedRevision) && clearedRevision > 0 && historyState.data?.clearedAt > 0
      && historyState.data?.marker === clearMarker,
    JSON.stringify(historyState.data),
  );
  const clearRetry = await (await fetch(`${base}/api/v1/playback/recent?marker=${clearMarker}`, { method: "DELETE", headers })).json();
  check(
    "同一个清空 marker 重试不重复推进版本",
    clearRetry.data?.revision === clearedRevision && clearRetry.data?.marker === clearMarker,
    JSON.stringify(clearRetry.data),
  );
  check("清空最近播放保留累计统计", stats.data?.totalListenedMs === 79_000 && stats.data?.playCount === 3, JSON.stringify(stats.data));

  // 不用客户端墙钟判断清空先后：此会话故意在清空完成后才上报，且 lastPlayedAt 比服务端
  // clearedAt 更晚，但它携带的是清空前的 revision，因而只能累计统计、不能复活历史。
  const staleSession = {
    ...baseSession,
    sessionId: `session-${suffix}-stale-revision`,
    songId: "stale-revision-song",
    startedAt: Date.now() - 5_000,
    lastPlayedAt: Date.now() - 1_000,
    listenedMs: 4_000,
    historyRevision: 0,
  };
  const stale = await (await report(staleSession)).json();
  check(
    "旧 revision 会话被固化为旧代际",
    stale.data?.historyRevision === 0 && stale.data?.currentHistoryRevision === clearedRevision,
    JSON.stringify(stale.data),
  );
  // 重试时即使客户端后来知道了新 revision，也不能抬高已有会话的代际，否则清空会被绕过。
  const staleRetry = await (await report({ ...staleSession, listenedMs: 5_000, lastPlayedAt: Date.now(), historyRevision: clearedRevision })).json();
  check("同一会话重试不能升级历史版本", staleRetry.data?.historyRevision === 0, JSON.stringify(staleRetry.data));
  recent = await (await fetch(`${base}/api/v1/playback/recent`, { headers })).json();
  check("清空后补传旧版本会话不会复活历史", recent.data?.length === 0, JSON.stringify(recent.data));

  // 新设备先拉到服务器 revision 后新建会话，即使设备墙钟不同也能立即进入当前历史代际。
  const fresh = await (await report({
    ...baseSession,
    sessionId: `session-${suffix}-fresh-revision`,
    songId: "fresh-revision-song",
    startedAt: Date.now() - 5_000,
    lastPlayedAt: Date.now() - 1_000,
    listenedMs: 4_000,
    historyRevision: clearedRevision,
  })).json();
  check(
    "新会话使用当前 revision 后可见",
    fresh.data?.historyRevision === clearedRevision && fresh.data?.currentHistoryRevision === clearedRevision,
    JSON.stringify(fresh.data),
  );
  recent = await (await fetch(`${base}/api/v1/playback/recent`, { headers })).json();
  check("当前 revision 的会话进入最近播放", recent.data?.some((item) => item.songId === "fresh-revision-song"), JSON.stringify(recent.data));

  const ahead = await report({ ...baseSession, sessionId: `session-${suffix}-ahead-revision`, historyRevision: clearedRevision + 1 });
  check("超前 revision 被拒绝并要求先同步", ahead.status === 409, `实际 ${ahead.status}`);

  // 标识不只保存在“最近一次清空”字段：A 的响应丢失后，B 先清空，再重试 A 的 marker，
  // 仍必须返回 A 的原版本，不能误判成一次新的清空。
  const otherMarker = `clear-${suffix}-other-device`;
  const otherClear = await (await fetch(`${base}/api/v1/playback/recent?marker=${otherMarker}`, { method: "DELETE", headers })).json();
  check(
    "另一设备使用新 marker 会推进一次版本",
    otherClear.data?.revision === clearedRevision + 1 && otherClear.data?.marker === otherMarker,
    JSON.stringify(otherClear.data),
  );
  const delayedOriginalRetry = await (await fetch(`${base}/api/v1/playback/recent?marker=${clearMarker}`, { method: "DELETE", headers })).json();
  check(
    "被插队后重试旧 marker 仍返回原版本",
    delayedOriginalRetry.data?.revision === clearedRevision && delayedOriginalRetry.data?.marker === clearMarker,
    JSON.stringify(delayedOriginalRetry.data),
  );
  const latestState = await (await fetch(`${base}/api/v1/playback/recent/state`, { headers })).json();
  check(
    "旧 marker 重试不改变当前清空状态",
    latestState.data?.revision === clearedRevision + 1 && latestState.data?.marker === otherMarker,
    JSON.stringify(latestState.data),
  );
} finally {
  if (userId !== undefined) await db.query("DELETE FROM users WHERE id = $1", [userId]);
  await db.end();
}

console.log(`\n最近播放验证：${passed} 通过，${failed} 失败`);
if (failed > 0) process.exitCode = 1;
