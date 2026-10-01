// 主题预置脚本：在首帧绘制前确定明暗，避免浅色用户先看到暗色再闪烁。
// 优先级：URL ?theme=light|dark（调试用）> localStorage 手动选择 > 系统偏好。
// 结果写在 <html data-theme="..."> 上，同时驱动 global.css 的变量主题
// 与 React 侧 antd 的 default/dark 算法（见 useTheme.ts）。
// 必须保持外置文件：官网 CSP 的 script-src 'self' 不允许内联脚本。
(function () {
  var forced = null;
  var stored = null;
  try {
    forced = new URLSearchParams(location.search).get("theme");
    stored = localStorage.getItem("taotao-website-theme");
  } catch (e) {
    // 隐私模式下 localStorage 不可用，按系统偏好走。
  }
  var light;
  if (forced) {
    light = forced === "light";
  } else if (stored) {
    light = stored === "light";
  } else {
    light = window.matchMedia("(prefers-color-scheme: light)").matches;
  }
  document.documentElement.dataset.theme = light ? "light" : "dark";
})();
