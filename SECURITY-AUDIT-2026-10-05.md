# taotaomusic/core 安全审查报告

- **审查对象**：<https://github.com/taotaomusic/core>（公开仓库，默认分支 `main`）
- **审查日期**：2026-10-05
- **代码基线**：`25b7772`（`fix: @tauri-apps/plugin-http 对齐 Rust 侧 2.8.0`）
- **方法**：GitHub 平台侧配置核查（REST API）+ 本地全量代码/依赖扫描 + OSV 与 npm 官方源依赖审计
- **与历史报告的关系**：`SECURITY-AUDIT-FINDINGS.md`（2026-09-17，仅覆盖 `server/src`）的 S1–S11 已复核闭环；
  本报告是其续篇，**补上了当时挂起的 S12（依赖 CVE）**，并把范围扩到 CI、仓库卫生、客户端依赖树

---

## 一、结论摘要

**未发现新的代码级高危漏洞。** 历史审计的 11 项代码问题全部复核为已修复；本轮新增发现集中在
**依赖 CVE** 与 **工程化/供应链配置** 两类。

| 编号 | 严重度 | 问题 | 位置 |
| --- | --- | --- | --- |
| H1 | **高** | `nodemailer@9.1.1` 命中 5 条公告（含 SMTP 凭据跨租户泄露），**修复 PR 已合并又被回滚，当前仍带漏洞** | `server/package.json:41` |
| M1 | 中 | 30 个 Actions 全部用可变标签引用，0 个 SHA 固定 | `.github/workflows/ci.yml` |
| M2 | 中 | 未启用代码扫描（CodeQL） | 仓库设置 |
| M3 | 中 | 无 `.github/dependabot.yml`、无 `SECURITY.md` | `.github/`、根目录 |
| M4 | 中 | CI 只在 push `main` 触发，**PR 无任何自动化门禁** | `ci.yml:13-16` |
| M5 | 中 | `purge` job 由提交信息触发且持 `contents: write`，可一键删除全部 Release | `ci.yml:81-96` |
| M6 | 中 | `keystore.b64`（签名私钥的 base64）**未被 `.gitignore` 覆盖** | 仓库根目录 |
| L1 | 低 | 桌面端 `glib@0.18.5` 不安全（RUSTSEC-2024-0429），Dependabot 更新任务持续失败 | `desktop/src-tauri/Cargo.lock` |
| L2 | 低 | 桌面端 6 个 crate 已停止维护（`proc-macro-error` + 5× `unic-*`） | 同上 |
| L3 | 低 | `crypto-src/.github/workflows/` 三个工作流是死文件，易误导 | `crypto-src/.github/` |
| L4 | 低 | `data.exp` 缺类型校验，缺 `exp` 字段的令牌会通过过期检查 | `server/src/auth/auth.service.ts:85` |

---

## 二、高危

### H1【高】`nodemailer@9.1.1` 带 5 条已知漏洞，且修复被回滚

**证据**

```jsonc
// server/package.json:41
"nodemailer": "^9.1.1",     // package-lock 实际锁定 9.1.1；最新为 10.0.14
```

`npm audit`（官方 registry）报 **1 high / 0 critical**，命中区间 `<=10.0.8`，含 5 条公告：

| 公告 | 性质 |
| --- | --- |
| Process-global DNS cache reuses TLS `servername` across transports | **跨租户 SMTP 凭据泄露**（最严重） |
| Nested structured recipient arrays bypass parser depth limit | 栈耗尽 DoS |
| Quoted local-part produces malformed envelope recipient | 信封收件人被篡改 |
| Quadratic backtracking in addressparser free-text fallback | 远程 DoS |
| addressparser O(n²) on comment-joined addresses | 远程 DoS |

**关键点：修复一度上线又被撤下。** 时间线（GitHub API 实证）：

```
03:24:56  Dependabot 安全更新任务 npm_and_yarn in /server for nodemailer  → success
03:33:57  PR #1（nodemailer 9.1.1 → 10.0.9）                            → 合并
03:34:58  PR #3  Revert "build(deps): bump nodemailer from 9.1.1 to 10.0.9" → 合并
```

