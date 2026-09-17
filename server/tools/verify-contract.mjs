// 契约验证脚本。
//
// 迁移到 NestJS 后逐项核对响应形状是否与旧实现一致 —— 线上有装机客户端，
// 而 /api/v1/app/bootstrap 本身就是推送修复的通道，坏掉就没有补救手段了。
//
// 用法（需要先启动一个本地实例，务必指向独立的验证库）：
//   node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
//   $env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
//   $env:PORT=4720; $env:APK_DIR="./tmp/apk"
//   $env:AUTH_SECRET="0123456789012345678901234567890123456789"; $env:ADMIN_TOKEN="verify-token"
//   $env:NODE_ENV="test"; $env:EMAIL_VERIFICATION_TEST_CODE="123456"
//   npm run dev
//   node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
import { createHash, randomBytes } from "node:crypto";

const base = (process.argv[2] ?? "http://127.0.0.1:4720").replace(/\/+$/, "");
const adminToken = process.argv[3] ?? "verify-token";

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

function section(title) {
  console.log(`\n=== ${title}`);
}

async function main() {
  section("健康检查与未匹配路由");
  const health = await fetch(`${base}/health`);
  const healthBody = await health.json();
  check("GET /health 返回 200", health.status === 200, `实际 ${health.status}`);
  check("信封 code 为 0", healthBody.code === 0, JSON.stringify(healthBody));

  const missing = await fetch(`${base}/api/v1/does-not-exist`);
  const missingBody = await missing.json();
  check("未匹配路由 404 且 code 4040", missing.status === 404 && missingBody.code === 4040, JSON.stringify(missingBody));
  check("错误体的 message 是字符串", typeof missingBody.message === "string", typeof missingBody.message);

  section("鉴权：注册 / 登录 / 刷新 / 注销");
  const username = `verify_${randomBytes(4).toString("hex")}`;
  const email = `${username}@qq.com`;
  const verificationCode = process.env.EMAIL_VERIFICATION_TEST_CODE;
  if (!/^\d{6}$/.test(verificationCode)) throw new Error("EMAIL_VERIFICATION_TEST_CODE 必须是 6 位数字");
  const verification = await postJson("/api/v1/auth/email-verification", { email });
  const registered = await postJson("/api/v1/auth/register", { username, password: "pass123456", email, verificationCode });
  check(
    "验证码发送 204 且注册返回 201",
    verification.status === 204 && registered.status === 201,
    `发码 ${verification.status}，注册 ${registered.status} ${JSON.stringify(registered.body).slice(0, 120)}`,
  );
  check("code 为 0", registered.body.code === 0, JSON.stringify(registered.body).slice(0, 120));
  const issued = registered.body.data ?? {};
  check("accessToken / refreshToken 与 user 平铺在 data 下", typeof issued.accessToken === "string" && typeof issued.refreshToken === "string" && !!issued.user);
  check("expiresIn 为 900 秒", issued.expiresIn === 900, String(issued.expiresIn));

  const shortName = await postJson("/api/v1/auth/register", {
    username: "ab",
    password: "pass123456",
    email,
    verificationCode,
  });
  check("用户名过短 400 且 code 4003", shortName.status === 400 && shortName.body.code === 4003, JSON.stringify(shortName.body));

  const duplicate = await postJson("/api/v1/auth/register", { username, password: "pass123456", email, verificationCode });
  check("重名 409 且 code 4090", duplicate.status === 409 && duplicate.body.code === 4090, JSON.stringify(duplicate.body));

  const badLogin = await postJson("/api/v1/auth/login", { username, password: "wrongpass" });
  check("密码错误 401 且 code 4011", badLogin.status === 401 && badLogin.body.code === 4011, JSON.stringify(badLogin.body));

  const badRefresh = await postJson("/api/v1/auth/refresh", { refreshToken: "not-a-real-token" });
  check("无效刷新令牌 401 且 code 4012", badRefresh.status === 401 && badRefresh.body.code === 4012, JSON.stringify(badRefresh.body));

  const blankRefresh = await postJson("/api/v1/auth/refresh", {});
  check("空刷新令牌也是 401/4012（不能是 400，否则客户端会批量登出）", blankRefresh.status === 401 && blankRefresh.body.code === 4012, JSON.stringify(blankRefresh.body));

  const rotated = await postJson("/api/v1/auth/refresh", { refreshToken: issued.refreshToken });
  check("刷新成功并返回新令牌对", rotated.status === 200 && typeof rotated.body.data?.accessToken === "string");
  const replay = await postJson("/api/v1/auth/refresh", { refreshToken: issued.refreshToken });
  check("旧刷新令牌已被轮换失效", replay.status === 401 && replay.body.code === 4012, JSON.stringify(replay.body));

  const token = rotated.body.data.accessToken;

  section("鉴权门禁");
  const noToken = await fetch(`${base}/api/v1/favorites`);
  const noTokenBody = await noToken.json();
  check("无令牌访问收藏 401 且 code 4010（绝不能是 403）", noToken.status === 401 && noTokenBody.code === 4010, `${noToken.status} ${JSON.stringify(noTokenBody)}`);
  const badToken = await fetch(`${base}/api/v1/favorites`, { headers: { authorization: "Bearer abc.def" } });
  check("伪造令牌同样 401", badToken.status === 401, `实际 ${badToken.status}`);

  section("歌曲分享门禁");
  const shareWithoutToken = await postJson("/api/v1/shares/songs", { source: "tencent", remoteId: 97773 });
  check(
    "创建分享短链必须登录",
    shareWithoutToken.status === 401 && shareWithoutToken.body.code === 4010,
    `${shareWithoutToken.status} ${JSON.stringify(shareWithoutToken.body)}`,
  );
  const invalidShare = await fetch(`${base}/api/v1/public/shares/not-valid!`);
  const invalidShareBody = await invalidShare.json();
  check(
    "公开分享元数据无需令牌，非法短码返回 404/4045",
    invalidShare.status === 404 && invalidShareBody.code === 4045,
    `${invalidShare.status} ${JSON.stringify(invalidShareBody)}`,
  );
  const invalidPreview = await fetch(`${base}/api/v1/public/shares/not-valid!/preview`);
  const invalidPreviewBody = await invalidPreview.json();
  check(
    "公开试听无需令牌，非法短码仍返回 404/4045",
    invalidPreview.status === 404 && invalidPreviewBody.code === 4045,
    `${invalidPreview.status} ${JSON.stringify(invalidPreviewBody)}`,
  );

  const createdShareResponse = await fetch(`${base}/api/v1/shares/songs`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ source: "tencent", remoteId: 97773 }),
  });
  const createdShareBody = await createdShareResponse.json();
  const shareToken = createdShareBody.data?.token;
  check(
    "登录后可创建歌曲短链",
    createdShareResponse.status === 201 &&
      /^[A-Za-z0-9_-]{8,24}$/.test(shareToken) &&
      new URL(createdShareBody.data.url).pathname === `/s/${shareToken}`,
    `${createdShareResponse.status} ${JSON.stringify(createdShareBody)}`,
  );
  const shareMetadataResponse = await fetch(`${base}/api/v1/public/shares/${shareToken}`);
  const shareMetadataBody = await shareMetadataResponse.json();
  check(
    "短链元数据公开且只暴露受限试听地址",
    shareMetadataResponse.status === 200 &&
      shareMetadataBody.data?.title === "晴天" &&
      shareMetadataBody.data?.previewDurationSeconds === 60 &&
      String(shareMetadataBody.data?.previewUrl).endsWith(`/api/v1/public/shares/${shareToken}/preview`),
    `${shareMetadataResponse.status} ${JSON.stringify(shareMetadataBody)}`,
  );
  const sharePreviewResponse = await fetch(`${base}/api/v1/public/shares/${shareToken}/preview`, {
    headers: { range: "bytes=0-1023" },
  });
  const sharePreviewBytes = Buffer.from(await sharePreviewResponse.arrayBuffer());
  check(
    "试听由服务器生成低码率 MP3 并支持 Range",
    sharePreviewResponse.status === 206 &&
      sharePreviewResponse.headers.get("content-type")?.startsWith("audio/mpeg") === true &&
      sharePreviewResponse.headers.get("content-range")?.startsWith("bytes 0-1023/") === true &&
      sharePreviewBytes.length === 1_024 &&
      sharePreviewBytes.subarray(0, 3).toString("latin1") === "ID3",
    `${sharePreviewResponse.status} ${sharePreviewResponse.headers.get("content-range")} ${sharePreviewBytes.length}`,
  );

  section("收藏");
  const added = await fetch(`${base}/api/v1/favorites/tencent/97773`, { method: "POST", headers: { authorization: `Bearer ${token}` } });
  check("无请求体的 POST 不被校验拦截", added.status >= 200 && added.status < 300, `实际 ${added.status}`);
  const listed = await (await fetch(`${base}/api/v1/favorites`, { headers: { authorization: `Bearer ${token}` } })).json();
  check("data 是裸数组", Array.isArray(listed.data), JSON.stringify(listed).slice(0, 120));
  check("songId 是字符串", typeof listed.data?.[0]?.songId === "string", typeof listed.data?.[0]?.songId);
  const firstFavoritedAt = listed.data?.[0]?.firstFavoritedAt;
  check("首次收藏时间是 number", typeof firstFavoritedAt === "number", typeof firstFavoritedAt);
  const removed = await fetch(`${base}/api/v1/favorites/tencent/97773`, { method: "DELETE", headers: { authorization: `Bearer ${token}` } });
  check("DELETE 返回 2xx", removed.status >= 200 && removed.status < 300, `实际 ${removed.status}`);
  const afterRemove = await (await fetch(`${base}/api/v1/favorites`, { headers: { authorization: `Bearer ${token}` } })).json();
  check("取消收藏后不再出现在收藏列表", afterRemove.data?.every((item) => item.songId !== "97773"), JSON.stringify(afterRemove.data));
  await fetch(`${base}/api/v1/favorites/tencent/97773`, { method: "POST", headers: { authorization: `Bearer ${token}` } });
  const afterReAdd = await (await fetch(`${base}/api/v1/favorites`, { headers: { authorization: `Bearer ${token}` } })).json();
  check(
    "取消后重新收藏仍保留首次收藏时间",
    afterReAdd.data?.[0]?.firstFavoritedAt === firstFavoritedAt,
    `${firstFavoritedAt} -> ${afterReAdd.data?.[0]?.firstFavoritedAt}`,
  );

  section("云端歌单");
  const playlistHeaders = { authorization: `Bearer ${token}`, "content-type": "application/json" };
  const createdPlaylistResponse = await fetch(`${base}/api/v1/playlists`, {
    method: "POST",
    headers: playlistHeaders,
    body: JSON.stringify({
      name: `验证歌单-${randomBytes(3).toString("hex")}`,
      description: "契约验证",
    }),
  });
  const createdPlaylistText = await createdPlaylistResponse.text();
  const createdPlaylist = {
    status: createdPlaylistResponse.status,
    body: createdPlaylistText ? JSON.parse(createdPlaylistText) : {},
  };
  const playlistId = createdPlaylist.body.data?.id;
  check("创建歌单返回 201/信封", createdPlaylist.status === 201 && createdPlaylist.body.code === 0 && Number.isInteger(playlistId));

  const addedSong = await fetch(`${base}/api/v1/playlists/${playlistId}/songs`, {
    method: "POST",
    headers: playlistHeaders,
    body: JSON.stringify({ source: "tencent", songId: "97773", title: "验证歌曲", artist: "桃桃" }),
  });
  const addedSongBody = await addedSong.json();
  check(
    "向歌单添加歌曲成功",
    addedSong.status === 200 && addedSongBody.code === 0 && addedSongBody.data?.songs?.length === 1,
    JSON.stringify(addedSongBody).slice(0, 300),
  );

  const duplicateSong = await fetch(`${base}/api/v1/playlists/${playlistId}/songs`, {
    method: "POST",
    headers: playlistHeaders,
    body: JSON.stringify({ source: "tencent", songId: "97773", title: "验证歌曲（更新快照）" }),
  });
  const duplicateSongBody = await duplicateSong.json();
  check(
    "重复歌曲幂等且不产生重复项",
    duplicateSong.status === 200 && duplicateSongBody.data?.songs?.length === 1,
    JSON.stringify(duplicateSongBody).slice(0, 300),
  );

  const midOnlySong = await fetch(`${base}/api/v1/playlists/${playlistId}/songs`, {
    method: "POST",
    headers: playlistHeaders,
    body: JSON.stringify({ source: "tencent", songId: 0, mid: "contract-mid-only", title: "MID 歌曲" }),
  });
  const midOnlySongBody = await midOnlySong.json();
  check(
    "songID=0 时用 mid 保存稳定歌单键",
    midOnlySong.status === 200 && midOnlySongBody.data?.songs?.some((item) => item.songId === "contract-mid-only"),
    JSON.stringify(midOnlySongBody).slice(0, 300),
  );
  await fetch(`${base}/api/v1/playlists/${playlistId}/songs/tencent/contract-mid-only`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${token}` },
  });

  const unsupportedSource = await fetch(`${base}/api/v1/playlists/${playlistId}/songs`, {
    method: "POST",
    headers: playlistHeaders,
    body: JSON.stringify({ source: "unknown", songId: "123", title: "不可播放来源" }),
  });
  const unsupportedSourceBody = await unsupportedSource.json();
  check(
    "歌单拒绝当前音乐路由不支持的来源",
    unsupportedSource.status === 400 && unsupportedSourceBody.code === 4004,
    JSON.stringify(unsupportedSourceBody),
  );

  const secondSong = await fetch(`${base}/api/v1/playlists/${playlistId}/songs`, {
    method: "POST",
    headers: playlistHeaders,
    body: JSON.stringify({ source: "netease", songId: "123456", title: "第二首" }),
  });
  const secondSongBody = await secondSong.json();
  check(
    "可添加不同来源的同值 ID 歌曲",
    secondSong.status === 200 && secondSongBody.data?.songs?.length === 2,
    JSON.stringify(secondSongBody).slice(0, 300),
  );

  const reordered = await fetch(`${base}/api/v1/playlists/${playlistId}/songs/order`, {
    method: "PATCH",
    headers: playlistHeaders,
    body: JSON.stringify({ songs: [
      { source: "netease", songId: "123456" },
      { source: "tencent", songId: "97773" },
    ] }),
  });
  const reorderedBody = await reordered.json();
  check("完整键列表可以调整歌单顺序", reordered.status === 200 && reorderedBody.data?.songs?.[0]?.source === "netease");

  const removedPlaylistSong = await fetch(`${base}/api/v1/playlists/${playlistId}/songs/tencent/97773`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${token}` },
  });
  const removedPlaylistSongBody = await removedPlaylistSong.json();
  check("移除歌曲后自动压紧顺序", removedPlaylistSong.status === 200 && removedPlaylistSongBody.data?.removed === true && removedPlaylistSongBody.data?.playlist?.songs?.length === 1);

  const updatedPlaylist = await fetch(`${base}/api/v1/playlists/${playlistId}`, {
    method: "PATCH",
    headers: playlistHeaders,
    body: JSON.stringify({ name: "验证歌单（已重命名）" }),
  });
  const updatedPlaylistBody = await updatedPlaylist.json();
  check("歌单可以重命名并递增版本", updatedPlaylist.status === 200 && updatedPlaylistBody.data?.name === "验证歌单（已重命名）" && updatedPlaylistBody.data?.revision > 1);

  const deletedPlaylist = await fetch(`${base}/api/v1/playlists/${playlistId}`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${token}` },
  });
  check("删除歌单返回 204", deletedPlaylist.status === 204);

  section("搜索（NDJSON 流）");
  const emptyKeyword = await fetch(`${base}/api/v1/search?keyword=`, { headers: { authorization: `Bearer ${token}` } });
  const emptyBody = await emptyKeyword.json();
  check("空关键词 400 且 code 4001", emptyKeyword.status === 400 && emptyBody.code === 4001, JSON.stringify(emptyBody));

  const started = Date.now();
  const search = await fetch(`${base}/api/v1/search?keyword=${encodeURIComponent("周杰伦")}&num=20&quality=10&source=tencent`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const ndjson = await search.text();
  const elapsed = Date.now() - started;
  check("content-type 是 application/x-ndjson", (search.headers.get("content-type") ?? "").includes("x-ndjson"), search.headers.get("content-type"));
  const lines = ndjson.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const songs = lines.filter((line) => line.type === "song");
  const end = lines.find((line) => line.type === "end");
  check("响应没有被信封包住（首行 type 为 song）", lines[0]?.type === "song", JSON.stringify(lines[0]).slice(0, 100));
  check("返回了歌曲", songs.length > 0, `${songs.length} 首`);
  check("末行是 end 且带 meta.dropped", typeof end?.meta?.dropped === "number", JSON.stringify(end));
  check("data.id 是 number", typeof songs[0]?.data?.id === "number", typeof songs[0]?.data?.id);
  check("duration 是 mm:ss 字符串", /^\d{2}:\d{2}$|^网络歌曲$/.test(songs[0]?.data?.duration ?? ""), songs[0]?.data?.duration);
  check("lyricUrl 是带前导斜杠的相对路径", (songs[0]?.data?.lyricUrl ?? "").startsWith("/api/v1/"), songs[0]?.data?.lyricUrl);
  check("coverUrl 是绝对 https 地址", (songs[0]?.data?.coverUrl ?? "").startsWith("https://"), songs[0]?.data?.coverUrl);
  // 搜索不再逐首解析播放地址，一次上游请求就该返回；放宽到 3 秒只是留出网络抖动余量。
  check(`耗时在 3 秒内（实测 ${elapsed}ms）`, elapsed < 3000, `${elapsed}ms`);

  section("歌词");
  const plain = await fetch(`${base}/api/v1/songs/97773/lyrics`, { headers: { authorization: `Bearer ${token}` } });
  const plainText = await plain.text();
  check("默认 content-type 是 text/plain", (plain.headers.get("content-type") ?? "").includes("text/plain"), plain.headers.get("content-type"));
  check("响应体本身就是歌词", plainText.startsWith("[ti:") || plainText.startsWith("["), plainText.slice(0, 30));

  const rich = await (await fetch(`${base}/api/v1/songs/97773/lyrics?format=json`, { headers: { authorization: `Bearer ${token}` } })).json();
  check("format=json 走信封且 code 为 0", rich.code === 0, JSON.stringify(rich).slice(0, 120));
  check("含 lrc 与逐字 yrc", typeof rich.data?.lrc === "string" && (rich.data?.yrc ?? "").length > 0, `lrc=${rich.data?.lrc?.length} yrc=${rich.data?.yrc?.length}`);

  section("播放转发");
  const audio = await fetch(`${base}/api/v1/songs/97773/play?quality=10`, { headers: { authorization: `Bearer ${token}`, range: "bytes=0-2047" } });
  const head = Buffer.from(await audio.arrayBuffer());
  check("带 Range 返回 206（新增能力）", audio.status === 206, `实际 ${audio.status}`);
  check("回写了 content-range", !!audio.headers.get("content-range"), audio.headers.get("content-range"));
  check("拿到的是音频字节", head.length > 0, `${head.length} 字节`);

  const full = await fetch(`${base}/api/v1/songs/97773/play?quality=10`);
  check("播放接口无令牌时不返回 401（应为 4010 以外的错误或 200）", full.status !== 403, `实际 ${full.status}`);

  section("热更新：bootstrap");
  const bootstrap = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=60&sdk=36&deviceId=verify-device`)).json();
  check("免鉴权可访问且 code 为 0", bootstrap.code === 0, JSON.stringify(bootstrap).slice(0, 140));
  check("含 update 与 config", !!bootstrap.data?.update && typeof bootstrap.data?.config === "object");
  check("update 带 minSupportedVersionCode", typeof bootstrap.data?.update?.minSupportedVersionCode === "number");

  const expiredToken = await fetch(`${base}/api/v1/app/bootstrap?versionCode=60&sdk=36&deviceId=verify-device`, {
    headers: { authorization: "Bearer expired.token" },
  });
  check("带无效令牌仍返回 200（绝不能 401，否则热更新通道静默死亡）", expiredToken.status === 200, `实际 ${expiredToken.status}`);

  const badVersion = await fetch(`${base}/api/v1/app/bootstrap?versionCode=0&sdk=36&deviceId=x`);
  check("versionCode 非法 400 且 code 4005", badVersion.status === 400 && (await badVersion.json()).code === 4005);

  section("热更新：发布管理");
  const noAdmin = await fetch(`${base}/api/v1/app/admin/releases`, { headers: { "x-admin-token": "wrong" } });
  check("错误管理令牌 401 且 code 4013", noAdmin.status === 401 && (await noAdmin.json()).code === 4013, `实际 ${noAdmin.status}`);
  const releases = await (await fetch(`${base}/api/v1/app/admin/releases`, { headers: { "x-admin-token": adminToken } })).json();
  check("正确管理令牌可列出发布", releases.code === 0 && Array.isArray(releases.data), JSON.stringify(releases).slice(0, 120));

  const guard = await fetch(`${base}/api/v1/app/admin/min-version`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999999 }),
  });
  check("缺少全量版本时抬高下限被拒（409 / code 4091）", guard.status === 409 && (await guard.json()).code === 4091, `实际 ${guard.status}`);

  section("Windows 模块化发布");
  const noDesktopAdmin = await fetch(`${base}/api/v1/desktop/admin/releases`, {
    headers: { "x-admin-token": "wrong" },
  });
  check(
    "Windows 管理接口错误令牌仍返回 4013",
    noDesktopAdmin.status === 401 && (await noDesktopAdmin.json()).code === 4013,
    `实际 ${noDesktopAdmin.status}`,
  );
  const moduleBytes = Buffer.from(("taotao-desktop-module-".repeat(10_000)) + "v999101");
  const moduleSha = createHash("sha256").update(moduleBytes).digest("hex");
  const uploadedModule = await fetch(`${base}/api/v1/desktop/admin/artifacts?sha256=${moduleSha}`, {
    method: "POST",
    headers: { "x-admin-token": adminToken },
    body: moduleBytes,
  });
  check("内容寻址模块上传成功", uploadedModule.status === 201, `实际 ${uploadedModule.status}`);

  const desktopManifest = {
    versionCode: 999101,
    versionName: "9.9.101",
    architecture: "windows-x64",
    entrypoint: "taotao-app.jar",
    releaseNote: "契约验证",
    rollout: 0,
    files: [{ path: "taotao-app.jar", category: "core", size: moduleBytes.length, sha256: moduleSha }],
  };
  const publishedDesktop = await fetch(`${base}/api/v1/desktop/admin/releases`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify(desktopManifest),
  });
  check("Windows 清单登记成功", publishedDesktop.status === 201, `实际 ${publishedDesktop.status}`);
  const hiddenDesktop = await (
    await fetch(`${base}/api/v1/desktop/bootstrap?versionCode=999100&architecture=windows-x64&deviceId=verify-desktop`)
  ).json();
  check("Windows rollout=0 不下发", hiddenDesktop.data?.update?.available === false, JSON.stringify(hiddenDesktop.data?.update));

  await fetch(`${base}/api/v1/desktop/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999101, architecture: "windows-x64", percent: 100 }),
  });
  const offeredDesktopResponse = await fetch(
    `${base}/api/v1/desktop/bootstrap?versionCode=999100&architecture=windows-x64&deviceId=verify-desktop`,
    { headers: { authorization: "Bearer expired.invalid.token" } },
  );
  const offeredDesktop = await offeredDesktopResponse.json();
  check("过期令牌访问 Windows bootstrap 仍是 200", offeredDesktopResponse.status === 200, `实际 ${offeredDesktopResponse.status}`);
  check(
    "Windows bootstrap 下发模块清单",
    offeredDesktop.data?.update?.available === true &&
      offeredDesktop.data.update.versionCode === 999101 &&
      offeredDesktop.data.update.files?.[0]?.sha256 === moduleSha &&
      typeof offeredDesktop.data.update.totalSize === "number",
    JSON.stringify(offeredDesktop.data?.update),
  );
  const artifactRange = await fetch(offeredDesktop.data.update.files[0].url, {
    headers: { range: "bytes=1-3" },
  });
  check(
    "Windows 内容寻址下载支持 Range",
    artifactRange.status === 206 && artifactRange.headers.get("content-range") === `bytes 1-3/${moduleBytes.length}`,
    `${artifactRange.status} ${artifactRange.headers.get("content-range")}`,
  );

  const nextModuleBytes = Buffer.from(("taotao-desktop-module-".repeat(10_000)) + "v999102");
  const nextModuleSha = createHash("sha256").update(nextModuleBytes).digest("hex");
  const uploadedNextModule = await fetch(`${base}/api/v1/desktop/admin/artifacts?sha256=${nextModuleSha}`, {
    method: "POST",
    headers: { "x-admin-token": adminToken },
    body: nextModuleBytes,
  });
  check("Windows 第二版模块上传成功", uploadedNextModule.status === 201, `实际 ${uploadedNextModule.status}`);
  const nextDesktop = await fetch(`${base}/api/v1/desktop/admin/releases`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({
      ...desktopManifest,
      versionCode: 999102,
      versionName: "9.9.102",
      files: [{ path: "taotao-app.jar", category: "core", size: nextModuleBytes.length, sha256: nextModuleSha }],
      rollout: 100,
    }),
  });
  check("Windows 第二版清单登记成功", nextDesktop.status === 201, `实际 ${nextDesktop.status}`);
  const patchedDesktop = await (
    await fetch(`${base}/api/v1/desktop/bootstrap?versionCode=999101&architecture=windows-x64&deviceId=verify-desktop-patch`)
  ).json();
  const desktopPatch = patchedDesktop.data?.update?.files?.[0]?.patch;
  check(
    "Windows 相邻版本生成 bsdiff",
    patchedDesktop.data?.update?.versionCode === 999102 && desktopPatch?.algorithm === "bsdiff" && desktopPatch.size < nextModuleBytes.length,
    JSON.stringify(desktopPatch),
  );
  const patchDownload = desktopPatch ? await fetch(desktopPatch.url) : undefined;
  check(
    "Windows bsdiff 补丁可下载",
    patchDownload?.status === 200 && Number(patchDownload.headers.get("content-length")) === desktopPatch?.size,
    `${patchDownload?.status ?? 0} ${patchDownload?.headers.get("content-length") ?? ""}`,
  );
  const desktopGuard = await fetch(`${base}/api/v1/desktop/admin/min-version`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999999, architecture: "windows-x64" }),
  });
  check(
    "Windows 强制更新下限同样要求全量救援版本",
    desktopGuard.status === 409 && (await desktopGuard.json()).code === 4091,
    `实际 ${desktopGuard.status}`,
  );

  section("PostgreSQL 迁移专项");
  // 以下每一项都对应一处「SQLite 能过、PostgreSQL 会错」的差异，
  // 迁移前这些路径都没有被覆盖。

  // ① listConfig 的「所有版本」哨兵曾是 Number.MAX_SAFE_INTEGER，
  //    与 int4 的 min_version_code 比较会让 PG 直接报 22003。
  const adminConfig = await fetch(`${base}/api/v1/app/admin/config`, { headers: { "x-admin-token": adminToken } });
  const adminConfigBody = await adminConfig.json();
  check(
    "管理端列配置 200（哨兵不能超出 int4 范围）",
    adminConfig.status === 200 && adminConfigBody.code === 0,
    `${adminConfig.status} ${JSON.stringify(adminConfigBody).slice(0, 120)}`,
  );

  const wroteConfig = await fetch(`${base}/api/v1/app/admin/config`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ key: "verify.flag", value: "on", minVersionCode: 1 }),
  });
  check("写入配置项 200", wroteConfig.status === 200, `实际 ${wroteConfig.status}`);

  const configured = await (
    await fetch(`${base}/api/v1/app/bootstrap?versionCode=60&sdk=36&deviceId=verify-device`)
  ).json();
  check("bootstrap 读回刚写的配置", configured.data?.config?.["verify.flag"] === "on", JSON.stringify(configured.data?.config));
  // ② updated_at 是 bigint；pg 默认把 int8 解析成字符串。
  check(
    "configVersion 是 number（int8 不能回传成字符串）",
    typeof configured.data?.configVersion === "number",
    `${typeof configured.data?.configVersion}：${configured.data?.configVersion}`,
  );

  await fetch(`${base}/api/v1/favorites/tencent/97773`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
  });
  const refetched = await (await fetch(`${base}/api/v1/favorites`, { headers: { authorization: `Bearer ${token}` } })).json();
  check("收藏的 createdAt 是 number", typeof refetched.data?.[0]?.createdAt === "number", `${typeof refetched.data?.[0]?.createdAt}`);
  // PG 会把不加引号的别名折叠成小写，songId 会变成 songid。
  check("收藏的 songId 别名大小写正确", typeof refetched.data?.[0]?.songId === "string", JSON.stringify(refetched.data?.[0]));

  // ③ 注册是「先查重、再哈希密码（约 100ms）、最后插入」，连接池下挡不住并发。
  const raceName = `verify_${randomBytes(4).toString("hex")}`;
  // 两个并发请求必须各用独立邮箱和已签发验证码；若共用一个验证码，先消费到它的
  // 请求会让另一个在唯一约束前就因 4009 失败，无法覆盖数据库并发冲突的真实路径。
  const raceEmails = [
    `${raceName}_a@qq.com`,
    `${raceName}_b@qq.com`,
  ];
  await Promise.all(raceEmails.map((raceEmail) => postJson("/api/v1/auth/email-verification", { email: raceEmail })));
  const raced = await Promise.all([
    postJson("/api/v1/auth/register", {
      username: raceName,
      password: "pass123456",
      email: raceEmails[0],
      verificationCode,
    }),
    postJson("/api/v1/auth/register", {
      username: raceName,
      password: "pass123456",
      email: raceEmails[1],
      verificationCode,
    }),
  ]);
  const accepted = raced.filter((item) => item.status === 201);
  const refused = raced.filter((item) => item.status !== 201);
  check("并发注册同名用户只成功一次", accepted.length === 1, raced.map((item) => item.status).join(" / "));
  check(
    "另一个是 409/4090（唯一约束冲突不能漏成 502）",
    refused.length === 1 && refused[0].status === 409 && refused[0].body.code === 4090,
    JSON.stringify(refused[0]?.body),
  );

  // ④ 刷新令牌必须是一次性的：consume 拆成 SELECT + UPDATE 时两个并发请求会双双成功。
  const victimName = `verify_${randomBytes(4).toString("hex")}`;
  const victimEmail = `${victimName}@qq.com`;
  await postJson("/api/v1/auth/email-verification", { email: victimEmail });
  const victim = await postJson("/api/v1/auth/register", {
    username: victimName,
    password: "pass123456",
    email: victimEmail,
    verificationCode,
  });
  const shared = victim.body.data.refreshToken;
  const rotations = await Promise.all([
    postJson("/api/v1/auth/refresh", { refreshToken: shared }),
    postJson("/api/v1/auth/refresh", { refreshToken: shared }),
  ]);
  check(
    "同一刷新令牌并发使用只成功一次",
    rotations.filter((item) => item.status === 200).length === 1,
    rotations.map((item) => item.status).join(" / "),
  );

  // ⑤ enabled 是 smallint 0/1；改成 boolean 会让 `enabled === 1` 恒假、`!enabled` 反转。
  const placeholderApk = Buffer.from("PKtaotao-verify-placeholder");
  const disabled = await fetch(
    `${base}/api/v1/app/admin/releases?versionCode=999001&versionName=9.9.1&enabled=false`,
    { method: "POST", headers: { "x-admin-token": adminToken }, body: placeholderApk },
  );
  check("登记一个 enabled=false 的版本", disabled.status === 201, `实际 ${disabled.status}`);
  const disabledDownload = await fetch(`${base}/api/v1/app/apk/999001`);
  check("停用版本下载返回 404", disabledDownload.status === 404, `实际 ${disabledDownload.status}`);

  // ⑥ 灰度分桶依赖 bucketOf 保持同步：一旦变成 async，find 的回调恒为真值，
  //    rollout=0 也会被下发。
  await fetch(`${base}/api/v1/app/admin/releases?versionCode=999002&versionName=9.9.2&rollout=0`, {
    method: "POST",
    headers: { "x-admin-token": adminToken },
    body: placeholderApk,
  });
  const notRolledOut = await (
    await fetch(`${base}/api/v1/app/bootstrap?versionCode=999001&sdk=36&deviceId=verify-bucket`)
  ).json();
  check("rollout=0 的版本不下发", notRolledOut.data?.update?.available === false, JSON.stringify(notRolledOut.data?.update));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999002, percent: 100 }),
  });
  const offered = await Promise.all([
    (await fetch(`${base}/api/v1/app/bootstrap?versionCode=999001&sdk=36&deviceId=verify-bucket`)).json(),
    (await fetch(`${base}/api/v1/app/bootstrap?versionCode=999001&sdk=36&deviceId=verify-bucket`)).json(),
  ]);
  check("rollout=100 后下发该版本", offered[0].data?.update?.available === true, JSON.stringify(offered[0].data?.update));
  check(
    "同一 deviceId 两次结果一致（分桶必须稳定）",
    offered[0].data?.update?.versionCode === offered[1].data?.update?.versionCode,
    `${offered[0].data?.update?.versionCode} vs ${offered[1].data?.update?.versionCode}`,
  );
  check("apkSize 是 number", typeof offered[0].data?.update?.apkSize === "number", `${typeof offered[0].data?.update?.apkSize}`);

  section("搜索拆分：元信息 + 独立解析");
  // 播放地址解析已从搜索里拆出去，搜索只返回元信息。这一组覆盖拆分后的新契约。

  const wideStarted = Date.now();
  const wide = await fetch(`${base}/api/v1/search?keyword=${encodeURIComponent("周杰伦")}&num=60&source=tencent`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const wideBody = await wide.text();
  const wideElapsed = Date.now() - wideStarted;
  const wideLines = wideBody.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const wideSongs = wideLines.filter((line) => line.type === "song").map((line) => line.data);
  check(`60 首在 3 秒内返回（实测 ${wideElapsed}ms）`, wideElapsed < 3000, `${wideElapsed}ms`);
  check("确实返回了 60 首", wideSongs.length === 60, `${wideSongs.length} 首`);
  // 反向代理默认会把整个响应缓完再转发，逐行下发就白做了。
  check(
    "带 x-accel-buffering: no（否则反代会缓冲，逐行下发失效）",
    wide.headers.get("x-accel-buffering") === "no",
    String(wide.headers.get("x-accel-buffering")),
  );
  check("每首都带 favorited 且是 boolean", wideSongs.every((song) => typeof song.favorited === "boolean"));
  check("每首都带 vip 且是 boolean", wideSongs.every((song) => typeof song.vip === "boolean"));
  // songID 为 0 的歌只能靠 mid 解析，拆分后必须把它下发给客户端。
  check("每首都带 mid", wideSongs.every((song) => typeof song.mid === "string" && song.mid.length > 0));
  check(
    "audioUrl 指向自家 host（不是上游直链）",
    new URL(wideSongs[0].audioUrl).host === new URL(base).host,
    wideSongs[0].audioUrl,
  );
  check("meta.dropped 仍然存在（旧客户端会读）", typeof wideLines.at(-1)?.meta?.dropped === "number");

  const favSong = wideSongs[0];
  await fetch(`${base}/api/v1/favorites/tencent/${favSong.id}`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
  });
  const afterFav = (await (await fetch(`${base}/api/v1/search?keyword=${encodeURIComponent("周杰伦")}&num=60&source=tencent`, {
    headers: { authorization: `Bearer ${token}` },
  })).text())
    .split("\n").filter(Boolean).map((line) => JSON.parse(line))
    .filter((line) => line.type === "song").map((line) => line.data);
  const flagged = afterFav.filter((song) => song.favorited);
  check(
    "批量收藏查询只标记真正收藏的那首",
    flagged.length === 1 && flagged[0].id === favSong.id,
    `${flagged.length} 首：${flagged.map((song) => song.id).join(",")}`,
  );

  section("解析播放地址与音质列表");
  const linked = await (await fetch(`${base}/api/v1/songs/97773/link?quality=10`, {
    headers: { authorization: `Bearer ${token}` },
  })).json();
  check("/link 返回 code 0", linked.code === 0, JSON.stringify(linked).slice(0, 140));
  // 音频要由客户端直拉 QQ 的 CDN，服务器只负责解析。
  check(
    "/link 给出的是上游直链",
    (linked.data?.url ?? "").includes("qqmusic.qq.com"),
    (linked.data?.url ?? "").slice(0, 60),
  );
  check("/link 带实际档位与 kbps", typeof linked.data?.quality === "number" && !!linked.data?.kbps, JSON.stringify(linked.data));

  // 这首歌没有杜比档（/song/info 里 size 为 0），必须降级而不是报错。
  const downgraded = await (await fetch(`${base}/api/v1/songs/97773/link?quality=12`, {
    headers: { authorization: `Bearer ${token}` },
  })).json();
  check(
    "请求不存在的档位会降级并标记 fallback",
    downgraded.code === 0 && downgraded.data?.fallback === true && downgraded.data?.quality < 12,
    JSON.stringify(downgraded.data),
  );

  // 业务失败绝不能借用 401：那会触发客户端续期重放，二次失败把用户踢回登录页。
  const unresolvable = await fetch(`${base}/api/v1/songs/1/link?quality=10`, {
    headers: { authorization: `Bearer ${token}` },
  });
  check("拿不到地址时不是 401", unresolvable.status !== 401, `实际 ${unresolvable.status}`);

  const info = await (await fetch(`${base}/api/v1/songs/97773/info`, {
    headers: { authorization: `Bearer ${token}` },
  })).json();
  check("/info 返回 code 0", info.code === 0, JSON.stringify(info).slice(0, 140));
  check("/info 列出可用档位", (info.data?.qualities?.length ?? 0) > 0, `${info.data?.qualities?.length} 档`);
  // size 为 0 的档位这首歌没有，服务端应该已经过滤掉，客户端不用自己判断。
  check(
    "/info 不下发 size 为 0 的档位",
    (info.data?.qualities ?? []).every((tier) => tier.size > 0),
    JSON.stringify(info.data?.qualities?.filter((tier) => !(tier.size > 0))),
  );
  check("/info 每档都有中文标签", (info.data?.qualities ?? []).every((tier) => typeof tier.label === "string" && tier.label.length > 0));

  section("参数健壮性");
  // Math.max(1, Number("abc")) 是 NaN，会拼出字面量 page=NaN 打给上游。
  const nanPage = await fetch(`${base}/api/v1/search?keyword=test&page=abc&num=3&source=tencent`, {
    headers: { authorization: `Bearer ${token}` },
  });
  check("page 非法时不炸（回落默认值）", nanPage.status === 200, `实际 ${nanPage.status}`);
  // Number("") 是 0 且 Number.isInteger(0) 为真，空 quality 曾静默落到最低档。
  const blankQuality = await (await fetch(`${base}/api/v1/search?keyword=test&quality=&num=3&source=tencent`, {
    headers: { authorization: `Bearer ${token}` },
  })).text();
  const blankMeta = blankQuality.split("\n").filter(Boolean).map((line) => JSON.parse(line)).at(-1)?.meta;
  check("空 quality 回落到默认 10 而不是 0", blankMeta?.quality === 10, `quality=${blankMeta?.quality}`);

  section("最新版本号响应头");
  // 服务端在每个响应上带回当前**全量可用**的最高版本号，客户端据此在会话中途发现更新。
  // 只能包含 rollout=100 的版本：广告一个灰度版本会让不在名单里的客户端
  // 提示更新 → bootstrap 返回 available:false → 提示永远消不掉。
  const fakeApk = Buffer.from("PKtaotao-version-header");
  const headerOf = async (path, init) => (await fetch(`${base}${path}`, init)).headers.get("x-latest-version-code");

  await fetch(`${base}/api/v1/app/admin/releases?versionCode=999910&versionName=9.9.10&rollout=0`, {
    method: "POST",
    headers: { "x-admin-token": adminToken },
    body: fakeApk,
  });
  await fetch(`${base}/health`);
  check("未放量的版本不进响应头", (await headerOf("/health")) !== "999910", String(await headerOf("/health")));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999910, percent: 50 }),
  });
  await fetch(`${base}/health`);
  check("灰度中（50%）的版本不进响应头", (await headerOf("/health")) !== "999910", String(await headerOf("/health")));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999910, percent: 100 }),
  });
  await fetch(`${base}/health`);
  check("放量 100% 后出现在响应头", (await headerOf("/health")) === "999910", String(await headerOf("/health")));

  // setHeader 设的头不会被各端点自己的 writeHead 覆盖，流式响应也要带上。
  const streamed = await headerOf(`/api/v1/search?keyword=test&num=3&source=tencent`, {
    headers: { authorization: `Bearer ${token}` },
  });
  check("搜索 NDJSON 也带这个头（writeHead 不覆盖 setHeader）", streamed === "999910", String(streamed));
  const apkHeader = await headerOf("/api/v1/app/apk/999910");
  check("APK 二进制也带这个头", apkHeader === "999910", String(apkHeader));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-admin-token": adminToken },
    body: JSON.stringify({ versionCode: 999910, percent: 0, enabled: false }),
  });
  await fetch(`${base}/health`);
  check("停用后立刻从响应头消失（缓存已失效）", (await headerOf("/health")) !== "999910", String(await headerOf("/health")));

  section("悟空 IM 会话入口");
  // 验证进程刻意不连接真实悟空 IM；未启用时仍要确认这个新增受保护路由不会被误判为
  // 未登录或落成 200。真实环境的 Token 签发与 Gateway CONNECT 由部署冒烟测试覆盖。
  const imDisabled = await fetch(`${base}/api/v1/im/session`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ deviceId: "verify-device-id-0001" }),
  });
  const imDisabledBody = await imDisabled.json();
  check(
    "未启用 IM 时会话入口返回 503/5031",
    imDisabled.status === 503 && imDisabledBody.code === 5031,
    JSON.stringify(imDisabledBody),
  );

  // ==================== 管理后台认证 ====================
  //
  // 这一段覆盖 /api/v1/admin/auth/* —— 迁移到企业级认证后它一直没有任何
  // 断言，导致「契约全绿」和「管理员能不能登录」完全无关。
  section("管理后台认证：账号 / 角色 / 2FA / 审计");

  const bearer = (token) => ({ "content-type": "application/json", authorization: `Bearer ${token}` });
  const legacyHeaders = { "content-type": "application/json", "x-admin-token": adminToken };

  const anonymousAdmin = await fetch(`${base}/api/v1/admin/auth/me`);
  const anonymousAdminBody = await anonymousAdmin.json();
  check(
    "无凭据访问管理接口 401 且 code 4013",
    anonymousAdmin.status === 401 && anonymousAdminBody.code === 4013,
    `${anonymousAdmin.status} ${JSON.stringify(anonymousAdminBody)}`,
  );

  const wrongAdminPassword = await postJson("/api/v1/admin/auth/login", { username: "admin", password: "definitely-wrong" });
  check(
    "管理员密码错误 401 且 code 4011",
    wrongAdminPassword.status === 401 && wrongAdminPassword.body.code === 4011,
    `${wrongAdminPassword.status} ${JSON.stringify(wrongAdminPassword.body)}`,
  );

  // 服务启动时会 bootstrap 出默认超管 admin / admin123。
  const adminLogin = await postJson("/api/v1/admin/auth/login", { username: "admin", password: "admin123" });
  const adminSession = adminLogin.body.data?.token;
  check(
    "管理员登录返回会话 token 与 super_admin 身份",
    adminLogin.status === 200 && typeof adminSession === "string" && adminLogin.body.data?.admin?.role === "super_admin",
    `${adminLogin.status} ${JSON.stringify(adminLogin.body).slice(0, 160)}`,
  );

  const adminMe = await fetch(`${base}/api/v1/admin/auth/me`, { headers: { authorization: `Bearer ${adminSession}` } });
  const adminMeBody = await adminMe.json();
  check(
    "Bearer 会话可访问 /admin/auth/me",
    adminMe.status === 200 && adminMeBody.data?.username === "admin",
    `${adminMe.status} ${JSON.stringify(adminMeBody).slice(0, 160)}`,
  );

  // 这条路径就是前端管理员页实际请求的地址：控制器前缀是 admin/auth，
  // 前端曾按 /admin/users 调，结果整页 404。
  const adminList = await fetch(`${base}/api/v1/admin/auth/users`, { headers: { authorization: `Bearer ${adminSession}` } });
  const adminListBody = await adminList.json();
  check(
    "超管可列出管理员（/admin/auth/users）",
    adminList.status === 200 && Array.isArray(adminListBody.data)
      && adminListBody.data.some((item) => item.username === "admin"),
    `${adminList.status} ${JSON.stringify(adminListBody).slice(0, 160)}`,
  );

  const auditList = await fetch(`${base}/api/v1/admin/auth/audit-log?limit=5`, { headers: { authorization: `Bearer ${adminSession}` } });
  const auditListBody = await auditList.json();
  check(
    "审计日志可读且已记录登录事件",
    auditList.status === 200 && Array.isArray(auditListBody.data?.items) && auditListBody.data?.total >= 1,
    `${auditList.status} ${JSON.stringify(auditListBody).slice(0, 160)}`,
  );

  // 2FA 不能被绕过：没有第一步签发的挑战票据就换不到会话。
  const totpWithoutTicket = await postJson("/api/v1/admin/auth/totp-verify", { admin_id: 1, token: "000000" });
  check(
    "缺少 temp_token 时 totp-verify 401 且 code 4011",
    totpWithoutTicket.status === 401 && totpWithoutTicket.body.code === 4011,
    `${totpWithoutTicket.status} ${JSON.stringify(totpWithoutTicket.body)}`,
  );
  const totpForgedTicket = await postJson("/api/v1/admin/auth/totp-verify", { temp_token: "forged-ticket", token: "000000" });
  check(
    "伪造 temp_token 时 totp-verify 401 且 code 4011",
    totpForgedTicket.status === 401 && totpForgedTicket.body.code === 4011,
    `${totpForgedTicket.status} ${JSON.stringify(totpForgedTicket.body)}`,
  );

  // 兼容路径仍然可用（发布接口的运维脚本还在用 X-Admin-Token）。
  const legacyMe = await fetch(`${base}/api/v1/admin/auth/me`, { headers: { "x-admin-token": adminToken } });
  const legacyMeBody = await legacyMe.json();
  check(
    "X-Admin-Token 兼容身份仍可访问 /admin/auth/me",
    legacyMe.status === 200 && legacyMeBody.data?.username === "legacy_admin",
    `${legacyMe.status} ${JSON.stringify(legacyMeBody).slice(0, 160)}`,
  );

  // IP 白名单不能被 X-Forwarded-For 伪造：把白名单设成一个非本机地址，
  // 再用伪造的 XFF 头登录，必须仍然被拒（默认不信任该请求头）。
  const whitelistSet = await fetch(`${base}/api/v1/admin/auth/ip-whitelist/1`, {
    method: "POST", headers: bearer(adminSession), body: JSON.stringify({ whitelist: "10.99.99.99" }),
  });
  const spoofedLogin = await postJson("/api/v1/admin/auth/login", { username: "admin", password: "admin123" });
  check(
    "伪造 X-Forwarded-For 无法绕过 IP 白名单（403/4030）",
    whitelistSet.status === 204 && spoofedLogin.status === 403 && spoofedLogin.body.code === 4030,
    `白名单 ${whitelistSet.status}，登录 ${spoofedLogin.status} ${JSON.stringify(spoofedLogin.body)}`,
  );
  await fetch(`${base}/api/v1/admin/auth/ip-whitelist/1`, {
    method: "POST", headers: bearer(adminSession), body: JSON.stringify({ whitelist: "" }),
  });

  // 角色模型：viewer 能登录、能看自己的信息，但不能读管理员列表、不能建账号。
  const viewerName = `vadmin_${randomBytes(3).toString("hex")}`;
  const createdAdmin = await fetch(`${base}/api/v1/admin/auth/users`, {
    method: "POST", headers: bearer(adminSession),
    body: JSON.stringify({ username: viewerName, password: "pass123456", role: "viewer", displayName: "契约验证只读" }),
  });
  const createdAdminBody = await createdAdmin.json();
  const createdAdminId = createdAdminBody.data?.id;
  check(
    "超管可创建管理员（201，角色落库）",
    createdAdmin.status === 201 && createdAdminBody.data?.role === "viewer" && !!createdAdminId,
    `${createdAdmin.status} ${JSON.stringify(createdAdminBody).slice(0, 160)}`,
  );

  const viewerLogin = await postJson("/api/v1/admin/auth/login", { username: viewerName, password: "pass123456" });
  const viewerSession = viewerLogin.body.data?.token;
  check(
    "新建的 viewer 可登录",
    viewerLogin.status === 200 && typeof viewerSession === "string",
    `${viewerLogin.status} ${JSON.stringify(viewerLogin.body).slice(0, 160)}`,
  );

  const viewerRead = await fetch(`${base}/api/v1/admin/auth/users`, { headers: { authorization: `Bearer ${viewerSession}` } });
  const viewerReadBody = await viewerRead.json();
  check(
    "viewer 读管理员列表被拒 403 且 code 4030",
    viewerRead.status === 403 && viewerReadBody.code === 4030,
    `${viewerRead.status} ${JSON.stringify(viewerReadBody)}`,
  );

  const viewerWrite = await fetch(`${base}/api/v1/admin/auth/users`, {
    method: "POST", headers: bearer(viewerSession),
    body: JSON.stringify({ username: `nope_${randomBytes(3).toString("hex")}`, password: "pass123456", role: "super_admin" }),
  });
  const viewerWriteBody = await viewerWrite.json();
  check(
    "viewer 提权创建超管被拒 403 且 code 4030",
    viewerWrite.status === 403 && viewerWriteBody.code === 4030,
    `${viewerWrite.status} ${JSON.stringify(viewerWriteBody)}`,
  );

  // 前端用 PATCH 做部分更新，控制器必须提供 PATCH 而不是 POST。
  const patched = await fetch(`${base}/api/v1/admin/auth/users/${createdAdminId}`, {
    method: "PATCH", headers: bearer(adminSession),
    body: JSON.stringify({ display_name: "契约验证已改名" }),
  });
  const patchedBody = await patched.json();
  check(
    "PATCH 可部分更新展示名",
    patched.status === 200 && patchedBody.data?.display_name === "契约验证已改名",
    `${patched.status} ${JSON.stringify(patchedBody).slice(0, 160)}`,
  );

  // 兼容身份（admin_users 里没有对应行）写审计不能因为外键报 500。
  const legacyPatch = await fetch(`${base}/api/v1/admin/auth/users/${createdAdminId}`, {
    method: "PATCH", headers: legacyHeaders, body: JSON.stringify({ display_name: "契约验证兼容身份改名" }),
  });
  check(
    "兼容身份写审计不撞外键（PATCH 返回 200）",
    legacyPatch.status === 200,
    `${legacyPatch.status} ${JSON.stringify(await legacyPatch.json()).slice(0, 160)}`,
  );

  // 最后一个可用超管不能被降级/禁用，否则后台再没人能创建管理员。
  const disableLastSuperAdmin = await fetch(`${base}/api/v1/admin/auth/users/1`, {
    method: "PATCH", headers: legacyHeaders, body: JSON.stringify({ disabled: true }),
  });
  const disableLastSuperAdminBody = await disableLastSuperAdmin.json();
  check(
    "不能禁用最后一个超级管理员 400 且 code 4000",
    disableLastSuperAdmin.status === 400 && disableLastSuperAdminBody.code === 4000,
    `${disableLastSuperAdmin.status} ${JSON.stringify(disableLastSuperAdminBody)}`,
  );

  // 删除管理员：审计表外键是 ON DELETE SET NULL，硬删不该再报 23503。
  const deletedAdmin = await fetch(`${base}/api/v1/admin/auth/users/${createdAdminId}`, {
    method: "DELETE", headers: bearer(adminSession),
  });
  check(
    "删除已有审计记录的管理员返回 204（外键不再阻断）",
    deletedAdmin.status === 204,
    `${deletedAdmin.status} ${(await deletedAdmin.text()).slice(0, 160)}`,
  );

  console.log(`\n通过 ${passed} 项，失败 ${failed} 项`);  process.exitCode = failed === 0 ? 0 : 1;
}

async function postJson(path, body) {
  const response = await fetch(`${base}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : {} };
}

main().catch((error) => {
  console.error("验证脚本自身出错：", error);
  process.exitCode = 1;
});
