// Kotlin/Wasm 播放逻辑门面（:shared 的 wasmJsMain）的 TS 类型声明。
// 模块本体（.mjs/.wasm）由根 CI 的 client-web job 从
// shared/build/dist/wasmJs/productionExecutable/ 拷贝到本目录；
// 这里手写声明：比编译器生成更严格，并把 Kotlin 顶层函数映射为 TS 命名导出。

export interface TaotaoSharedWasm {
  /** 解析歌词并缓存到 wasm 侧，返回完整结构的 JSON 字符串。 */
  loadLyric(lrc: string | null, yrc: string | null): string;
  /** 行级二分查找：当前进度落在第几行；早于第一行返回 -1。 */
  lyricIndexAt(positionMs: number): number;
  /** 指定行已唱到的比例 0..1；行下标越界返回 0。 */
  lyricProgressOf(lineIndex: number, positionMs: number): number;
  /** 音质档位中文名（上游 0–18 全档），未知档位返回「音质 N」。 */
  qualityLabel(value: number): string;
  /** 默认音质取值（HQ = 8）。 */
  defaultQualityValue(): number;
  /** 取值是否为无损档；未知取值返回 false。 */
  isLossless(value: number): boolean;
}

/** Kotlin 顶层 @JsExport 函数编译为模块的命名导出（非默认导出）。 */
export declare function loadLyric(lrc: string | null, yrc: string | null): string;
export declare function lyricIndexAt(positionMs: number): number;
export declare function lyricProgressOf(lineIndex: number, positionMs: number): number;
export declare function qualityLabel(value: number): string;
export declare function defaultQualityValue(): number;
export declare function isLossless(value: number): boolean;
