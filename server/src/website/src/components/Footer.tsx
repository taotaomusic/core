import { Flex, Typography } from "antd";
import { useI18n } from "../i18n";
import { Logo } from "./Logo";

const { Text } = Typography;

/** 页脚：品牌语 + 三条链接（GitHub / 管理入口 / 服务状态），均为站内可达的真实地址。 */
export function Footer() {
  const { dict } = useI18n();

  return (
    <footer className="site-footer">
      <div className="container">
        <Flex justify="space-between" align="center" gap={16} wrap="wrap" className="footer-inner">
          <span className="footer-brand">
            <Logo size={22} />
            {dict.footer.slogan}
          </span>
          <Flex gap={20} wrap="wrap" className="footer-links">
            <a href="https://github.com/taotaomusic/core" target="_blank" rel="noreferrer">
              GitHub
            </a>
            <a href="/admin">{dict.footer.admin}</a>
            <a href="/health">{dict.footer.status}</a>
          </Flex>
          <Text type="secondary" className="footer-copy">
            {dict.footer.copy}
          </Text>
        </Flex>
      </div>
    </footer>
  );
}
