# 音源账号(酷我/波点)

[返回文档中心](README.md)

最后更新:2026-09-30

酷我、波点这类需要登录态的音源,其凭据由服务端统一持有:管理后台维护账号,播放链路按 `source` 取用,**凭据只进不出**。代码分布在 `src/upstream/`(协议适配与凭据读取)和 `src/upstream/music-source-admin.*`(后台接口);表结构见 [43-database-tables-admin.md](43-database-tables-admin.md) 的 `music_source_account` 一节。

## 1. 两套账号体系,不要混淆

- **酷我(Kuwo)**:账号以纯数字 `uid` + token 标识,历史最久,`source = kuwo`。
- **波点(Bodian)**:独立的一套账号体系,有自己的客户端协议(`bodian.client.ts`)、设备号与短信登录;`source = bodian`。搜索联想、热搜当前只实现波点源(见 [32-api-search-music.md](32-api-search-music.md))。

两者凭据互不通用,排查时先确认说的是哪一个。服务端默认设备号是 `md5("taotao-music-server")`,可用 `BODIAN_DEVICE_ID` 覆盖(见 [21-configuration.md](21-configuration.md))。

## 2. 与播放链路的关系

`music-source.registry.ts` 按注册表把请求分派到对应音源客户端;取址前按 `source` 读取启用中的音源账号凭据:

- **表里没有可用账号时走匿名链路**,代码不报错 —— 表现为「搜索正常但部分内容取不到/被判定不可播」。排查这类问题**先查 `music_source_account` 有没有启用行,再查代码**。
- 播放链路对音源凭据有**进程内缓存,最长 30 秒 TTL 自愈**;管理接口的写操作(创建、PATCH、启停、登录)会主动清缓存,正常情况下立即生效。若超过 30 秒仍不生效,确认改的账号 `enabled = 1`,以及客户端请求的 `source` 与账号的 `source` 一致。

酷我的 KPK 原生签名与波点逐字歌词解析都有向量自检,命令:`npm run verify:kpk`、`npm run verify:lrcx`(见 [20-development-setup.md](20-development-setup.md))。

## 3. 后台接口

`/app/admin/music-sources*` 共 9 条路由:

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/` | `PRIVILEGED_READ_ROLES` | 清单(凭据属个人信息,观察者不可见) |
| GET | `/available` | `READ_ROLES` | 可用账号 |
| POST | `/` | `WRITE_ROLES` | 新建 |
| PATCH | `/:id` | `WRITE_ROLES` | 编辑 |
| PUT | `/:id/enabled` | `WRITE_ROLES` | 启停 |
| POST | `/:id/probe` | `WRITE_ROLES` | 连通性探测 |
| DELETE | `/:id` | `WRITE_ROLES` | 删除 |
| POST | `/sms` | `WRITE_ROLES` | 发短信 |
| POST | `/login` | `WRITE_ROLES` | 短信登录 |

写操作审计 action:`music_source.create` / `update` / `enable` / `disable` / `probe` / `delete` / `sms` / `login`。审计界面中文化的配套要求见 [81-admin-roles-audit.md](81-admin-roles-audit.md)。

短信登录有独立限流桶 `music-source-sms`:每 IP 5 次 + 每手机号 3 次/15 分钟(全量限流表见 [30-api-conventions.md](30-api-conventions.md))。

## 4. 凭据红线

- **token 与完整手机号只进不出**:所有响应只回掩码手机号;审计也只记掩码(`music_source.sms` 的 `target_id` 为空、只记掩码手机号)。
- 酷我 `uid` 必须是**纯数字**,写入时 400/4007 拦截(「账号 ID 必须是纯数字」),否则播放链路取址会失败。
- 探测失败落库 `last_status = invalid` 并把错误写 `last_error`;成功记录 `last_note`(探测到的真实音质)。
- 明文凭据不进日志、不进审计 `detail`、不进任何查询结果(日志红线见 [74-error-codes.md](74-error-codes.md))。

## 5. 常见问题

### 「搜得到放不出」

按顺序查:

1. `music_source_account` 是否有该 `source` 的启用行(表空 = 匿名链路,不是代码坏了)。
2. 账号 `last_status` 是否为 `invalid`,`last_error` 写了什么 —— 先看这两列再决定是否重新登录,不要凭感觉重复探测。
3. 酷我账号 `uid` 是否非纯数字(绕过校验写入的旧数据会让播放链路取到坏凭据),修正是重新登录该账号。
4. 改凭据后是否等满 30 秒缓存 TTL。

### probe 失败但不知道错在哪

`last_status` / `last_error` / `last_checked_at` 三列就是为此设计的:探测结果全部落库,管理列表直接可看。

### 波点「下架」判定异常

波点上游的「下架」判定与客户端 payload 的设备头相关,该行为封装在 `bodian.client.ts`;调整判定逻辑改这里,不要去改 `crypto/dist/` 或注册表。
