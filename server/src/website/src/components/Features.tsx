import { Card, Col, Row, Tag, Typography } from "antd";
import { useI18n } from "../i18n";
import { Icon } from "./Icon";
import { Reveal } from "./Reveal";

const { Title, Paragraph } = Typography;

/** 功能特性：栅格响应式（手机一列 / 平板两列 / 桌面三列），图标统一用线性 SVG。 */
export function Features() {
  const { dict } = useI18n();

  return (
    <section id="features" className="section">
      <div className="container">
        <Reveal className="section-head">
          <Tag className="kicker">{dict.features.kicker}</Tag>
          <Title level={2} className="section-title">
            {dict.features.title}
          </Title>
          <Paragraph className="section-desc">{dict.features.desc}</Paragraph>
        </Reveal>
        <Row gutter={[20, 20]}>
          {dict.features.items.map((feature) => (
            <Col xs={24} sm={12} lg={8} key={feature.title}>
              <Reveal className="stretch">
                <Card className="feature-card" hoverable>
                  <span className="feature-icon">
                    <Icon name={feature.icon} size={24} />
                  </span>
                  <h3 className="card-title">{feature.title}</h3>
                  <Paragraph className="card-desc">{feature.desc}</Paragraph>
                </Card>
              </Reveal>
            </Col>
          ))}
        </Row>
      </div>
    </section>
  );
}
