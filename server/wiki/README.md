# 桃桃音乐后端文档中心

这里是桃桃音乐后端的专题文档目录。文档按主题拆成约 40 篇短文,每篇只负责一个主题;修改功能时应同步更新对应专题,避免把内容继续堆回单篇长文。

每篇文档头部带「最后更新」日期。发现某篇长期未动又拿不准是否过期时,先按 [00-code-index.md](00-code-index.md) 的 CodeGraph 流程核对源码,再决定改哪篇。

## 📚 文档导航

### 核心索引

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [00-code-index.md](00-code-index.md) | CodeGraph 基准、模块地图、路由清单(以实测为准)、文档同步规则 | 先确认源码当前形状、查路由、判断文档应该改在哪里 |

### 架构(10–13)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [10-service-boundary.md](10-service-boundary.md) | 服务负责什么、不负责什么、外部系统边界 | 第一次接触项目、判断某个需求该不该进后端 |
| [11-architecture-modules.md](11-architecture-modules.md) | 模块依赖图、源码职责、模块边界规则、依赖注入规则、进程内状态与单实例假设 | 准备新增模块、弄不清某个业务该写在哪个模块、评估水平扩容时 |
| [12-request-pipeline.md](12-request-pipeline.md) | 请求链路、全局守卫/拦截器/过滤器、响应阶段、原始请求体例外、静态资源 | 排查某个请求为什么被拦、要加全局行为时 |
| [13-startup-lifecycle.md](13-startup-lifecycle.md) | 启动时序、`onModuleInit` / `onApplicationBootstrap` 的区别、误判点 | 初始化代码该写在哪、启动日志看不懂时 |

### 开发与配置(20–23)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [20-development-setup.md](20-development-setup.md) | 环境要求、首次初始化、常用命令、数据库建表、调试技巧 | 搭本地环境、日常跑服务时 |
| [21-configuration.md](21-configuration.md) | **全部环境变量的唯一完整出处**,含校验行为与坑 | 新增或查配置项、部署前核对 `.env` 时 |
| [22-contract-verification.md](22-contract-verification.md) | 验证库准备、验证实例启动参数、契约脚本运行与失败定位 | 数据层改动后验证、交付前全绿验收时 |
| [23-coding-rules.md](23-coding-rules.md) | 新增模块步骤、DTO 校验、错误处理、提交前检查 | 写新接口、写新 Repository、准备提交时 |

### 接口契约(30–37)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [30-api-conventions.md](30-api-conventions.md) | 响应信封、业务码总表、鉴权矩阵、限流桶、响应头、兼容性红线 | 改任何接口前必读;与客户端联调时 |
| [31-api-auth-user.md](31-api-auth-user.md) | 注册、登录、令牌刷新、资料、头像、邮箱绑定/换绑 | 改账号体系或用户资料接口时 |
| [32-api-search-music.md](32-api-search-music.md) | 搜索联想、热搜、NDJSON 搜索、播放地址、播放代理、歌词 | 改搜索或播放链路、处理音质降级时 |
| [33-api-playlists.md](33-api-playlists.md) | 云端歌单 CRUD、歌曲快照、排序与完整替换、revision 语义 | 改歌单接口或排查多端同步冲突时 |
| [34-api-playback.md](34-api-playback.md) | 播放会话上报、最近播放、听歌统计、清空代际、单曲倒带日记 | 改播放记录链路、处理 409 因果冲突时 |
| [35-api-shares.md](35-api-shares.md) | 歌曲分享短链、公开元数据、试听转发 | 改分享页链路、排查试听 502 时 |
| [36-api-open.md](36-api-open.md) | 开放搜歌 API、API Key 管理、401/4014 语义 | 第三方接入、维护开放 Key 时 |
| [37-api-crypto.md](37-api-crypto.md) | 传输加密握手、AEAD 中间件、PSK 配置、失败语义与降级 | 配置或排查加密链路时 |

