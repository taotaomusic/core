import type { ReactNode } from "react";

export type IconName =
  | "cloud"
  | "mic"
  | "equalizer"
  | "search"
  | "link"
  | "shield"
  | "box"
  | "badge-check"
  | "dashboard"
  | "code";

/** 统一的线性图标路径：24 viewBox、1.8 描边、圆角端点，颜色交给外层 currentColor。 */
const ICON_CONTENT: Record<IconName, ReactNode> = {
  cloud: (
    <path d="M17.5 18.5H7a4 4 0 1 1 .6-7.96 6 6 0 0 1 11.63 1.66 3.5 3.5 0 0 1-1.73 6.3Z" />
  ),
  mic: (
    <>
      <path d="M12 3a3 3 0 0 0-3 3v5a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3Z" />
      <path d="M18.5 10.5v.5a6.5 6.5 0 0 1-13 0v-.5" />
      <path d="M12 17.5V21" />
    </>
  ),
  equalizer: (
    <path d="M4 10v4M8 6v12M12 3v18M16 6v12M20 10v4" />
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M20 20l-4.4-4.4" />
    </>
  ),
  link: (
    <>
      <path d="M9 17H7A5 5 0 0 1 7 7h2" />
      <path d="M15 7h2a5 5 0 0 1 0 10h-2" />
      <path d="M8.5 12h7" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3l7.5 3.5v5.1c0 4.6-3.2 7.8-7.5 9.4-4.3-1.6-7.5-4.8-7.5-9.4V6.5Z" />
      <path d="M9.2 11.8l2 2 3.8-3.8" />
    </>
  ),
  box: (
    <>
      <path d="M21 8.2 12 3 3 8.2v7.6L12 21l9-5.2Z" />
      <path d="M3.3 8.3 12 13.2l8.7-4.9" />
      <path d="M12 13.2V21" />
    </>
  ),
  "badge-check": (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M8.5 12.2l2.4 2.4 4.6-4.8" />
    </>
  ),
  dashboard: (
    <>
      <rect x="4" y="4" width="6" height="7" rx="1.5" />
      <rect x="14" y="4" width="6" height="4" rx="1.5" />
      <rect x="14" y="12" width="6" height="8" rx="1.5" />
      <rect x="4" y="15" width="6" height="5" rx="1.5" />
    </>
  ),
  code: (
    <>
      <path d="M8.5 7 4 12l4.5 5" />
      <path d="M15.5 7 20 12l-4.5 5" />
      <path d="M13.5 5.5 10.5 18.5" />
    </>
  ),
};

type IconProps = {
  name: IconName;
  /** 渲染尺寸，走 SVG 的 width/height 属性（CSP 禁行内样式）。 */
  size?: number;
};

/** 线性图标：替代 emoji 的统一图形语言，颜色随外层 CSS 的 currentColor。 */
export function Icon({ name, size = 24 }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {ICON_CONTENT[name]}
    </svg>
  );
}
