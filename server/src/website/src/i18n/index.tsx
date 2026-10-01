import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import enUS from "antd/locale/en_US";
import zhCN from "antd/locale/zh_CN";
import { en } from "./en";
import { zh, type Dict } from "./zh";

export type Lang = "zh" | "en";

const LANG_KEY = "taotao-website-lang";
const DICTS: Record<Lang, Dict> = { zh, en };

const I18nContext = createContext<{
  lang: Lang;
  dict: Dict;
  toggleLang: () => void;
}>({
  lang: "zh",
  dict: zh,
  toggleLang: () => {},
});

function initialLang(): Lang {
  try {
    const stored = localStorage.getItem(LANG_KEY);
    if (stored === "zh" || stored === "en") return stored;
  } catch {
    // 隐私模式下 localStorage 可能不可用，落到浏览器语言。
  }
  return navigator.language.toLowerCase().startsWith("zh") ? "zh" : "en";
}

/**
 * 站点双语（中/英）：无路由、无第三方 i18n 依赖，字典直查。
 * 语言选择写入 localStorage；切换时同步 <html lang> 与页面标题，
 * antd 组件库的内置文案由 App 按 lang 传对应 locale。
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLang] = useState<Lang>(initialLang);

  useEffect(() => {
    try {
      localStorage.setItem(LANG_KEY, lang);
    } catch {
      // 同上：写不进去就只影响下次记忆，不挡切换。
    }
    document.documentElement.lang = lang === "zh" ? "zh-CN" : "en";
    document.title = DICTS[lang].docTitle;
  }, [lang]);

  const toggleLang = () => setLang((prev) => (prev === "zh" ? "en" : "zh"));

  return (
    <I18nContext.Provider value={{ lang, dict: DICTS[lang], toggleLang }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useI18n() {
  return useContext(I18nContext);
}

/** antd 组件库内置文案（空状态、默认按钮文字等）跟随站点语言。 */
export function antdLocale(lang: Lang) {
  return lang === "zh" ? zhCN : enUS;
}
