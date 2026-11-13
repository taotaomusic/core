import { createHash } from "node:crypto";

/**
 * 灰度分桶 —— 与服务端 `ReleaseRepository.bucketOf` **逐字节一致**的参考实现。
 *
 * 规则（见 `server/src/release/release.repository.ts`）：
 * 对「发布 ID + 主体标识」做 SHA-1，取摘要前 4 字节按大端无符号整数对 100 取模。
 * 混入发布 ID 让每个版本得到互相独立的分桶；哈希对同一用户稳定，
 * 否则每次检查结果都会翻转，更新提示时有时无。
 *
 * 客户端/运维工具侧用它做**本地预判**（这个主体能不能拿到某版本的灰度），
 * 权威判定永远在服务端 `/app/bootstrap` —— 两边算法一致，预判才不会误导。
 */
export function bucketOf(releaseId: number, subject: string): number {
  return createHash("sha1").update(`${releaseId}:${subject}`).digest().readUInt32BE(0) % 100;
}

/**
 * 判断 subject 是否落在某个候选版本的灰度名单内。
 *
 * candidates 需要带 `id` 与 `rollout_percent` 字段（管理端 `/app/admin/releases`
 * 的返回形状）；返回第一个命中的候选，语义与服务端 `resolveUpdate` 的
 * `candidates.find(...)` 一致（候选应按版本号降序传入）。
 */
export function pickGrayCandidate<T extends { id: number; rollout_percent: number }>(
  candidates: readonly T[],
  subject: string,
): T | undefined {
  return candidates.find((item) => bucketOf(item.id, subject) < item.rollout_percent);
}

/** 构造 bootstrap 的分桶主体：登录用户用 `user:<id>`，匿名退回 `device:<deviceId>`。 */
export function bucketSubject(user: { id: number | string } | null, deviceId: string): string {
  return user ? `user:${user.id}` : `device:${deviceId || "unknown"}`;
}