也就是说 **main 分支当前仍是带漏洞的 9.1.1**。当天 03:37–03:49 的 CI 失败全部落在 `desktop`
job（「安装前端依赖」/「构建 + 签名 + 发布」），与 nodemailer 无关——回滚大概率是为了清理
Dependabot 批量更新的连带影响，而非 nodemailer 本身不可用。

**影响面很小**：全仓 `nodemailer` 只有一个调用点。

```
server/src/mail/mail.service.ts:15   nodemailer.createTransport({...})
```

**建议**

1. 直接升到 `10.0.14`（而非 10.0.9），跑 `server/tools/verify-contract.mjs` 确认邮件链路；
2. 顺手把 `@types/nodemailer@8.0.1` 与 nodemailer 主版本对齐；
3. 确认回滚原因后，**不要让这个 PR 再被回滚**——目前是「已修复状态被人工退回」的少见情形。

---

## 三、中危

### M1【中】Actions 供应链：30 个引用全部是可变标签

```
SHA 固定（uses: owner/repo@<40 位 sha>）：0 个
可变标签（uses: owner/repo@v4 / @stable / @v0）：30 个
```

其中风险最高的两个是**分支/浮动引用**：

- `dtolnay/rust-toolchain@stable` —— 指向分支，上游任何一次推送都直接进入构建
- `tauri-apps/tauri-action@v0` —— `v0` 是可移动的大版本标签，且该 action 在持有
  `TAURI_SIGNING_PRIVATE_KEY` 的步骤里执行

**建议**：至少把这两个改成 SHA 固定；其余 `@v4` 类可按 `dependabot` 的
`github-actions` 生态自动跟进。

### M2【中】未启用代码扫描

仓库无 `.github/workflows/codeql.yml`，`security_and_analysis` 对外不可见（无 admin 权限），
活动工作流只有 `CI（统一按需构建）` 与 `Dependabot Updates` 两个。
公开仓库的 CodeQL 免费，对 TypeScript + Kotlin + Rust 均可覆盖。

### M3【中】缺少依赖更新配置与漏洞上报渠道

- **无 `.github/dependabot.yml`**（`git log --all -- .github/dependabot.yml` 确认从未存在）。
  当前只有 **安全更新** 在跑（由仓库设置开启，因此能看到 Dependabot PR），
  **版本更新没有配置**，无法设定分组、更新频率与忽略规则。
  这也是当天出现「批量 PR → 合并 → 立刻回滚」混乱的直接原因。
- **无 `SECURITY.md`**：公开仓库没有漏洞上报入口，研究者只能开 issue（公开暴露）。

### M4【中】PR 没有 CI 门禁

```yaml
# .github/workflows/ci.yml:13-16
on:
  push:
    branches: [main]
  workflow_dispatch:
```

没有 `pull_request` 触发器。**所有 PR 在合并前不跑任何构建或测试**——包括 Dependabot 的
依赖升级 PR。H1 的回滚就是这个缺口的直接后果：一个未经验证的依赖升级直接落到 `main`，
CI 红了才发现。项目自己维护的契约验证（约 300 项）在这种情况下完全无法在合并前拦截回归。

### M5【中】`purge` job：提交信息可触发全量 Release 删除

```yaml
# ci.yml:81-96
purge:
  needs: changes
  permissions:
    contents: write
  steps:
    - if: contains(github.event.head_commit.message, '[purge]')
      env:
        GH_TOKEN: ${{ github.token }}
      run: gh release list --limit 300 ... | while read t; do gh release delete "$t" --cleanup-tag -y; done
```

任何能推 `main` 的人（含被盗账号、被劫持的自动化），只要提交信息里带 `[purge]`，
就会**删除仓库全部 Release**，无二次确认、无白名单、无审计。
同一机制还有 `[full]`（强制全量构建，可被用来刷爆 CI 配额）。

**建议**：把破坏性操作改为 `workflow_dispatch` 手动触发并加 `environment` 保护规则；
或至少改成只删非 `*-latest` 的滚动 tag。

### M6【中】`keystore.b64` 未被忽略，一次 `git add .` 即泄露签名私钥

```
$ git check-ignore -v keystore.b64 taotao-release.jks local.properties
（taotao-release.jks、local.properties 命中 .gitignore；keystore.b64 无输出）

$ git status --short
?? keystore.b64          ← 未跟踪，但也未被忽略
```

