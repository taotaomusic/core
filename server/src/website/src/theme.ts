/**
 * 站点主题常量：给 antd ConfigProvider 的 token 用。
 * 注意 fontFamily、色值必须与 global.css 里的 CSS 变量保持一致 ——
 * 组件库管组件，自定义样式管品牌视觉，两边共用同一套调色板。
 */

export const FONT_STACK =
  '"PingFang SC", "HarmonyOS Sans SC", "Source Han Sans SC", "Microsoft YaHei UI", "Microsoft YaHei", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

/** antd token 按明暗两套取值；色值与 global.css 的 :root 变量一一对应。 */
export function antdTokens(prefersLight: boolean) {
  return prefersLight
    ? {
        colorBgBase: "#faf5ef",
        colorTextBase: "#2c2018",
        colorPrimary: "#e05a32",
        borderRadius: 14,
        fontFamily: FONT_STACK,
      }
    : {
        colorBgBase: "#171220",
        colorTextBase: "#f4eee8",
        colorPrimary: "#ff8a65",
        borderRadius: 14,
        fontFamily: FONT_STACK,
      };
}
