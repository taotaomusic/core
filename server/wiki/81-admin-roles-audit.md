# 管理后台:角色、IP 白名单与审计

[返回文档中心](README.md)

最后更新:2026-09-27

登录与 2FA 见 [80-admin-auth-login.md](80-admin-auth-login.md);路由表见 [82-admin-routes-data.md](82-admin-routes-data.md)。角色矩阵的唯一出处是 `server/src/admin-auth/admin-roles.ts` —— **新增管理接口时从这里取常量,不要手写角色数组**(手写迟早会把 `viewer` 放进写权限)。

## 1. 三种角色与四组常量

三种角色,定义在 `admin_users.role` 的 CHECK 约束里。权限分成三块:**后台自身**(管理员账号、审计日志、IP 白名单)、**个人数据**(用户资料与听歌历史)、**业务管理接口**(发布、Windows 发布、公告、图片 Key):

| 角色 | 后台自身 | 个人数据 | 业务管理接口 |
| --- | --- | --- | --- |
| `super_admin` | 全部(含管理员增删改、IP 白名单) | 读 | 读写 |
| `admin` | 读管理员列表与审计日志 | 读 | 读写 |
| `viewer` | 无 | **无** | 读,写操作 403/4030 |

分组常量:

| 常量 | 取值 | 用途 |
| --- | --- | --- |
| `ADMIN_ROLES` | `super_admin` / `admin` / `viewer` | 创建管理员时的合法角色集合 |
| `READ_ROLES` | 三种角色 | 业务管理接口的读接口 |
| `WRITE_ROLES` | `super_admin` / `admin` | 业务管理接口的写接口 |
| `PRIVILEGED_READ_ROLES` | `super_admin` / `admin` | 后台自身 + 个人数据的读接口 |

`READ_ROLES` 与 `PRIVILEGED_READ_ROLES` 必须分开。后者覆盖四处:

- **审计日志**会暴露「谁在什么时候改了什么」。
- **管理员列表**会带出 `ip_whitelist` 与 `last_login_ip`。
- **用户接口**返回的是个人数据:`GET /app/admin/users` 带 `email` 与听歌统计,`GET /app/admin/users/:id/playback` 带逐首歌的播放次数和时间戳。
- **音源账号清单**会带出音源账号的凭据状态与手机号掩码(见 [53-feature-music-sources.md](53-feature-music-sources.md))。

观察者能进后台是为了看发布状态这类运营数据,不是来看别人的个人信息和操作记录。`/app/admin/users` 下的**读写**都要求 `admin` 及以上,前端「用户与统计」页签对观察者隐藏。

**返回个人数据的读接口用 `PRIVILEGED_READ_ROLES`,不要用 `READ_ROLES`。** 新增接口时先问一句「这个响应里有没有别人的个人信息」,有就用 `PRIVILEGED_READ_ROLES`;改这类接口要同时改前端 `App.vue` 的页签可见性(见 [83-admin-frontend.md](83-admin-frontend.md))。

## 2. RolesGuard 的行为

`RolesGuard` 读 `@RequireRole(...)` 元数据:

- 方法或类上**没有** `@RequireRole` → 放行(只要求已认证)。**所以漏标等于没有权限校验,而且不会报错** —— 这是这套机制最容易出事的地方,不会报错、也不会被类型检查发现。
- 有标注但不满足 → 403/4030,消息里带上需要的角色名。
- 完全没有身份 → 401/4013。

`super_admin` 在 `RolesGuard` 里直接放行,不必逐个接口列出。

`RolesGuard` **抛异常**而不是返回 `false`。返回 `false` 会被 Nest 变成 403 且不带业务码,客户端拿不到可判断的错误码;抛 `ApiErrors` 才能落到统一信封里。

非超管调用 `GET /admin/auth/users` 时,`trimAdminRow()` 会裁掉 `ip_whitelist` 和 `last_login_ip` 两列。这不是显示优化,而是防止普通管理员读到别人的网络位置。

### 写操作的三条自保护规则(400/4000)

- 不能降级或禁用当前登录的账号。
- 不能删除自己。
- 不能降级、禁用或删除**最后一个**在用的超级管理员(`assertNotLastSuperAdmin`)。

## 3. IP 白名单与客户端取址

`admin_users.ip_whitelist` 是可空文本列,按换行/逗号分隔存一组 IP。为空表示不限制;非空时 `assertIpAllowed()` 要求当前请求来源命中其中一项,否则 403/4030「当前 IP 不在白名单中」。

登录链路和 2FA 第二步都会校验白名单,所以伪造票据也无法绕过。

**取址是这套机制唯一的软肋。** `X-Forwarded-For` 是客户端可写的请求头,无条件采信它等于把白名单变成摆设。因此:

```text
TRUST_PROXY 未设置或不是 1/true
  → 用 socket.remoteAddress,忽略 X-Forwarded-For

TRUST_PROXY=1 或 true
  → 取 X-Forwarded-For 的第一个值
```

只有确实部署在可信反向代理之后才设 `TRUST_PROXY=1`,并且代理必须自己覆写而不是追加 `X-Forwarded-For`。直接暴露到公网时保持默认关闭。

当前白名单只做**精确字符串匹配**:不支持 CIDR 网段,也不做 `::ffff:192.168.1.1` 这类 IPv4-mapped 前缀的归一化。写白名单时要填写服务端实际看到的地址格式。管理接口在 `GET/POST /admin/auth/ip-whitelist/:adminId`,仅 `super_admin`(见 [82-admin-routes-data.md](82-admin-routes-data.md))。

## 4. 操作审计

