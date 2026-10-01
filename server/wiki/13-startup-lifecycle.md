# 启动生命周期

[返回文档中心](README.md)

最后更新:2026-09-30

启动不是「加载模块后立即监听端口」。本文讲启动的完整时序和三个关键时间点,初始化代码写错位置是启动期故障的最常见根因。

## 1. 完整时序

```text
读取 .env / 系统环境变量
  → validateEnvironment 校验端口、AUTH_SECRET、DATABASE_URL、SMTP_PORT、
    ADMIN_INITIAL_PASSWORD、IM_* 与 EMAIL_VERIFICATION_TEST_CODE(逐条行为见 [21-configuration.md](21-configuration.md))
  → NestFactory.create(AppModule):实例化 Module 和 Provider(构造函数在这里跑)
  → 挂载传输加密中间件(先于 body parser;含强制加密白名单)
  → 挂载按路由分流的 JSON parser(普通 16KB、原始上传跳过)
  → 挂载 /admin(CSP 先于 expressStatic)与 /share 静态资源
  → setGlobalPrefix("api/v1", exclude: ["health"])
  → 注册全局 ValidationPipe,关闭 x-powered-by
  → app.listen(PORT)
      ├─ app.init()
      │    ├─ onModuleInit:DatabaseService 等待 PostgreSQL → 顾问锁 → 幂等 DDL
      │    └─ onApplicationBootstrap:AdminBootstrapService 创建默认管理员;
      │         CryptoTransportService 加载加密原生产物(协议 ≥2)、登记 PSK、启动会话清理定时器
      └─ 端口开始接受连接
```

数据库连接在 `onModuleInit` 阶段最多重试 10 次、每次间隔 1 秒;全部失败时服务退出,不会继续提供残缺接口。

## 2. 三个关键时间点

`main.ts` 的顶层代码、`onModuleInit`、`onApplicationBootstrap` 是三个不同的时间点:

| 时间点 | 能做什么 | 不能做什么 |
| --- | --- | --- |
| `main.ts` 顶层 | 挂中间件、静态资源、全局管道 | **任何写数据库的操作**(表还不存在) |
| `onModuleInit` | 建表(幂等 DDL,见 [40-database-overview.md](40-database-overview.md)) | 依赖其它模块已完成业务初始化 |
| `onApplicationBootstrap` | 需要写表的初始化(默认管理员、加密产物加载) | — |

建表在 `onModuleInit`,所以 `main.ts` 里能跑代码时**表还不存在**。任何需要写表的初始化都必须放在 `onApplicationBootstrap`(默认管理员 `AdminBootstrapService` 就是这么做的)。

## 3. 容易误判的点

- Provider 构造函数发生在数据库迁移之前,所以不能在构造函数查询表。
- 日志出现「Module dependencies initialized」不表示数据库已经就绪;要等「数据库已就绪」。
- 默认管理员如果写在 `main.ts`,会早于迁移执行,必然报「关系 admin_users 不存在」。
- 数据库连接重试全部失败时,服务会退出,不会继续带病监听。
- 端口只有在迁移完成后才开始监听,因此**健康检查成功意味着表结构也已初始化**。
- 验证库被 `reset-db.mjs` 清空后必须重启服务,建表只在启动时运行一次(见 [22-contract-verification.md](22-contract-verification.md))。

## 4. 启动期会自动发生的事

- **幂等迁移**:同一事务内取顾问锁 `pg_advisory_xact_lock(913720001)` 后执行全部 DDL(见 [40-database-overview.md](40-database-overview.md))。
- **默认管理员**:`AdminBootstrapService` 找不到 `admin` 用户名才创建(`super_admin`),口令取 `ADMIN_INITIAL_PASSWORD`(未设置则随机生成并以 WARN 打印一次),带 `must_change_password = 1` 强制首登改密(见 [80-admin-auth-login.md](80-admin-auth-login.md))。已存在则跳过,重启幂等。
- **加密产物加载**:`CryptoTransportService` 加载 `crypto/dist` 的原生产物;产物缺失或协议版本 <2 时只 WARN 并降级明文,不阻断启动(见 [37-api-crypto.md](37-api-crypto.md))。
- **PSK 登记**:配置了 `CRYPTO_PSK_HEX` 就用固定值;未配置则随机生成一把并 WARN(重启即变、多实例不一致)。无论哪种,都经 `GET /api/v1/crypto/psk` 下发给已登录客户端,因此只要产物加载成功,加密链路即启用。
- **会话清理定时器**:加密会话每分钟清理过期项。

启动失败的具体症状与处理见 [70-troubleshooting-startup.md](70-troubleshooting-startup.md)。
