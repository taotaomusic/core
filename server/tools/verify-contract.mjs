// 契约验证脚本。
//
// 迁移到 NestJS 后逐项核对响应形状是否与旧实现一致 —— 线上有装机客户端，
// 而 /api/v1/app/bootstrap 本身就是推送修复的通道，坏掉就没有补救手段了。
//
// 用法（需要先启动一个本地实例，务必指向独立的验证库）：
//   node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
//   $env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
//   $env:PORT=4720; $env:APK_DIR="./tmp/apk"
//   $env:AUTH_SECRET="0123456789012345678901234567890123456789"
//   $env:ADMIN_INITIAL_PASSWORD="verify-initial-123456"
//   $env:NODE_ENV="test"; $env:EMAIL_VERIFICATION_TEST_CODE="123456"
//   $env:IM_ENABLED="false"
//   $env:ADMIN_RATE_LIMIT="1000"
//   npm run dev
//   node tools/verify-contract.mjs http://127.0.0.1:4720
//
// 两个容易踩的坑：
//   * IM_ENABLED 必须显式设为 false。.env 里通常是 true，服务会继承它，
//     于是「未启用 IM 时会话入口返回 503/5031」这一项会变成 502/5020。
//   * 每次运行前必须重置验证库。版本/rollout 类的断言依赖空库，
//     上一轮留下的 release 记录会让「rollout=0 不下发」失败。
//   * EMAIL_VERIFICATION_TEST_CODE 要同时给**脚本自己**的环境，脚本会读它做断言。
//   * ADMIN_INITIAL_PASSWORD 必须与服务启动时的环境一致：脚本要用它完成
//     默认管理员的首次登录与强制改密，才能拿到后续断言要用的管理会话。
//   * CORS_ALLOWED_ORIGINS 必须包含 https://verify.example，否则「白名单来源
//     回显自身 Origin」一条会失败（默认不下发任何 CORS 头，失败即证明这一点）。
//   * dist/public 必须先构建（npm run build:frontend），否则 /admin 下的 CSP
//     与主题脚本断言拿不到 200。
//   * dist/share-player 必须存在（node tools/build-web-player.mjs），否则分享页
//     入口的缓存头断言拿不到 200。
//   * ADMIN_RATE_LIMIT 必须显式调大（默认 60 次 / 15 分钟，按来源地址）。本脚本一次
//     运行要打上百次管理接口，用默认值会在中途把自己限流掉，报一批 4290 —— 失败位置
//     还取决于脚本里请求的顺序，看起来像业务坏了。这个变量只该在验证/本地环境设置。
//   全绿应为『通过 N 项，失败 0 项』。检查项数量随脚本版本变化，以实际输出为准。
import { createHash, randomBytes } from "node:crypto";

const base = (process.argv[2] ?? "http://127.0.0.1:4720").replace(/\/+$/, "");

/**
 * 默认管理员的初始口令，与服务启动环境共用同一个变量。
 *
 * 服务端不再有写死的默认口令；契约验证必须显式提供，否则拿不到管理会话，
 * 后面所有管理端断言都会连锁失败。
 */
const initialAdminPassword = process.env.ADMIN_INITIAL_PASSWORD ?? "";

/** 强制改密后使用的新口令。 */
const NEW_ADMIN_PASSWORD = "verify-admin-pass-12345";

