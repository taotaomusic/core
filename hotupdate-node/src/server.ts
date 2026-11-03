import { createHash } from "node:crypto";
import { createReadStream, existsSync, readFileSync, statSync } from "node:fs";
import { mkdir, rename, writeFile } from "node:fs/promises";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { dirname, join, resolve } from "node:path";
import { bucketOf } from "./bucket.js";

/**
 * 零依赖可自建热更新后端 —— 一套与桃桃主服务端**同契约**的独立实现。
 *
 * 适用场景：其它系统想要热更新能力但不想部署整个 NestJS 服务；
 * 或作为主服务端的开发替身（记录为「参考实现」，生产仍以
 * `server/src/release/` 的 NestJS 版本为准）。
 *
 * 契约对齐（改任何一边都要三方同步：Kotlin hotupdate / 本 SDK / 服务端 release 模块）：
 * - GET /api/v1/app/bootstrap   → { code:0, data:{ update, patch, config, configVersion } }
 *   灰度分桶算法与主服务端一致：SHA-1("<releaseId>:<subject>") 前 4 字节大端 %100。
 *   带有效/过期令牌按 `user:<id>` 分桶，未登录退回 `device:<deviceId>`。
 * - GET /api/v1/app/apk/:versionCode        → 安装包字节（支持 Range/HEAD/ETag）
 * - GET /api/v1/app/patch/:target/:version  → 补丁字节
 * - POST /api/v1/admin/…（管理接口，Bearer 静态令牌）：releases / releases/link /
 *   rollout / min-version / patches / patch-rollout / config / releases/list…
 *
 * 有意从简的部分（参考实现的定位决定的，README 里已说明）：
 * - 存储用内存 + 可选 JSON 快照（persistPath），不依赖任何数据库；
 * - 管理接口用静态 Bearer 令牌（adminToken），不走管理员会话/2FA ——
 *   自建部署通常只有一两个运维，主服务端的企业级认证体系不必照搬；
 * - 没有响应头 x-latest-version-code / x-latest-patch-version（那是主服务端
 *   借每个业务响应做的提示优化；独立部署只服务 bootstrap 场景，冷启动查一次即可）。
 */

/** 一条发布记录（字段与主服务端 app_release 表同形状）。 */
export type ReleaseRecord = {
  id: number;
  channel: string;
  version_code: number;
  version_name: string;
  apk_file: string;
  apk_url: string;
  apk_size: number;
  apk_sha256: string;
  release_note: string;
  rollout_percent: number;
  min_sdk: number;
  enabled: number;
  published_at: number;
};

/** 一条补丁记录（字段与主服务端 app_patch 表同形状）。 */
export type PatchRecord = {
  id: number;
  channel: string;
  target_version_code: number;
  patch_version: number;
  patch_file: string;
  patch_url: string;
  patch_size: number;
  patch_sha256: string;
  note: string;
  rollout_percent: number;
  enabled: number;
  published_at: number;
};

export type HotUpdateServerOptions = {
  /** 端口，默认 4730。 */
  port?: number;
  /** 管理接口的 Bearer 令牌；不传则管理接口全部 401（安全默认）。 */
  adminToken?: string;
  /** APK/补丁落盘目录，默认 `<cwd>/.hotupdate-data`。 */
  dataDir?: string;
  /** JSON 快照路径；给了就启动时加载、每次写入后落盘。 */
  persistPath?: string;
};

export type HotUpdateServer = {
  /** 端口。 */
  port: number;
  /** Node http server；`listen()` 后可用。 */
  node: Server;
  /** 启动监听。 */
  listen(): Promise<void>;
  /** 关闭并清空数据（测试用）。 */
  close(): Promise<void>;
};

