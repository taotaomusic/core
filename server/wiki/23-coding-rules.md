# 编码规则与提交流程

[返回文档中心](README.md)

最后更新:2026-09-30

本文汇总写后端代码时的结构性规则:新增模块的步骤、DTO 校验、错误处理、提交前检查。模块之间的边界与依赖规则见 [11-architecture-modules.md](11-architecture-modules.md)。

## 1. 新增模块

建议结构:

```text
src/example/
├─ dto/
│  └─ create-example.dto.ts
├─ example.controller.ts
├─ example.service.ts
├─ example.repository.ts
└─ example.module.ts
```

步骤:

1. 创建 Module、Controller 和必要的 Service/Repository。
2. 在 `AppModule.imports` 注册模块。
3. 默认依赖全局访问令牌守卫,不要重复实现鉴权。
4. 需要管理员会话鉴权时用 `@AdminGuarded()`(方法级),并且**必须在 `imports` 里加 `AdminAuthModule`**,否则启动报 `UnknownDependenciesException`。控制器里既有公开又有受保护方法时,守卫只能挂方法,不能提到类上。
5. 需要公开时添加 `@Public()`。
6. 需要独立限流时扩展 `RateLimitBucket` 和 `RateLimitService`。
7. 普通 JSON 返回领域数据,由 `EnvelopeInterceptor` 包装。
8. 流式或二进制响应添加 `@RawResponse()` 并自行结束响应。
9. 更新 [00-code-index.md](00-code-index.md)(路由计数与模块地图)和对应专题文档。

## 2. DTO 与参数校验

全局 `ValidationPipe` 开启 `transform`。DTO 使用 `class-validator`:

```typescript
export class ExampleDto {
  @IsString()
  name: string;

  @IsOptional()
  @IsInt()
  count?: number;
}
```

认证模块刻意不用 DTO 处理部分输入,因为客户端对认证 4xx 有特殊行为。修改认证参数校验前先读源码注释和 [31-api-auth-user.md](31-api-auth-user.md)。

DTO 校验失败由全局过滤器压平成单个字符串(中文分号连接),顶层 `message` 必须非空,这层处理不能移除(见 [30-api-conventions.md](30-api-conventions.md))。

## 3. 错误处理

业务错误使用 `ApiErrors` 或 `ApiException`:

```typescript
throw ApiErrors.badRequest(4007, "参数不合法");
throw ApiErrors.notFound(4042, "任务不存在");
throw ApiErrors.upstream("上游服务不可用");
```

不要直接返回 `{ code, message }`,也不要在 Controller 中捕获所有异常后统一改成 200。业务码语义与总表见 [30-api-conventions.md](30-api-conventions.md),新增业务码前先搜索现有使用点,不能复用语义不同的旧码。

第三方错误处理原则:

- 用户输入错误可映射为 400。
- 第三方任务不存在可映射为 404。
- 第三方限流可映射为 429。
- 第三方 API Key、余额、网络或内部故障映射为 502/503。
- **第三方 401 不能透传为客户端 401** —— 客户端收到 401 会触发令牌刷新,把上游故障变成「被登出」。

## 4. 提交前流程

```powershell
cd server
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720
git diff --check
git status --short
```

检查:

- 没有 `.env`、API Key、密码或测试 APK 被暂存。
- TypeScript 正常缩进,没有压缩源码;生产源码不得压缩、混淆或以单行形式提交。
- 数据层变化已经过独立 PostgreSQL 验证库验证(见 [22-contract-verification.md](22-contract-verification.md))。
- 文档与错误提示使用简体中文。
- 没有顺手提交工作区内其它模块的改动。
- 路由、表、配置变化已同步 [00-code-index.md](00-code-index.md) 与对应专题。
