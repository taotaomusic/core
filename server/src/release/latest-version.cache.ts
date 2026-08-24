import { Global, Injectable, Module } from "@nestjs/common";

/**
 * 「当前全量可用的最高版本号」缓存。
 *
 * 存在的理由是响应头拦截器必须**同步**取值:它跑在每个请求上,包括音频代理这种
 * 热路径,不能为了一个提示性的头去等一次数据库往返。
 *
 * 缓存由 [ReleaseRepository] 的写入路径失效 —— 失效点放在 repository 而不是
 * controller,这样任何新增的管理接口都不可能忘记刷新它。
 *
 * 单进程内有效。若将来跑多实例,某个实例上的发布不会立刻反映到其它实例的响应头上,
 * 最坏结果只是客户端晚一点才发现新版本,`/app/bootstrap` 仍然是权威判定。
 */
@Injectable()
export class LatestVersionCache {
  /** undefined 表示还没查过;null 表示查过但没有可下发的版本。 */
  private value: number | null | undefined = undefined;
  /** 宿主版本 → 已全量放量的最新补丁；同样供响应热路径同步读取。 */
  private readonly patches = new Map<number, number | null>();

  get(): number | null | undefined {
    return this.value;
  }

  set(versionCode: number | null): void {
    this.value = versionCode;
  }

  invalidate(): void {
    this.value = undefined;
    this.patches.clear();
  }

  patchFor(versionCode: number): number | null | undefined {
    return this.patches.get(versionCode);
  }

  setPatch(versionCode: number, patchVersion: number | null): void {
    this.patches.set(versionCode, patchVersion);
  }
}

/** 全局模块:响应头拦截器与发布仓库都要用到同一个实例。 */
@Global()
@Module({
  providers: [LatestVersionCache],
  exports: [LatestVersionCache],
})
export class LatestVersionModule {}
