# 排障:热更新与契约验证

[返回文档中心](README.md)

最后更新:2026-09-30

发布契约与操作流程见 [61-release-android.md](61-release-android.md);验证流程见 [22-contract-verification.md](22-contract-verification.md)。

## 1. 热更新(Android)

### 登记后客户端看不到版本

检查:

- `rollout_percent` 是否仍为 0。
- 发布是否 `enabled = 1`。
- 客户端 versionCode 是否小于发布版本。
- SDK 是否满足 `min_sdk`。
- 灰度主体是否命中。

### 安装后仍反复提示更新

登记时可能错误读取了构建后的 `version.properties`。实际 APK 版本必须取自 `output-metadata.json`(见 [61-release-android.md](61-release-android.md))。

### APK 下载永久卡住

核对数据库 `apk_size` 与文件真实字节数;检查 Range 206、`Content-Range` 和越界 416。

### `X-Latest-Version-Code` 不更新

只有 `rollout_percent = 100` 且启用的最高版本进入响应头。调整放量后确认缓存已失效。

### 最低版本抬高了但没有全量包

这是必须立即回滚处理的事故:抬高下限前必须已有版本号不低于目标、启用且放量 100% 的发布(顺序见 [61-release-android.md](61-release-android.md))。

## 2. 契约验证失败

失败定位的完整流程(环境变量、重置顺序、常见假失败)见 [22-contract-verification.md](22-contract-verification.md)。速记:

先区分:

- 代码真的破坏契约。
- 外部腾讯音乐接口临时波动。
- 验证库未正确重置或服务未重启。
- 4720 端口运行的是旧进程。
- 未显式调大 `ADMIN_RATE_LIMIT`(如 `1000`):脚本一次运行要打上百次管理接口,管理端限流桶(默认 60 次/15 分钟)会在中途命中 4290,失败位置随请求顺序漂移,看起来像业务坏了。

推荐顺序:

1. 停止旧验证实例。
2. 重置 `music_verify`。
3. 用最新源码启动 4720。
4. 再运行契约脚本。
5. 只针对失败分组定位,不修改正式库。

CI 上的同一套验证见 [62-ci-cloud-build.md](62-ci-cloud-build.md)。

## 3. 文档与索引漂移

发现文档写了不存在的路由或漏掉新模块时,先运行:

```powershell
codegraph index server
codegraph status server --json
codegraph query --path server --kind route --limit 200 --json ""
```

再对照 [00-code-index.md](00-code-index.md) 的路由和表清单。不要通过 `grep` 猜测 Controller 是否已注册,也不要在未确认索引状态时直接修改契约文档。
