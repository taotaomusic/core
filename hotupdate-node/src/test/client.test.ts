import assert from "node:assert/strict";
import test from "node:test";
import { HotUpdateClient } from "../client.js";

/** 构造一个固定信封响应的 fetch stub。 */
function stubFetch(status: number, body: unknown, headers: Record<string, string> = {}) {
  const calls: { url: string; init: RequestInit }[] = [];
  const impl = (async (url: string | URL, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json", ...headers },
    });
  }) as typeof fetch;
  return { impl, calls };
}

test("HotUpdateClient 正常请求：URL/头/令牌/信封解析", async () => {
  const { impl, calls } = stubFetch(200, {
    code: 0,
    data: {
      update: { available: false, forced: false, minSupportedVersionCode: 60 },
      patch: { available: false },
      config: { k: "v" },
      configVersion: 5,
    },
  }, { "x-latest-version-code": "72", "x-latest-patch-version": "3" });
  const client = new HotUpdateClient("https://music.xydaigua.cn", "TaotaoMusic/1.0", impl);

  const check = await client.check({ versionCode: 71, sdk: 35, deviceId: "dev-1", token: "tok" });

  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /^https:\/\/music\.xydaigua\.cn\/api\/v1\/app\/bootstrap\?/);
  assert.match(calls[0].url, /versionCode=71/);
  assert.match(calls[0].url, /sdk=35/);
  assert.match(calls[0].url, /deviceId=dev-1/);
  const auth = (calls[0].init.headers as Record<string, string>).Authorization;
  assert.equal(auth, "Bearer tok");
  assert.equal(check.latestVersionCode, 72);
  assert.equal(check.latestPatchVersion, 3);
  assert.equal(check.result.release, null);
  assert.equal(check.result.config.k, "v");
});

test("未登录不带 Authorization 头", async () => {
  const { impl, calls } = stubFetch(200, {
    code: 0,
    data: {
      update: { available: false, forced: false, minSupportedVersionCode: 0 },
      patch: { available: false },
      config: {},
      configVersion: 0,
    },
  });
  const client = new HotUpdateClient("https://x", "UA", impl);
  await client.bootstrap({ versionCode: 1, sdk: 35, deviceId: "d" });
  assert.equal((calls[0].init.headers as Record<string, string>).Authorization, undefined);
});

test("HTTP 错误与业务 code 非 0 都抛错", async () => {
  const httpFail = stubFetch(503, {});
  await assert.rejects(
    new HotUpdateClient("https://x", "UA", httpFail.impl).bootstrap({ versionCode: 1, sdk: 35, deviceId: "d" }),
    /HTTP 503/,
  );
  const bizFail = stubFetch(200, { code: 4005, message: "versionCode 不合法" });
  await assert.rejects(
    new HotUpdateClient("https://x", "UA", bizFail.impl).bootstrap({ versionCode: 1, sdk: 35, deviceId: "d" }),
    /versionCode 不合法/,
  );
});