`keystore.b64`（3660 B）正是 `taotao-release.jks`（2744 B）的 base64 编码，
**等价于 Android 发布签名私钥**。当前只是「碰巧没被 add」——仓库公开，
一旦误提交，任何人都能签发可覆盖安装的 APK。

好消息是历史干净：`git log --all --diff-filter=A --name-only` 全量扫描确认，
历史上从未提交过 `.jks/.keystore/.p12/.pem/.env/secret/credential` 类文件。

**建议**：立刻在根 `.gitignore` 加 `keystore.b64`（或 `*.b64`），
并考虑把该文件移出仓库目录。

---

## 四、低危

### L1【低】桌面端 `glib@0.18.5` 不安全，且 Dependabot 修不动

`desktop/src-tauri/Cargo.lock` 扫描 521 个包，命中 8 条：

| crate | 版本 | 公告 | 性质 |
| --- | --- | --- | --- |
| `glib` | 0.18.5 | RUSTSEC-2024-0429 / GHSA-wrw7-89jp-8q8g | **不安全**（`VariantStrIter` 的 Iterator 实现产生 UB），修复于 0.20.0 |
| `proc-macro-error` | 1.0.4 | RUSTSEC-2024-0370 | 已停止维护 |
| `unic-char-property` / `unic-char-range` / `unic-common` / `unic-ucd-ident` / `unic-ucd-version` | 0.9.0 | RUSTSEC-2025-0081/0075/0080/0100/0098 | 已停止维护 |

- `glib` 属 **Linux/GTK 路径**，Windows 交付物不打包它，实际风险限于在 Linux 上开发/构建。
- 但 **Dependabot 为 `glib` 发起的更新任务在持续失败**（当天 3 次，job 名
  `cargo in /desktop/src-tauri for glib - Update`，失败步骤 `Run Dependabot`），
  意味着这条依赖短期内不会自动收敛。需要人工处理（升级 `gtk-rs` 0.18 → 0.20 线，
  或至少用 `cargo update -p glib` 试推）。
- 另外 6 条均为 **unmaintained**（信息性），非可利用漏洞。

### L2【低】`crypto-src/.github/workflows/` 是死文件

仓库只有 1 个活动工作流（根 `.github/workflows/ci.yml`）。GitHub 不读取子目录下的
`.github/workflows/`，因此 `crypto-src/.github/workflows/{build,ci,release}.yml`
（约 34 KB）**完全不生效**。维护者若按这些文件理解加密层的 CI 行为会得到错误结论。

### L3【低】访问令牌的过期校验缺少类型防护

```ts
// server/src/auth/auth.service.ts:85
if (!data || data.typ !== "access" || data.exp <= Date.now() / 1000) return undefined;
```

`data.exp` 若缺失，`undefined <= number` 求值为 `false`，该分支不成立 → **过期检查被跳过**。
由于前置的 HMAC 校验（`:73-75`）已经通过，只有在 `AUTH_SECRET` 泄露时才可利用，
属防御纵深缺口而非可直接利用的漏洞。建议补 `typeof data.exp !== "number"` 判断。

### L4【低】Android 依赖来自 JitPack 且不可变

`androidApp/build.gradle.kts:103`：`com.github.WuKongIM:WuKongIMAndroidSDK:1.5.2`
由 JitPack 从 GitHub 源码现场构建，非不可变产物；`1.5.2` 是 tag 而非 commit 哈希，
上游 tag 可被移动。其余 Android 依赖（compose-bom 2024.12.01、media3 1.5.1、
activity-compose 1.10.0、coil 2.7.0）版本正常，未发现已知漏洞版本。

---

## 五、已复核为良好的部分（勿重复劳动）

历史审计（2026-09-17）的 S1–S11 **逐条复核确认已闭环**：

| 项 | 复核结果 |
| --- | --- |
| S1 `AUTH_SECRET` 硬编码回退 | ✅ 已改为 `?? ""`，`env.validation.ts` 与 `NODE_ENV` 解耦、缺失即拒绝启动 |
| S2 默认管理员 `admin/admin123` | ✅ 已改为 `ADMIN_INITIAL_PASSWORD` 或随机生成，日志不打印口令，强制首次改密 |
| S3 `X-Admin-Token` 静态超管通道 | ✅ 整体移除 |
| S11 `speakeasy` 停止维护 | ✅ 已移除（改用自实现 RFC 6238） |

