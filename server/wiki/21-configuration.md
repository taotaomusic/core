# 配置索引(环境变量唯一完整出处)

[返回文档中心](README.md)

最后更新:2026-09-30

本页是**全部环境变量的唯一完整出处**。其它文档提到配置时一律链接到这里,不要再复制配置表(历史上 02 与 00 各维护一份导致过漂移)。新增环境变量时:改 `.env.example`(若它也参与) → 改本页 → 改后端 README。注意仓库里的 `.env.example` 目前是 **docker-compose 部署模板**,只收录部署最小集,应用读取的全部变量以本页为准。

读取与校验逻辑在 `src/config/app-config.service.ts` 和 `src/config/env.validation.ts`;所有变量经 `AppConfigService` 类型化暴露,业务代码禁止直接读 `process.env`(现存例外:`ENV_FILE`、`TRUST_PROXY`、`ADMIN_RATE_LIMIT`、`BODIAN_DEVICE_ID`、`CRYPTO_REQUEST_LOG`,各自小节有标注)。

## 1. 必需项

| 变量 | 要求 | 说明 |
| --- | --- | --- |
| `DATABASE_URL` | 必填 | PostgreSQL 连接串,必须以 `postgres://` 或 `postgresql://` 开头;没有默认值 |
| `AUTH_SECRET` | **必填,至少 32 字符** | 访问令牌的 HMAC-SHA256 签名密钥。**没有开发兜底值**,缺失或过短直接启动失败(此校验与 `NODE_ENV` 无关,任何环境都生效)。轮换密钥会使所有现存访问令牌立即失效 |

## 2. 服务基础

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `4500` | HTTP 监听端口,必须是 1–65535 的整数 |
| `ENV_FILE` | `.env` | 指定其它 dotenv 文件路径;系统环境变量始终优先于文件 |
| `NODE_ENV` | 空 | 设 `test` 时 `EMAIL_VERIFICATION_TEST_CODE` 才生效;其余取值不影响校验行为 |
| `CORS_ALLOWED_ORIGINS` | 空 | 跨域来源白名单,白名单回显 + `Vary: Origin`;**留空不下发任何 CORS 头** |
| `TRUST_PROXY` | 关闭 | 只有 `1`/`true` 才采信 `X-Forwarded-For`;否则用 `socket.remoteAddress`(`common/request.types.ts` 直接读 `process.env`,不经 `AppConfigService`)。只在可信反向代理之后才打开,否则 IP 白名单可被伪造请求头绕过 |
| `PUBLIC_BASE_URL` | 空(按请求推导) | APK 和分享地址的外部基地址;生产建议显式配置,否则按代理头推导,可能生成错误协议的地址 |
| `DOWNLOAD_PROXY_PREFIX` | 空 | 构件下载代理前缀,给上云后的 GitHub Release 直链提速(如 `https://gh-proxy.org/`)。bootstrap 下发 `apkUrl`/`patch.url` 与 desktop `latest.json` 的安装包地址时拼在直链前面;留空则下发原始直链 |
| `GITHUB_WEBHOOK_SECRET` | 空 | GitHub Release webhook 的验签密钥**兜底值**(空库首次引导用)。首选在管理后台「系统设置」里配置(存库、可轮换);两者都空则 `/api/v1/app/github-webhook` 拒绝所有请求 |
| `DEFAULT_CHANNEL` | `release` | 默认发布渠道 |

## 3. 文件目录与发布

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APK_DIR` | `./data/apk` | Android APK 和补丁文件目录,运行用户必须可写 |
| `SEARCH_CONCURRENCY` | `8` | 类型化配置仍保留;**当前搜索实现不读取该字段**,不要误以为能改变请求并发 |

## 4. 管理后台与安全

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ADMIN_INITIAL_PASSWORD` | 空 | 默认超管初始口令,至少 12 字符(不足启动失败);留空则随机生成并以 WARN 打印**一次**。两种来源都强制首登改密(见 [80-admin-auth-login.md](80-admin-auth-login.md)) |
| `ADMIN_RATE_LIMIT` | `60` | 管理端 `admin` 限流桶每 15 分钟次数(`rate-limit.service.ts` 直接读 `process.env`,解析失败或非正整数静默回落 60)。**仅供契约验证调大,生产勿设**(见 [22-contract-verification.md](22-contract-verification.md)) |
| `TOTP_ISSUER` | `桃桃音乐管理后台` | 管理员 2FA 在验证器里显示的名称 |

## 5. 传输加密

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CRYPTO_PSK_ID` | `prod-v1` | 传输加密 PSK 标识(留空落到 `prod-v1`) |
| `CRYPTO_PSK_HEX` | 空 | 64 个 hex 字符(32 字节)的 PSK。**留空不是关掉加密**:启动时随机生成一把并以 WARN 打印提示,链路照常启用;代价是重启即变、多实例不一致,已连客户端要重新握手。**生产必须配固定值** |
| `CRYPTO_REQUEST_LOG` | 开 | 设 `off` 关闭加密中间件对每个 `/api/v1` 请求的「明文/加密」可观测日志(`crypto.middleware.ts` 直接读 `process.env`,不经 `AppConfigService`) |

PSK 由后端经 `GET /api/v1/crypto/psk`(受登录令牌保护)动态下发给已登录客户端,客户端不再内嵌密钥。握手 503/5031 只发生在**原生产物缺失或协议版本 <2**(`enabled` 为 false)时;PSK 未配置不影响启用。协议细节见 [37-api-crypto.md](37-api-crypto.md)。

## 6. 图片生成

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APISWEET_BASE_URL` | `https://apisweet.com` | 图片上游地址;本地联调可指向假上游 |

