import { createHash, timingSafeEqual } from "node:crypto";
import { createReadStream, createWriteStream, existsSync, mkdirSync, renameSync, rmSync, statSync } from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import { once } from "node:events";
import { basename, resolve } from "node:path";
import { adminToken, apkDirectory, defaultChannel, publicBaseUrl } from "../config.js";
import { authenticate } from "../auth.js";
import {
  bucketOf, configVersion, deleteConfig, findRelease, insertRelease, listConfig, listReleases,
  listUpgradeCandidates, minSupportedVersionCode, setMinSupportedVersionCode, setReleaseEnabled,
  updateRollout, upsertConfig, type ReleaseRecord,
} from "../app-release.js";
import { allowAdminRequest, allowAppRequest } from "../rate-limit.js";
import { json, readJson, success } from "../utils/http.js";

/**
 * 客户端引导与发布管理接口。
 *
 * 这一组路由必须注册在 api.ts 的访问令牌门禁之前：最需要强制更新的场景恰恰是
 * 「上一个版本把登录搞坏了」，若检查接口自己要求登录，这些客户端永远收不到升级通知。
 */
export async function handleApp(url: URL, request: IncomingMessage, response: ServerResponse) {
  const path = url.pathname.replace(/\/$/, "");
  const address = client(request);
  const deviceId = (url.searchParams.get("deviceId") ?? "").slice(0, 64);

  if (path === "/api/v1/app/bootstrap" && request.method === "GET") {
    if (!allowAppRequest(address, deviceId)) return json(response, 429, { code: 4290, message: "请求过于频繁，请稍后再试" });
    return bootstrap(url, request, response);
  }

  const apk = path.match(/^\/api\/v1\/app\/apk\/(\d+)$/);
  if (apk && (request.method === "GET" || request.method === "HEAD")) {
    if (!allowAppRequest(address, deviceId)) return json(response, 429, { code: 4290, message: "请求过于频繁，请稍后再试" });
    return downloadApk(url, request, response, Number(apk[1]));
  }

  if (path.startsWith("/api/v1/app/admin/")) {
    if (!allowAdminRequest(address)) return json(response, 429, { code: 4290, message: "请求过于频繁，请稍后再试" });
    if (!verifyAdmin(request)) return json(response, 401, { code: 4013, message: "管理令牌无效" });
    return handleAdmin(path, url, request, response);
  }

  return json(response, 404, { code: 4040, message: "接口不存在" });
}

/** 一次请求返回更新信息与远程配置，减少启动时的往返。 */
function bootstrap(url: URL, request: IncomingMessage, response: ServerResponse) {
  const channel = url.searchParams.get("channel") ?? defaultChannel;
  const versionCode = Number(url.searchParams.get("versionCode") ?? 0);
  const sdk = Number(url.searchParams.get("sdk") ?? 0);
  const deviceId = (url.searchParams.get("deviceId") ?? "").slice(0, 64);
  if (!Number.isInteger(versionCode) || versionCode <= 0) return json(response, 400, { code: 4005, message: "versionCode 不合法" });
  if (!Number.isInteger(sdk) || sdk <= 0) return json(response, 400, { code: 4005, message: "sdk 不合法" });

  // 令牌可选：带了且有效就按用户分桶，未登录时退回匿名设备号，保证登录态异常也能收到更新。
  const user = authenticate(request);
  const subject = user ? `user:${user.id}` : `device:${deviceId || client(request)}`;

  return json(response, 200, success({
    update: resolveUpdate(channel, versionCode, sdk, subject, request),
    config: Object.fromEntries(listConfig(versionCode).map((item) => [item.key, item.value])),
    configVersion: configVersion(),
  }));
}

/**
 * 版本判定。
 *
 * 强制更新的目标一律取放量 100% 的版本，绝不返回灰度包：否则抬高最低可用版本后，
 * 不在灰度名单里的客户端会被拦住却拿不到升级包，直接变砖。
 */
function resolveUpdate(channel: string, versionCode: number, sdk: number, subject: string, request: IncomingMessage) {
  const floor = minSupportedVersionCode(channel);
  const forced = versionCode < floor;
  const candidates = listUpgradeCandidates(channel, versionCode, sdk);
  const target = forced
    ? candidates.find((item) => item.rollout_percent >= 100)
    : candidates.find((item) => bucketOf(item.id, subject) < item.rollout_percent);

  if (forced && !target) {
    console.warn(`[热更新] 渠道 ${channel} 的最低可用版本为 ${floor}，但没有可下发的全量版本，客户端 ${versionCode} 将被放行`);
  }
  if (!target) return { available: false, forced: false, minSupportedVersionCode: floor };

  return {
    available: true,
    forced,
    versionCode: target.version_code,
    versionName: target.version_name,
    apkUrl: apkUrlOf(request, channel, target.version_code),
    apkSize: target.apk_size,
    apkSha256: target.apk_sha256,
    releaseNote: target.release_note,
    minSupportedVersionCode: floor,
  };
}