export function createHotUpdateServer(options: HotUpdateServerOptions = {}): HotUpdateServer {
  const port = options.port ?? 4730;
  const dataDir = resolve(options.dataDir ?? join(process.cwd(), ".hotupdate-data"));
  const adminToken = options.adminToken;

  // ---- 内存存储 ----
  let nextId = 1;
  const releases = new Map<number, ReleaseRecord>();
  const patches = new Map<number, PatchRecord>();
  const minSupported = new Map<string, number>(); // channel → versionCode
  const remoteConfig = new Map<string, { value: string; min?: number; max?: number }>();

  // ---- 可选持久化 ----
  const snapshot = () =>
    JSON.stringify({
      nextId,
      releases: [...releases.values()],
      patches: [...patches.values()],
      minSupported: [...minSupported.entries()],
      config: [...remoteConfig.entries()],
    });
  const persist = () =>
    options.persistPath
      ? mkdir(dirname(options.persistPath), { recursive: true })
          .then(() => writeFile(`${options.persistPath}.tmp`, snapshot()))
          .then(() => rename(`${options.persistPath}.tmp`, options.persistPath as string))
          .catch(() => undefined)
      : Promise.resolve();
  if (options.persistPath && existsSync(options.persistPath)) {
    try {
      const saved = JSON.parse(readSnapshotSync(options.persistPath));
      nextId = saved.nextId ?? 1;
      for (const r of saved.releases ?? []) releases.set(r.id, r);
      for (const p of saved.patches ?? []) patches.set(p.id, p);
      for (const [k, v] of saved.minSupported ?? []) minSupported.set(k, v);
      for (const [k, v] of saved.config ?? []) remoteConfig.set(k, v);
    } catch {
      // 快照损坏按空库启动，不挡服务。
    }
  }

  const node = createServer((request, response) => {
    handle(request, response).catch(() => sendJson(response, 500, { code: 5000, message: "内部错误" }));
  });

  async function handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    // URL 解析只需路径与查询参数；下发地址的 host 一律从 Host 请求头取
    //（服务可能监听在动态端口/反代后面，兜底 base 的端口不可信）。
    const url = new URL(request.url ?? "/", "http://internal.invalid");
    const requestHost = String(request.headers.host ?? "localhost");
    const path = url.pathname;
    const method = (request.method ?? "GET").toUpperCase();

    // ---- 公开接口 ----
    if (method === "GET" && path === "/api/v1/app/bootstrap") return bootstrap(request, response, url, requestHost);
    if ((method === "GET" || method === "HEAD") && /^\/api\/v1\/app\/apk\/\d+$/.test(path)) {
      return serveApk(request, response, Number(path.split("/").pop()), url, requestHost);
    }
    if ((method === "GET" || method === "HEAD") && /^\/api\/v1\/app\/patch\/\d+\/\d+$/.test(path)) {
      // /api/v1/app/patch/:target/:patchVersion → split 后 [,"api","v1","app","patch",target,version]
      const parts = path.split("/");
      return servePatch(request, response, Number(parts[5]), Number(parts[6]), url, requestHost);
    }

    // ---- 管理接口 ----
    if (path.startsWith("/api/v1/admin/")) {
      if (!adminToken) return sendJson(response, 401, { code: 4031, message: "管理接口未启用" });
      const auth = String(request.headers.authorization ?? "");
      if (auth !== `Bearer ${adminToken}`) {
        return sendJson(response, 401, { code: 4031, message: "管理员会话无效" });
      }
      return admin(request, response, method, path, url);
    }

    sendJson(response, 404, { code: 4040, message: "接口不存在" });
  }

  // ---- bootstrap：与主服务端 ReleaseService.resolveUpdate/resolvePatch 同判定 ----
  function bootstrap(request: IncomingMessage, response: ServerResponse, url: URL, requestHost: string): void {
    const channel = url.searchParams.get("channel") ?? "release";
    const versionCode = Number(url.searchParams.get("versionCode") ?? 0);
    const sdk = Number(url.searchParams.get("sdk") ?? 0);
    const deviceId = String(url.searchParams.get("deviceId") ?? "").slice(0, 64);
    if (!Number.isInteger(versionCode) || versionCode <= 0) {
      return sendJson(response, 400, { code: 4005, message: "versionCode 不合法" });
    }
    if (!Number.isInteger(sdk) || sdk <= 0) {
      return sendJson(response, 400, { code: 4005, message: "sdk 不合法" });
    }

    // 分桶主体：带令牌按 user 分桶（自建服务不校验令牌有效性，Header 有值即认）；
    // 未登录退回设备号，再退回来源地址 —— 与主服务端的降级顺序一致。
    const auth = String(request.headers.authorization ?? "");
    const subject = auth.startsWith("Bearer ") && auth.length > 7
      ? `user:${auth.slice(7)}`
      : `device:${deviceId || clientAddress(request)}`;

    const floor = minSupported.get(channel) ?? 0;
    const forced = versionCode < floor;
    const candidates = [...releases.values()]
      .filter((r) => r.channel === channel && r.enabled === 1 && r.version_code > versionCode && r.min_sdk <= sdk)
      .sort((a, b) => b.version_code - a.version_code);
    const target = forced
      ? candidates.find((r) => r.rollout_percent >= 100)
      : candidates.find((r) => bucketOf(r.id, subject) < r.rollout_percent);

    const update = target
      ? {
          available: true,
          forced,
          versionCode: target.version_code,
          versionName: target.version_name,
          apkUrl: apkUrlOf(url, requestHost, channel, target),
          apkSize: target.apk_size,
          apkSha256: target.apk_sha256,
          releaseNote: target.release_note,
          minSupportedVersionCode: floor,
        }
      : { available: false, forced: false, minSupportedVersionCode: floor };

    // 有整包更新时不下发补丁（与主服务端一致）。
    const patch = target
      ? { available: false }
      : resolvePatch(channel, versionCode, subject, url, requestHost);

    sendJson(response, 200, {
      code: 0,
      message: "ok",
      data: {
        update,
        patch,
        config: configFor(versionCode),
        // 与主服务端语义对齐：配置版本取最大更新时间；参考实现简化为「有配置即当前时间」。
        configVersion: remoteConfig.size > 0 ? Date.now() : 0,
      },
    });
  }

  function resolvePatch(channel: string, versionCode: number, subject: string, url: URL, requestHost: string) {
    const candidates = [...patches.values()]
      .filter((p) => p.channel === channel && p.target_version_code === versionCode && p.enabled === 1)
      .sort((a, b) => b.patch_version - a.patch_version);
    const target = candidates.find((p) => bucketOf(p.id, subject) < p.rollout_percent);
    if (!target) return { available: false };
    return {
      available: true,
      patchVersion: target.patch_version,
      targetVersionCode: target.target_version_code,
      url: patchUrlOf(url, requestHost, channel, target),
      size: target.patch_size,
      sha256: target.patch_sha256,
      note: target.note,
    };
  }

  // ---- 文件下发（Range/ETag/HEAD 都支持；路径白名单防穿越） ----
  function serveApk(request: IncomingMessage, response: ServerResponse, versionCode: number, url: URL, _requestHost: string): void {
    const channel = url.searchParams.get("channel") ?? "release";
    const release = [...releases.values()].find(
      (r) => r.channel === channel && r.version_code === versionCode && r.enabled === 1,
    );
    if (!release) return sendJson(response, 404, { code: 4041, message: "版本不存在" });
    if (release.apk_url) {
      response.writeHead(302, { Location: release.apk_url });
      response.end();
      return;
    }
    serveFile(request, response, join(dataDir, release.apk_file), release.apk_sha256, release.apk_size);
  }

  function servePatch(
    request: IncomingMessage,
    response: ServerResponse,
    targetVersionCode: number,
    patchVersion: number,
    url: URL,
    _requestHost: string,
  ): void {
    const channel = url.searchParams.get("channel") ?? "release";
    const patch = [...patches.values()].find(
      (p) => p.channel === channel && p.target_version_code === targetVersionCode && p.patch_version === patchVersion,
    );
    if (!patch || patch.enabled !== 1) return sendJson(response, 404, { code: 4041, message: "补丁不存在" });
    if (patch.patch_url) {
      response.writeHead(302, { Location: patch.patch_url });
      response.end();
      return;
    }
    serveFile(request, response, join(dataDir, patch.patch_file), patch.patch_sha256, patch.patch_size);
  }

  function serveFile(
    request: IncomingMessage,
    response: ServerResponse,
    filePath: string,
    sha256: string,
    size: number,
  ): void {
    if (!existsSync(filePath)) return sendJson(response, 404, { code: 4041, message: "文件缺失" });
    const actual = statSync(filePath);
    const etag = `"${sha256 || createHash("sha256").update(String(actual.size)).digest("hex")}"`;
    response.setHeader("ETag", etag);
    response.setHeader("Accept-Ranges", "bytes");
    response.setHeader("Content-Type", "application/vnd.android.package-archive");

    const range = String(request.headers.range ?? "");
    const match = /^bytes=(\d+)-(\d*)$/.exec(range);
    if (match) {
      const start = Number(match[1]);
      const end = match[2] ? Math.min(Number(match[2]), actual.size - 1) : actual.size - 1;
      if (start >= actual.size || start > end) {
        response.writeHead(416, { "Content-Range": `bytes */${actual.size}` });
        response.end();
        return;
      }
      response.writeHead(206, {
        "Content-Range": `bytes ${start}-${end}/${actual.size}`,
        "Content-Length": end - start + 1,
      });
      createReadStream(filePath, { start, end }).pipe(response);
      return;
    }
    if (request.method === "HEAD") {
      response.writeHead(200, { "Content-Length": actual.size });
      response.end();
      return;
    }
    response.writeHead(200, { "Content-Length": actual.size });
    createReadStream(filePath).pipe(response);
  }

  // ---- 管理接口（静态 Bearer 令牌；形状对齐主服务端管理路由） ----
  async function admin(
    request: IncomingMessage,
    response: ServerResponse,
    method: string,
    path: string,
    url: URL,
  ): Promise<void> {
    const now = Date.now();

    if (method === "POST" && path === "/api/v1/admin/releases") {
      // 上传 APK 字节登记（APK 字节即 body，元数据在查询参数）。
      const versionCode = Number(url.searchParams.get("versionCode") ?? 0);
      const versionName = (url.searchParams.get("versionName") ?? "").trim();
      const channel = url.searchParams.get("channel") ?? "release";
      if (!Number.isInteger(versionCode) || versionCode <= 0 || !/^\d+(\.\d+)*$/.test(versionName)) {
        return sendJson(response, 400, { code: 4005, message: "versionCode/versionName 不合法" });
      }
      const chunks: Buffer[] = [];
      for await (const chunk of request) chunks.push(chunk as Buffer);
      const bytes = Buffer.concat(chunks);
      const sha256 = createHash("sha256").update(bytes).digest("hex");
      const fileName = `${channel}-${versionCode}.apk`;
      await mkdir(dataDir, { recursive: true });
      await writeFile(join(dataDir, fileName), bytes);
      upsertRelease({
        channel,
        version_code: versionCode,
        version_name: versionName,
        apk_file: fileName,
        apk_url: "",
        apk_size: bytes.length,
        apk_sha256: sha256,
        release_note: url.searchParams.get("note") ?? "",
        rollout_percent: clampPercent(url.searchParams.get("rollout") ?? "0"),
        min_sdk: Number(url.searchParams.get("minSdk") ?? 24) || 24,
        enabled: url.searchParams.get("enabled") === "false" ? 0 : 1,
        published_at: now,
      });
      await persist();
      return sendJson(response, 201, { code: 0, message: "ok" });
    }

    if (method === "POST" && path === "/api/v1/admin/releases/link") {
      const body = (await readJsonBody(request).catch(() => ({}))) as Record<string, any>;
      if (typeof body.versionCode !== "number" || typeof body.apkUrl !== "string" || typeof body.versionName !== "string") {
        return sendJson(response, 400, { code: 4005, message: "versionCode/versionName/apkUrl 必填" });
      }
      upsertRelease({
        channel: body.channel ?? "release",
        version_code: body.versionCode,
        version_name: body.versionName,
        apk_file: "",
        apk_url: body.apkUrl,
        apk_size: body.apkSize ?? 0,
        apk_sha256: String(body.sha256 ?? "").toLowerCase(),
        release_note: body.releaseNote ?? "",
        rollout_percent: clampPercent(String(body.rollout ?? 0)),
        min_sdk: body.minSdk ?? 24,
        enabled: body.enabled === false ? 0 : 1,
        published_at: now,
      });
      await persist();
      return sendJson(response, 201, { code: 0, message: "ok" });
    }

    if (method === "POST" && path === "/api/v1/admin/rollout") {
      const body = (await readJsonBody(request).catch(() => ({}))) as Record<string, any>;
      const record = [...releases.values()].find(
        (r) => r.channel === (body.channel ?? "release") && r.version_code === body.versionCode,
      );
      if (!record) return sendJson(response, 404, { code: 4041, message: "版本不存在" });
      record.rollout_percent = clampPercent(String(body.percent ?? 0));
      if (body.enabled !== undefined) record.enabled = body.enabled ? 1 : 0;
      await persist();
      return sendJson(response, 200, { code: 0, message: "ok" });
    }

    if (method === "POST" && path === "/api/v1/admin/min-version") {
      const body = (await readJsonBody(request).catch(() => ({}))) as Record<string, any>;
      minSupported.set(body.channel ?? "release", Math.max(0, Number(body.versionCode ?? 0)));
      await persist();
      return sendJson(response, 200, { code: 0, message: "ok" });
    }

    if (method === "POST" && path === "/api/v1/admin/patches") {
      const targetVersionCode = Number(url.searchParams.get("targetVersionCode") ?? 0);
      const patchVersion = Number(url.searchParams.get("patchVersion") ?? 0);
      const channel = url.searchParams.get("channel") ?? "release";
      if (targetVersionCode <= 0 || patchVersion <= 0) {
        return sendJson(response, 400, { code: 4005, message: "targetVersionCode/patchVersion 不合法" });
      }
      const chunks: Buffer[] = [];
      for await (const chunk of request) chunks.push(chunk as Buffer);
      const bytes = Buffer.concat(chunks);
      const sha256 = createHash("sha256").update(bytes).digest("hex");
      const fileName = `patch-${channel}-${targetVersionCode}-${patchVersion}.apk`;
      await mkdir(dataDir, { recursive: true });
      await writeFile(join(dataDir, fileName), bytes);
      upsertPatch({
        channel,
        target_version_code: targetVersionCode,
        patch_version: patchVersion,
        patch_file: fileName,
        patch_url: "",
        patch_size: bytes.length,
        patch_sha256: sha256,
        note: url.searchParams.get("note") ?? "",
        rollout_percent: clampPercent(url.searchParams.get("rollout") ?? "0"),
        enabled: 1,
        published_at: now,
      });
      await persist();
      return sendJson(response, 201, { code: 0, message: "ok" });
    }

    if (method === "POST" && path === "/api/v1/admin/patch-rollout") {
      const body = (await readJsonBody(request).catch(() => ({}))) as Record<string, any>;
      const patch = [...patches.values()].find(
        (p) =>
          p.channel === (body.channel ?? "release") &&
          p.target_version_code === body.targetVersionCode &&
          p.patch_version === body.patchVersion,
      );
      if (!patch) return sendJson(response, 404, { code: 4041, message: "补丁不存在" });
      patch.rollout_percent = clampPercent(String(body.percent ?? 0));
      if (body.enabled !== undefined) patch.enabled = body.enabled ? 1 : 0;
      await persist();
      return sendJson(response, 200, { code: 0, message: "ok" });
    }

    if (method === "POST" && path === "/api/v1/admin/config") {
      const body = (await readJsonBody(request).catch(() => ({}))) as Record<string, any>;
      if (typeof body.key !== "string" || !body.key) {
        return sendJson(response, 400, { code: 4005, message: "key 必填" });
      }
      if (body.value === null) {
        remoteConfig.delete(body.key);
      } else {
        remoteConfig.set(body.key, {
          value: String(body.value),
          min: body.minVersionCode,
          max: body.maxVersionCode,
        });
      }
      await persist();
      return sendJson(response, 200, { code: 0, message: "ok" });
    }

    if (method === "GET" && path === "/api/v1/admin/releases") {
      const channel = url.searchParams.get("channel") ?? "release";
      const list = [...releases.values()]
        .filter((r) => r.channel === channel)
        .sort((a, b) => b.version_code - a.version_code);
      return sendJson(response, 200, { code: 0, message: "ok", data: list });
    }

    if (method === "GET" && path === "/api/v1/admin/patches") {
      const channel = url.searchParams.get("channel") ?? "release";
      const list = [...patches.values()].filter((p) => p.channel === channel);
      return sendJson(response, 200, { code: 0, message: "ok", data: list });
    }

    if (method === "GET" && path === "/api/v1/admin/config") {
      const entries = [...remoteConfig.entries()].map(([key, c]) => ({
        key,
        value: c.value,
        min_version_code: c.min ?? null,
        max_version_code: c.max ?? null,
      }));
      return sendJson(response, 200, { code: 0, message: "ok", data: entries });
    }

    sendJson(response, 404, { code: 4040, message: "管理接口不存在" });
  }

  function upsertRelease(record: Omit<ReleaseRecord, "id">): void {
    const existing = [...releases.values()].find(
      (r) => r.channel === record.channel && r.version_code === record.version_code,
    );
    if (existing) {
      // 与主服务端一致：覆盖登记刻意不动 rollout_percent。
      const { rollout_percent: _keep, ...rest } = record;
      Object.assign(existing, rest);
    } else {
      releases.set(nextId, { ...record, id: nextId });
      nextId += 1;
    }
  }

  function upsertPatch(record: Omit<PatchRecord, "id">): void {
    const existing = [...patches.values()].find(
      (p) =>
        p.channel === record.channel &&
        p.target_version_code === record.target_version_code &&
        p.patch_version === record.patch_version,
    );
    if (existing) {
      const { rollout_percent: _keep, ...rest } = record;
      Object.assign(existing, rest);
    } else {
      patches.set(nextId, { ...record, id: nextId });
      nextId += 1;
    }
  }

  function configFor(versionCode: number): Record<string, string> {
    const entries = [...remoteConfig.entries()].filter(
      ([, c]) => (c.min === undefined || c.min <= versionCode) && (c.max === undefined || c.max >= versionCode),
    );
    return Object.fromEntries(entries.map(([key, c]) => [key, c.value]));
  }

  function apkUrlOf(url: URL, requestHost: string, channel: string, release: ReleaseRecord): string {
    if (release.apk_url) return release.apk_url;
    const query = channel === "release" ? "" : `?channel=${encodeURIComponent(channel)}`;
    return `http://${requestHost}/api/v1/app/apk/${release.version_code}${query}`;
  }

  function patchUrlOf(url: URL, requestHost: string, channel: string, patch: PatchRecord): string {
    if (patch.patch_url) return patch.patch_url;
    const query = channel === "release" ? "" : `?channel=${encodeURIComponent(channel)}`;
    return `http://${requestHost}/api/v1/app/patch/${patch.target_version_code}/${patch.patch_version}${query}`;
  }

  function clampPercent(raw: string): number {
    const value = Number(raw);
    if (!Number.isFinite(value)) return 0;
    return Math.min(100, Math.max(0, Math.round(value)));
  }

  return {
    port,
    node,
    listen: () =>
      new Promise((resolveListen) => {
        node.listen(port, () => resolveListen());
      }),
    close: () =>
      new Promise((resolveClose) => {
        if (options.persistPath) {
          void writeFile(options.persistPath, snapshot()).catch(() => undefined);
        }
        node.close(() => resolveClose());
      }),
  };
}

// ---- 小工具 ----

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  response.end(payload);
}

async function readJsonBody(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(chunk as Buffer);
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function clientAddress(request: IncomingMessage): string {
  return request.socket.remoteAddress ?? "unknown";
}

/** 同步读快照（仅启动路径，避免把 createHotUpdateServer 变成异步）。 */
function readSnapshotSync(path: string): string {
  return readFileSync(path, "utf8");
}
