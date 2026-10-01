import { Reveal } from "./Reveal";

/**
 * 自部署区块：命令行示例 + 四张能力卡。
 * 镜像名跟随仓库（ghcr.io/taotaomusic/core），与根 CI 的 Docker 推送保持一致。
 */
const DOCKER_COMMAND = "docker run -d --name taotao-music -p 4720:4720 ghcr.io/taotaomusic/core:latest";

export function SelfHost() {
  return (
    <section id="selfhost" className="section">
      <div className="container selfhost-grid">
        <Reveal className="selfhost-copy">
          <span className="section-kicker">自部署</span>
          <h2>为自部署而生</h2>
          <p>
            服务端是标准的 NestJS + PostgreSQL 应用，一条命令即可跑起来；
            每次发布前都会在独立验证库上跑完 300+ 项接口契约检查，全绿才出包。
          </p>
          <pre className="selfhost-command">
            <code>{DOCKER_COMMAND}</code>
          </pre>
          <p className="selfhost-hint">
            镜像跟随仓库发布，latest / 语义化版本 / 提交短哈希三个 tag 任选。
          </p>
        </Reveal>
        <div className="selfhost-cards">
          <Reveal>
            <article className="selfhost-card">
              <h3>📦 Docker 部署</h3>
              <p>一条命令拉起运行时镜像，直接复用已通过契约验证的构建产物。</p>
            </article>
          </Reveal>
          <Reveal>
            <article className="selfhost-card">
              <h3>✅ 契约验证</h3>
              <p>接口行为由 300+ 项自动化契约锁定，升级不会悄悄破坏客户端。</p>
            </article>
          </Reveal>
          <Reveal>
            <article className="selfhost-card">
              <h3>🛡️ 管理后台</h3>
              <p>角色分级、两步验证、强制改密与操作审计一应俱全。</p>
              <a href="/admin">打开管理后台 →</a>
            </article>
          </Reveal>
          <Reveal>
            <article className="selfhost-card">
              <h3>🧩 开放 API</h3>
              <p>分享、歌单等能力以 REST 形式开放，方便接入自己的自动化流程。</p>
            </article>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