/** APK 下载，支持 Range 断点续传：安装包约 14 MB，弱网下必须能续传。 */
function downloadApk(url: URL, request: IncomingMessage, response: ServerResponse, versionCode: number) {
  const channel = url.searchParams.get("channel") ?? defaultChannel;
  const release = findRelease(channel, versionCode);
  if (!release || !release.enabled) return json(response, 404, { code: 4041, message: "版本不存在" });
  // 文件名来自数据库并再次取 basename，避免任何路径穿越。
  const file = resolve(apkDirectory, basename(release.apk_file));
  if (!existsSync(file)) return json(response, 404, { code: 4042, message: "安装包文件缺失" });

  const total = statSync(file).size;
  const headers: Record<string, string> = {
    "content-type": "application/vnd.android.package-archive",
    "accept-ranges": "bytes",
    "etag": `"${release.apk_sha256}"`,
    "cache-control": "public, max-age=86400",
    "content-disposition": `attachment; filename="taotao-${release.version_name}.apk"`,
  };

  const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range ?? "");
  if (range) {
    const start = range[1] ? Number(range[1]) : 0;
    const end = range[2] ? Math.min(Number(range[2]), total - 1) : total - 1;
    if (start >= total || start > end) {
      response.writeHead(416, { "content-range": `bytes */${total}` });
      return response.end();
    }
    response.writeHead(206, { ...headers, "content-length": String(end - start + 1), "content-range": `bytes ${start}-${end}/${total}` });
    if (request.method === "HEAD") return response.end();
    return void createReadStream(file, { start, end }).pipe(response);
  }

  response.writeHead(200, { ...headers, "content-length": String(total) });
  if (request.method === "HEAD") return response.end();
  return void createReadStream(file).pipe(response);
}

async function handleAdmin(path: string, url: URL, request: IncomingMessage, response: ServerResponse) {
  const action = path.slice("/api/v1/app/admin/".length);

  if (action === "releases" && request.method === "GET") {
    return json(response, 200, success(listReleases(url.searchParams.get("channel") ?? defaultChannel)));
  }

  if (action === "releases" && request.method === "POST") return publish(url, request, response);
  if (action === "rollout" && request.method === "POST") return rollout(request, response);
  if (action === "min-version" && request.method === "POST") return minVersion(request, response);
  if (action === "config" && request.method === "GET") return json(response, 200, success(listConfig(Number.MAX_SAFE_INTEGER)));
  if (action === "config" && request.method === "POST") return changeConfig(request, response);

  return json(response, 404, { code: 4040, message: "接口不存在" });
}

/**
 * 登记一个新版本。APK 以原始字节放在请求体，元数据走查询参数 —— 避免 base64 的 33% 膨胀，
 * 也不必抬高 readJson 的 16 KB 请求体上限。写盘时同步计算 sha256，先写 .part 再改名。
 *
 * versionCode / versionName 必须由调用方从构建产物的 output-metadata.json 读取，
 * 不能用 version.properties：incrementVersion 是 assemble 的 finalizedBy，
 * 构建结束时该文件里的值已经比刚产出的 APK 大 1，登记错了客户端会陷入
 * 「提示更新 → 装完还提示」的死循环。
 */
async function publish(url: URL, request: IncomingMessage, response: ServerResponse) {
  const parameters = url.searchParams;
  const channel = (parameters.get("channel") ?? defaultChannel).trim();
  const versionCode = Number(parameters.get("versionCode"));
  const versionName = (parameters.get("versionName") ?? "").trim();
  if (!Number.isInteger(versionCode) || versionCode <= 0) return json(response, 400, { code: 4005, message: "versionCode 不合法" });
  if (!/^\d+(\.\d+)*$/.test(versionName)) return json(response, 400, { code: 4005, message: "versionName 不合法" });

  mkdirSync(apkDirectory, { recursive: true });
  const apkFile = `${channel}-${versionCode}.apk`;
  const target = resolve(apkDirectory, apkFile);
  const temporary = `${target}.part`;
  const hash = createHash("sha256");
  let size = 0;
  let magicChecked = false;

  try {
    const output = createWriteStream(temporary);
    for await (const chunk of request) {
      const buffer = chunk as Buffer;
      // APK 是 ZIP 容器，尽早用魔数拦住上传错文件的情况。
      if (!magicChecked && buffer.length >= 2) {
        if (buffer[0] !== 0x50 || buffer[1] !== 0x4b) throw new Error("上传内容不是 APK");
        magicChecked = true;
      }
      size += buffer.length;
      if (size > maxApkBytes) throw new Error("安装包超过大小上限");
      hash.update(buffer);
      if (!output.write(buffer)) await once(output, "drain");
    }
    output.end();
    await once(output, "finish");
  } catch (error) {
    rmSync(temporary, { force: true });
    return json(response, 400, { code: 4005, message: error instanceof Error ? error.message : "安装包写入失败" });
  }

  if (size === 0) { rmSync(temporary, { force: true }); return json(response, 400, { code: 4005, message: "安装包内容为空" }); }

  const sha256 = hash.digest("hex");
  const expected = (parameters.get("sha256") ?? "").trim().toLowerCase();
  if (expected && expected !== sha256) {
    rmSync(temporary, { force: true });
    return json(response, 400, { code: 4006, message: `安装包校验不一致，实际为 ${sha256}` });
  }
  renameSync(temporary, target);

  insertRelease({
    channel,
    version_code: versionCode,
    version_name: versionName,
    apk_file: apkFile,
    apk_size: size,
    apk_sha256: sha256,
    release_note: parameters.get("note") ?? "",
    // 默认不放量：先登记，确认无误后再逐步放开。
    rollout_percent: clampPercent(parameters.get("rollout") ?? 0),
    min_sdk: positiveIntOr(parameters.get("minSdk"), 24),
    enabled: parameters.get("enabled") === "false" ? 0 : 1,
  });

  return json(response, 201, success(findRelease(channel, versionCode)));
}

