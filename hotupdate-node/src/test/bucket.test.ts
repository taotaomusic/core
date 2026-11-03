import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { bucketOf, bucketSubject, pickGrayCandidate } from "../bucket.js";

test("bucketOf 与服务端 ReleaseRepository.bucketOf 完全一致", () => {
  // 参考实现直接照抄 server/src/release/release.repository.ts 的算法。
  const serverBucket = (releaseId: number, subject: string): number =>
    createHash("sha1").update(`${releaseId}:${subject}`).digest().readUInt32BE(0) % 100;
  const samples = [
    [1, "user:42"],
    [7, "device:abc-123"],
    [100, "user:1"],
    [999_999, "device:8f2c-9911-aa"],
    [0, ""],
  ] as const;
  for (const [id, subject] of samples) {
    assert.equal(bucketOf(id, subject), serverBucket(id, subject), `release ${id} / ${subject}`);
  }
});

test("bucketOf 对同一主体稳定、取值在 0-99 之间", () => {
  const first = bucketOf(3, "user:7");
  assert.equal(bucketOf(3, "user:7"), first);
  for (let id = 1; id <= 200; id++) {
    const value = bucketOf(id, "user:7");
    assert.ok(value >= 0 && value <= 99, `bucket 越界：${value}`);
  }
});

test("bucketSubject 登录用户与匿名设备两种形状", () => {
  assert.equal(bucketSubject({ id: 42 }, "dev"), "user:42");
  assert.equal(bucketSubject(null, "dev-001"), "device:dev-001");
  // deviceId 为空且未登录：服务端会退回按来源 IP，这里兜底 unknown 占位。
  assert.equal(bucketSubject(null, ""), "device:unknown");
});

test("pickGrayCandidate 按灰度名单命中第一个满足的候选", () => {
  const candidates = [
    { id: 10, rollout_percent: 0, version_code: 3 },
    { id: 20, rollout_percent: 50, version_code: 3 },
    { id: 30, rollout_percent: 100, version_code: 3 },
  ];
  const picked = pickGrayCandidate(candidates, "user:42");
  // 该主体对 id=30 必然命中（100% 全量），对 id=10 永不命中。
  if (bucketOf(20, "user:42") < 50) {
    assert.equal(picked?.id, 20);
  } else {
    assert.equal(picked?.id, 30);
  }
  // 全部不放量时无候选命中。
  assert.equal(pickGrayCandidate([{ id: 10, rollout_percent: 0 }], "user:42"), undefined);
});
