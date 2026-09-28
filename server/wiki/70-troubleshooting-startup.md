# 排障:启动与数据库

[返回文档中心](README.md)

最后更新:2026-09-27

## 1. 排查顺序

1. 查看服务是否监听目标端口。
2. 请求 `/health`(预期 `{"code":0,"message":"success","data":{"status":"up"}}`)。
3. 查看启动日志中的数据库状态。
4. 确认请求是否经过正确域名、反向代理和路径前缀。
5. 根据 HTTP 状态码和业务码定位模块(速查表见 [74-error-codes.md](74-error-codes.md))。
6. 只在独立验证库复现数据层问题。

**不要第一时间重置数据库或修改线上数据。**

## 2. 服务无法启动

### 缺少 `DATABASE_URL`

表现:启动阶段直接报缺少数据库连接串。

处理:检查 `.env`、`ENV_FILE` 和进程管理器环境变量。连接串必须以 `postgres://` 或 `postgresql://` 开头。

### `AUTH_SECRET` 缺失或过短

表现:启动即失败,报「AUTH_SECRET 至少需要 32 个字符」。此校验**无条件生效**,与 `NODE_ENV` 无关 —— 不能用「本地就不设了」绕过。

### PostgreSQL 未就绪

表现:日志每秒输出一次连接失败,10 次后退出。

处理:

- 确认 PostgreSQL 服务状态。
- 检查端口、防火墙、用户名、密码和数据库名。
- 确认 systemd 启动顺序(`After=postgresql.service`)。
- 让进程管理器负责重启,不要把重试改成无限循环。

### NestJS 注入为 undefined

表现:启动时出现 `Cannot read properties of undefined` 或 Provider 构造参数为空。

原因:使用了 esbuild、tsx 或未生成 `emitDecoratorMetadata` 的工具。

处理:只使用 `npm run dev` 或 `npm run build`。

### 启动报 `UnknownDependenciesException`(守卫解析不到依赖)

表现:

```text
UnknownDependenciesException: Nest can't resolve dependencies of the AdminAuthGuard (?)
```

原因:某个模块的 Controller 用了 `AdminAuthGuard` 或 `RolesGuard`,但没有在自己的 `imports` 里加 `AdminAuthModule`。守卫的依赖是在**声明 Controller 的模块**里解析的,不是在提供守卫的模块里。

处理:给该模块补 `imports: [AdminAuthModule]`。这是启动致命错误,进程完全起不来(完整约束见 [82-admin-routes-data.md](82-admin-routes-data.md))。

### 启动报「关系 admin_users 不存在」

表现:

```text
WARN [Bootstrap] 创建默认管理员失败:关系 "admin_users" 不存在
```

原因:初始化代码写在了 `main.ts` 或某个构造函数里。建表在 `DatabaseService.onModuleInit`,而 `main.ts` 顶层代码更早执行。

处理:把需要写表的初始化移到 `onApplicationBootstrap`(时序见 [13-startup-lifecycle.md](13-startup-lifecycle.md),`AdminBootstrapService` 就是这么做的)。

### 端口不合法或被占用

`PORT` 必须是 1–65535 的整数。确认没有旧验证实例仍监听 4500/4720。

## 3. 数据库错误

### `relation does not exist`

常见于重置验证库后没有重启服务。迁移只在应用启动时运行,先重置、再重启(验证流程见 [22-contract-verification.md](22-contract-verification.md))。

### 字段变成字符串

若 `createdAt`、`configVersion` 或 `apkSize` 类型异常:

- 检查列是否错误使用 bigint。
- 检查 int8 parser 是否仍注册。
- 普通大小和版本号应使用 integer(类型规则见 [40-database-overview.md](40-database-overview.md))。

### camelCase 字段为 undefined

检查 SQL 别名是否加双引号:

```sql
AS "songId"
```

### 并发注册偶发 502

检查唯一约束冲突是否在 `UsersRepository` 中翻译为 409/4090。不能只依赖插入前查重。

### 刷新令牌并发使用两次都成功

检查消费逻辑是否仍是单条 `UPDATE ... RETURNING`。拆成 SELECT + UPDATE 会产生竞态(见 [44-database-operations.md](44-database-operations.md))。

## 4. 只读诊断命令

检查端口:

```powershell
Get-NetTCPConnection -State Listen | Where-Object LocalPort -In 4500,4720
```

检查 Node 进程:

```powershell
Get-Process node -ErrorAction SilentlyContinue
```

检查健康响应:

```powershell
curl.exe -i http://127.0.0.1:4500/health
```

检查数据库连通性:

```powershell
psql "$env:DATABASE_URL" -c "SELECT 1"
```

检查所需表是否存在,不读取业务数据:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

检查图片任务统计,不读取提示词和 Key:

```sql
SELECT channel, state, count(*) AS count
FROM image_generation_task
GROUP BY channel, state
ORDER BY channel, state;
```

## 5. 何时停止自行处理

遇到以下情况应暂停写操作并先备份或请求确认:

- 连接串可能指向正式库,但无法确认。
- 需要 DROP、TRUNCATE、删除 APK 或删除 Key。
- 正式库表结构与当前迁移定义不一致。
- 同一个 taskId 对应多个外部账单或疑似重复扣费。
- 热更新最低版本已抬高但没有可下载的全量 APK。
- 回滚需要恢复数据库结构而不仅是替换 `dist/`。
