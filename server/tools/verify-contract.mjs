// 契约验证脚本。
//
// 迁移到 NestJS 后逐项核对响应形状是否与旧实现一致 —— 线上有装机客户端，
// 而 /api/v1/app/bootstrap 本身就是推送修复的通道，坏掉就没有补救手段了。
//
// 用法（需要先启动一个本地实例，建议用独立的临时数据库）：
//   $env:PORT=4720; $env:DATABASE_PATH="./tmp/verify.sqlite"; $env:APK_DIR="./tmp/apk"
//   $env:AUTH_SECRET="0123456789012345678901234567890123456789"; $env:ADMIN_TOKEN="verify-token"
//   npm run dev
//   node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
import { randomBytes } from "node:crypto";

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
  const registered = await postJson("/api/v1/auth/register", { username, password: "pass123456" });
  check("注册返回 201", registered.status === 201, `实际 ${registered.status}`);
  check("code 为 0", registered.body.code === 0, JSON.stringify(registered.body).slice(0, 120));
  const issued = registered.body.data ?? {};
  check("accessToken / refreshToken 与 user 平铺在 data 下", typeof issued.accessToken === "string" && typeof issued.refreshToken === "string" && !!issued.user);
  check("expiresIn 为 900 秒", issued.expiresIn === 900, String(issued.expiresIn));

  const shortName = await postJson("/api/v1/auth/register", { username: "ab", password: "pass123456" });
  check("用户名过短 400 且 code 4003", shortName.status === 400 && shortName.body.code === 4003, JSON.stringify(shortName.body));

  const duplicate = await postJson("/api/v1/auth/register", { username, password: "pass123456" });
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

  section("收藏");
  const added = await fetch(`${base}/api/v1/favorites/tencent/97773`, { method: "POST", headers: { authorization: `Bearer ${token}` } });
  check("无请求体的 POST 不被校验拦截", added.status >= 200 && added.status < 300, `实际 ${added.status}`);
  const listed = await (await fetch(`${base}/api/v1/favorites`, { headers: { authorization: `Bearer ${token}` } })).json();
  check("data 是裸数组", Array.isArray(listed.data), JSON.stringify(listed).slice(0, 120));
  check("songId 是字符串", typeof listed.data?.[0]?.songId === "string", typeof listed.data?.[0]?.songId);
  const removed = await fetch(`${base}/api/v1/favorites/tencent/97773`, { method: "DELETE", headers: { authorization: `Bearer ${token}` } });
  check("DELETE 返回 2xx", removed.status >= 200 && removed.status < 300, `实际 ${removed.status}`);

  section("搜索（NDJSON 流）");
  const emptyKeyword = await fetch(`${base}/api/v1/search?keyword=`, { headers: { authorization: `Bearer ${token}` } });
  const emptyBody = await emptyKeyword.json();
  check("空关键词 400 且 code 4001", emptyKeyword.status === 400 && emptyBody.code === 4001, JSON.stringify(emptyBody));

  const started = Date.now();
  const search = await fetch(`${base}/api/v1/search?keyword=${encodeURIComponent("周杰伦")}&num=20&quality=10`, {
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
  check(`耗时在 6 秒内（实测 ${elapsed}ms）`, elapsed < 6000, `${elapsed}ms`);

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

  console.log(`\n通过 ${passed} 项，失败 ${failed} 项`);
  process.exitCode = failed === 0 ? 0 : 1;
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