ApiSweet API Key 存在 PostgreSQL `api_key` 表,**不使用环境变量**;`.env` 里没有也不需要有 Key 相关条目。

## 7. 邮件

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | 空 / `587` | 注册、绑定和换绑邮箱验证码的发信配置;`SMTP_PORT` 必须 1–65535。`SMTP_HOST`/`USER`/`PASSWORD`/`FROM` 四项任一为空即视为未配置,发信时报 502/5020「邮件服务尚未配置」,注册保持关闭 |
| `EMAIL_VERIFICATION_TEST_CODE` | 空 | 固定验证码后门,**仅 `NODE_ENV=test` 生效**且必须是 6 位数字;普通开发/生产进程填写该变量会在启动时拒绝 |

## 8. 悟空 IM

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `IM_ENABLED` | `false` | 是否启用 IM;启用时以下三项必需 |
| `IM_INTERNAL_API_BASE_URL` | `http://127.0.0.1:5001` | 悟空 IM 产品 HTTP API;必须 `http://`/`https://`,只允许本机/私网 |
| `IM_EXTERNAL_GATEWAY_URL` | `tcp://im.xydaigua.cn:5100` | 下发给客户端的原生 TCP 地址;必须是 `tcp://`,写成 `http://` 启动即被拒绝 |
| `IM_API_TOKEN` | 空 | 悟空 IM 服务端 API Token;绝不返回客户端、不写日志 |
| `IM_SESSION_LIFETIME_SECONDS` | `900` | 会话凭据续签周期,IM 启用时必须 60–86400 |

契约验证实例必须显式 `IM_ENABLED=false`,否则「未启用 IM 返回 503/5031」会变成 502/5020 导致断言失败(见 [22-contract-verification.md](22-contract-verification.md))。

## 9. 音源

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BODIAN_DEVICE_ID` | `md5("taotao-music-server")` | 波点客户端设备号覆盖项(`bodian.client.ts` 直接读 `process.env`;见 [53-feature-music-sources.md](53-feature-music-sources.md)) |

## 10. LDAP/SSO(可选)

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LDAP_URL` / `LDAP_BIND_DN` / `LDAP_USER_SEARCH_BASE` | 空 | **最小必填组,三者缺一即视为未配置**(`isLdapConfigured` 只检查这三项);`LDAP_BIND_PASSWORD` 不属于必填组 |
| `LDAP_BIND_PASSWORD` | 空 | 绑定口令 |
| `LDAP_USER_SEARCH_FILTER` | `(uid={{username}})` | 用户搜索过滤器,`{{username}}` 会被 RFC 4515 转义后替换 |
| `LDAP_GROUP_SEARCH_BASE` / `LDAP_GROUP_SEARCH_FILTER` | 空 | 组搜索;过滤器可用 `{{userDn}}` |
| `LDAP_ROLE_MAPPING` | 空 | JSON 对象,LDAP 组 DN → 角色;写坏了只告警不崩,退化为空映射 |
| `LDAP_TLS_REJECT_UNAUTHORIZED` | `true` | 只有显式 `false`/`0`/`no`/`off` 才关闭 LDAPS 证书校验;**生产保持 true**,关闭等于把服务账号凭据和用户口令暴露给同网段中间人 |
| `LDAP_TIMEOUT_MS` | `10000` | 单次 LDAP 操作超时,连接和搜索都受它约束 |

回落规则(`success`/`denied`/`skipped` 三态)见 [80-admin-auth-login.md](80-admin-auth-login.md)。

## 11. Docker Compose 专用变量

以下变量**只被 `docker-compose.yml` 消费**,后端代码不读取;它们出现在仓库的 `.env.example`(Docker 部署模板)里:

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `POSTGRES_USER` | `taotao` | 数据库容器用户;compose 据此拼 `DATABASE_URL` 传给应用 |
| `POSTGRES_PASSWORD` | 无(必填) | 数据库容器口令,`.env` 里不设则 `docker compose up` 直接报错 |
| `POSTGRES_DB` | `music` | 数据库容器库名 |
| `APP_PORT` | `4720` | 宿主机暴露端口(容器内固定 4720) |

## 12. `.env.example` 与本地开发最小配置

仓库的 `.env.example` 是 docker-compose 部署模板,当前内容如下(与应用相关的变量只有 `AUTH_SECRET` / `ADMIN_INITIAL_PASSWORD` / `CORS_ALLOWED_ORIGINS` / `CRYPTO_PSK_*`):

```env
# --- 数据库 ---
POSTGRES_USER=taotao
POSTGRES_PASSWORD=换成强密码
POSTGRES_DB=music

# --- 应用 ---
AUTH_SECRET=换成至少32字符的随机串
ADMIN_INITIAL_PASSWORD=换成初始管理员密码
CORS_ALLOWED_ORIGINS=https://你的域名
APP_PORT=4720

# --- 传输加密 ---
CRYPTO_PSK_ID=prod-v1
CRYPTO_PSK_HEX=换成64位十六进制
```

**本地直跑(不经 Docker)开发时**,按 [20-development-setup.md](20-development-setup.md) 的最小配置手填 `.env`:`DATABASE_URL`、`AUTH_SECRET`、`PORT` 等应用变量不在这份模板里,以本页各节为准。
