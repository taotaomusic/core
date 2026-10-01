import { useCallback, useEffect, useState } from "react";

const THEME_KEY = "taotao-website-theme";

/**
 * 应用主题并做平滑过渡：切换瞬间给全站元素挂颜色过渡（见 global.css 的
 * html[data-switching] 规则），约半秒后摘掉，避免常驻 transition 影响交互。
 */
function applyTheme(light: boolean, persist: boolean) {
  const root = document.documentElement;
  root.dataset.theme = light ? "light" : "dark";
  if (persist) {
    try {
      localStorage.setItem(THEME_KEY, light ? "light" : "dark");
    } catch {
      // 隐私模式下写不进 localStorage，只影响记忆，不挡本次切换。
    }
  }
  root.dataset.switching = "1";
  window.setTimeout(() => {
    delete root.dataset.switching;
  }, 480);
}

/** 站点明暗主题：手动切换优先，未选择时跟随系统。 */
export function useTheme() {
  const [light, setLight] = useState(
    () => document.documentElement.dataset.theme === "light",
  );

  const toggle = useCallback(() => {
    applyTheme(document.documentElement.dataset.theme !== "light", true);
    setLight(document.documentElement.dataset.theme === "light");
  }, []);

  useEffect(() => {
    // URL 强制主题（?theme=）用于调试与测试，不挂系统监听。
    if (new URLSearchParams(window.location.search).get("theme")) return;
    const query = window.matchMedia("(prefers-color-scheme: light)");
    const onChange = (event: MediaQueryListEvent) => {
      let stored: string | null = null;
      try {
        stored = localStorage.getItem(THEME_KEY);
      } catch {
        stored = null;
      }
      if (stored) return; // 手动选择过，不跟随系统
      applyTheme(event.matches, false);
      setLight(event.matches);
    };
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  return { light, toggle };
}
