import type { SyntheticEvent } from "react";

/** 无封面时隐藏 <img>，避免碎图标。 */
export function hideOnError(e: SyntheticEvent<HTMLImageElement>) {
  (e.target as HTMLImageElement).style.visibility = "hidden";
}
