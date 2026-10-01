import { GlobalOutlined, GithubOutlined, MoonOutlined, SunOutlined } from "@ant-design/icons";
import { Button, Flex, Tooltip } from "antd";
import { useI18n } from "../i18n";
import { Logo } from "./Logo";

const REPO = "https://github.com/taotaomusic/core";

type NavProps = {
  /** 当前是否浅色（由 App 持有的唯一主题状态传入）。 */
  themeLight: boolean;
  onToggleTheme: () => void;
};

/**
 * 顶部导航：通栏吸顶毛玻璃 —— 品牌贴屏幕左缘、操作组贴右缘。
 * 右侧依次为：明暗切换（手动选择记忆到 localStorage）、语言切换（中/英）、GitHub。
 */
export function Nav({ themeLight, onToggleTheme }: NavProps) {
  const { dict, toggleLang } = useI18n();

  const links = [
    { href: "#features", label: dict.nav.features },
    { href: "#download", label: dict.nav.download },
    { href: "#selfhost", label: dict.nav.selfhost },
  ];

  return (
    <header className="site-nav">
      <Flex className="site-nav-inner" align="center" gap={8}>
        <a className="brand" href="/">
          <Logo size={30} />
          <span>{dict.brand}</span>
        </a>
        <nav className="nav-links" aria-label="site sections">
          {links.map((link) => (
            <a key={link.href} href={link.href}>
              {link.label}
            </a>
          ))}
        </nav>
        <Flex align="center" gap={4} className="nav-actions">
          <Tooltip title={dict.nav.themeTitle}>
            <Button
              type="text"
              shape="circle"
              aria-label={dict.nav.themeTitle}
              icon={themeLight ? <MoonOutlined /> : <SunOutlined />}
              onClick={onToggleTheme}
            />
          </Tooltip>
          <Tooltip title={dict.nav.langTitle}>
            <Button
              type="text"
              shape="circle"
              aria-label={dict.nav.langTitle}
              icon={<GlobalOutlined />}
              onClick={toggleLang}
            />
          </Tooltip>
          <Button
            shape="round"
            className="nav-github-btn"
            icon={<GithubOutlined />}
            href={REPO}
            target="_blank"
            rel="noreferrer"
          >
            {dict.nav.github}
          </Button>
        </Flex>
      </Flex>
    </header>
  );
}
