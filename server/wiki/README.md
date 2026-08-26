# 桃桃音乐后端文档中心

这里是桃桃音乐后端的专题文档目录。每篇文档只负责一个主题，修改功能时应同步更新对应专题，避免把所有内容继续堆回单个 README。

## 文档导航

| 文档 | 适合什么时候看 |
| --- | --- |
| [01-architecture.md](01-architecture.md) | 第一次接触项目、准备新增模块、需要理解请求链路时 |
| [02-development.md](02-development.md) | 搭建本地环境、运行、构建、调试和执行测试时 |
| [03-api-contracts.md](03-api-contracts.md) | 新增或修改接口、与客户端联调、处理错误码时 |
| [04-database.md](04-database.md) | 修改表结构、Repository、SQL 或排查 PostgreSQL 问题时 |
| [05-image-generation.md](05-image-generation.md) | 维护 gpt-image-2、Key 池、额度和图片任务时 |
| [06-release-deployment.md](06-release-deployment.md) | 部署后端、发布 APK、灰度或回滚时 |
| [07-troubleshooting.md](07-troubleshooting.md) | 服务启动失败、接口异常或线上行为不符合预期时 |
| [08-native-crypto.md](08-native-crypto.md) | 接口需要原生加密、认证、防重放或排查 Kiwi Crypto 时 |
| [09-wukongim.md](09-wukongim.md) | 接入悟空 IM、配置端口、排查聊天连接或凭据问题时 |

## 上位文档

- [后端 README](../README.md)：接口和当前能力的完整说明。
- [项目开发规范](../../AGENTS.md)：代码、测试、安全和交付要求。
- [发布与热更新手册](../../RELEASE.md)：版本号铁律和不能破坏的客户端契约。
- [热更新设计](../../HOT_UPDATE.md)：热更新通道的设计背景。

## 文档维护规则

1. 文档、代码注释、错误提示统一使用简体中文。
2. 新模块先更新架构和接口专题；数据层变化同时更新数据库专题。
3. 客户端可感知的响应变化必须写入接口契约专题。
4. 新环境变量同时更新 `.env.example`、后端 README 和开发专题。
5. 不在文档中写入真实密码、API Key、数据库连接串或签名信息。
6. 示例密钥只能使用明显的占位文本，例如 `替换为真实Key`。
7. 文档中的命令应能从标注的工作目录直接执行。
8. 原生加密格式或密钥规则变化时同步更新 Kiwi Crypto 专题和模块内 `SECURITY.md`。

## 快速入口

```powershell
cd server
npm install
npm run dev
```

生产构建：

```powershell
npm run build
```

健康检查：

```powershell
curl.exe http://127.0.0.1:4500/health
```

接口统一前缀是 `/api/v1`，只有健康检查保留在 `/health`。

## 推荐阅读路径

### 新后端开发者

1. [架构](01-architecture.md)：先理解模块和全局请求链路。
2. [开发环境](02-development.md)：启动本地数据库和服务。
3. [接口契约](03-api-contracts.md)：了解不能改变的响应形状。
4. [数据库](04-database.md)：开始写 Repository 前阅读类型与并发规则。

### 图片生成功能维护者

1. [图片生成](05-image-generation.md)：理解 Key 池、任务状态和额度边界。
2. [数据库](04-database.md)：理解两张业务表及原子扣额 SQL。
3. [故障排查](07-troubleshooting.md)：按 404/502/503 分类处理。

### 发布维护者

1. [发布与部署](06-release-deployment.md)。
2. [项目发布手册](../../RELEASE.md)。
3. [故障排查](07-troubleshooting.md)中的热更新章节。