`admin_audit_log` 记录 `action`、`target_type`、`target_id`、`detail`、`ip_address`、`user_agent` 和 `created_at`。`detail` 是 JSON 字符串,只放结构化摘要,不放长文本(例如公告正文不入审计)。

**管理端写操作必须写审计**:注入 `AdminAuditService`,在操作成功之后调用。漏写不会报错,但 `admin_audit_log` 里就查不到这次操作。

### 后台自身的 action

| action | 触发点 |
| --- | --- |
| `auth.login` | 本地密码登录成功 |
| `auth.login_ldap` | LDAP 登录成功 |
| `auth.login_totp` | 通过 TOTP 第二步登录成功 |
| `auth.change_password` | 修改自己的密码 |
| `auth.totp_enable_requested` | 生成 2FA 密钥(尚未启用) |
| `auth.totp_enabled` | 动态码校验通过,2FA 正式生效 |
| `auth.totp_disabled` | 关闭 2FA |
| `admin.create` / `admin.update` / `admin.delete` | 管理员增删改 |
| `admin.ip_whitelist` | 修改 IP 白名单 |

### 业务管理接口的 action(`target_type` 见括号)

| action | 触发点 |
| --- | --- |
| `release.publish` / `release.rollout` / `release.min_version` | Android 发版、放量、抬高下限(`release`) |
| `release.patch_publish` / `release.patch_rollout` | 热修复补丁登记与放量(`patch`) |
| `release.config_set` / `release.config_remove` | 远端配置写入与删除(`remote_config`) |
| `announcement.create` / `update` / `set_enabled` / `set_pinned` / `delete` | 公告生命周期(`announcement`) |
| `user.set_disabled` / `user.delete` | 禁用与删除普通用户(`user`) |
| `image_key.import` / `image_key.delete` | 图片 Key 导入与删除(`image_key`) |
| `open_api_key.create` / `set_enabled` / `revoke` | 开放 API Key 生命周期(`open_api_key`,只记 `keyPrefix`) |
| `music_source.create` / `update` / `enable` / `disable` / `probe` / `delete` | 音源账号生命周期(`music_source_account`) |
| `music_source.sms` / `music_source.login` | 音源短信与登录;`sms` 的 `target_id` 为空、只记掩码手机号 |

注意 `logout` **不写审计** —— 退出接口没有凭据也能调用,记一条没有操作人的记录没有意义。

### 正确的写法

业务控制器不要直接注入 `AuditLogRepository`,用 `AdminAuditService`:

```ts
await this.audit.record(request, "release.rollout", "release", `${channel}#${body.versionCode}`, {
  channel, versionCode: body.versionCode, percent, enabled: body.enabled,
});
```

它统一处理两件事:把非法/缺失的操作人 id 收敛成 `null`(不让它撞 `admin_users` 外键),以及只在 `TRUST_PROXY` 开启时才采信 `X-Forwarded-For`。**只在操作成功之后调用** —— 控制器抛异常时这行不会执行,审计记的是「发生了什么」,不是「尝试了什么」。

图片 Key 的审计只记 `maskedKey`,明文连审计表也不落;音源账号同理(掩码手机号)。

**审计写入失败不能把主流程带崩。** 登录、改密码这些操作先完成业务动作再写日志;日志表故障时应该只影响审计完整性,不应该让管理员登不进来。代价是审计与业务不在同一事务:日志表故障时业务已生效,重试可能重复操作(见 §6 已知限制)。

## 5. 审计日志查询与界面

`GET /admin/auth/audit-log` 支持 `adminId`、`action`、`limit`(1–200,默认 50)、`offset` 查询参数,按时间倒序返回。**筛选是精确匹配**,action 名写错会静默查空。

`admin_id` 为 `null` 表示**原管理员已被删除**(外键是 `ON DELETE SET NULL`,历史审计必须保留),这是当前唯一会产生无归属记录的原因。

`AuditLogViewer.vue` 负责把库里「域名.动作」式的英文 action 翻译成中文展示:

- `ACTION_GROUPS` 按域分组(登录与账号 / 管理员 / 公告 / 客户端版本 / 音源账号 / 密钥 / 用户),既是筛选下拉的数据源,也派生出整张动作名翻译表;未收录的 action 回退显示原文。
- `TARGET_TYPE_LABELS` 翻译 `target_type`(如 `music_source_account` → 音源账号)。
- `DETAIL_KEY_LABELS` / `DETAIL_VALUE_LABELS` 把 `detail` JSON 还原成「中文键名:中文值」一行文本(布尔转是/否、字节数转 MB、放量加 %),非法 JSON 原样展示。

**维护规则:后端新增审计 action 时,必须同步在前端 `ACTION_GROUPS` 补一行**,否则界面上只会显示英文原文。`actionTag()` 按 delete/revoke/remove/disable 等关键词上色,新动作自然落入默认样式,无需单独登记。

## 6. 已知限制(与角色审计相关)

- 审计写入与业务操作不在同一个事务里:先做业务、后写日志;重试可能造成重复操作。
- `admin_audit_log` 没有留存或归档策略,全部管理端写操作(业务域加后台自身共 30+ 个 action)持续写入,表只增不减。
- 退避只按账号计数,不按「账号 + 来源地址」:同一 NAT 出口下的其它管理员不受影响,但一个被锁的账号会让所有试图登录它的人一起等 —— 这是有意的取舍(防止换 IP 绕过)。
- 前端的写按钮没有按角色隐藏:观察者打开发布、公告、设置页仍能看到按钮,点了才会收到 403。服务端是权威,但交互上可以再收敛。**例外是「用户与统计」** —— 该页签对观察者隐藏,因为它对应的读接口本身就不放行,留着只会点进一片报错。
- `admin_users.email` 列在界面上没有编辑入口(后端已支持)。
