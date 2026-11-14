/**
 * 热更新协议模型 —— 与服务端 `/api/v1/app/bootstrap` 的响应形状一一对应。
 *
 * 这里是「整套兼容」的锚点：任何一端（Kotlin `hotupdate` 模块、Node SDK、服务端
 * `release.service.ts` 的 UpdateDescriptor / PatchDescriptor）改动字段都要三方同步。
 * 服务端源码见 `server/src/release/release.service.ts`；Kotlin 侧解析见
 * `hotupdate/src/main/java/com/taotao/music/update/AppUpdateApi.kt`。
 */

/** 服务端下发的一个可安装版本（available=false 时其余字段不出现）。 */
export type UpdateDescriptor =
  | { available: false; forced: false; minSupportedVersionCode: number }
  | {
      available: true;
      forced: boolean;
      versionCode: number;
      versionName: string;
      apkUrl: string;
      apkSize: number;
      apkSha256: string;
      releaseNote: string;
      minSupportedVersionCode: number;
    };

/** 服务端下发的一个可用补丁（available=false 时其余字段不出现）。 */
export type PatchDescriptor =
  | { available: false }
  | {
      available: true;
      patchVersion: number;
      targetVersionCode: number;
      url: string;
      size: number;
      sha256: string;
      note: string;
    };

/** bootstrap 完整响应（信封 `data` 字段的内容）。 */
export type BootstrapData = {
  update: UpdateDescriptor;
  patch: PatchDescriptor;
  config: Record<string, string>;
  configVersion: number;
};

/** 服务端统一信封：`{ code, message, data }`，code=0 表示成功。 */
export type ApiEnvelope<T> = { code: number; message?: string; data: T };

/** 供分桶与展示的标准化结果：把 Descriptor 的判别联合展开成可为空的字段。 */
export type NormalizedBootstrap = {
  release: {
    versionCode: number;
    versionName: string;
    apkUrl: string;
    apkSize: number;
    apkSha256: string;
    releaseNote: string;
  } | null;
  forced: boolean;
  minSupportedVersionCode: number;
  patch: {
    patchVersion: number;
    targetVersionCode: number;
    url: string;
    size: number;
    sha256: string;
    note: string;
  } | null;
  config: Record<string, string>;
  configVersion: number;
};

/** 把 bootstrap 响应展开成可直接判空的形状（Kotlin 侧 AppUpdateApi 的解析语义）。 */
export function normalizeBootstrap(data: BootstrapData): NormalizedBootstrap {
  const update = data.update;
  const release =
    update.available && update.versionCode > 0 && update.apkUrl
      ? {
          versionCode: update.versionCode,
          versionName: update.versionName,
          apkUrl: update.apkUrl,
          apkSize: update.apkSize,
          apkSha256: String(update.apkSha256).toLowerCase(),
          releaseNote: update.releaseNote,
        }
      : null;
  const patchSource = data.patch;
  const patch =
    patchSource.available &&
    patchSource.patchVersion > 0 &&
    patchSource.targetVersionCode > 0 &&
    patchSource.url
      ? {
          patchVersion: patchSource.patchVersion,
          targetVersionCode: patchSource.targetVersionCode,
          url: patchSource.url,
          size: patchSource.size,
          sha256: String(patchSource.sha256).toLowerCase(),
          note: patchSource.note,
        }
      : null;
  return {
    release,
    forced: update.available ? update.forced : false,
    minSupportedVersionCode: update.minSupportedVersionCode,
    patch,
    config: data.config ?? {},
    configVersion: data.configVersion ?? 0,
  };
}
