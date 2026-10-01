import {
  AndroidOutlined,
  ChromeOutlined,
  GlobalOutlined,
  WindowsOutlined,
} from "@ant-design/icons";
import { Button, Card, Col, Row, Tag, Typography } from "antd";
import { useI18n } from "../i18n";
import { Reveal } from "./Reveal";

const { Title, Paragraph, Text } = Typography;

/** core 仓库的两个滚动 Release tag：APK 与桌面包的资产名带版本号，只能落到 Release 页。 */
const RELEASE_ANDROID = "https://github.com/taotaomusic/core/releases/tag/latest";
const RELEASE_DESKTOP = "https://github.com/taotaomusic/core/releases/tag/desktop-latest";

/** 多端下载：栅格三列（手机一列），图标全部来自 @ant-design/icons。 */
export function Download() {
  const { dict } = useI18n();

  return (
    <section id="download" className="section section-alt">
      <div className="container">
        <Reveal className="section-head">
          <Tag className="kicker">{dict.download.kicker}</Tag>
          <Title level={2} className="section-title">
            {dict.download.title}
          </Title>
          <Paragraph className="section-desc">{dict.download.desc}</Paragraph>
        </Reveal>
        <Row gutter={[20, 20]}>
          <Col xs={24} md={8}>
            <Reveal className="stretch">
              <Card className="download-card is-primary" hoverable>
                <span className="platform-icon">
                  <AndroidOutlined />
                </span>
                <h3 className="card-title">{dict.download.android.title}</h3>
                <Paragraph className="card-desc">{dict.download.android.desc}</Paragraph>
                <Text type="secondary" className="download-meta">
                  {dict.download.android.meta}
                </Text>
                <Button
                  type="primary"
                  shape="round"
                  className="btn-cta"
                  icon={<AndroidOutlined />}
                  href={RELEASE_ANDROID}
                  block
                >
                  {dict.download.android.action}
                </Button>
              </Card>
            </Reveal>
          </Col>
          <Col xs={24} md={8}>
            <Reveal className="stretch">
              <Card className="download-card" hoverable>
                <span className="platform-icon">
                  <WindowsOutlined />
                </span>
                <h3 className="card-title">{dict.download.windows.title}</h3>
                <Paragraph className="card-desc">{dict.download.windows.desc}</Paragraph>
                <Text type="secondary" className="download-meta">
                  {dict.download.windows.meta}
                </Text>
                <Button
                  shape="round"
                  className="btn-ghost-strong"
                  icon={<WindowsOutlined />}
                  href={RELEASE_DESKTOP}
                  block
                >
                  {dict.download.windows.action}
                </Button>
              </Card>
            </Reveal>
          </Col>
          <Col xs={24} md={8}>
            <Reveal className="stretch">
              <Card className="download-card" hoverable>
                <span className="platform-icon">
                  <ChromeOutlined />
                </span>
                <h3 className="card-title">{dict.download.web.title}</h3>
                <Paragraph className="card-desc">{dict.download.web.desc}</Paragraph>
                <Text type="secondary" className="download-meta">
                  <code>{dict.download.web.link}</code>
                </Text>
                <Text type="secondary" className="download-note">
                  <GlobalOutlined /> {dict.download.web.note}
                </Text>
              </Card>
            </Reveal>
          </Col>
        </Row>
      </div>
    </section>
  );
}