本轮独立复核的其他正面结论：

1. **注入面为零**。全仓无 `child_process` / `execSync` / `eval` / `new Function`；
   `query(\`...\${...}\`)` 形式的 SQL 模板插值 **0 命中**，`migrations.ts` 的模板串是纯 DDL。
2. **无硬编码凭据**。跟踪文件中 `(password|secret|api_key|token)\s*[:=]\s*"<16+ 字符>"` 扫描 0 命中；
   `.env.example` 全是中文占位说明；`server/.env` 未入库且被 `server/.gitignore` 覆盖。
3. **历史无密钥泄露**。`git log --all` 全历史新增文件扫描，从未出现 `.jks/.env/.pem` 类文件。
4. **认证实现规范**。口令 scrypt(`N=65536,r=8,p=1`)+16B 盐；令牌 HMAC-SHA256；
   `timingSafeEqual` 前先比长度（`auth.service.ts:74`）；刷新令牌库中只存 SHA-256。
5. **Webhook 校验正确**。`X-Hub-Signature-256` 用 HMAC-SHA256 + `timingSafeEqual`，且先做长度比对
   （`release/github-webhook.controller.ts:191-195`）。
6. **OpenAPI Key 存储规范**。只存 SHA-256、列表页只回显前缀（`open-api-key.service.ts:17,57`）。
7. **全局防线在位**。`app.module.ts` 全局挂 `AccessTokenGuard` + `RateLimitGuard`；
   公开路由均显式 `@Public()`；CORS 走 `CORS_ALLOWED_ORIGINS` 白名单，无通配。
8. **依赖审计结果**：
   - 加密层 `crypto-src/Cargo.lock`（94 包）→ OSV **0 命中**
   - `desktop/package-lock.json` → npm audit **0 命中**
   - 加密层 `psk-guard`（生产构建缺 PSK 即失败）在位；`CRYPTIFY_KEY` 缺省用内置固定密钥，
     可复现构建未被破坏

---

## 六、建议处置顺序

**立即（今天）**

1. **M6** 根 `.gitignore` 加 `keystore.b64`（一行，零风险，堵住签名私钥泄露路径）
2. **H1** 重新升级 `nodemailer` 到 `10.0.14` 并跑契约验证

**本周**

3. **M4** 给 `ci.yml` 加 `pull_request` 触发器（PR 至少跑 `changes` + 受影响模块的构建与契约验证）
4. **M3** 补 `.github/dependabot.yml`（分组 + 每周节奏）与 `SECURITY.md`
5. **M5** `purge` 改手动触发 + environment 保护，或限制删除范围
6. **M2** 启用 CodeQL（公开仓库免费）

**两周内**

7. **M1** `dtolnay/rust-toolchain@stable`、`tauri-apps/tauri-action@v0` 改 SHA 固定
8. **L1** 人工处理 `glib`（Dependabot 已连续失败，不会自愈）
9. **L3** 补 `exp` 类型校验
10. **L2** 删除或明确标注 `crypto-src/.github/workflows/` 为历史文件

---

## 七、审查局限

1. **无法读取需鉴权的平台数据**：Dependabot 告警列表、Code Scanning 结果、
   Secret Scanning 告警、分支保护规则、`security_and_analysis` 开关状态均需仓库 admin 权限，
   本次通过 API 只能确认「无 CodeQL 工作流」「无 dependabot.yml」这类可见事实。
   **建议在 GitHub 网页端自行确认 Secret Scanning / Push Protection 是否已开启**
   （公开仓库免费，能直接堵住 M6 那类误提交）。
2. **Gradle/Maven 依赖未做 CVE 扫描**：本地无 SCA 工具链，Android 侧仅做了关键依赖的版本人工核对，
   未逐条比对公告。
3. **未做动态验证**：全部结论来自静态代码阅读、锁文件解析与公开 API 查询；
   未启动服务、未做渗透测试。
4. **未覆盖客户端 Kotlin/Compose 源码**：范围是后端、CI、仓库卫生与依赖树；
   Android/Web 客户端的业务逻辑未逐文件审计。
