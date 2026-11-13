import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createHotUpdateServer, type HotUpdateServer } from "../server.js";

/** 起一个一次性服务实例，测试结束自动关闭。 */
async function withServer(fn: (server: HotUpdateServer, base: string, dataDir: string) => Promise<void>) {
  const dataDir = mkdtempSync(join(tmpdir(), "hotupdate-"));
  const server = createHotUpdateServer({ port: 0, adminToken: "test-token", dataDir });
  await server.listen();
  const address = server.node.address();
  const base = `http://127.0.0.1:${typeof address === "object" && address ? address.port : server.port}`;
  try {
    await fn(server, base, dataDir);
  } finally {
    await server.close();
    rmSync(dataDir, { recursive: true, force: true });
  }
}

async function admin(base: string, method: string, path: string, body?: unknown): Promise<{ status: number; json: any }> {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: { Authorization: "Bearer test-token", "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: response.status, json: await response.json().catch(() => ({})) };
}

test("登记 → 放量 → bootstrap 全链路（含灰度与强制更新）", async () => {
  await withServer(async (server, base) => {
    // 登记两个版本：71 全量、72 不放量。
    await admin(base, "POST", "/api/v1/admin/releases/link", {
      versionCode: 71,
      versionName: "1.0.71",
      apkUrl: "https://example.com/71.apk",
      sha256: "A".repeat(64),
      rollout: 100,
    });
    await admin(base, "POST", "/api/v1/admin/releases/link", {
      versionCode: 72,
      versionName: "1.0.72",
      apkUrl: "https://example.com/72.apk",
      sha256: "B".repeat(64),
      rollout: 0,
    });

    // 71 客户端：72 未放量 → 只看到 71 不算更新（available=false）。
    const bootstrap1: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap1.code, 0);
    assert.equal(bootstrap1.data.update.available, false);

    // 放量 72 到 100%。
    await admin(base, "POST", "/api/v1/admin/rollout", { versionCode: 72, percent: 100 });
    const bootstrap2: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap2.data.update.available, true);
    assert.equal(bootstrap2.data.update.versionCode, 72);
    assert.equal(bootstrap2.data.update.forced, false);
    assert.equal(bootstrap2.data.update.apkSha256, "b".repeat(64));

    // 参数校验。
    const bad: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=0&sdk=35&deviceId=d1`)).json();
    assert.equal(bad.code, 4005);

    // 抬强制下限到 72：71 客户端 forced=true，且下发 100% 放量版本。
    await admin(base, "POST", "/api/v1/admin/min-version", { versionCode: 72 });
    const bootstrap3: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap3.data.update.forced, true);
    assert.equal(bootstrap3.data.update.versionCode, 72);
  });
});

test("补丁：仅当没有整包更新时下发，且目标版本精确匹配", async () => {
  await withServer(async (_server, base, dataDir) => {
    // 登记全量 71（外链）+ 针对 71 的补丁 v1。
    await admin(base, "POST", "/api/v1/admin/releases/link", {
      versionCode: 71,
      versionName: "1.0.71",
      apkUrl: "https://example.com/71.apk",
      rollout: 100,
    });
    const patchBytes = Buffer.from("fake-dex-payload");
    const upload = await fetch(
      `${base}/api/v1/admin/patches?targetVersionCode=71&patchVersion=1&rollout=100`,
      { method: "POST", headers: { Authorization: "Bearer test-token" }, body: patchBytes },
    );
    assert.equal(upload.status, 201);

    // 71 客户端：无整包更新 → 补丁可用，带 sha256。
    const bootstrap: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap.data.patch.available, true);
    assert.equal(bootstrap.data.patch.patchVersion, 1);
    assert.equal(bootstrap.data.patch.sha256, createHash("sha256").update(patchBytes).digest("hex"));

    // 补丁下载字节一致。
    const downloaded = Buffer.from(await (await fetch(bootstrap.data.patch.url)).arrayBuffer());
    assert.deepEqual(downloaded, patchBytes);

    // 灰度 0%：不再下发。
    await admin(base, "POST", "/api/v1/admin/patch-rollout", { targetVersionCode: 71, patchVersion: 1, percent: 0 });
    const bootstrap2: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap2.data.patch.available, false);

    // 整包更新可用时补丁不下发（登记 72 并放量）。
    await admin(base, "POST", "/api/v1/admin/releases/link", {
      versionCode: 72,
      versionName: "1.0.72",
      apkUrl: "https://example.com/72.apk",
      rollout: 100,
    });
    await admin(base, "POST", "/api/v1/admin/patch-rollout", { targetVersionCode: 71, patchVersion: 1, percent: 100 });
    const bootstrap3: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap3.data.update.available, true);
    assert.equal(bootstrap3.data.patch.available, false);
    void dataDir;
  });
});

test("整包字节上传 → 下载（Range/ETag/HEAD/404）", async () => {
  await withServer(async (_server, base) => {
    const apk = Buffer.from("apk-bytes-0123456789".repeat(10));
    const upload = await fetch(`${base}/api/v1/admin/releases?versionCode=73&versionName=1.0.73`, {
      method: "POST",
      headers: { Authorization: "Bearer test-token" },
      body: apk,
    });
    assert.equal(upload.status, 201);
    await admin(base, "POST", "/api/v1/admin/rollout", { versionCode: 73, percent: 100 });

    const url = `${base}/api/v1/app/apk/73`;
    // 全量下载。
    const full = await fetch(url);
    assert.equal(full.status, 200);
    assert.deepEqual(Buffer.from(await full.arrayBuffer()), apk);
    assert.equal(full.headers.get("etag"), `"${createHash("sha256").update(apk).digest("hex")}"`);

    // Range 续传。
    const ranged = await fetch(url, { headers: { Range: "bytes=4-" } });
    assert.equal(ranged.status, 206);
    assert.deepEqual(Buffer.from(await ranged.arrayBuffer()), apk.subarray(4));

    // HEAD。
    const head = await fetch(url, { method: "HEAD" });
    assert.equal(head.status, 200);

    // 未登记版本 404。
    const missing = await fetch(`${base}/api/v1/app/apk/999`);
    assert.equal(missing.status, 404);
  });
});

test("管理接口鉴权：无令牌服务一律 401；配置读写与删除", async () => {
  await withServer(async (_server, base, _dataDir) => {
    const noAuth = await fetch(`${base}/api/v1/admin/releases`);
    assert.equal(noAuth.status, 401);
    const wrongAuth = await fetch(`${base}/api/v1/admin/releases`, {
      headers: { Authorization: "Bearer wrong" },
    });
    assert.equal(wrongAuth.status, 401);

    await admin(base, "POST", "/api/v1/admin/config", { key: "player.crossfade", value: "true" });
    const cfg = await admin(base, "GET", "/api/v1/admin/config");
    assert.equal(cfg.json.data[0].key, "player.crossfade");

    // bootstrap 拿到配置。
    const bootstrap: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap.data.config["player.crossfade"], "true");
    assert.ok(Number(bootstrap.data.configVersion) > 0);

    // 删除。
    await admin(base, "POST", "/api/v1/admin/config", { key: "player.crossfade", value: null });
    const bootstrap2: any = await (await fetch(`${base}/api/v1/app/bootstrap?versionCode=71&sdk=35&deviceId=d1`)).json();
    assert.equal(bootstrap2.data.config["player.crossfade"], undefined);
  });
});
