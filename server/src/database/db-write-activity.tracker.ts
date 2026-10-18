import { Injectable } from "@nestjs/common";

/**
 * 「写后立读」窗口，单位毫秒。
 *
 * 物理流复制是异步的：写请求虽然固定走主库，但紧随其后的读如果落到副本，
 * 可能还没同步到刚写入的数据 —— 注册后立即鉴权、收藏后立即刷新列表这类
 * 时序必须读到自己的写。2 秒覆盖一次前端往返 + 网络延迟，同时把窗口压得
 * 足够短，不让整个会话的读都被拖回主库。
 */
const WRITE_ACTIVITY_WINDOW_MS = 2_000;

/** 过期标记的清扫间隔。窗口只有 2 秒，残留条目极少，无需每次访问都全量清扫。 */
const SWEEP_INTERVAL_MS = 60_000;

/**
 * 用户写操作活动追踪（进程内状态，懒清扫）。
 *
 * 写请求成功后由 `DbWriteMarkerInterceptor` 标记用户 ID，路由中间件在 2 秒
 * 窗口内把该用户的读请求也升级到主库。只活在进程内存里，不落库 ——
 * 单实例部署前提下自洽，扩容假设见 [11-architecture-modules.md §4]。
 *
 * 与限流桶同理：重启后窗口清空，最坏情况是个别用户写后的第一次读短暂读到
 * 旧数据，下一次轮询即恢复，不影响正确性之外的任何东西。
 */
@Injectable()
export class DbWriteActivityTracker {
  private readonly activeUntil = new Map<number, number>();
  private lastSweepAt = Date.now();

  /** 让该用户的读请求在窗口期内走主库。写失败时不调用（失败写没产生新数据）。 */
  mark(userId: number): void {
    this.activeUntil.set(userId, Date.now() + WRITE_ACTIVITY_WINDOW_MS);
  }

  /** 该用户是否处于写后立读窗口。读取即顺手清除已过期条目。 */
  isActive(userId: number): boolean {
    const expiresAt = this.activeUntil.get(userId);
    if (expiresAt === undefined) return false;
    const now = Date.now();
    if (expiresAt <= now) {
      this.activeUntil.delete(userId);
      return false;
    }
    this.sweep(now);
    return true;
  }

  /** 惰性清扫：到期条目留着也只是死数据，周期到了就清一遍，防止 Map 单调增长。 */
  private sweep(now: number): void {
    if (now - this.lastSweepAt < SWEEP_INTERVAL_MS) return;
    this.lastSweepAt = now;
    for (const [userId, expiresAt] of this.activeUntil) {
      if (expiresAt <= now) this.activeUntil.delete(userId);
    }
  }
}