### 数据库(40–44)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [40-database-overview.md](40-database-overview.md) | 连接池、迁移机制、类型规则、Repository 规则、事务边界、表变更流程 | 改任何数据层代码前必读 |
| [41-database-tables-core.md](41-database-tables-core.md) | users、refresh_tokens、favorites、playlists、playback 四表、song_share | 查用户域/播放域字段语义时 |
| [42-database-tables-release.md](42-database-tables-release.md) | app_release、app_channel、app_config、app_patch、app_announcement | 查发布/公告/配置字段语义时 |
| [43-database-tables-admin.md](43-database-tables-admin.md) | admin_users、admin_sessions、admin_audit_log、open_api_key、music_source_account、api_key、image_generation_task | 查管理域/凭据域字段语义、外键删除语义时 |
| [44-database-operations.md](44-database-operations.md) | 并发 SQL 范例、Key 运维 SQL、图片额度一致性、索引清单 | 写并发路径、运维额度、评估查询性能时 |

### 功能专题(50–53)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [50-feature-image-generation.md](50-feature-image-generation.md) | gpt-image-2 任务、Key 池、额度扣减归还、状态轮询 | 维护图片生成、Key 池、额度时 |
| [51-feature-wukongim.md](51-feature-wukongim.md) | 悟空 IM 接入、端口基线、凭据签发、频道同步 | 接入或排查聊天链路时 |
| [53-feature-music-sources.md](53-feature-music-sources.md) | 音源账号(酷我/波点)、凭据管理、KPK 签名、探测与缓存 | 维护音源账号、排查「搜得到放不出」时 |

### 发布与部署(60–62)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [60-deploy-backend.md](60-deploy-backend.md) | 后端构建 6 步、部署文件、生产配置检查、systemd、nginx、回滚 | 部署后端、改生产环境时 |
| [61-release-android.md](61-release-android.md) | APK 构建/登记/下载验证、灰度、最低版本、热更新契约 | 发 Android 版本、排灰度问题时 |
| [62-ci-cloud-build.md](62-ci-cloud-build.md) | GitHub Actions 云端构建、三仓快照同步、版本号收编 | 同步仓库、用云端产物发版时 |

### 排障(70–74)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [70-troubleshooting-startup.md](70-troubleshooting-startup.md) | 排查顺序、启动失败、数据库错误 | 服务起不来、迁移报错时 |
| [71-troubleshooting-auth.md](71-troubleshooting-auth.md) | 401/403 区分、管理后台登录、2FA、退避、白名单 | 登录相关一切异常时 |
| [72-troubleshooting-music.md](72-troubleshooting-music.md) | 搜索/播放/歌词、图片任务、分享试听、IM 排障 | 音乐业务或图片任务异常时 |
| [73-troubleshooting-release.md](73-troubleshooting-release.md) | 热更新失效、契约验证失败 | 客户端收不到更新、验证脚本红了时 |
| [74-error-codes.md](74-error-codes.md) | HTTP/业务码快速定位表、日志安全红线 | 看到错误码不知道先查什么时 |

### 管理后台(80–83)

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [80-admin-auth-login.md](80-admin-auth-login.md) | 管理员会话、登录链路、账号退避、TOTP 2FA | 维护登录、2FA、LDAP 回落时 |
| [81-admin-roles-audit.md](81-admin-roles-audit.md) | 角色矩阵、IP 白名单、操作审计 | 加管理接口、查权限问题时 |
| [82-admin-routes-data.md](82-admin-routes-data.md) | 管理路由表、限流分桶、三张表模型、启动期硬约束 | 加管理路由、排查启动即失败时 |
| [83-admin-frontend.md](83-admin-frontend.md) | Vue 管理后台组件、CSP 与安全响应头、契约断言 | 改管理后台前端、调安全头时 |

## 🔗 上位文档

项目级文档,位于项目根目录:

| 文档 | 说明 | 关键内容 |
| --- | --- | --- |
| [后端 README](../README.md) | 后端服务总览 | 接口完整列表、启动命令、模块结构、配置项说明 |
| [项目 README](../../README.md) | 项目总览 | 完整播放链路、功能清单、反复踩过的坑 |
| [AGENTS.md](../../AGENTS.md) | 开发规范 | 代码风格、模块划分、测试、签名、提交规范 |
| [RELEASE.md](../../RELEASE.md) | 发布与热更新手册 | **版本号铁律、不能破坏的客户端契约、发布流程** |
| [HOT_UPDATE.md](../../HOT_UPDATE.md) | 热更新设计 | 热更新能力边界、设计动机、自愈机制 |

