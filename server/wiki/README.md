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