async function rollout(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request);
  const channel = textOf(body.channel) || defaultChannel;
  const versionCode = Number(body.versionCode);
  const percent = clampPercent(body.percent);
  if (!findRelease(channel, versionCode)) return json(response, 404, { code: 4041, message: "版本不存在" });
  updateRollout(channel, versionCode, percent);
  if (body.enabled !== undefined) setReleaseEnabled(channel, versionCode, body.enabled !== false);
  return json(response, 200, success(findRelease(channel, versionCode)));
}

/**
 * 抬高最低可用版本（强制更新下限）。
 *
 * 守卫：必须已经存在放量 100% 且不低于该下限的发布，否则被判定为强制更新的客户端
 * 会被拦在门外却拿不到升级包。这里从接口层面堵住这条变砖路径。
 */
async function minVersion(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request);
  const channel = textOf(body.channel) || defaultChannel;
  const versionCode = Number(body.versionCode);
  if (!Number.isInteger(versionCode) || versionCode < 0) return json(response, 400, { code: 4005, message: "versionCode 不合法" });

  const rescue = listReleases(channel).find((item: ReleaseRecord) => item.enabled === 1 && item.rollout_percent >= 100 && item.version_code >= versionCode);
  if (versionCode > 0 && !rescue) {
    return json(response, 409, { code: 4091, message: `不存在放量 100% 且版本号不低于 ${versionCode} 的发布，抬高下限会让客户端无法升级` });
  }
  setMinSupportedVersionCode(channel, versionCode);
  return json(response, 200, success({ channel, minSupportedVersionCode: versionCode, rescueVersionCode: rescue?.version_code ?? null }));
}

async function changeConfig(request: IncomingMessage, response: ServerResponse) {
  const body = await readJson(request);
  const key = textOf(body.key);
  if (!/^[a-z0-9_.-]{1,64}$/i.test(key)) return json(response, 400, { code: 4005, message: "配置键不合法" });
  if (body.value === null) { deleteConfig(key); return json(response, 200, success({ key, removed: true })); }
  upsertConfig(key, String(body.value ?? ""), numberOrNull(body.minVersionCode), numberOrNull(body.maxVersionCode));
  return json(response, 200, success(listConfig(Number.MAX_SAFE_INTEGER)));
}

/** 静态管理令牌校验，使用定长比较避免时序泄漏。未配置 ADMIN_TOKEN 时整组管理接口关闭。 */
function verifyAdmin(request: IncomingMessage) {
  if (!adminToken) return false;
  const provided = String(request.headers["x-admin-token"] ?? "");
  const a = createHash("sha256").update(provided).digest();
  const b = createHash("sha256").update(adminToken).digest();
  return timingSafeEqual(a, b);
}

/** 下载地址优先用配置的对外基地址；未配置时按请求推导，TLS 由 socket 或代理头判断。 */
function apkUrlOf(request: IncomingMessage, channel: string, versionCode: number) {
  const query = channel === defaultChannel ? "" : `?channel=${encodeURIComponent(channel)}`;
  const path = `/api/v1/app/apk/${versionCode}${query}`;
  if (publicBaseUrl) return `${publicBaseUrl}${path}`;
  const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
  const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
  return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}${path}`;
}

function client(request: IncomingMessage) { return request.socket.remoteAddress ?? "unknown"; }
function textOf(value: unknown) { return typeof value === "string" ? value.trim() : ""; }
function clampPercent(value: unknown) { return Math.min(100, Math.max(0, Math.trunc(Number(value) || 0))); }
function numberOrNull(value: unknown) { return Number.isInteger(Number(value)) && value !== null && value !== undefined && value !== "" ? Number(value) : null; }

/** 解析正整数参数。注意查询参数缺省是 null，而 Number(null) 为 0，不能只用 Number.isInteger 判断。 */
function positiveIntOr(value: string | null, fallback: number) {
  if (value === null || value.trim() === "") return fallback;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

/** 安装包大小上限，防止管理令牌泄漏后被用来塞满磁盘。 */
const maxApkBytes = 300 * 1024 * 1024;
