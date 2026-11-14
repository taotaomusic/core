import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { HotUpdateClient } from "../client.js";
import { downloadToFile } from "../download.js";
import { createHotUpdateServer, type HotUpdateServer } from "../server.js";

/**
 * 端到端联调：createHotUpdateServer 起真实 HTTP 服务，
 * HotUpdateClient（真实 fetch，带超时重试）与 downloadToFile 走完整链路 ——
 * 等价于「其它系统零改造接入」的验收场景。
 */
async function withServer(fn: (server: HotUpdateServer, base: string) => Promise<void>) {
  const dataDir = mkdtempSync(join(tmpdir(), "hotupdate-e2e-"));
  const server = createHotUpdateServer({ port: 0, adminToken: "e2e", dataDir });
  await server.listen();
  const address = server.node.address();
  const base = `http://127.0.0.1:${typeof address === "object" && address ? address.port : server.port}`;
  try {
    await fn(server, base);
  } finally {
    await server.close();
    rmSync(dataDir, { recursive: true, force: true });
  }
}

/**
 * Windows 环境偶发 EADDRNOTAVAIL（临时端口/防火墙瞬时问题），对纯连接失败重试一次。
 */
async function fetchRetry(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (error) {
    if (error instanceof Error && "cause" in error) {
      await new Promise((resolve) => setTimeout(resolve, 200));
      return fetch(url, init);
    }
    throw error;
  }
}

const APK = Buffer.from("e2e-apk-payload".repeat(100));
const APK_SHA = createHash("sha256").update(APK).digest("hex");

test("端到端：SDK 客户端 + 校验下载跑通自建服务全链路", async () => {
  await withServer(async (_server, base) => {
    // 管理端 SDK 形状的登记（fetch 直调，逻辑与 HotUpdateAdmin 一致）。
    const upload = await fetchRetry(`${base}/api/v1/admin/releases?versionCode=80&versionName=1.0.80&rollout=100`, {
      method: "POST",
      headers: { Authorization: "Bearer e2e" },
      body: APK,
    });
    assert.equal(upload.status, 201);
    await fetchRetry(`${base}/api/v1/admin/config`, {
      method: "POST",
      headers: { Authorization: "Bearer e2e", "Content-Type": "application/json" },
      body: JSON.stringify({ key: "player.crossfade", value: "true" }),
    });

    // SDK 客户端检查更新（真实 HTTP，attempts=4 容忍本机瞬时连接抖动）。
    const client = new HotUpdateClient(base, "E2E/1.0", fetch, { attempts: 4, retryBackoffMs: 200 });
    const { result } = await client.check({ versionCode: 79, sdk: 35, deviceId: "e2e-device" });
    assert.equal(result.release?.versionCode, 80);
    assert.equal(result.release?.apkSha256, APK_SHA);
    assert.equal(result.config["player.crossfade"], "true");

    // 带校验下载。
    const dir = mkdtempSync(join(tmpdir(), "hotupdate-dl-"));
    try {
      const target = join(dir, "app-80.apk");
      const download = await downloadToFile(result.release!.apkUrl, target, { sha256: APK_SHA });
      assert.equal(download.bytes, APK.length);
      assert.equal(download.sha256, APK_SHA);
      assert.deepEqual(readFileSync(target), APK);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

test("downloadToFile：sha256 不匹配删除临时文件并抛错", async () => {
  const dir = mkdtempSync(join(tmpdir(), "hotupdate-dl-bad-"));
  try {
    // data: URL 让 fetch 直接可用且内容确定。
    const url = `data:application/octet-stream;base64,${APK.toString("base64")}`;
    await assert.rejects(
      downloadToFile(url, join(dir, "bad.apk"), { sha256: "0".repeat(64) }),
      /sha256 校验失败/,
    );
    // .part 已被清理、目标文件不存在。
    assert.equal(readFileSyncSafe(join(dir, "bad.apk")), null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("HotUpdateClient：超时触发可重试错误信息", async () => {
  await withServer(async (_server, base) => {
    // timeoutMs=1 必然超时；attempts=1 不重试，直接抛超时错误。
    const client = new HotUpdateClient(base, "E2E/1.0", fetch, { timeoutMs: 1, attempts: 1 });
    await assert.rejects(
      client.bootstrap({ versionCode: 1, sdk: 35, deviceId: "d" }),
      /检查更新超时/,
    );
  });
});

function readFileSyncSafe(path: string): Buffer | null {
  try {
    return readFileSync(path);
  } catch {
    return null;
  }
}
