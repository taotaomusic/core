/**
 * 播放器共享图标集（feather 风格线性图标）。
 *
 * 统一笔触与 MainScreen 里 NavIcon 的先例保持一致：
 * stroke=currentColor、strokeWidth=2、圆角端点/拐点（round cap/join）、fill=none，
 * 颜色自动跟随文字色（currentColor），尺寸由调用方通过 size 指定。
 *
 * 传输类图标（播放/暂停/上下首）用实心字形：在同一个 svg 基座内，
 * 对应 path/rect 单独声明 fill="currentColor" stroke="none"。
 */

import type { ReactNode } from "react";

/** 所有图标的统一入参：size 为渲染边长（px），默认 20。 */
type IconProps = { size?: number };

/** 线性图标的统一 svg 基座：几何坐标基于 24x24 视窗，缩放不失真。 */
function IconBase({ size = 20, children }: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {children}
    </svg>
  );
}

/** 播放：实心三角形（feather/Material play 风格，描边同色加圆角顶点）。 */
export function IconPlay({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <path
        d="M9 5.8v12.4L19.6 12z"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth={2.4}
      />
    </IconBase>
  );
}

/** 暂停：两条实心竖杠（圆角矩形）。 */
export function IconPause({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <rect x="6" y="5" width="4.2" height="14" rx="1.4" fill="currentColor" stroke="none" />
      <rect x="13.8" y="5" width="4.2" height="14" rx="1.4" fill="currentColor" stroke="none" />
    </IconBase>
  );
}

/** 上一首：左竖线 + 左指实心三角（Material skip_previous 风格）。 */
export function IconPrev({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <rect x="5.4" y="6" width="2.4" height="12" rx="1.2" fill="currentColor" stroke="none" />
      <path
        d="M18.6 7v10L11 12z"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth={2}
      />
    </IconBase>
  );
}

/** 下一首：右竖线 + 右指实心三角（上一首的镜像）。 */
export function IconNext({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <rect x="16.2" y="6" width="2.4" height="12" rx="1.2" fill="currentColor" stroke="none" />
      <path
        d="M5.4 7v10L13 12z"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth={2}
      />
    </IconBase>
  );
}

/** 列表循环：循环箭头环（feather repeat）。 */
export function IconRepeat({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <polyline points="17 1 21 5 17 9" />
      <path d="M3 11V9a4 4 0 0 1 4-4h14" />
      <polyline points="7 23 3 19 7 15" />
      <path d="M21 13v2a4 4 0 0 1-4 4H3" />
    </IconBase>
  );
}

/**
 * 单曲循环：循环箭头环 + 中间数字「1」。
 * 直接用文本元素叠在 IconRepeat 的留白带上（上下箭头之间），不另画路径。
 */
export function IconRepeatOne({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <polyline points="17 1 21 5 17 9" />
      <path d="M3 11V9a4 4 0 0 1 4-4h14" />
      <polyline points="7 23 3 19 7 15" />
      <path d="M21 13v2a4 4 0 0 1-4 4H3" />
      <text
        x="12"
        y="15.5"
        textAnchor="middle"
        fontSize="8.5"
        fontWeight="700"
        fill="currentColor"
        stroke="none"
      >
        1
      </text>
    </IconBase>
  );
}

/** 收藏心形（feather heart）；filled 为 true 时实心填充，表示已收藏。 */
export function IconHeart({ size = 20, filled = false }: IconProps & { filled?: boolean }) {
  return (
    <IconBase size={size}>
      <path
        d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21.2l7.8-7.8 1-1a5.5 5.5 0 0 0 0-7.8z"
        fill={filled ? "currentColor" : "none"}
      />
    </IconBase>
  );
}

/**
 * 播放队列：三横线列表 + 四分音符（音符头实心、符杆线性），
 * 区别于导航里纯列表风格的「歌单」图标。
 */
export function IconQueue({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <line x1="12.5" y1="5.5" x2="21" y2="5.5" />
      <line x1="12.5" y1="10.5" x2="21" y2="10.5" />
      <line x1="12.5" y1="15.5" x2="21" y2="15.5" />
      <circle cx="7.2" cy="16.8" r="2.6" fill="currentColor" stroke="none" />
      <line x1="9.8" y1="16.8" x2="9.8" y2="6.5" />
    </IconBase>
  );
}

/** 分享：三节点两连线的网络分享图标（feather share-2）。 */
export function IconShare({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <circle cx="18" cy="5" r="3" />
      <circle cx="6" cy="12" r="3" />
      <circle cx="18" cy="19" r="3" />
      <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
      <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
    </IconBase>
  );
}

/** 定时关闭：表盘 + 时针分针（feather clock）。 */
export function IconClock({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7 12 12 15.5 14" />
    </IconBase>
  );
}

/** 收起详情页：向下箭头（线性 chevron）。 */
export function IconChevronDown({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <polyline points="6 9 12 15 18 9" />
    </IconBase>
  );
}

/** 关闭：两条对角线组成的 ×。 */
export function IconClose({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <line x1="6" y1="6" x2="18" y2="18" />
      <line x1="18" y1="6" x2="6" y2="18" />
    </IconBase>
  );
}

/** 音量：喇叭 + 一段声波弧线；静音时由调用方改用斜线版本。 */
export function IconVolume({ size = 20, muted = false }: IconProps & { muted?: boolean }) {
  return (
    <IconBase size={size}>
      <polygon
        points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth={1.6}
      />
      {muted ? (
        <line x1="16" y1="9.5" x2="22" y2="14.5" />
      ) : (
        <path d="M15.5 8.6a5 5 0 0 1 0 6.8" />
      )}
    </IconBase>
  );
}

/** 音乐占位：双符头音符（feather music），替代 unicode「♪」。 */
export function IconMusicNote({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <path d="M9 18V5l12-2v13" />
      <circle cx="6" cy="18" r="3" />
      <circle cx="18" cy="16" r="3" />
    </IconBase>
  );
}

/** 新增/添加：加号。 */
export function IconPlus({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </IconBase>
  );
}

/** 音质：头戴耳机（feather headphones）。 */
export function IconQuality({ size = 20 }: IconProps) {
  return (
    <IconBase size={size}>
      <path d="M3 18v-6a9 9 0 0 1 18 0v6" />
      <path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3v5z" />
      <path d="M3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3v5z" />
    </IconBase>
  );
}
