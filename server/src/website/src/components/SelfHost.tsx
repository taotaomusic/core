import { Card, Col, Row, Tag, Typography } from "antd";
import { useI18n } from "../i18n";
import { Icon } from "./Icon";
import { Reveal } from "./Reveal";

const { Title, Paragraph, Link } = Typography;

/**
 * 自部署区块：左侧文案 + 命令示例，右侧 2×2 能力卡。
 * 镜像名跟随仓库（ghcr.io/taotaomusic/core），与根 CI 的 Docker 推送保持一致。
 */
const DOCKER_COMMAND = "docker run -d --name taotao-music -p 4720:4720 ghcr.io/taotaomusic/core:latest";

export function SelfHost() {
  const { dict } = useI18n();

  return (
    <section id="selfhost" className="section">
      <div className="container">
        <Row gutter={[64, 40]} align="top">
          <Col xs={24} lg={12}>
            <Reveal>
              <Tag className="kicker">{dict.selfhost.kicker}</Tag>
              <Title level={2} className="section-title">
                {dict.selfhost.title}
              </Title>
              <Paragraph className="section-desc">{dict.selfhost.desc}</Paragraph>
              <pre className="selfhost-command">
                <code>{DOCKER_COMMAND}</code>
              </pre>
              <Paragraph className="selfhost-hint">{dict.selfhost.hint}</Paragraph>
            </Reveal>
          </Col>
          <Col xs={24} lg={12}>
            <Row gutter={[16, 16]}>
              {dict.selfhost.cards.map((card) => (
                <Col xs={24} sm={12} key={card.title}>
                  <Reveal className="stretch">
                    <Card className="selfhost-card" hoverable>
                      <h3 className="card-title card-title-icon">
                        <Icon name={card.icon} size={18} />
                        {card.title}
                      </h3>
                      <Paragraph className="card-desc">{card.desc}</Paragraph>
                      {card.link ? (
                        <Link className="card-link" href="/admin">
                          {card.link}
                        </Link>
                      ) : null}
                    </Card>
                  </Reveal>
                </Col>
              ))}
            </Row>
          </Col>
        </Row>
      </div>
    </section>
  );
}
