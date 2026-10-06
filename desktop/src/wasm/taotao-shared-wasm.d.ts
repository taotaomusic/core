// Kotlin/Wasm 共享层门面的类型声明（模块由根 CI 的 client-web job 生成到本目录）。
// 手写声明而非编译器生成：Kotlin/Wasm 的导出面窄（@JsExport 仅支持扁平类型），
// 手写能给出更严格的字面类型，并把 Kotlin 的有状态门面包成 TS 习惯的无状态形态。

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

/** Kotlin 门面的单例导出（@JsExport object 编译产物形态）。 */
declare const PlayerFacade: TaotaoSharedWasm;
export default PlayerFacade;
