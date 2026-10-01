import { ReloadOutlined } from "@ant-design/icons";
import { Button, Card, Col, Flex, Row, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { useI18n } from "../i18n";
import { Reveal } from "./Reveal";

const { Title, Paragraph, Text } = Typography;

type CheckStatus = "checking" | "ok" | "fail";

type HealthResult = {
  /** HTTP 服务本身是否应答（拿到任何 HTTP 响应都算活着）。 */
  http: { status: CheckStatus; latencyMs: number };
  /** 数据库：/health 成功（code 0）即探活通过。 */
  db: { status: CheckStatus; detail?: string };
};

/**
 * 探活 /health：信封成功为 { code: 0 }；数据库挂时后端返回 502/5020，
 * 此时 HTTP 服务本身是通的 —— 两张卡片要分开呈现这两种情况。
 */
async function probeHealth(): Promise<HealthResult> {
  const startedAt = performance.now();
  try {
    const res = await fetch("/health", { cache: "no-store" });
    const latencyMs = Math.round(performance.now() - startedAt);
    const body = (await res.json().catch(() => null)) as { code?: number; message?: string } | null;
    const dbOk = res.ok && body?.code === 0;
    return {
      http: { status: "ok", latencyMs },
      db: dbOk ? { status: "ok" } : { status: "fail", detail: body?.message ?? `HTTP ${res.status}` },
    };
  } catch (error) {
    return {
      http: { status: "fail", latencyMs: Math.round(performance.now() - startedAt) },
      db: { status: "fail", detail: error instanceof Error ? error.message : String(error) },
    };
  }
}

/** 服务状态页：分项展示 HTTP 服务与数据库连接，30 秒自动刷新 + 手动刷新。 */
export function StatusPage() {
  const { dict } = useI18n();
  const [result, setResult] = useState<HealthResult | null>(null);
  const [checkedAt, setCheckedAt] = useState<Date | null>(null);
  const [checking, setChecking] = useState(true);

  const runCheck = useCallback(async () => {
    setChecking(true);
    setResult(await probeHealth());
    setCheckedAt(new Date());
    setChecking(false);
  }, []);

  useEffect(() => {
    document.title = `${dict.status.title} · ${dict.brand}`;
    void runCheck();
    const timer = window.setInterval(() => void runCheck(), 30_000);
    return () => window.clearInterval(timer);
  }, [dict.brand, dict.status.title, runCheck]);

  const statusLabel = (status: CheckStatus) =>
    status === "ok" ? dict.status.ok : status === "fail" ? dict.status.fail : dict.status.checking;

  const overall: CheckStatus = !result
    ? "checking"
    : result.http.status === "ok" && result.db.status === "ok"
      ? "ok"
      : "fail";

  return (
    <section className="section status-page">
      <div className="container">
        <Reveal className="section-head">
          <Tag className="kicker">{dict.status.kicker}</Tag>
          <Title level={2} className="section-title">
            {dict.status.title}
          </Title>
          <Paragraph className="section-desc">{dict.status.desc}</Paragraph>
          <Flex align="center" gap={12} wrap="wrap" className="status-toolbar">
            <Button
              shape="round"
              icon={<ReloadOutlined spin={checking} />}
              onClick={() => void runCheck()}
              loading={checking}
            >
              {dict.status.refresh}
            </Button>
            <Text type="secondary">
              {dict.status.lastCheck}：
              {checkedAt ? checkedAt.toLocaleTimeString() : "—"}
            </Text>
          </Flex>
        </Reveal>

        <Reveal>
          <Flex align="center" gap={12} className={`status-banner ${overall}`}>
            <span className={`status-dot ${overall}`} />
            <span className="status-overall-text">
              {overall === "ok"
                ? dict.status.overallOk
                : overall === "fail"
                  ? dict.status.overallBad
                  : dict.status.checking}
            </span>
          </Flex>
        </Reveal>

        <Row gutter={[20, 20]}>
          <Col xs={24} md={12}>
            <Reveal className="stretch">
              <Card className="status-card">
                <Flex align="center" gap={10} className="status-card-head">
                  <span className={`status-dot ${result?.http.status ?? "checking"}`} />
                  <h3 className="card-title">{dict.status.http.name}</h3>
                  <Tag className={`status-tag ${result?.http.status ?? "checking"}`}>
                    {statusLabel(result?.http.status ?? "checking")}
                  </Tag>
                </Flex>
                <Paragraph className="card-desc">
                  {result?.http.status === "fail" ? dict.status.http.failDesc : dict.status.http.okDesc}
                </Paragraph>
                {result ? (
                  <Text type="secondary" className="status-meta">
                    {dict.status.latency}：{result.http.latencyMs} ms
                  </Text>
                ) : null}
              </Card>
            </Reveal>
          </Col>
          <Col xs={24} md={12}>
            <Reveal className="stretch">
              <Card className="status-card">
                <Flex align="center" gap={10} className="status-card-head">
                  <span className={`status-dot ${result?.db.status ?? "checking"}`} />
                  <h3 className="card-title">{dict.status.db.name}</h3>
                  <Tag className={`status-tag ${result?.db.status ?? "checking"}`}>
                    {statusLabel(result?.db.status ?? "checking")}
                  </Tag>
                </Flex>
                <Paragraph className="card-desc">
                  {result?.db.status === "fail"
                    ? `${dict.status.db.failDesc}${result.db.detail ?? ""}`
                    : dict.status.db.okDesc}
                </Paragraph>
                <Text type="secondary" className="status-meta">
                  /health
                </Text>
              </Card>
            </Reveal>
          </Col>
        </Row>

        <Reveal>
          <Flex className="status-footer-row">
            <Button type="text" shape="round" href="/">
              ← {dict.status.backHome}
            </Button>
          </Flex>
        </Reveal>
      </div>
    </section>
  );
}
