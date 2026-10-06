// 桌面端对 Kotlin/Wasm 播放逻辑门面（:shared 的 wasmJsMain PlayerFacade）的 TS 包装层。
//
// 职责边界：
// - 懒加载 wasm 模块（首次调用时动态 import，之后复用同一实例）；
// - 把 Kotlin 门面的「有状态缓存」包成 TS 侧无状态的纯函数（模块内部持缓存句柄）；
// - wasm 不可用时（模块缺失 / 老环境）退回本目录 lyrics.ts 的纯 TS 实现——
//   两套实现语义一致（TS 版本就是 Kotlin 版的移植），调用方无感。
//
// 构建产物接入：CI 的 desktop job 会把 :shared 的 wasmJs 产物（.mjs + .wasm）
// 拷进 desktop/src/wasm/（见根 ci.yml 的 player-wasm 步骤），vite 直接打包，无需 npm 包。

import {
  parseLyric as parseLyricTs,
  lyricIndexAt as lyricIndexAtTs,
  type Lyric,
} from "./lyrics";

/** Kotlin/Wasm 门面的 TS 侧形态（与 PlayerFacade.kt 一一对应，手写保证类型严格）。 */
interface WasmPlayerModule {
  loadLyric(lrc: string | null, yrc: string | null): string;
  lyricIndexAt(positionMs: number): number;
  lyricProgressOf(lineIndex: number, positionMs: number): number;
  qualityLabel(value: number): string;
  defaultQualityValue(): number;
  isLossless(value: number): boolean;
}

/** 缓存的 wasm 模块：null = 未加载过；undefined = 加载失败（本会话不再重试）。 */
let wasmModule: WasmPlayerModule | null | undefined;

/** Kotlin 侧 current 缓存的 JSON 镜像；null 表示「本会话尚未加载过歌词」。 */
let cachedLyricJson: string | null = null;
/** TS 兜底路径的缓存（wasm 不可用时 parseLyricAdaptive 的产物）。 */
let fallbackLyric: Lyric | null = null;

/**
 * 懒加载 wasm 模块；失败返回 null 并记住失败（undefined 哨兵），同一会话不再重试。
 */
async function wasm(): Promise<WasmPlayerModule | null> {
  if (wasmModule) return wasmModule;
  if (wasmModule === undefined) return null;
  try {
    wasmModule = (await import("../wasm/taotao-shared-wasm")) as unknown as WasmPlayerModule;
    return wasmModule;
  } catch {
    wasmModule = undefined;
    return null;
  }
}

/**
 * 解析歌词（自适应）：wasm 可用走 Kotlin 实现并同步两侧缓存；否则退回 TS 实现。
 * 返回值语义与 lyrics.ts 的 parseLyric 完全一致，调用方不感知走了哪条路径。
 */
export async function parseLyricAdaptive(lrc: string | null, yrc: string | null): Promise<Lyric> {
  const mod = await wasm();
  if (mod) {
    cachedLyricJson = mod.loadLyric(lrc, yrc);
    const lyric = JSON.parse(cachedLyricJson) as Lyric;
    fallbackLyric = lyric;
    return lyric;
  }
  fallbackLyric = parseLyricTs(lrc ?? "", yrc ?? "");
  return fallbackLyric;
}

/**
 * 当前进度落在第几行（二分）。必须在最近的 parseLyricAdaptive 之后调用；
 * 从未加载歌词时返回 -1（空态语义与两侧实现一致）。
 */
export function lyricIndexAtAdaptive(positionMs: number): number {
  const mod = wasmModule;
  if (mod && cachedLyricJson !== null) return mod.lyricIndexAt(positionMs);
  return fallbackLyric ? lyricIndexAtTs(fallbackLyric.lines, positionMs) : -1;
}

/** 音质档位中文名：wasm 不可用时退回内置表（与 Kotlin labelOfQuality 同口径）。 */
export async function qualityLabelAdaptive(value: number): Promise<string> {
  const mod = await wasm();
  return mod ? mod.qualityLabel(value) : qualityLabelTs(value);
}

/** 默认音质取值（HQ）：wasm 不可用时退回 TS 兜底常量。 */
export async function defaultQualityValueAdaptive(): Promise<number> {
  const mod = await wasm();
  return mod ? mod.defaultQualityValue() : 8;
}

/** 音质取值是否无损：wasm 不可用时退回 TS 兜底表（枚举档 10/11/14）。 */
export async function isLosslessAdaptive(value: number): Promise<boolean> {
  const mod = await wasm();
  return mod ? mod.isLossless(value) : value === 10 || value === 11 || value === 14;
}

/** TS 兜底的音质名表（与 Kotlin labelOfQuality 同口径；仅 wasm 不可用时使用）。 */
function qualityLabelTs(value: number): string {
  if (value === 0) return "试听";
  if (value === 1 || value === 2) return "有损";
  if (value >= 3 && value <= 7) return "标准";
  if (value === 8 || value === 9) return "HQ 高音质";
  if (value === 10) return "SQ 无损";
  if (value === 11) return "Hi-Res";
  if (value === 12) return "杜比全景声";
  if (value === 13) return "臻品全景声";
  if (value === 14) return "臻品母带";
  if (value === 15) return "AI 伴奏消音";
  if (value === 16) return "AI 人声消音";
  if (value === 17) return "AI 钢琴";
  if (value === 18) return "NAC";
  return `音质 ${value}`;
}
