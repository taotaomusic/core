type LogoProps = {
  /** 渲染尺寸，走 SVG 的 width/height 属性（CSP 禁行内样式，不能用 style）。 */
  size?: number;
};

/** 桃桃音乐 Logo：纯 SVG 桃子，不依赖任何图片资源。 */
export function Logo({ size = 28 }: LogoProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" role="img" aria-label="桃桃音乐">
      <circle cx="32" cy="38" r="22" fill="#ff8a65" />
      <circle cx="25" cy="31" r="9" fill="#ffb199" opacity="0.65" />
      <path d="M33 17c1.5-7.5 7.5-11.5 14.5-10.5C47 13.5 41 18 33.5 18" fill="#79c98b" />
      <path d="M31 18c-4.5-4-11-4.5-15.5-1.5 3.5 5.5 10.5 7 15.5 4.5" fill="#57b16b" />
    </svg>
  );
}