/** 管理端会话令牌。由下面的「管理端会话准备」段落填充。 */
let adminSession = "";

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

  // ==================== 管理端会话准备 ====================
  //
  // 必须在所有管理端断言之前完成：管理接口只接受登录后签发的会话令牌
  // （静态 X-Admin-Token 已整体移除），而默认管理员启动时被标记为
  // 「首次登录必须改密」，改密前除 me / change-password 外一律 403/4031。
  // 所以这里先走完整链路：初始口令登录 → 验证拦截 → 改密 → 新口令重登。
  section("管理端会话准备：强制改密与 Bearer 会话");

  if (!initialAdminPassword) {
    throw new Error("必须设置 ADMIN_INITIAL_PASSWORD（需与服务启动环境一致）");
  }

  const bootstrapLogin = await postJson("/api/v1/admin/auth/login", {
    username: "admin", password: initialAdminPassword,
  });
  const pendingToken = bootstrapLogin.body.data?.token;
  check(
    "默认管理员用初始口令登录成功，且标记为必须改密",
    bootstrapLogin.status === 200 && typeof pendingToken === "string"
      && bootstrapLogin.body.data?.admin?.must_change_password === true,
    `${bootstrapLogin.status} ${JSON.stringify(bootstrapLogin.body).slice(0, 160)}`,
  );

  const pendingBlocked = await fetch(`${base}/api/v1/admin/auth/users`, {
    headers: { authorization: `Bearer ${pendingToken}` },
  });
  const pendingBlockedBody = await pendingBlocked.json();
  check(
    "改密前访问其它管理接口 403 且 code 4031",
    pendingBlocked.status === 403 && pendingBlockedBody.code === 4031,
    `${pendingBlocked.status} ${JSON.stringify(pendingBlockedBody).slice(0, 160)}`,
  );

  const changedPassword = await fetch(`${base}/api/v1/admin/auth/change-password`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${pendingToken}` },
    body: JSON.stringify({ oldPassword: initialAdminPassword, newPassword: NEW_ADMIN_PASSWORD }),
  });
  check("强制改密成功（204）", changedPassword.status === 204, `实际 ${changedPassword.status}`);

  const relogin = await postJson("/api/v1/admin/auth/login", {
    username: "admin", password: NEW_ADMIN_PASSWORD,
  });
  adminSession = relogin.body.data?.token ?? "";
  check(
    "新口令登录成功且不再要求改密",
    relogin.status === 200 && typeof adminSession === "string" && adminSession.length > 0
      && relogin.body.data?.admin?.must_change_password === false,
    `${relogin.status} ${JSON.stringify(relogin.body).slice(0, 160)}`,
  );

  const stalePassword = await postJson("/api/v1/admin/auth/login", {
    username: "admin", password: initialAdminPassword,
  });
  check(
    "初始口令改密后失效（401/4011）",
    stalePassword.status === 401 && stalePassword.body.code === 4011,
    `${stalePassword.status} ${JSON.stringify(stalePassword.body).slice(0, 160)}`,
  );

  // ==================== 管理路由逐条无凭据探测 ====================
  //
  // 管理端授权全靠 `@AdminGuarded()` 这一个装饰器。它是**白名单式**的：漏标一条
  // 路由，那条路由就变成裸奔（既不校验会话也不校验角色），而且 TypeScript 不会报错、
  // 启动也不会失败 —— 只能靠枚举来兜底。
  //
  // 期望 401/4013 而不是 401/4010：4013 说明 [AdminAuthGuard] 确实跑了。
  // 若某天有人把 `@Public()` 从控制器上摘掉，全局访问令牌守卫会抢先返回 4010，
  // 这条断言同样会失败，等于顺带看住了 `@Public()`。
  section("管理路由逐条无凭据探测（漏标 @AdminGuarded 即裸奔）");
  const guardedAdminRoutes = [
    // admin/auth —— 公开路由（login / totp-verify / logout）刻意不在表内
    ["GET", "/api/v1/admin/auth/me"],
    ["POST", "/api/v1/admin/auth/change-password"],
    ["POST", "/api/v1/admin/auth/totp-enable"],
    ["POST", "/api/v1/admin/auth/totp-confirm"],
    ["POST", "/api/v1/admin/auth/totp-disable"],
    ["GET", "/api/v1/admin/auth/users"],
    ["POST", "/api/v1/admin/auth/users"],
    ["PATCH", "/api/v1/admin/auth/users/1"],
    ["DELETE", "/api/v1/admin/auth/users/1"],
    ["GET", "/api/v1/admin/auth/audit-log"],
    ["GET", "/api/v1/admin/auth/ip-whitelist/1"],
    ["POST", "/api/v1/admin/auth/ip-whitelist/1"],
    // app/admin —— 发布管理
    ["GET", "/api/v1/app/admin/releases"],
    ["POST", "/api/v1/app/admin/releases"],
    ["POST", "/api/v1/app/admin/rollout"],
    ["POST", "/api/v1/app/admin/min-version"],
    ["GET", "/api/v1/app/admin/config"],
    ["GET", "/api/v1/app/admin/patches"],
    ["POST", "/api/v1/app/admin/patches"],
    ["POST", "/api/v1/app/admin/patch-rollout"],
    ["POST", "/api/v1/app/admin/config"],
    // desktop/admin —— Windows 发布管理
    ["GET", "/api/v1/desktop/admin/releases"],
    ["POST", "/api/v1/desktop/admin/artifacts"],
    ["POST", "/api/v1/desktop/admin/releases"],
    ["POST", "/api/v1/desktop/admin/rollout"],
    ["POST", "/api/v1/desktop/admin/min-version"],
    // app/admin/users —— 用户隐私数据
    ["GET", "/api/v1/app/admin/users"],
    ["GET", "/api/v1/app/admin/users/1/playback"],
    ["POST", "/api/v1/app/admin/users/1/disabled"],
    ["DELETE", "/api/v1/app/admin/users/1"],
    // app/admin/image-keys
    ["GET", "/api/v1/app/admin/image-keys"],
    ["POST", "/api/v1/app/admin/image-keys"],
    ["DELETE", "/api/v1/app/admin/image-keys/1"],
    // app/admin/announcements
    ["GET", "/api/v1/app/admin/announcements"],
    ["POST", "/api/v1/app/admin/announcements"],
    ["POST", "/api/v1/app/admin/announcements/1"],
    ["POST", "/api/v1/app/admin/announcements/1/enabled"],
    ["POST", "/api/v1/app/admin/announcements/1/pinned"],
    ["DELETE", "/api/v1/app/admin/announcements/1"],
    // app/admin/music-sources —— 音源账号（凭据）
    ["GET", "/api/v1/app/admin/music-sources"],
    ["GET", "/api/v1/app/admin/music-sources/available"],
    ["POST", "/api/v1/app/admin/music-sources"],
    ["PATCH", "/api/v1/app/admin/music-sources/1"],
    ["PUT", "/api/v1/app/admin/music-sources/1/enabled"],
    ["POST", "/api/v1/app/admin/music-sources/1/probe"],
    ["DELETE", "/api/v1/app/admin/music-sources/1"],
    ["POST", "/api/v1/app/admin/music-sources/sms"],
    ["POST", "/api/v1/app/admin/music-sources/login"],
  ];
  for (const [method, path] of guardedAdminRoutes) {
    const response = await fetch(`${base}${path}`, { method });
    const body = await response.json().catch(() => ({}));
    check(
      `无凭据 ${method} ${path} 被拒 401/4013`,
      response.status === 401 && body.code === 4013,
      `${response.status} ${JSON.stringify(body).slice(0, 120)}`,
    );
  }

  // 公开路由不能被顺手锁死：这几条没有会话也必须能到达处理器。
  // 断言的是「不是 401/4013」，因为入参不合法本来就会 400。
  const publicAdminRoutes = [
    ["POST", "/api/v1/admin/auth/login"],
    ["POST", "/api/v1/admin/auth/totp-verify"],
    ["POST", "/api/v1/admin/auth/logout"],
    ["GET", "/api/v1/app/announcements"],
  ];
  for (const [method, path] of publicAdminRoutes) {
    const response = await fetch(`${base}${path}`, { method });
    check(
      `公开路由 ${method} ${path} 未被管理员守卫拦截`,
      !(response.status === 401 && (await response.json()).code === 4013),
      `实际 ${response.status}`,
    );
  }

  // ==================== 安全响应头与跨域 ====================
  //
  // 这两项都是「不配置就等于没有」的控制：CSP 漏了没人会发现，CORS 写通配
  // 也不会报错，只能靠断言盯住。
  //
  // 白名单断言要求服务启动时设置 `CORS_ALLOWED_ORIGINS=https://verify.example`；
  // 没设置时下面的「命中白名单」一条会失败，这本身也是对的 —— 它证明默认
  // 不下发任何 CORS 头。
  section("安全响应头与跨域");
  const adminPage = await fetch(`${base}/admin/`);
  const csp = adminPage.headers.get("content-security-policy") ?? "";
  check(
    "管理后台下发 CSP 且 script-src 只允许 'self'",
    adminPage.status === 200 && /script-src 'self'(;|$)/.test(csp) && !/script-src[^;]*unsafe-inline/.test(csp),
    `HTTP ${adminPage.status}，CSP=${csp || "(缺失)"}`,
  );
  check(
    "CSP 禁止被 iframe 嵌套（frame-ancestors none）",
    /frame-ancestors 'none'/.test(csp),
    csp || "(缺失)",
  );
  const themeBootstrap = await fetch(`${base}/admin/theme-bootstrap.js`);
  check(
    "主题预置脚本已外置且可加载（否则暗色模式会闪烁）",
    themeBootstrap.status === 200 && (themeBootstrap.headers.get("content-type") ?? "").includes("javascript"),
    `HTTP ${themeBootstrap.status} ${themeBootstrap.headers.get("content-type")}`,
  );

  // 分享页的入口文件是**不带内容哈希的固定名**，而 JS 胶水必须提供 wasm 要 import 的
  // 那批 `js_code` 实现 —— 两者一旦新旧混用，浏览器直接抛
  // `LinkError: WebAssembly.instantiate(): Import #N "js_code" ... requires a callable`
  // （2026-09-21 真实事故，且每次发版都会复现）。所以这里钉住：入口文件必须可协商
  // （`no-cache` + ETag），只有带内容哈希的文件才允许 `immutable`。
  // 前提：`dist/share-player` 存在（`node tools/build-web-player.mjs` 产出）。
  //
  // ⚠️ **不要在这里断言 304**：undici 的 `fetch` 走条件请求拿不到 304（实测 ETag 与
  // Last-Modified 完全一致仍回 200），会把正确的服务端行为判成失败。
  // 条件请求的行为由 `node tools/verify-static-cache.ts` 用原生 http 覆盖。
  const shareEntry = await fetch(`${base}/share/taotao-share-player.js`);
  check(
    "分享页入口 JS 可协商缓存（否则新旧 JS / wasm 会混用）",
    shareEntry.status === 200 &&
      shareEntry.headers.get("cache-control") === "no-cache" &&
      Boolean(shareEntry.headers.get("etag")),
    `HTTP ${shareEntry.status} cache-control=${shareEntry.headers.get("cache-control")} etag=${shareEntry.headers.get("etag")}`,
  );
  await shareEntry.arrayBuffer();

  const bootstrapPath = "/api/v1/app/bootstrap?versionCode=1&sdk=36&deviceId=verify-cors";
  const foreignOrigin = await fetch(`${base}${bootstrapPath}`, {
    headers: { origin: "https://evil.example" },
  });
  check(
    "非白名单来源不下发 access-control-allow-origin",
    foreignOrigin.headers.get("access-control-allow-origin") === null,
    `实际 ${foreignOrigin.headers.get("access-control-allow-origin")}`,
  );
  check(
    "响应声明 Vary: Origin（避免缓存把 A 站的响应喂给 B 站）",
    /\borigin\b/i.test(foreignOrigin.headers.get("vary") ?? ""),
    `实际 ${foreignOrigin.headers.get("vary")}`,
  );
  const allowedOrigin = await fetch(`${base}${bootstrapPath}`, {
    headers: { origin: "https://verify.example" },
  });
  check(
    "白名单来源回显自身 Origin（而不是通配 *）",
    allowedOrigin.headers.get("access-control-allow-origin") === "https://verify.example",
    `实际 ${allowedOrigin.headers.get("access-control-allow-origin")}`,
  );

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

  // 头像上传：格式判定必须看文件头，不能信请求里的 Content-Type。
  // 验证环境没配 LSKY_API_KEY，所以「魔数正确」的那条会停在「服务未配置」——
  // 这正好把两条路径区分开：假图片在格式判定就被拒，真图片才走到图床调用。
  section("头像上传：按文件头判定格式");
  const notAnImage = await fetch(`${base}/api/v1/auth/avatar`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
    body: (() => {
      const form = new FormData();
      // 声明成 image/png，内容却是纯文本 —— 只校验 mimetype 的实现会放行。
      form.append("file", new Blob([Buffer.from("this is definitely not a png")], { type: "image/png" }), "fake.png");
      return form;
    })(),
  });
  const notAnImageBody = await notAnImage.json();
  check(
    "伪装成 image/png 的文本被拒 400 且 code 4000",
    notAnImage.status === 400 && notAnImageBody.code === 4000,
    `${notAnImage.status} ${JSON.stringify(notAnImageBody)}`,
  );
  check(
    "拒绝原因是格式判定而不是其它前置条件",
    String(notAnImageBody.message ?? "").includes("PNG"),
    String(notAnImageBody.message ?? ""),
  );
  const realPngHeader = await fetch(`${base}/api/v1/auth/avatar`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
    body: (() => {
      const form = new FormData();
      // 只有 PNG 魔数，没有完整像素数据。魔数校验过关后才会走到图床调用。
      const png = Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        Buffer.alloc(32),
      ]);
      form.append("file", new Blob([png], { type: "application/octet-stream" }), "real.png");
      return form;
    })(),
  });
  const realPngHeaderBody = await realPngHeader.json();
  check(
    "PNG 魔数通过格式判定（即使 Content-Type 声明成 octet-stream）",
    realPngHeader.status === 400 && String(realPngHeaderBody.message ?? "").includes("未配置"),
    `${realPngHeader.status} ${JSON.stringify(realPngHeaderBody)}`,
  );

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
    "短链元数据公开，试听地址指向自家转发端点（不下发上游直链）",
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
  // 试听**不再在服务端裁剪、也不落盘**：这里直接把上游的完整标准音频按 Range 转发出去。
  // 因此 content-type 与总字节数都由上游决定（实测《晴天》标准音质是 6.5 MB 的 m4a；
  // 上游偶发 110001 风控时 `resolveLink` 会回退到 v2 的低码率试听链，那只有约 960 KB），
  // 所以这里**不断言具体大小、也不断言 ID3 魔数**，只守住「是真实音频流 + Range 可用」。
  const sharePreviewTotal = Number((sharePreviewResponse.headers.get("content-range") ?? "").split("/")[1]);
  check(
    "试听直接转发上游音频流（不再由服务端裁剪/落盘）并支持 Range",
    sharePreviewResponse.status === 206 &&
      sharePreviewResponse.headers.get("content-type")?.startsWith("audio/") === true &&
      sharePreviewResponse.headers.get("content-range")?.startsWith("bytes 0-1023/") === true &&
      sharePreviewBytes.length === 1_024 &&
      sharePreviewTotal > 100_000,
    `${sharePreviewResponse.status} ${sharePreviewResponse.headers.get("content-type")} ${sharePreviewResponse.headers.get("content-range")} ${sharePreviewBytes.length}`,
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

  section("单曲倒带日记");
  const diarySource = "tencent";
  const diarySongId = `diary${randomBytes(4).toString("hex")}`;
  const diaryNow = Date.now();
  const diaryReport = await fetch(`${base}/api/v1/playback/sessions`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      sessionId: `sess${randomBytes(6).toString("hex")}`,
      deviceId: `dev${randomBytes(4).toString("hex")}`,
      source: diarySource,
      songId: diarySongId,
      startedAt: diaryNow - 5_000,
      lastPlayedAt: diaryNow,
      // 时长 6s → 合格阈值 min(30s, 3s)=3s；听 4s 计一次合格播放。
      listenedMs: 4_000,
      completed: true,
      durationSeconds: 6,
    }),
  });
  check("倒带日记：上报一次合格播放会话成功", diaryReport.status >= 200 && diaryReport.status < 300, `实际 ${diaryReport.status}`);

  const diaryResponse = await fetch(
    `${base}/api/v1/playback/diary?source=${diarySource}&songId=${diarySongId}`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  const diaryBody = await diaryResponse.json();
  const diary = diaryBody.data;
  check(
    "倒带日记：首次邂逅/上次收听为 number，播放次数≥1",
    diaryResponse.status === 200 &&
      typeof diary?.firstPlayedAt === "number" &&
      typeof diary?.lastPlayedAt === "number" &&
      diary?.playCount >= 1,
    `${diaryResponse.status} ${JSON.stringify(diaryBody).slice(0, 160)}`,
  );
  check(
    "倒带日记：近半年/近一年计数≥1，狂热循环单日计数≥1",
    diary?.playsLastHalfYear >= 1 && diary?.playsLastYear >= 1 && diary?.peakDay?.count >= 1,
    JSON.stringify({ half: diary?.playsLastHalfYear, year: diary?.playsLastYear, peak: diary?.peakDay }),
  );
  check(
    "倒带日记：播放记录是数组且含刚上报的会话",
    Array.isArray(diary?.records) && diary.records.length >= 1 && typeof diary.records[0]?.startedAt === "number",
    JSON.stringify(diary?.records).slice(0, 160),
  );
  check(
    "倒带日记：yearly 是 6 个自然年且末项为今年、今年计数≥1",
    Array.isArray(diary?.yearly) && diary.yearly.length === 6 &&
      diary.yearly[5]?.year === new Date().getFullYear() && diary.yearly[5]?.count >= 1,
    JSON.stringify(diary?.yearly),
  );
  check(
    "倒带日记：dailyCounts 是长度 180 的逐日数组且末项≥1（含今天的播放）",
    Array.isArray(diary?.dailyCounts) && diary.dailyCounts.length === 180 &&
      diary.dailyCounts[179] >= 1,
    JSON.stringify({ len: diary?.dailyCounts?.length, last: diary?.dailyCounts?.[179] }),
  );
  const diaryNoToken = await fetch(`${base}/api/v1/playback/diary?source=${diarySource}&songId=${diarySongId}`);
  const diaryNoTokenBody = await diaryNoToken.json();
  check(
    "倒带日记：无令牌 401 且 code 4010（不能是 403）",
    diaryNoToken.status === 401 && diaryNoTokenBody.code === 4010,
    `${diaryNoToken.status} ${JSON.stringify(diaryNoTokenBody)}`,
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

  const suggestions = await fetch(
    `${base}/api/v1/search/suggestions?keyword=${encodeURIComponent("雨")}&limit=10&source=kuwo`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  const suggestionsBody = await suggestions.json();
  check(
    "酷我搜索联想返回 JSON 字符串数组",
    suggestions.status === 200
      && suggestionsBody.code === 0
      && Array.isArray(suggestionsBody.data)
      && suggestionsBody.data.length > 0
      && suggestionsBody.data.every((word) => typeof word === "string" && word.length > 0),
    `${suggestions.status} ${JSON.stringify(suggestionsBody).slice(0, 180)}`,
  );
  check(
    "搜索联想包含真实相关词「雨爱」",
    suggestionsBody.data?.includes("雨爱") === true,
    JSON.stringify(suggestionsBody.data),
  );

  const emptySuggestion = await fetch(`${base}/api/v1/search/suggestions?keyword=`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const emptySuggestionBody = await emptySuggestion.json();
  check(
    "搜索联想空关键词 400 且 code 4001",
    emptySuggestion.status === 400 && emptySuggestionBody.code === 4001,
    `${emptySuggestion.status} ${JSON.stringify(emptySuggestionBody)}`,
  );

  const hotSearch = await fetch(`${base}/api/v1/search/hot?limit=10&source=kuwo`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const hotSearchBody = await hotSearch.json();
  check(
    "酷我热搜返回官方字段的 JSON 对象数组",
    hotSearch.status === 200
      && hotSearchBody.code === 0
      && Array.isArray(hotSearchBody.data)
      && hotSearchBody.data.length > 0
      && hotSearchBody.data.every((item) => typeof item?.keyword === "string"
        && typeof item?.sort === "number" && typeof item?.searchType === "number"),
    `${hotSearch.status} ${JSON.stringify(hotSearchBody).slice(0, 220)}`,
  );

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

  // 带上 Range 只是防御：万一将来播放接口真的裸奔了，这条断言会失败但不必把整首歌拉下来。
  // 鉴权在守卫层，与 Range 无关，不影响判定。
  const full = await fetch(`${base}/api/v1/songs/97773/play?quality=10`, { headers: { range: "bytes=0-2047" } });
  const fullBody = await full.json().catch(() => ({}));
  // 播放链路受全局 `AccessTokenGuard` 保护：无令牌必须 **401/4010**，不能是 403 ——
  // 403 会让客户端把「没登录」当成「没权限」直接判定失败，而不是去续期。
  //
  // 早先这里写的是「不返回 401」但条件只查 `!== 403`：**名字与条件相反**，
  // 而且返回 200 或 500 同样会「通过」—— 播放接口一旦意外裸奔，这条断言不会响。
  // 实测（2026-09-18）：search / info / link / lyrics / play 无令牌全部 401。
  check(
    "播放接口无令牌时返回 401/4010（不能是 403）",
    full.status === 401 && fullBody.code === 4010,
    `${full.status} ${JSON.stringify(fullBody).slice(0, 120)}`,
  );

  // ==================== 开放搜歌 API（API Key） ====================
  //
  // 面向第三方的只读搜歌 / 歌词 / 取址通道，与内部接口的关键差异：
  //   * 鉴权是独立的 API Key（tt_ 前缀），用户访问令牌**不能**当开放 Key 用 ——
  //     否则泄露的 accessToken 就能白嫖开放配额。
  //   * 失败一律 401/4014，不能是 403：接入方要能区分「key 该换了」与「没权限」，
  //     客户端也不能把 403 当成续期信号。
  //   * 不下发 audioUrl：开放端点只出元信息，取址必须再走 /open/songs/{id}/link。
  //
  // 管理端 key 的准备、停用与吊销都放在本段内：adminSession（管理端会话准备段）
  // 与 token（鉴权段）此刻均已就绪；key 供本段全部开放端点复用，段尾清理。
  section("开放搜歌 API（API Key）");

  // ---- 管理端：无会话探测 / 创建 / 列表 ----
  const openKeyNoAuth = await fetch(`${base}/api/v1/app/admin/open-api-keys`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name: "无会话不应成功" }),
  });
  const openKeyNoAuthBody = await openKeyNoAuth.json().catch(() => ({}));
  check(
    "无管理会话创建开放 Key 被拒 401（管理守卫，不能是 403）",
    openKeyNoAuth.status === 401,
    `${openKeyNoAuth.status} ${JSON.stringify(openKeyNoAuthBody).slice(0, 160)}`,
  );

  const openKeyCreate = await fetch(`${base}/api/v1/app/admin/open-api-keys`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
    body: JSON.stringify({ name: "契约验证开放 Key" }),
  });
  const openKeyCreateBody = await openKeyCreate.json().catch(() => ({}));
  const openApiKey = openKeyCreateBody.data?.apiKey ?? "";
  const openKeyId = openKeyCreateBody.data?.id;
  check(
    "创建开放 Key 返回 201 且信封 code 0",
    openKeyCreate.status === 201 && openKeyCreateBody.code === 0,
    `${openKeyCreate.status} ${JSON.stringify(openKeyCreateBody).slice(0, 160)}`,
  );
  check(
    "data.apiKey 是 tt_ 开头的明文字符串（仅创建时返回这一次）",
    typeof openKeyCreateBody.data?.apiKey === "string"
      && openKeyCreateBody.data.apiKey.startsWith("tt_"),
    typeof openKeyCreateBody.data?.apiKey,
  );
  check(
    "data.keyPrefix 是明文 apiKey 的前 12 位",
    typeof openKeyCreateBody.data?.keyPrefix === "string"
      && openKeyCreateBody.data.keyPrefix === openApiKey.slice(0, 12),
    `${openKeyCreateBody.data?.keyPrefix} vs ${openApiKey.slice(0, 12)}`,
  );
  check("data.id 是 number", typeof openKeyId === "number", typeof openKeyId);

  const openKeyList = await fetch(`${base}/api/v1/app/admin/open-api-keys`, {
    headers: { authorization: `Bearer ${adminSession}` },
  });
  const openKeyListBody = await openKeyList.json().catch(() => ({}));
  const openKeyRows = openKeyListBody.data;
  check(
    "GET 开放 Key 列表 200 且 data 是数组",
    openKeyList.status === 200 && openKeyListBody.code === 0 && Array.isArray(openKeyRows),
    `${openKeyList.status} ${JSON.stringify(openKeyListBody).slice(0, 160)}`,
  );
  check(
    "列表包含刚创建的 id",
    Array.isArray(openKeyRows) && openKeyRows.some((row) => row?.id === openKeyId),
    JSON.stringify(openKeyRows).slice(0, 160),
  );
  check(
    "列表元素不含明文 apiKey / key_hash 字段（只允许 keyPrefix）",
    Array.isArray(openKeyRows) && openKeyRows.length > 0
      && openKeyRows.every((row) => !("apiKey" in (row ?? {})) && !("key_hash" in (row ?? {}))),
    JSON.stringify(openKeyRows?.[0] ?? null).slice(0, 200),
  );

  // ---- 开放端点：有效 key 下的搜索 ----
  // 与内部搜索同一信任假设：source=tencent 无结果时下面的断言会失败 ——
  // 音源波动不在本脚本的容错范围内（现有搜索断言同样如此）。
  const openSearchStarted = Date.now();
  const openSearch = await fetch(
    `${base}/api/v1/open/search?keyword=${encodeURIComponent("周杰伦")}&num=10&quality=10&source=tencent`,
    { headers: { "x-api-key": openApiKey } },
  );
  const openSearchElapsed = Date.now() - openSearchStarted;
  const openSearchBody = await openSearch.json().catch(() => ({}));
  const openSongs = openSearchBody.data?.songs;
  const openFirstSong = openSongs?.[0];
  check(
    "开放搜索 200 且信封 code 0",
    openSearch.status === 200 && openSearchBody.code === 0,
    `${openSearch.status} ${JSON.stringify(openSearchBody).slice(0, 160)}`,
  );
  check(
    "data.songs 是非空数组",
    Array.isArray(openSongs) && openSongs.length > 0,
    `长度 ${Array.isArray(openSongs) ? openSongs.length : typeof openSongs}`,
  );
  check(
    "data.meta.count 是 number",
    typeof openSearchBody.data?.meta?.count === "number",
    typeof openSearchBody.data?.meta?.count,
  );
  check("首首 id 是 number", typeof openFirstSong?.id === "number", typeof openFirstSong?.id);
  check(
    "duration 是 mm:ss 或「网络歌曲」",
    /^\d{2}:\d{2}$|^网络歌曲$/.test(openFirstSong?.duration ?? ""),
    openFirstSong?.duration,
  );
  check(
    "coverUrl 以 https:// 开头或为空",
    (openFirstSong?.coverUrl ?? "") === "" || openFirstSong.coverUrl.startsWith("https://"),
    openFirstSong?.coverUrl,
  );
  check(
    "lyricUrl 指向 /api/v1/open/songs/ 前缀",
    String(openFirstSong?.lyricUrl ?? "").startsWith("/api/v1/open/songs/"),
    openFirstSong?.lyricUrl,
  );
  check(
    "不下发 audioUrl（开放端点只出元信息）",
    openFirstSong?.audioUrl === undefined,
    String(openFirstSong?.audioUrl),
  );
  check(
    "favorited 恒为 false（开放端点没有用户态）",
    openFirstSong?.favorited === false,
    String(openFirstSong?.favorited),
  );
  check(
    "access-control-allow-origin 为 *",
    openSearch.headers.get("access-control-allow-origin") === "*",
    String(openSearch.headers.get("access-control-allow-origin")),
  );
  // 沿用内部搜索的 3 秒惯例：开放端点复用同一条搜索链路，耗时量级应当相同。
  check(`开放搜索耗时在 3 秒内（实测 ${openSearchElapsed}ms）`, openSearchElapsed < 3000, `${openSearchElapsed}ms`);

  // ---- 开放端点：流式搜索 ----
  const openStream = await fetch(
    `${base}/api/v1/open/search/stream?keyword=${encodeURIComponent("周杰伦")}&num=5&source=tencent`,
    { headers: { "x-api-key": openApiKey } },
  );
  const openStreamText = await openStream.text();
  const openStreamLines = openStreamText.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  check(
    "流式搜索 content-type 含 x-ndjson",
    (openStream.headers.get("content-type") ?? "").includes("x-ndjson"),
    openStream.headers.get("content-type"),
  );
  check(
    "流式搜索首行 type 为 song",
    openStreamLines[0]?.type === "song",
    JSON.stringify(openStreamLines[0]).slice(0, 100),
  );
  const openStreamEnd = openStreamLines.at(-1);
  check(
    "流式搜索末行 type 为 end 且 meta.dropped 是 number",
    openStreamEnd?.type === "end" && typeof openStreamEnd?.meta?.dropped === "number",
    JSON.stringify(openStreamEnd).slice(0, 160),
  );

  // ---- 开放端点：参数校验与鉴权失败矩阵 ----
  const openEmptyKeyword = await fetch(`${base}/api/v1/open/search?keyword=`, {
    headers: { "x-api-key": openApiKey },
  });
  const openEmptyKeywordBody = await openEmptyKeyword.json().catch(() => ({}));
  check(
    "空关键词 400 且 code 4001",
    openEmptyKeyword.status === 400 && openEmptyKeywordBody.code === 4001,
    `${openEmptyKeyword.status} ${JSON.stringify(openEmptyKeywordBody).slice(0, 160)}`,
  );

  const openNoKey = await fetch(`${base}/api/v1/open/search?keyword=test`);
  const openNoKeyBody = await openNoKey.json().catch(() => ({}));
  check(
    "无 key 访问开放搜索 401 且 code 4014（不能是 403）",
    openNoKey.status === 401 && openNoKeyBody.code === 4014,
    `${openNoKey.status} ${JSON.stringify(openNoKeyBody).slice(0, 160)}`,
  );

  // ApiKeyGuard 挂在控制器类上，新增开放路由自动受保护。逐条探测**每个**开放端点
  // 在无 key 时都必须 401 —— 若某条路由漏挂守卫（或类级守卫被误删），它会静默裸奔，
  // 而单测「/open/search 无 key 401」并不能替其它路由背书。
  const openRoutesNoKey = [
    ["GET", "/api/v1/open/search?keyword=test"],
    ["GET", "/api/v1/open/search/stream?keyword=test"],
    ["GET", "/api/v1/open/songs/97773/lyrics"],
    ["GET", "/api/v1/open/songs/97773/link?quality=10"],
  ];
  for (const [method, path] of openRoutesNoKey) {
    const probe = await fetch(`${base}${path}`, { method });
    check(
      `无 key 访问 ${path.split("?")[0]} 必须 401（漏挂 ApiKeyGuard 即裸奔）`,
      probe.status === 401,
      `${probe.status}`,
    );
  }

  const openForgedKey = await fetch(`${base}/api/v1/open/search?keyword=test`, {
    headers: { "x-api-key": "tt_invalid_invalid_invalid_invalid_0000" },
  });
  const openForgedKeyBody = await openForgedKey.json().catch(() => ({}));
  check(
    "伪造 key 同样 401 且 code 4014",
    openForgedKey.status === 401 && openForgedKeyBody.code === 4014,
    `${openForgedKey.status} ${JSON.stringify(openForgedKeyBody).slice(0, 160)}`,
  );

  const openUserToken = await fetch(`${base}/api/v1/open/search?keyword=test`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const openUserTokenBody = await openUserToken.json().catch(() => ({}));
  check(
    "用户访问令牌不能当开放 key（仍 401/4014）",
    openUserToken.status === 401 && openUserTokenBody.code === 4014,
    `${openUserToken.status} ${JSON.stringify(openUserTokenBody).slice(0, 160)}`,
  );

  // ---- 开放端点：歌词 / 取址 ----
  const openLyrics = await fetch(`${base}/api/v1/open/songs/97773/lyrics`, {
    headers: { "x-api-key": openApiKey },
  });
  const openLyricsText = await openLyrics.text();
  check(
    "开放歌词 200 且 content-type 含 text/plain",
    openLyrics.status === 200 && (openLyrics.headers.get("content-type") ?? "").includes("text/plain"),
    `${openLyrics.status} ${openLyrics.headers.get("content-type")}`,
  );
  check("开放歌词体以 [ 开头", openLyricsText.startsWith("["), openLyricsText.slice(0, 40));

  // 两种投递方式都写进契约：X-API-Key 与 Authorization: Bearer 必须等效。
  const openBearerLyrics = await fetch(`${base}/api/v1/open/songs/97773/lyrics`, {
    headers: { authorization: `Bearer ${openApiKey}` },
  });
  const openBearerLyricsText = await openBearerLyrics.text();
  check(
    "Authorization: Bearer tt_... 与 X-API-Key 等效",
    openBearerLyrics.status === 200 && openBearerLyricsText.startsWith("["),
    `${openBearerLyrics.status} ${openBearerLyricsText.slice(0, 60)}`,
  );

  const openLink = await fetch(`${base}/api/v1/open/songs/97773/link?quality=10`, {
    headers: { "x-api-key": openApiKey },
  });
  const openLinkBody = await openLink.json().catch(() => ({}));
  check(
    "开放取址 200 且信封 code 0",
    openLink.status === 200 && openLinkBody.code === 0,
    `${openLink.status} ${JSON.stringify(openLinkBody).slice(0, 160)}`,
  );
  check(
    "取址 data.url 是非空字符串",
    typeof openLinkBody.data?.url === "string" && openLinkBody.data.url.length > 0,
    typeof openLinkBody.data?.url,
  );

  // ---- 回归：开放端点不能改写内部 401 语义 ----
  // 内部 /search 无令牌必须仍然是全局 AccessTokenGuard 的 401/4010 ——
  // 若新守卫把它改成了 4014，所有装机客户端的错误分支都会走错。
  const internalSearchNoAuth = await fetch(`${base}/api/v1/search?keyword=test&num=3&source=tencent`);
  const internalSearchNoAuthBody = await internalSearchNoAuth.json().catch(() => ({}));
  check(
    "内部 /api/v1/search 无令牌仍 401 且 code 4010",
    internalSearchNoAuth.status === 401 && internalSearchNoAuthBody.code === 4010,
    `${internalSearchNoAuth.status} ${JSON.stringify(internalSearchNoAuthBody).slice(0, 160)}`,
  );

  // ---- 段尾清理：停用 → 吊销，并验证失效后的 key 不可再用 ----
  const openKeyPatch = await fetch(`${base}/api/v1/app/admin/open-api-keys/${openKeyId}`, {
    method: "PATCH",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
    body: JSON.stringify({ enabled: false }),
  });
  check("停用开放 Key 返回 200", openKeyPatch.status === 200, `实际 ${openKeyPatch.status}`);

  const openDisabled = await fetch(`${base}/api/v1/open/search?keyword=test&num=3&source=tencent`, {
    headers: { "x-api-key": openApiKey },
  });
  const openDisabledBody = await openDisabled.json().catch(() => ({}));
  check(
    "停用后的 key 打开放搜索 401 且 code 4014",
    openDisabled.status === 401 && openDisabledBody.code === 4014,
    `${openDisabled.status} ${JSON.stringify(openDisabledBody).slice(0, 160)}`,
  );

  const openKeyDelete = await fetch(`${base}/api/v1/app/admin/open-api-keys/${openKeyId}`, {
    method: "DELETE",
    headers: { authorization: `Bearer ${adminSession}` },
  });
  check("删除开放 Key 返回 204", openKeyDelete.status === 204, `实际 ${openKeyDelete.status}`);

  const openRevoked = await fetch(`${base}/api/v1/open/search?keyword=test&num=3&source=tencent`, {
    headers: { "x-api-key": openApiKey },
  });
  const openRevokedBody = await openRevoked.json().catch(() => ({}));
  check(
    "吊销后的 key 打开放搜索 401 且 code 4014",
    openRevoked.status === 401 && openRevokedBody.code === 4014,
    `${openRevoked.status} ${JSON.stringify(openRevokedBody).slice(0, 160)}`,
  );

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
  const noAdmin = await fetch(`${base}/api/v1/app/admin/releases`, { headers: { authorization: "Bearer wrong" } });
  check("错误管理会话令牌 401 且 code 4013", noAdmin.status === 401 && (await noAdmin.json()).code === 4013, `实际 ${noAdmin.status}`);
  const releases = await (await fetch(`${base}/api/v1/app/admin/releases`, { headers: { authorization: `Bearer ${adminSession}` } })).json();
  check("正确管理令牌可列出发布", releases.code === 0 && Array.isArray(releases.data), JSON.stringify(releases).slice(0, 120));

  const guard = await fetch(`${base}/api/v1/app/admin/min-version`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
    body: JSON.stringify({ versionCode: 999999 }),
  });
  check("缺少全量版本时抬高下限被拒（409 / code 4091）", guard.status === 409 && (await guard.json()).code === 4091, `实际 ${guard.status}`);

  section("Windows 模块化发布");
  const noDesktopAdmin = await fetch(`${base}/api/v1/desktop/admin/releases`, {
    headers: { authorization: "Bearer wrong" },
  });
  check(
    "Windows 管理接口无有效凭据仍返回 4013",
    noDesktopAdmin.status === 401 && (await noDesktopAdmin.json()).code === 4013,
    `实际 ${noDesktopAdmin.status}`,
  );
  const moduleBytes = Buffer.from(("taotao-desktop-module-".repeat(10_000)) + "v999101");
  const moduleSha = createHash("sha256").update(moduleBytes).digest("hex");
  const uploadedModule = await fetch(`${base}/api/v1/desktop/admin/artifacts?sha256=${moduleSha}`, {
    method: "POST",
    headers: { authorization: `Bearer ${adminSession}` },
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
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
    body: JSON.stringify(desktopManifest),
  });
  check("Windows 清单登记成功", publishedDesktop.status === 201, `实际 ${publishedDesktop.status}`);
  const hiddenDesktop = await (
    await fetch(`${base}/api/v1/desktop/bootstrap?versionCode=999100&architecture=windows-x64&deviceId=verify-desktop`)
  ).json();
  check("Windows rollout=0 不下发", hiddenDesktop.data?.update?.available === false, JSON.stringify(hiddenDesktop.data?.update));

  await fetch(`${base}/api/v1/desktop/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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
    headers: { authorization: `Bearer ${adminSession}` },
    body: nextModuleBytes,
  });
  check("Windows 第二版模块上传成功", uploadedNextModule.status === 201, `实际 ${uploadedNextModule.status}`);
  const nextDesktop = await fetch(`${base}/api/v1/desktop/admin/releases`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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
  const adminConfig = await fetch(`${base}/api/v1/app/admin/config`, { headers: { authorization: `Bearer ${adminSession}` } });
  const adminConfigBody = await adminConfig.json();
  check(
    "管理端列配置 200（哨兵不能超出 int4 范围）",
    adminConfig.status === 200 && adminConfigBody.code === 0,
    `${adminConfig.status} ${JSON.stringify(adminConfigBody).slice(0, 120)}`,
  );

  const wroteConfig = await fetch(`${base}/api/v1/app/admin/config`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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
    { method: "POST", headers: { authorization: `Bearer ${adminSession}` }, body: placeholderApk },
  );
  check("登记一个 enabled=false 的版本", disabled.status === 201, `实际 ${disabled.status}`);
  const disabledDownload = await fetch(`${base}/api/v1/app/apk/999001`);
  check("停用版本下载返回 404", disabledDownload.status === 404, `实际 ${disabledDownload.status}`);

  // ⑥ 灰度分桶依赖 bucketOf 保持同步：一旦变成 async，find 的回调恒为真值，
  //    rollout=0 也会被下发。
  await fetch(`${base}/api/v1/app/admin/releases?versionCode=999002&versionName=9.9.2&rollout=0`, {
    method: "POST",
    headers: { authorization: `Bearer ${adminSession}` },
    body: placeholderApk,
  });
  const notRolledOut = await (
    await fetch(`${base}/api/v1/app/bootstrap?versionCode=999001&sdk=36&deviceId=verify-bucket`)
  ).json();
  check("rollout=0 的版本不下发", notRolledOut.data?.update?.available === false, JSON.stringify(notRolledOut.data?.update));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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
    headers: { authorization: `Bearer ${adminSession}` },
    body: fakeApk,
  });
  await fetch(`${base}/health`);
  check("未放量的版本不进响应头", (await headerOf("/health")) !== "999910", String(await headerOf("/health")));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
    body: JSON.stringify({ versionCode: 999910, percent: 50 }),
  });
  await fetch(`${base}/health`);
  check("灰度中（50%）的版本不进响应头", (await headerOf("/health")) !== "999910", String(await headerOf("/health")));

  await fetch(`${base}/api/v1/app/admin/rollout`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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
    headers: { "content-type": "application/json", authorization: `Bearer ${adminSession}` },
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

  // 管理会话已在上面的「管理端会话准备」段落取得（含强制改密流程）。
  check(
    "管理员登录返回会话 token 与 super_admin 身份",
    typeof adminSession === "string" && adminSession.length > 0,
    `会话长度 ${adminSession.length}`,
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

  // 静态 X-Admin-Token 兼容通道已整体移除：它曾同时绕过 2FA、IP 白名单、
  // 会话撤销与审计归属，且合成固定的 super_admin 身份。这里改为断言它确实失效。
  const legacyHeaderRejected = await fetch(`${base}/api/v1/admin/auth/me`, {
    headers: { "x-admin-token": "verify-token" },
  });
  check(
    "已移除的 X-Admin-Token 通道不再被接受（401/4013）",
    legacyHeaderRejected.status === 401 && (await legacyHeaderRejected.json()).code === 4013,
    `实际 ${legacyHeaderRejected.status}`,
  );

  // IP 白名单不能被 X-Forwarded-For 伪造：把白名单设成一个非本机地址，
  // 再用伪造的 XFF 头登录，必须仍然被拒（默认不信任该请求头）。
  const whitelistSet = await fetch(`${base}/api/v1/admin/auth/ip-whitelist/1`, {
    method: "POST", headers: bearer(adminSession), body: JSON.stringify({ whitelist: "10.99.99.99" }),
  });
  const spoofedLogin = await postJson("/api/v1/admin/auth/login", { username: "admin", password: NEW_ADMIN_PASSWORD });
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

  // ---- 业务管理接口的权限模型 ----
  // 发布、Windows 发布、用户、公告、图片 Key 这 5 个控制器此前只挂 AdminAuthGuard，
  // 没有任何角色校验：任何能登录后台的账号（包括 viewer）都能放量、删用户、导入密钥，
  // 而且这些操作一条审计都不留。下面逐个域验证「写被拒、读放行」，并确认写操作落进审计表。
  section("业务管理接口：角色校验与审计");

  // 造一个 admin 账号，用来证明「拒绝 viewer」不是因为接口整体坏了。
  const adminRoleName = `aadmin_${randomBytes(3).toString("hex")}`;
  const createdAdminRole = await fetch(`${base}/api/v1/admin/auth/users`, {
    method: "POST", headers: bearer(adminSession),
    body: JSON.stringify({
      username: adminRoleName, password: "pass123456", role: "admin", displayName: "契约验证管理员",
    }),
  });
  const createdAdminRoleBody = await createdAdminRole.json();
  const adminRoleId = createdAdminRoleBody.data?.id;
  const adminRoleLogin = await postJson("/api/v1/admin/auth/login", { username: adminRoleName, password: "pass123456" });
  const adminRoleSession = adminRoleLogin.body.data?.token;
  check(
    "可创建 admin 角色并登录",
    createdAdminRole.status === 201 && typeof adminRoleSession === "string",
    `${createdAdminRole.status} ${JSON.stringify(adminRoleLogin.body).slice(0, 120)}`,
  );

  // 每个域挑一个写接口。守卫在处理器之前执行，所以即使请求体不合法，viewer 也应当
  // 先被 4030 挡下 —— 这让断言不依赖业务数据当前处于什么状态。
  const writeProbes = [
    { name: "发布放量", path: "/api/v1/app/admin/rollout", body: { versionCode: 1, percent: 100 } },
    { name: "Windows 放量", path: "/api/v1/desktop/admin/rollout", body: { versionCode: 1, percent: 100 } },
    { name: "禁用用户", path: "/api/v1/app/admin/users/1/disabled", body: { disabled: true } },
    { name: "发布公告", path: "/api/v1/app/admin/announcements", body: { title: "越权公告", content: "越权公告" } },
    { name: "导入图片 Key", path: "/api/v1/app/admin/image-keys", body: { key: "viewer-should-not-write-this-key", quota: 1 } },
  ];
  for (const probe of writeProbes) {
    const response = await fetch(`${base}${probe.path}`, {
      method: "POST", headers: bearer(viewerSession), body: JSON.stringify(probe.body),
    });
    const responseBody = await response.json();
    check(
      `viewer 写「${probe.name}」被拒 403 且 code 4030`,
      response.status === 403 && responseBody.code === 4030,
      `${response.status} ${JSON.stringify(responseBody).slice(0, 120)}`,
    );
  }

  // 读接口不能被顺手关掉：观察者进后台就是为了看发布状态这类运营数据。
  const readProbes = [
    { name: "发布列表", path: "/api/v1/app/admin/releases" },
    { name: "Windows 发布列表", path: "/api/v1/desktop/admin/releases" },
    { name: "公告列表", path: "/api/v1/app/admin/announcements" },
    { name: "图片 Key 列表", path: "/api/v1/app/admin/image-keys" },
    // 音源清单只有音源名与能力标记，不含账号数据，观察者可以看。
    { name: "音源清单", path: "/api/v1/app/admin/music-sources/available" },
  ];
  for (const probe of readProbes) {
    const response = await fetch(`${base}${probe.path}`, { headers: bearer(viewerSession) });
    check(
      `viewer 可读「${probe.name}」`,
      response.status === 200,
      `${response.status} ${(await response.text()).slice(0, 120)}`,
    );
  }

  // 用户数据是例外：列表带 email，详情带逐首歌的播放次数与时间戳。这类个人数据
  // 用的是 PRIVILEGED_READ_ROLES，观察者即使能进后台也看不到。
  const userPrivacyProbes = [
    { name: "用户列表", path: "/api/v1/app/admin/users" },
    { name: "听歌历史", path: "/api/v1/app/admin/users/1/playback" },
    // 音源账号列表带掩码手机号与上游账号 ID，同属个人信息面。
    { name: "音源账号列表", path: "/api/v1/app/admin/music-sources" },
  ];
  for (const probe of userPrivacyProbes) {
    const response = await fetch(`${base}${probe.path}`, { headers: bearer(viewerSession) });
    const responseBody = await response.json();
    check(
      `viewer 读「${probe.name}」被拒 403 且 code 4030`,
      response.status === 403 && responseBody.code === 4030,
      `${response.status} ${JSON.stringify(responseBody).slice(0, 120)}`,
    );
  }

  // admin（非超管）应当能写业务接口：拒绝 viewer 不等于把接口整体锁死。
  const adminRoleWrite = await fetch(`${base}/api/v1/app/admin/announcements`, {
    method: "POST", headers: bearer(adminRoleSession),
    body: JSON.stringify({ title: "契约验证公告", content: "由契约脚本以 admin 角色创建" }),
  });
  const adminRoleWriteBody = await adminRoleWrite.json();
  const adminAnnouncementId = adminRoleWriteBody.data?.id;
  check(
    "admin 角色可发布公告（201）",
    adminRoleWrite.status === 201 && !!adminAnnouncementId,
    `${adminRoleWrite.status} ${JSON.stringify(adminRoleWriteBody).slice(0, 160)}`,
  );

  // 同一批用户数据对 admin 必须放行，否则就是把隐私收紧成了功能不可用。
  const adminUserList = await fetch(`${base}/api/v1/app/admin/users`, { headers: bearer(adminRoleSession) });
  check(
    "admin 角色可读用户列表",
    adminUserList.status === 200,
    `${adminUserList.status} ${(await adminUserList.text()).slice(0, 120)}`,
  );
  const adminPlayback = await fetch(`${base}/api/v1/app/admin/users/1/playback`, {
    headers: bearer(adminRoleSession),
  });
  // 验证库里不一定有 id=1 的用户，404 说明守卫已放行、只是数据不存在。
  check(
    "admin 角色读听歌历史不被守卫拦下",
    adminPlayback.status === 200 || adminPlayback.status === 404,
    `${adminPlayback.status} ${(await adminPlayback.text()).slice(0, 120)}`,
  );

  // 审计留痕：写操作必须能在 admin_audit_log 里查到，并且记在正确的操作人头上。
  const auditAfterWrite = await fetch(`${base}/api/v1/admin/auth/audit-log?action=announcement.create&limit=20`, {
    headers: bearer(adminSession),
  });
  const auditAfterWriteBody = await auditAfterWrite.json();
  const auditRow = (auditAfterWriteBody.data?.items ?? []).find(
    (row) => String(row.target_id) === String(adminAnnouncementId),
  );
  check(
    "公告发布写入审计且操作人正确",
    !!auditRow && auditRow.admin_id === adminRoleId && auditRow.target_type === "announcement",
    JSON.stringify(auditRow ?? auditAfterWriteBody).slice(0, 200),
  );

  // 账号维度退避：只按 IP 限流挡不住代理池 —— 换 IP 就能对已知的 admin 账号
  // 无限猜口令，对 LDAP 路径还会打爆企业目录的账号锁定策略。
  //
  // 断言的是 **4291** 而不是 4290：两者同为 429，但 4291 专属于账号退避。
  // 如果只断言「429」或「4290」，来源地址限流会先一步命中，让这条用例在
  // 账号退避完全没生效时也照样变绿 —— 那就成了假阳性。
  //
  // 用临时账号测，**绝不能拿主 admin 账号试**，那会把后面所有断言一起锁死。
  const lockTargetName = `lock_${randomBytes(3).toString("hex")}`;
  const lockTargetCreated = await fetch(`${base}/api/v1/admin/auth/users`, {
    method: "POST", headers: bearer(adminSession),
    body: JSON.stringify({
      username: lockTargetName, password: "pass123456", role: "viewer", displayName: "退避验证",
    }),
  });
  const lockTargetId = (await lockTargetCreated.json()).data?.id;
  let lastLockStatus = 0;
  let lastLockCode = 0;
  for (let attempt = 0; attempt < 7; attempt++) {
    const response = await postJson("/api/v1/admin/auth/login", {
      username: lockTargetName, password: "definitely-wrong",
    });
    lastLockStatus = response.status;
    lastLockCode = response.body.code;
    if (lastLockCode === 4291) break;
  }
  check(
    "连续失败达到阈值后账号被退避（429/4291）",
    lastLockStatus === 429 && lastLockCode === 4291,
    `${lastLockStatus} ${lastLockCode}`,
  );

  // 退避只针对账号：换回**正确**口令也不该立刻放行（否则退避形同虚设），
  // 但**其它账号**必须完全不受影响 —— 这正是不能用全局锁的原因。
  const lockedTargetCorrectPassword = await postJson("/api/v1/admin/auth/login", {
    username: lockTargetName, password: "pass123456",
  });
  check(
    "被退避的账号即使口令正确也仍然被拒（429/4291）",
    lockedTargetCorrectPassword.status === 429 && lockedTargetCorrectPassword.body.code === 4291,
    `${lockedTargetCorrectPassword.status} ${JSON.stringify(lockedTargetCorrectPassword.body).slice(0, 120)}`,
  );

  const unaffectedLogin = await postJson("/api/v1/admin/auth/login", {
    username: "admin", password: NEW_ADMIN_PASSWORD,
  });
  check(
    "退避只作用于目标账号，其它账号不受影响",
    unaffectedLogin.status === 200,
    `${unaffectedLogin.status} ${JSON.stringify(unaffectedLogin.body).slice(0, 160)}`,
  );

  // 清掉本段造出来的退避账号，别让它留在管理员列表里影响后续断言。
  if (lockTargetId) {
    await fetch(`${base}/api/v1/admin/auth/users/${lockTargetId}`, {
      method: "DELETE", headers: bearer(adminSession),
    });
  }

  // 清掉本段造出来的账号，别影响后面的断言（删账号会级联清掉它的会话）。
  if (adminRoleId) {
    await fetch(`${base}/api/v1/admin/auth/users/${adminRoleId}`, {
      method: "DELETE", headers: bearer(adminSession),
    });
  }

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

  // 最后一个可用超管不能被降级/禁用，否则后台再没人能创建管理员。
  const disableLastSuperAdmin = await fetch(`${base}/api/v1/admin/auth/users/1`, {
    method: "PATCH", headers: bearer(adminSession), body: JSON.stringify({ disabled: true }),
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

  section("音源账号（后台）与酷我音源接入");

  const sourceList = await fetch(`${base}/api/v1/app/admin/music-sources/available`, { headers: bearer(adminSession) });
  const sourceListBody = await sourceList.json();
  const sourceRows = sourceListBody.data ?? [];
  check(
    "音源清单包含酷我且标记支持登录",
    sourceList.status === 200 && sourceRows.some((row) => row.source === "kuwo" && row.supportsLogin === true),
    JSON.stringify(sourceListBody).slice(0, 240),
  );
  check(
    "腾讯与网易未标记支持后台登录",
    sourceRows.filter((row) => row.source !== "kuwo").every((row) => row.supportsLogin === false),
    JSON.stringify(sourceRows).slice(0, 240),
  );
  // 前端据 `numericUidOnly` 决定 uid 输入框要不要提示「必须纯数字」。
  // 它是能力标记，和 `supportsLogin` 一样只有注册表一处定义 —— 前端不硬编码音源名。
  check(
    "音源清单标记酷我要求纯数字 uid",
    sourceRows.some((row) => row.source === "kuwo" && row.numericUidOnly === true),
    JSON.stringify(sourceRows).slice(0, 240),
  );
  check(
    "腾讯与网易未标记要求纯数字 uid",
    sourceRows.filter((row) => row.source !== "kuwo").every((row) => row.numericUidOnly === false),
    JSON.stringify(sourceRows).slice(0, 240),
  );

  // 新增：只填手机号占位，凭据留空。这条也是「空凭据按匿名探测」的前提。
  const createdSource = await fetch(`${base}/api/v1/app/admin/music-sources`, {
    method: "POST", headers: bearer(adminSession),
    body: JSON.stringify({ source: "kuwo", label: "契约验证号", phone: "13800001111", remark: "契约验证" }),
  });
  const createdSourceBody = await createdSource.json();
  const sourceAccountId = createdSourceBody.data?.id;
  check(
    "新增音源账号返回 201",
    createdSource.status === 201 && Number.isInteger(sourceAccountId),
    `${createdSource.status} ${JSON.stringify(createdSourceBody).slice(0, 200)}`,
  );
  // 响应体里不能出现 token 这个键 —— 明文凭据只允许存在于服务端数据库里。
  check(
    "新增响应里没有明文凭据字段",
    !Object.prototype.hasOwnProperty.call(createdSourceBody.data ?? {}, "token"),
    Object.keys(createdSourceBody.data ?? {}).join(","),
  );
  check(
    "手机号只回掩码（保留号段与尾号）",
    createdSourceBody.data?.maskedPhone === "138****1111",
    createdSourceBody.data?.maskedPhone,
  );

  // 无凭据时按匿名探测。这是唯一能在不持有真实账号的前提下验证取址链路的方式。
  const anonProbe = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}/probe`, {
    method: "POST", headers: bearer(adminSession), body: JSON.stringify({}),
  });
  const anonProbeBody = await anonProbe.json();
  check(
    "无凭据时按匿名探测成功并标注「匿名」",
    anonProbe.status === 201 && typeof anonProbeBody.data?.note === "string" && anonProbeBody.data.note.startsWith("匿名 "),
    `${anonProbe.status} ${JSON.stringify(anonProbeBody).slice(0, 200)}`,
  );

  // 非数字 uid 必须在**写入时**就被拒掉。
  //
  // 实测的上游规则是「**uid 必须是纯数字**」：传非数字时上游拒绝下发任何播放地址，
  // 而搜索、单曲信息、歌词都照常 —— 表现为「搜得到、放不出」，换任何 token 都救不回来。
  // token 的取值与取址无关（乱码 token 配数字 uid 照样能取到地址）。
  // 早先这条被记成「无效凭据」，是因为当时用的凭据恰好是非数字 uid，
  // 把「uid 形状」和「token 有效性」两个变量混在了一起。
  //
  // 这种账号存进库就是坏的，而且要到播放时才暴露，所以两条写入路径都要拦。
  const nonNumericUidPatch = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}`, {
    method: "PATCH", headers: bearer(adminSession), body: JSON.stringify({ uid: "contract-uid" }),
  });
  const nonNumericUidPatchBody = await nonNumericUidPatch.json();
  check(
    "非数字 uid 的局部更新被拒 400 且原因指向 uid",
    nonNumericUidPatch.status === 400 && String(nonNumericUidPatchBody.message ?? "").includes("纯数字"),
    `${nonNumericUidPatch.status} ${JSON.stringify(nonNumericUidPatchBody).slice(0, 200)}`,
  );
  // 新增路径漏了这条，就能绕开 PATCH 直接造出一个坏账号。
  const nonNumericUidCreate = await fetch(`${base}/api/v1/app/admin/music-sources`, {
    method: "POST", headers: bearer(adminSession),
    body: JSON.stringify({ source: "kuwo", uid: "contract-uid", token: "contract-invalid-token" }),
  });
  const nonNumericUidCreateBody = await nonNumericUidCreate.json();
  check(
    "非数字 uid 的新增被拒 400 且原因指向 uid",
    nonNumericUidCreate.status === 400 && String(nonNumericUidCreateBody.message ?? "").includes("纯数字"),
    `${nonNumericUidCreate.status} ${JSON.stringify(nonNumericUidCreateBody).slice(0, 200)}`,
  );
  // 数字 uid 必须放行 —— 只拦形状，不拦内容。token 故意给乱码：
  // 取址与 token 取值无关，所以这条校验不该影响「能不能取到地址」。
  const patchedSource = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}`, {
    method: "PATCH", headers: bearer(adminSession),
    body: JSON.stringify({ token: "contract-invalid-token", uid: "900000001" }),
  });
  check("音源账号可以局部更新", patchedSource.status === 200, String(patchedSource.status));

  // 探测失败必须报 502，且错误文案要同时列出「uid 形状」与「凭据失效」两种可能。
  //
  // 触发手段是给一个不存在的曲目 ID，而不是靠非数字 uid —— 后者现在连库都进不去
  // （上面两条断言就是拦它的）。**失败路径本身仍要测**：它要落库、要写审计，
  // 而且这条文案是管理员唯一的线索来源，改坏了没人会发现。
  const badProbe = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}/probe`, {
    method: "POST", headers: bearer(adminSession), body: JSON.stringify({ musicId: 999999999999999 }),
  });
  const badProbeBody = await badProbe.json();
  check(
    "探测失败被报成 502 且原因列出 uid 形状与凭据两种可能",
    badProbe.status === 502 && String(badProbeBody.message ?? "").includes("凭据"),
    `${badProbe.status} ${JSON.stringify(badProbeBody).slice(0, 200)}`,
  );

  const sourceAccounts = await fetch(`${base}/api/v1/app/admin/music-sources?source=kuwo`, { headers: bearer(adminSession) });
  const sourceAccountsText = await sourceAccounts.text();
  const sourceAccountsBody = JSON.parse(sourceAccountsText);
  const myAccount = (sourceAccountsBody.data ?? []).find((row) => row.id === sourceAccountId);
  check(
    "列表只回掩码且不含明文 token",
    sourceAccounts.status === 200 && myAccount?.maskedToken === "••••••••oken" && !sourceAccountsText.includes("contract-invalid-token"),
    // `JSON.stringify(undefined)` 返回 undefined，再 `.slice` 会抛 TypeError 把整节断言带走 ——
    // 上面那条断言已经失败了，这里只需要把现场打印出来，不该再制造一次崩溃。
    JSON.stringify(myAccount ?? null).slice(0, 240),
  );
  check("探测失败已落库（状态与原因都在）", myAccount?.lastStatus === "invalid" && myAccount?.lastError.length > 0, myAccount?.lastStatus);

  // 同一音源同一 uid 的第二条必须被唯一索引拦下并翻成 409，而不是冒成 502。
  // 索引是带 `WHERE uid <> ''` 的部分索引，`ON CONFLICT` 的推断必须带上同样的谓词 ——
  // 漏了谓词会在运行时报「no unique or exclusion constraint matching the ON CONFLICT
  // specification」，而那条路径只在重复登录时才走到。
  //
  // uid 用数字：形状校验在唯一索引之前，非数字根本走不到冲突分支。
  const duplicateSource = await fetch(`${base}/api/v1/app/admin/music-sources`, {
    method: "POST", headers: bearer(adminSession),
    body: JSON.stringify({ source: "kuwo", uid: "900000001", token: "duplicate-token" }),
  });
  const duplicateSourceBody = await duplicateSource.json();
  check(
    "同音源同 uid 的第二条被拒 409 且 code 4090",
    duplicateSource.status === 409 && duplicateSourceBody.code === 4090,
    `${duplicateSource.status} ${JSON.stringify(duplicateSourceBody).slice(0, 160)}`,
  );

  // 局部更新要能**清空**字段：传空串必须真的清掉，不能被当成「没提交」。
  // 用 COALESCE 实现就会永远清不掉，所以这条断言盯的是 SQL 的写法。
  const clearedLabel = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}`, {
    method: "PATCH", headers: bearer(adminSession), body: JSON.stringify({ label: "" }),
  });
  const clearedLabelBody = await clearedLabel.json();
  check(
    "局部更新可以把字段清成空串",
    clearedLabel.status === 200 && clearedLabelBody.data?.label === "",
    JSON.stringify(clearedLabelBody.data).slice(0, 160),
  );

  // 空补丁（一个字段都没提交）应当被拒，否则等于一次什么都不做的审计噪音。
  const emptyPatch = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}`, {
    method: "PATCH", headers: bearer(adminSession), body: JSON.stringify({}),
  });
  check("空补丁被拒 400", emptyPatch.status === 400, String(emptyPatch.status));

  const disabledSource = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}/enabled`, {
    method: "PUT", headers: bearer(adminSession), body: JSON.stringify({ enabled: false }),
  });
  const disabledSourceBody = await disabledSource.json();
  check(
    "音源账号可以停用",
    disabledSource.status === 200 && disabledSourceBody.data?.enabled === 0,
    JSON.stringify(disabledSourceBody).slice(0, 160),
  );

  const badSourceCreate = await fetch(`${base}/api/v1/app/admin/music-sources`, {
    method: "POST", headers: bearer(adminSession), body: JSON.stringify({ source: "spotify" }),
  });
  const badSourceCreateBody = await badSourceCreate.json();
  check(
    "未知音源被拒 400 且 code 4001",
    badSourceCreate.status === 400 && badSourceCreateBody.code === 4001,
    JSON.stringify(badSourceCreateBody).slice(0, 160),
  );

  const removedSource = await fetch(`${base}/api/v1/app/admin/music-sources/${sourceAccountId}`, {
    method: "DELETE", headers: bearer(adminSession),
  });
  check("删除音源账号返回 204", removedSource.status === 204, String(removedSource.status));

  // 酷我已在音源白名单里：/search 必须接受它，并且仍然返回**裸 NDJSON**（不能被信封包住）。
  const kuwoSearch = await fetch(`${base}/api/v1/search?keyword=${encodeURIComponent("轻音乐")}&num=5&source=kuwo`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const kuwoNdjson = await kuwoSearch.text();
  const kuwoLines = kuwoNdjson.split("\n").filter(Boolean).map((line) => JSON.parse(line));
  const kuwoSongs = kuwoLines.filter((line) => line.type === "song");
  check(
    "source=kuwo 的搜索被接受且返回裸 NDJSON",
    kuwoSearch.status === 200
      && (kuwoSearch.headers.get("content-type") ?? "").includes("x-ndjson")
      && kuwoLines[0]?.type === "song",
    `${kuwoSearch.status} ${kuwoNdjson.slice(0, 160)}`,
  );
  check("酷我搜索返回了歌曲", kuwoSongs.length > 0, `${kuwoSongs.length} 首`);
  // 酷我没有 mid 概念，身份完全由数字 id 承载，所以 mid 必须缺省而不是空串。
  check(
    "酷我歌曲 id 是 number 且不带 mid",
    typeof kuwoSongs[0]?.data?.id === "number" && kuwoSongs[0]?.data?.mid === undefined,
    JSON.stringify(kuwoSongs[0]?.data ?? {}).slice(0, 200),
  );
  check(
    "酷我搜索结果的 source 字段回填为 kuwo",
    kuwoSongs.every((line) => line.data?.source === "kuwo"),
    kuwoSongs[0]?.data?.source,
  );

  // ---------- 翻页起点（防「第一页拿到第二页的歌」回归）----------
  //
  // 上游 `search/music/list` 的偏移量是 **`pn × rn`**，且 `pn` 是 **0 基**的。
  // 参考实现写的是 `pn: page`，本项目照抄过，于是第一页实际拿到第二页的歌
  // （`pn=1&rn=60` → 偏移 60），表现就是「搜索结果和波点 App 对不上」。
  // 2026-09-19 用真实 VIP 凭据 + blutter 逆向出的 App 请求形状定位并修复。
  //
  // 这里用一条**不需要外部裁判**的不变量来守住它：
  // 同一个 `page` 下，无论 `num` 取多少，**起点必须相同**（因为偏移 = pn × rn，pn 相同时
  // 起点只由 pn 决定，而修复后 pn 恒为 page-1，与 num 无关）。
  // 旧实现下 `num=20` → pn=1,rn=20 → 偏移 20，`num=60` → pn=1,rn=60 → 偏移 60，两者必然不同。
  //
  // ⚠️ 关键词挑的是**可播比例高**的：酷我会把拿不到播放地址的歌滤掉，
  // 用「周杰伦」这种全被滤掉的词会让下面的比较变成空对空，断言假通过。
  const pageIds = async (page, num, keyword = "稻香") => {
    const res = await fetch(
      `${base}/api/v1/search?keyword=${encodeURIComponent(keyword)}&page=${page}&num=${num}&source=kuwo`,
      { headers: { authorization: `Bearer ${token}` } },
    );
    return (await res.text())
      .split("\n")
      .filter(Boolean)
      .map((line) => JSON.parse(line))
      .filter((line) => line.type === "song")
      .map((line) => line.data.id);
  };

  const p1n20 = await pageIds(1, 20);
  const p1n60 = await pageIds(1, 60);
  check(
    "同一页不同页大小返回同一批歌（起点不随 num 漂移）",
    p1n20.length > 0 && p1n20.join() === p1n60.slice(0, p1n20.length).join(),
    `num=20 得 ${p1n20.length} 首 [${p1n20.slice(0, 3)}]，num=60 得 ${p1n60.length} 首 [${p1n60.slice(0, 3)}]`,
  );

  const p2n60 = await pageIds(2, 60);
  check(
    "第 1 页与第 2 页不重叠（翻页必须前进）",
    p1n60.length > 0 && p2n60.length > 0 && p1n60.filter((id) => p2n60.includes(id)).length === 0,
    `第 1 页 ${p1n60.length} 首、第 2 页 ${p2n60.length} 首，重叠 ${p1n60.filter((id) => p2n60.includes(id)).length} 首`,
  );

  // 「拿不到地址的歌」必须**照常下发并逐首标记**，不能再整列滤掉。
  //
  // 酷我搜「周杰伦」上游返回的每一条都是 `listen_fragment=1`（取不到播放地址，
  // 上游逐首回 `code 20012 歌曲已下线`）。2026-09-19 之前适配器据此把它们全滤掉，
  // 客户端收到 0 条 —— 用户看到「搜不到歌」，第一反应是账号或音源坏了。
  // 实测波点 App 自己也不滤（它综合页的 `musicpage` 与我们的原始列表逐条一致），
  // 所以现在改成「照列 + 逐首标记 playable=false」。
  //
  // 下面第一条是这次修复的核心回归点：**列表不能再空掉**。
  //
  // ⚠️ 这两条都依赖上游曲库：哪天周杰伦的歌在酷我拿到版权（`dropped` 类断言当年也这么写），
  // 它们会失败。那时换一个同样全部不可播的关键词即可，不代表代码坏了。
  const playableSearch = async (keyword, num) => {
    const res = await fetch(
      `${base}/api/v1/search?keyword=${encodeURIComponent(keyword)}&num=${num}&source=kuwo`,
      { headers: { authorization: `Bearer ${token}` } },
    );
    const lines = (await res.text()).split("\n").filter(Boolean).map((line) => JSON.parse(line));
    return {
      songs: lines.filter((line) => line.type === "song").map((line) => line.data),
      meta: lines.at(-1)?.meta,
    };
  };

  const blocked = await playableSearch("周杰伦", 5);
  check(
    "全部不可播的关键词仍返回完整列表（不再整列滤空）",
    blocked.songs.length === 5,
    `num=5 得到 ${blocked.songs.length} 首 [${blocked.songs.slice(0, 3).map((s) => s.id)}]`,
  );
  check(
    "不可播的歌逐首带 playable=false（不是被丢掉）",
    blocked.songs.length > 0 && blocked.songs.every((song) => song.playable === false),
    `playable 取值：${blocked.songs.map((song) => song.playable).join(",")}`,
  );
  // 反向守卫：`playable` 必须是逐首判定的，不能退化成常量 true/false。
  // 用「稻香」——它的结果里同时有可播与不可播的歌（原唱 440613 不可播、翻唱可播）。
  const mixed = await playableSearch("稻香", 20);
  check(
    "playable 逐首判定（同一关键词下 true / false 同时出现）",
    mixed.songs.some((song) => song.playable === true) && mixed.songs.some((song) => song.playable === false),
    `true ${mixed.songs.filter((song) => song.playable === true).length} 条 / false ${mixed.songs.filter((song) => song.playable === false).length} 条`,
  );

  // `dropped` 与 `droppedBySource` 现在恒为 0 / 空对象 —— 可播性改由逐首的 `playable` 承载。
  // 字段本身**不能删**（装机的旧客户端会读它做算术，拿到 undefined 会算出 NaN），
  // 所以这里守的是「字段还在，且语义确实是 0」。
  check(
    "不再丢歌：meta.dropped 为 0 且 droppedBySource 为空",
    blocked.meta?.dropped === 0
      && typeof blocked.meta?.droppedBySource === "object"
      && blocked.meta?.droppedBySource !== null
      && Object.keys(blocked.meta.droppedBySource).length === 0,
    JSON.stringify({ dropped: blocked.meta?.dropped, bySource: blocked.meta?.droppedBySource }),
  );

  const unknownSourceSearch = await fetch(`${base}/api/v1/search?keyword=test&num=3&source=spotify`, {
    headers: { authorization: `Bearer ${token}` },
  });
  const unknownSourceSearchBody = await unknownSourceSearch.json();
  check(
    "未知音源的搜索被拒 400 且 code 4001",
    unknownSourceSearch.status === 400 && unknownSourceSearchBody.code === 4001,
    `${unknownSourceSearch.status} ${JSON.stringify(unknownSourceSearchBody).slice(0, 160)}`,
  );

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
