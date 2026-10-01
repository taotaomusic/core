import { ArrowDownOutlined, AndroidOutlined, WindowsOutlined } from "@ant-design/icons";
import { Button, Col, Row, Space, Tag, Typography } from "antd";
import { useI18n } from "../i18n";
import { PlayerMock } from "./PlayerMock";

const { Title, Paragraph } = Typography;

/**
 * 首屏：左侧文案 + 下载入口（antd 按钮/栅格），右侧纯 CSS 播放器卡片（装饰）。
 * 下载链接指向 core 仓库的滚动 Release tag —— 产物文件名带版本号，
 * 静态页面没法预知，只能落到 Release 页让用户点最新资产。
 */
const RELEASE_ANDROID = "https://github.com/taotaomusic/core/releases/tag/latest";
const RELEASE_DESKTOP = "https://github.com/taotaomusic/core/releases/tag/desktop-latest";
const REPO = "https://github.com/taotaomusic/core";

export function Hero() {
  const { dict } = useI18n();

  return (
    <section className="hero">
      <div className="container">
        <Row align="middle" gutter={[56, 48]}>
          <Col xs={24} lg={13} className="hero-copy">
            <Tag className="kicker">{dict.hero.badge}</Tag>
            <Title className="hero-title">
              {dict.hero.titleBefore}
              <span className="accent">{dict.hero.titleAccent}</span>
            </Title>
            <Paragraph className="hero-sub">{dict.hero.sub}</Paragraph>
            <Space wrap size={12} className="hero-actions">
              <Button
                type="primary"
                shape="round"
                size="large"
                className="btn-cta"
                icon={<AndroidOutlined />}
                href={RELEASE_ANDROID}
              >
                {dict.hero.android}
              </Button>
              <Button
                shape="round"
                size="large"
                className="btn-ghost-strong"
                icon={<WindowsOutlined />}
                href={RELEASE_DESKTOP}
              >
                {dict.hero.desktop}
              </Button>
              <Button type="text" shape="round" size="large" href="#features">
                {dict.hero.more}
                <ArrowDownOutlined />
              </Button>
            </Space>
            <Paragraph className="hero-meta">
              {dict.hero.metaPrefix}
              <a href={REPO} target="_blank" rel="noreferrer">
                {dict.hero.repo}
              </a>
              {dict.hero.metaSuffix}
            </Paragraph>
          </Col>
          <Col xs={24} lg={11}>
            <PlayerMock />
          </Col>
        </Row>
      </div>
    </section>
  );
}
