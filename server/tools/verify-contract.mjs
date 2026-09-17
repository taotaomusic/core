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
//   全绿应为「通过 213 项，失败 0 项」。检查项数量随脚本版本变化，以实际输出为准。
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
