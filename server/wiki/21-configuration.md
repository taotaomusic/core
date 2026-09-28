# 配置索引(环境变量唯一完整出处)

[返回文档中心](README.md)

最后更新:2026-09-27

本页是**全部环境变量的唯一完整出处**。其它文档提到配置时一律链接到这里,不要再复制配置表(历史上 02 与 00 各维护一份导致过漂移)。新增环境变量时:改 `.env.example` → 改本页 → 改后端 README,三处同步。

读取与校验逻辑在 `src/config/app-config.service.ts` 和 `src/config/env.validation.ts`;所有变量经 `AppConfigService` 类型化暴露,业务代码禁止直接读 `process.env`。

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
| `TRUST_PROXY` | 关闭 | 只有 `1`/`true` 才采信 `X-Forwarded-For`;否则用 `socket.remoteAddress`。只在可信反向代理之后才打开,否则 IP 白名单可被伪造请求头绕过 |
| `PUBLIC_BASE_URL` | 空(按请求推导) | APK 和分享地址的外部基地址;生产建议显式配置,否则按代理头推导,可能生成错误协议的地址 |
| `DEFAULT_CHANNEL` | `release` | 默认发布渠道 |

## 3. 文件目录与发布

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APK_DIR` | `./data/apk` | Android APK 和补丁文件目录,运行用户必须可写 |
| `DESKTOP_RELEASE_DIR` | `./data/desktop` | Windows 内容寻址模块和差分目录;契约验证前要清空(原因见 [22-contract-verification.md](22-contract-verification.md)) |
| `COURGETTE_PATH` | 空 | PE 文件差分工具路径;未配置时使用内置 bsdiff-wasm |
| `SEARCH_CONCURRENCY` | `8` | 类型化配置仍保留;**当前搜索实现不读取该字段**,不要误以为能改变请求并发 |
| `BSDIFF_BIN` | 空 | `AppConfigService` 仍读取以兼容旧配置,但当前差分实现直接使用 bsdiff-wasm,**不需要安装外部 bsdiff** |

## 4. 管理后台与安全

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ADMIN_INITIAL_PASSWORD` | 空 | 默认超管初始口令,至少 12 字符(不足启动失败);留空则随机生成并以 WARN 打印**一次**。两种来源都强制首登改密(见 [80-admin-auth-login.md](80-admin-auth-login.md)) |
| `ADMIN_RATE_LIMIT` | `60` | 管理端 `admin` 限流桶每 15 分钟次数。**仅供契约验证调大,生产勿设**(见 [22-contract-verification.md](22-contract-verification.md)) |
| `TOTP_ISSUER` | `桃桃音乐管理后台` | 管理员 2FA 在验证器里显示的名称 |

## 5. 传输加密

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CRYPTO_PSK_ID` | 空 | 传输加密 PSK 标识 |
| `CRYPTO_PSK_HEX` | 空 | 32 字节 hex 原始密钥(64 个 hex 字符) |

两者**缺任一项**:握手接口固定 503/5031、链路保持明文(这是预期的优雅降级,不是故障);配置后两侧值必须与客户端一致,且 `device_id` 折进握手密钥(协议 v2)。协议细节见 [37-api-crypto.md](37-api-crypto.md)。

## 6. 图片生成

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APISWEET_BASE_URL` | `https://apisweet.com` | 图片上游地址;本地联调可指向假上游 |

ApiSweet API Key 存在 PostgreSQL `api_key` 表,**不使用环境变量**;`.env` 里没有也不需要有 Key 相关条目。

## 7. 邮件

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | 空 / `587` | 注册、绑定和换绑邮箱验证码的发信配置;发码功能要求五项完整,`PORT` 1–65535 |
| `EMAIL_VERIFICATION_TEST_CODE` | 空 | 固定验证码后门,**仅 `NODE_ENV=test` 生效**;普通开发/生产进程填写该变量会在启动时拒绝 |

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
| `BODIAN_DEVICE_ID` | `md5("taotao-music-server")` | 波点客户端设备号覆盖项(见 [53-feature-music-sources.md](53-feature-music-sources.md)) |

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

## 11. `.env` 示例(完整可复制)

```env
PORT=4500
DATABASE_URL=postgres://postgres:密码@localhost:5432/music
AUTH_SECRET=change-me-to-a-random-string-at-least-32-chars
ADMIN_INITIAL_PASSWORD=
CORS_ALLOWED_ORIGINS=
APK_DIR=./data/apk
DESKTOP_RELEASE_DIR=./data/desktop
COURGETTE_PATH=
DEFAULT_CHANNEL=release
PUBLIC_BASE_URL=http://127.0.0.1:4500
SEARCH_CONCURRENCY=8
APISWEET_BASE_URL=https://apisweet.com
# 可选:传输加密 PSK。缺任一项握手全部 503/5031,链路保持明文(本地开发可不配)。
# CRYPTO_PSK_ID=dev-psk
# CRYPTO_PSK_HEX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
SMTP_HOST=
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
SMTP_FROM=
IM_ENABLED=false
IM_INTERNAL_API_BASE_URL=http://127.0.0.1:5001
IM_EXTERNAL_GATEWAY_URL=tcp://im.xydaigua.cn:5100
IM_API_TOKEN=
IM_SESSION_LIFETIME_SECONDS=900
TOTP_ISSUER=桃桃音乐管理后台
# 可选:LDAP/SSO。不配置则只用本地管理员账号。
# LDAP_URL=ldap://ldap.example.com:389
# LDAP_BIND_DN=cn=admin,dc=example,dc=com
# LDAP_BIND_PASSWORD=
# LDAP_USER_SEARCH_BASE=ou=users,dc=example,dc=com
# LDAP_TLS_REJECT_UNAUTHORIZED=true
# LDAP_TIMEOUT_MS=10000
# 只在可信反向代理之后才打开,否则 IP 白名单可被伪造请求头绕过。
# TRUST_PROXY=1
```