> **⚠️ 改后端接口前必读 [RELEASE.md](../../RELEASE.md)**:其中列出了 15+ 条不能破坏的客户端契约,违反会导致装机客户端功能静默失效。

## 📋 文档维护规则

1. **语言统一**:文档、代码注释、错误提示统一使用简体中文。
2. **一篇一主题**:新内容找不到归属时新增一篇编号文档并登记到本页,不要把多个主题塞进同一篇。
3. **同步更新**:
   - 新模块:先更新 [00-code-index.md](00-code-index.md) 和 [11-architecture-modules.md](11-architecture-modules.md);
   - 数据层变化:同时更新数据库专题(40–44);
   - 客户端可感知的响应变化:必须写入接口契约专题(30–37);
   - 新环境变量:同时更新 `.env.example`、[21-configuration.md](21-configuration.md) 和后端 README;
   - 新审计 action:同步 [81-admin-roles-audit.md](81-admin-roles-audit.md) 与前端 `AuditLogViewer.vue` 的 `ACTION_GROUPS`。
4. **安全第一**:不在文档中写入真实密码、API Key、数据库连接串或签名信息;示例密钥只能使用明显的占位文本(例如 `替换为真实Key`)。
5. **可执行性**:文档中的命令应能从标注的工作目录直接执行。
6. **加密同步**:加密层源码在 monorepo 的 `crypto-src/`(Rust,core/jni/node/wasm 四 crate),由 `tools/sync-repos.ps1` 快照推到 GitHub `hdppppppp/tools` 仓库交叉编译,产物发 Release 后经 `tools/fetch-crypto.ps1` 落回本仓库 `crypto/dist/`。改协议格式或密钥规则去 `crypto-src/` 改,同步更新它的 `README.md` 与 `SECURITY.md`;`crypto/dist/` 只放产物,不放文档、不手改。
7. **索引同步**:新增或删除路由、表、环境变量或模块后先刷新 CodeGraph,再更新 [00-code-index.md](00-code-index.md) 和对应专题;不要凭旧 README 猜测路由。
8. **时效标注**:每篇文档头部保留「最后更新」日期;修改当天更新它。

## 📖 推荐阅读路径

### 新后端开发者(首次接触项目)

1. [10-service-boundary.md](10-service-boundary.md) → [11-architecture-modules.md](11-architecture-modules.md):先知道服务管什么、模块怎么分。
2. [12-request-pipeline.md](12-request-pipeline.md) → [13-startup-lifecycle.md](13-startup-lifecycle.md):理解一个请求从进入到返回经过什么,以及启动的三个时间点。
3. [20-development-setup.md](20-development-setup.md) → [21-configuration.md](21-configuration.md):把本地服务跑起来。
4. [30-api-conventions.md](30-api-conventions.md) → [40-database-overview.md](40-database-overview.md):写接口与写 Repository 前的红线。

### 维护图片生成

1. [50-feature-image-generation.md](50-feature-image-generation.md):Key 池、扣额归还、状态机全流程。
2. [43-database-tables-admin.md](43-database-tables-admin.md) 的 `api_key` / `image_generation_task` 两节。
3. [72-troubleshooting-music.md](72-troubleshooting-music.md) 的图片任务排障、[74-error-codes.md](74-error-codes.md) 速查。

### 发布与部署

1. [60-deploy-backend.md](60-deploy-backend.md) → [61-release-android.md](61-release-android.md)。
2. [项目根目录 RELEASE.md](../../RELEASE.md):版本号铁律,**推版本前必读**。
3. [73-troubleshooting-release.md](73-troubleshooting-release.md):热更新失效诊断。

### 维护管理后台

1. [80-admin-auth-login.md](80-admin-auth-login.md) → [81-admin-roles-audit.md](81-admin-roles-audit.md) → [82-admin-routes-data.md](82-admin-routes-data.md)。
2. [43-database-tables-admin.md](43-database-tables-admin.md):三张管理表的字段语义与外键设计。
3. [71-troubleshooting-auth.md](71-troubleshooting-auth.md):登录恒 401、强制改密 403/4031、退避 429/4291 的定位路径。

### 排查线上问题

直接跳 [70-troubleshooting-startup.md](70-troubleshooting-startup.md),按症状走 70→74 的子篇;不确定分类时先看 [74-error-codes.md](74-error-codes.md) 的状态码速查表。
