/**
 * 主题预置脚本：在应用加载前同步执行，避免暗色模式下出现白屏闪烁。
 *
 * 单独成文件而不是写在 index.html 的 <script> 里，是为了让管理后台能下发
 * `script-src 'self'` —— 允许内联脚本等于把 XSS 的最后一道防线去掉，而这里
 * 恰恰要读 localStorage 决定 DOM 状态。
 *
 * 必须是**阻塞式**经典脚本（不加 type="module"、不加 defer/async），否则会
 * 晚于首屏渲染执行，闪烁就回来了。
 */
(function () {
  try {
    var theme = localStorage.getItem("taotao_admin_theme");
    var prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    // 兼容 VueUse 默认写入 'auto' 的情况，或者用户之前存下的值。
    if (theme === "dark" || theme === "auto" || (!theme && prefersDark)) {
      document.documentElement.classList.add("dark");
    } else {
      document.documentElement.classList.remove("dark");
    }
  } catch (error) {
    // localStorage 在隐私模式或跨域 iframe 下可能抛异常。这里绝不能把
    // 整个后台挡在白屏上 —— 主题没生效是可以接受的降级。
    document.documentElement.classList.remove("dark");
  }
})();
