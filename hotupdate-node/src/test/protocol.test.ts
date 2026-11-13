import assert from "node:assert/strict";
import test from "node:test";
import { normalizeBootstrap, type BootstrapData } from "../protocol.js";

const data = (patch: BootstrapData["patch"]): BootstrapData => ({
  update: {
    available: true,
    forced: false,
    versionCode: 72,
    versionName: "1.0.72",
    apkUrl: "https://gh-proxy.org/https://github.com/x/y/releases/download/v72/app.apk",
    apkSize: 42_000_000,
    apkSha256: "ABCD1234",
    releaseNote: "修复若干问题",
    minSupportedVersionCode: 60,
  },
  patch,
  config: { "player.crossfade": "true" },
  configVersion: 7,
});

test("normalizeBootstrap 展开整包更新、sha256 转小写", () => {
  const result = normalizeBootstrap(data({ available: false }));
  assert.equal(result.release?.versionCode, 72);
  assert.equal(result.release?.apkSha256, "abcd1234");
  assert.equal(result.patch, null);
  assert.equal(result.forced, false);
  assert.equal(result.minSupportedVersionCode, 60);
  assert.equal(result.config["player.crossfade"], "true");
});

test("normalizeBootstrap 展开补丁（与 Kotlin AppUpdateApi 同判据）", () => {
  const result = normalizeBootstrap(
    data({
      available: true,
      patchVersion: 3,
      targetVersionCode: 71,
      url: "https://music.xydaigua.cn/api/v1/app/patch/71/3",
      size: 120_000,
      sha256: "FF00EE11",
      note: "热修崩溃",
    }),
  );
  assert.equal(result.release?.versionCode, 72);
  assert.equal(result.patch?.patchVersion, 3);
  assert.equal(result.patch?.sha256, "ff00ee11");
});

test("normalizeBootstrap 拒绝不完整字段（available=false / versionCode<=0 / 空地址）", () => {
  // 无整包、无补丁。
  const empty = normalizeBootstrap({
    update: { available: false, forced: false, minSupportedVersionCode: 60 },
    patch: { available: false },
    config: {},
    configVersion: 0,
  });
  assert.equal(empty.release, null);
  assert.equal(empty.patch, null);
  assert.equal(empty.forced, false);

  // available=true 但 versionCode<=0 → 不当作可更新（与 Kotlin AppUpdateApi 同判据）。
  const base = data({ available: false });
  const badVersion = normalizeBootstrap({
    ...base,
    update: { ...base.update, available: true, versionCode: 0, apkUrl: "https://x" },
  } as BootstrapData);
  assert.equal(badVersion.release, null);

  // available=true 但 apkUrl 为空 → 不当作可更新。
  const badUrl = normalizeBootstrap({
    ...base,
    update: { ...base.update, apkUrl: "" },
  } as BootstrapData);
  assert.equal(badUrl.release, null);

  // 补丁版本/目标版本非法 → patch 为 null。
  const badPatch = normalizeBootstrap({
    ...base,
    patch: {
      available: true,
      patchVersion: 0,
      targetVersionCode: 71,
      url: "https://x",
      size: 1,
      sha256: "aa",
      note: "",
    },
  } as BootstrapData);
  assert.equal(badPatch.patch, null);
});
