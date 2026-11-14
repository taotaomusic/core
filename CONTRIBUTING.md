# 贡献指南（开发流程单一入口）

这份文档是桃桃音乐**改动代码的唯一流程入口**。以前流程散在 `AGENTS.md`、`RELEASE.md`、
`.workbuddy-ai/memory/MEMORY.md`、`VERSIONING.md` 四处，新人要拼四份文件才看得懂；
现在流程部分收敛到这里，**规范细节与踩坑记录仍留在原文档**，本文只给入口和不可省略的硬规则。

| 想知道什么 | 看哪里 |
| --- | --- |
| 怎么改、怎么提交、怎么验收（**本文**） | `CONTRIBUTING.md` ← 当前文件 |
| 项目结构、编码规范、模块边界 | [AGENTS.md](AGENTS.md) |
| 发布与热更新的完整操作与坑 | [RELEASE.md](RELEASE.md) |
| 版本号体系（MAJOR.MINOR 人工维护） | [VERSIONING.md](VERSIONING.md) |
| 历史踩坑的详细证据 | `.workbuddy-ai/memory/` 当日日志、[plans/](plans/README.md) |
| 后端接口与数据层规矩 | [server/README.md](server/README.md)、[server/wiki/](server/wiki/README.md) |
| 客户端模块与符号索引 | [client-code-index.md](client-code-index.md) |

---

## 一、改动的固定六步（强制，顺序不可颠倒）

**任何改动进 `main` 都必须走完整 PR 流程。禁止把本地分支直接 merge 到 main 后推走。**

```bash
# 1. 从 main 拉分支
git switch main && git fetch core --prune && git merge --ff-only core/main
git switch -c <type>/<slug>          # fix/ | feat/ | docs/ | chore/

# 2. 提交改动（逐个文件 add，不要 git add -A）
git add <具体文件>
git diff --cached --stat             # 核对暂存区
git commit -F - <<'EOF'
<type>(<scope>): <祈使句标题>

<问题 / 根因 / 修复 / 验证>
EOF

# 3. 推分支
git push -u core <branch>

# 4. 建 PR（base=main, head=<branch>）—— 这一步就是「把构建交给云端」的开关
gh pr create --base main --head <branch> --title "<type>(<scope>): <描述>" --body-file <路径>

# 5. 等云端构建绿 —— 未绿不得合并（见下节，本地不构建）
gh pr checks <n> --watch
gh pr view <n> --json mergeable,mergeStateStatus

# 6. 合并 + 删分支（合并即推 main → 触发正式构建与发布）
gh pr merge <n> --merge
git fetch core --prune && git merge --ff-only core/main
git branch -d <branch> && git push core --delete <branch>
```

### 这条流程的本质：PR 是云端验证闸门，merge 才是发布开关

`ci.yml` 有意做了**双触发分流**，两者跑的是同一份流水线，区别只在「要不要产出」：

| 触发事件 | 谁触发 | 干什么 | 会不会发布 |
| --- | --- | --- | --- |
| `pull_request` → main | 第 4 步建 PR | 编译 / 测试 / 契约验证 / 加密四端构建 | **不会**。所有发布、删 Release、回写仓库的步骤都被 `github.event_name == 'push'` 挡住 |
| `push` → main | 第 6 步 merge | 同上 + 发 Release、推镜像、出 APK/桌面产物 | **会** |

所以这条流程不是「先走形式再合并」，而是**唯一的验证路径**：

```
本地改代码 → 推分支 → 建 PR（云端开始编译验证，绝不发布）
                            ↓ 绿
                      合并 PR → 推到 main → 云端正式构建 + 发布
```

因为本地不跑构建（见第三节），**PR 上那次 `pull_request` 构建就是你唯一的编译/测试证据**。
没建 PR 直接 merge 到 main，等于跳过验证直接发布 —— 而且因为不产出 PR，事后连记录都没有。

### 为什么必须「先建 PR，再合并」

GitHub 要求 head 相对 base 有**领先提交**。分支一旦被 merge 吸收进 main，`compare/main...<branch>`
返回 `ahead_by=0`，再建 PR 会被 422 拒绝（`No commits between main and <branch>`），
而且**不可逆**。

> 2026-10-06 踩过：`ac3782e`（剥 `<em>` 高亮）与 `240c5ef`（歌词卡片化）两次修复直接 merge 到 main，
> 事后想补 PR 记录，两个分支都已经 `ahead_by=0`，补不回来，只能删分支认账。
> 回滚 redo 会更糟 —— 丢失已验证 sha、可能触发版本号链路副作用。

### 建 PR 用什么通道

| 通道 | 结果 |
| --- | --- |
| **`gh` CLI（推荐）** | ✅ 本机已装 v2.102.0 并登录 `hdppppppp`（scope repo+workflow） |
| SSH 密钥 | ❌ 只承载 `git-upload-pack`/`git-receive-pack`，REST API 认 Bearer token，**两套并行体系** |
| GitHub connector (MCP) | ⚠️ 默认只读，`POST /pulls` 返 403，需 App 开 `Pull requests: Read and write` |
| PAT | ✅ 需手动配；classic 勾 `repo`，或 fine-grained 开 `Pull requests: Read and write` |

⚠️ **`gh` 不在默认 PATH**，每次先 `export PATH="$PATH:/c/Program Files/GitHub CLI"`。

> **文档漂移已修正**：`AGENTS.md` 历史段落里"PR 得用 PAT、connector 只有读权限"的说法**已过时**
> （那是本机还没装 gh 时的结论）。现在优先用 `gh`，PAT 只是没有 gh 时的兜底。

### 合并方式

用 **merge commit**（`gh pr merge <n> --merge`），**不要 squash / rebase**。
合并发生在远端，**本地 main 不会自动前进**，收尾必须手动 `git merge --ff-only core/main`。

> ⚠️ **不要用 `--delete-branch`**。`gh pr merge --merge --delete-branch` 会在合并同时删掉远端分支，
> 但本地分支还在、`git remote prune` 又要单独跑；分两步做更可控（先 merge，确认 main 前进了，
> 再删两端分支）。

### PR 分支落后于 main 时

并发开发时别的 PR 先合了，你的分支就会落后。**不要用 `git merge main` 把 main 拉进功能分支**
（会制造一个多余的合并提交，PR diff 变脏）。用 rebase：

```bash
git fetch core --prune
git rebase core/main
git push --force-with-lease core <branch>   # 用 --force-with-lease，不要 --force
```

⚠️ rebase 会改写提交 sha，**PR 上已经跑完的构建结果会失效并重新跑一遍** —— 这是预期的。
推用 `--force-with-lease`（远端被他人更新时会拒绝，比 `--force` 安全）。

### 只在文档/配置改动时：构建会被 skip

只改 `.md` / `.gitignore` / `AGENTS.md` 这类文件时，各构建 job 会按 `paths-filter` 全部 `skip`，
run 显示 success 但没编译任何东西 —— **这是正常的，不是配置坏了**。此时「CI 绿」不代表
代码被编译过，因为本来就没有代码要编译。

⚠️ **例外：`.github/**` 不在任何 paths-filter 里**。改工作流本身时必须显式全量：
在**提交信息里写 `[full]`**，或手动 dispatch 勾 `full`。否则全部 job 跳过、
run 显示 success 却什么都没验证 —— 这是最容易自我欺骗的一种绿。

---

## 二、分支与远端拓扑

- 本地只有 **`main` 一个分支**，只有 **`core` 一个远端**（`git@github.com:taotaomusic/core.git`，SSH）。
- 旧的 4 个远端（GitHub 镜像 `music`/`music-server`/`tools` + gitee `origin`）已于 2026-10-18
  从本地配置移除，远端仓库本身仍在。**不要写 `git push origin`。** 历史 SHA 见
  `.workbuddy-ai/memory/branch-backup-2026-10-18.txt`。
- 仓库已**公开**（PSK 改为后端动态下发，产物不含密钥）。

---

## 三、★ 硬规则：本地不构建，构建交给云端

> **这是本项目的硬规则，不是建议。** 除非是交付产物，**一律不在本地跑构建或测试**，
> 构建与验证统一交给 GitHub Actions —— 也就是第一节第 4、5 步的「建 PR → 等云端绿」。

### 为什么

F: 盘长期紧张（2026-10-18 剩 29G，曾低至 3G），而各类构建产物动辄上 GB：
Rust `target/` 一次编译就能从 902MB 涨到 1.4GB；Gradle 依赖缓存、Node 的 `node_modules`、
wasm 全套工具链同理。**本地跑一遍构建的代价是几十 GB 级磁盘 + 大量时间，而 CI 每次都能干净重跑。**

> 2026-09-25 违反过一次：本地跑 crypto 构建，`target/` 从 902MB 涨到 1.4GB，
> 而当时 F: 只剩 3.1GB。这是这条规则被写成硬规则的原因。

### 唯一例外：交付产物

**Android Release APK 是交付物，必须本地构建**（它不在 CI 的交付路径上，需要你本机签名）。
除它之外没有任何例外。

### 各模块的构建归属

| 改动范围 | 验收标准 | 谁跑 |
| --- | --- | --- |
| `crypto-src/**` | fmt + clippy `-D warnings` + 单测 + 冒烟 15/15 | **云端**（本地不跑） |
| `server/**` | `server/tools/verify-contract.mjs` **全绿**（约 300 项，以实际输出为准） | **云端**；本地仅在需要定位具体失败项时跑 |
| 客户端编译（`shared`/`player-ui`/`webApp`/`desktop`） | 三端编译通过 | **云端** |
| `androidApp` Release APK | 签名包生成，报**绝对路径 + 版本号 + 构建结果** | **本地**（唯一例外） |
| `desktop/**` | Tauri 产物，走 CI `desktop` job | **云端** |
| `.md` / `.gitignore` | 无构建验收，但 PR 仍需走完六步 | — |

### 确实需要在本地跑时

只允许两种情形：**① 交付 APK ② 云端构建失败、需要本地复现定位**。做法：

- **必须把缓存/输出指向系统 temp**，且用完立即清理，不要落在工程目录里：
  ```bash
  # Rust：target/ 指向系统 temp
  CARGO_TARGET_DIR="${TEMP:-/tmp}/taotao-cargo-target" cargo build ...
  # 用完清掉
  rm -rf "${TEMP:-/tmp}/taotao-cargo-target"
  ```
- **Gradle** 报 `fileHashes.lock (拒绝访问。)` 时，换独立缓存目录，
  **不要杀用户的 daemon**（本机常年有 3 个以上 Gradle daemon 在跑）：
  ```bash
  ./gradlew <task> --project-cache-dir="${TEMP:-/tmp}/taotao-gradle-verify"
  ```
- `cargo fmt --all -- --check` 是**例外中的例外** —— 它不编译、不写 `target/`、磁盘开销为零，
  可以放心本地跑。发现不合规直接 `cargo fmt --all` 修（只动 rustfmt 认为不合规的地方）。
  **`clippy` 和单测要编译，那就别在本地跑**，改完 push 让 CI 报。

### 看云端构建结果

```bash
export PATH="$PATH:/c/Program Files/GitHub CLI"   # gh 不在默认 PATH
gh pr checks <n> --watch                          # 盯着 PR 上的构建
gh run list --limit 10                            # 看最近的 run
gh run watch <run-id>                             # 盯某一次
```

判定标准：

| 状态 | 含义 |
| --- | --- |
| `success` | ✅ 过 |
| `skipped` | ✅ 过（`paths-filter` 未命中的**正常**结果，见第一节末） |
| `mergeable_state=unstable` | ⏳ 还有 check 在跑，等 |
| `mergeable_state=clean` | ✅ 全绿，可合 |

⚠️ **「绿灯」≠「走了正确分支」**：要同时看两个判据 ——
① check-run 注解（`echo "::warning::..."` 会变成 annotation，
`gh api <job.check_run_url>/annotations` 免鉴权可读）
② 产物体积（`server-dist.zip` 12.8MB ≈ 播放器 11.67 + 后端 1.1；走占位会掉到 ~1.2MB）。

⚠️ **改 `.github/**` 必须用 `[full]`**：它不在任何 `paths-filter` 里，只改工作流的提交会让全部
job 跳过，run 显示 success 但**什么都没验证**。在提交信息里写 `[full]`，或手动 dispatch 勾 `full`。

---

## 四、版本号体系（两套，别搞混）

项目有**两个互不相干的版本号体系**：

### A. 产品版本 `VERSION`（人工维护，SemVer 按接口影响定档）

根目录 `VERSION` 只写 `MAJOR.MINOR`，**人工改**：

| 变更类型 | 动作 |
| --- | --- |
| 后端接口**破坏性**变化（旧客户端必须同步升级） | 提 MAJOR（`1.0` → `2.0`） |
| 后端接口**兼容新增/变化** | 提 MINOR（`1.0` → `1.1`） |
| **仅 UI / 前端**变化（不动接口契约） | **不用改** —— PATCH 由 CI 用 `GITHUB_RUN_NUMBER` 自动填 |

最终版本 = `<VERSION>.<构建号>`（如 `VERSION=1.0` 时构建出 `1.0.408`）。各端落地：
Tauri 写 `tauri.conf.json`、后端写 `package.json`、Docker 镜像 tag 用它。

### B. Android `versionCode`（`version.properties`，机器维护，单调递增）

| | 规则 |
| --- | --- |
| 谁递增 | `androidApp/build.gradle.kts` 的 `incrementVersion`，挂成 `assembleDebug`/`assembleRelease` 的 `finalizedBy` |
| 登记发布时读哪 | **只能读 `output-metadata.json`** —— 构建结束时 `version.properties` 已比刚产出的包大 1 |
| 能不能回滚 | **绝对不能**。跳号无害，重号是事故 |

⚠️ **重号的后果**：登记接口是 `ON CONFLICT DO UPDATE`，会**静默覆盖**已发布记录的 sha256 ——
已装该版本的客户端看到 `available=false` 更新推不出去，没下完的客户端因 sha 不匹配校验失败。
只能往前发新版本并把被污染的版本停用。

> **文档漂移已修正**：`RELEASE.md` §2② 里"云端同样在递增这个号、会回写 `version.properties` 到
> music 仓库"是**旧三仓时代**的描述。单仓 core 时代（`ci.yml:459` 注释）**`version.properties`
> 是基线、云端在 runner 内改完即弃、不回写仓库**。所以本地只需保证"不回滚"这一条。

⚠️ 云端 Android 版本用 `基线 VERSION_CODE + GITHUB_RUN_NUMBER` 派生，避免每次干净 checkout
产出同一个号。

---

## 五、发布流程（详见 RELEASE.md）

三种发布物的入口不同，**不要在 PR 里做发布动作**（CI 的发布步骤都被 `github.event_name == 'push'` 挡住）：

| 发布物 | 触发 | 产物 |
| --- | --- | --- |
| APK 整包更新 | 管理后台或 curl 登记 + 放量 | Release `latest`（每次覆盖） |
| DEX 热修复补丁 | `:patch:buildPatch` → 登记 | 只能改 `data`/`player`/`update` 包 |
| 后端 dist | `npm run build` → 上传 | Release `server-dist-latest`、ghcr.io 镜像 |
| 桌面端 | CI `desktop` job | Release `desktop-latest`，应用内签名更新 |

**热修复的四条铁律**（`RELEASE.md` §5）：① 不要在 `dispatch` 里调用被自己接管的方法（无限递归）
② 不要 new 宿主已有的类并跨边界传递（`ClassCastException`）③ 不要改方法签名 ④ **改完补丁必须
同步改源码**，否则下个整包版本 bug 回归。

**强制更新的顺序永远是**：先把目标版本放到 100%，再抬高 `min-version` 下限 —— 反了会变砖
（接口有 409/4091 守卫拦着）。

**加密层部署顺序**：先发客户端、再上服务端白名单。旧 APK 对强制路径会收 426「请升级客户端」，
属设计内强升机制，但意味着服务端这版上线后旧 APK 的收藏/歌单/播放上报**立即不可用**直到完成更新。

---

## 六、不能破的客户端契约（改后端时逐条核对）

线上有装机客户端，以下每一条都能**悄无声息**弄坏线上功能。完整表见
[RELEASE.md §七](RELEASE.md)，最常踩的几条：

| 规则 | 破坏后的表现 |
| --- | --- |
| 访问令牌无效必须 **401，不能 403** | 客户端只对 401 触发续期重放，403 会让所有接口失去自动续期 |
| `/app/bootstrap` **永远不能返回 401** | 客户端刻意带**已过期**令牌调它；返 401 会让热更新通道静默失效 |
| `/search` 必须是**裸 NDJSON**，不能套信封 | 客户端取不到 `type` → 「搜不到东西」且无报错 |
| 错误响应必须含**顶层字符串** `message` | NestJS 校验失败的 `message` 是数组，会被原样显示成 `["..."]` |
| `accessToken`/`refreshToken` 与 `user` 必须**平铺**在 `data` 下 | 客户端用 `getString` 硬取，包一层就抛异常 |
| SQL 别名必须**加双引号**（`AS "songId"`） | PostgreSQL 折叠成小写，客户端读不到 → 所有歌显示未收藏、无报错 |
| `coverUrl`/`apkUrl` 必须**绝对 https 且免鉴权** | 客户端直接交给图片库和下载器，不带 Authorization |
| 业务错误不能借用 401 | 「没有歌词」走 502；借用 401 会触发重放并把用户踢回登录页 |

**改后端前先读 [RELEASE.md](RELEASE.md) §七全表。**

---

## 七、几类改动的专项规矩

### 后端

- 构建**必须用 `tsc`**，开发用 `ts-node`。**不能用 esbuild / tsx** —— 不支持
  `emitDecoratorMetadata`，NestJS 构造器注入拿不到 `design:paramtypes`。
- 数据层改动必须跑契约脚本，且**用独立验证库**（`music_verify`），不能对着正式库跑。
- 新增路由默认受全局令牌守卫保护；公开路由**必须显式标 `@Public()`**。
- 管理端：守卫**挂在方法上**，不能挂类上（类级会把 `login` 一起 401 掉）；
  用 `AdminAuthGuard`/`RolesGuard` 的模块**必须自己 `imports: [AdminAuthModule]`**，
  漏导入会在启动时抛 `UnknownDependenciesException`；写操作必须标 `@RequireRole(...WRITE_ROLES)`
  并写审计 —— **漏标等于没有权限校验且不会报错**。

### 客户端 UI

- 新代码**一律走 token**，不要再写 `.sp`、`RoundedCornerShape(N.dp)` 或裸间距字面量。
- 命名硬规则：`Taotao*` = token 对象（只有数值，`playerui/theme/`）；
  `Shared*` = 复合组件（只有结构，`playerui/` 根包）。**不要把组件命名成 `TaotaoCard`。**
- 标题只有 `SharedSectionHeader`，返回按钮只有 `SharedBackButton` —— 不要手写、不要内联。
- 中文字体三约束（有测试兜着）：字重只用 `400/500/700`；`letterSpacing` 一律 `0.sp`；
  `lineHeight` 取 1.3–1.55 倍字号的偶数 sp。
- ⚠️ `MaterialTheme.typography` **没有 `caption` 槽位** —— 写它会编译失败而迁移脚本发现不了。
- **新增整页必须同步三处**：`switchTab`、`AnimatedContent` 的 `targetState`、`BackHandler`。
  漏 `switchTab` 的表现是"点了底部标签却还停在原页面"（踩过两次）。

### 音源

- 分派只有 `MusicSourceRegistry.of()` 一处，**禁止 `if (source === "kuwo")`**，差异用适配器能力标记。
- 新增音源五步：① `MUSIC_SOURCES` 加名 ② 写适配器注册 ③ `allowedMediaHosts` 精确加 CDN（不通配）
  ④ 酷我不进 `AGGREGATED_SOURCES` ⑤ `song_share.source` 的 CHECK 按 `migrations.ts` 换写法。
- ⚠️ **「凭据写进去了」≠「凭据会被读到」**：读侧条件是
  `WHERE source=$1 AND enabled=1 AND token<>''`，每条刷新凭据的写入路径都要保证
  `enabled=1` 且 `token<>''`（`upsertByLogin()` 曾漏 `enabled` → 「登录成功但按匿名走」）。

---

## 八、提交信息与操作纪律

- 提交信息用**简洁祈使句 + 模块范围**，如 `ui: 增加播放器控制栏`。
- **把「为什么」写进提交信息** —— 本项目注释里大量记录了"为什么不是另一种做法"，那是提交信息
  最有价值的部分，不要只抄文件名。
- 工作区积压大批改动时，按主题拆分提交（技能 `commit-split-by-theme`），**不要 `git add -A`**。
- **同一条消息里不要对同一个文件发多个编辑** —— 并发读改写会丢更新，且工具仍报 success。
  改同一文件必须串行。
- 交付 UI 变更时说明影响模块、验证命令和 APK 输出路径；有界面变化应附截图或录屏。
- 改动了视觉数值但**没截图验证的，必须在交付说明里显式列出"需要你装上确认"**，不要声称已验证。

---

## 九、已沉淀的技能（配方，照着做就不会错）

项目在 `.workbuddy-ai/skills/` 下积累了 8 个技能，每个都是踩坑后提炼的操作配方：

| 技能 | 什么时候用 |
| --- | --- |
| `client-build-verify` | 构建/验证客户端；token 约定；两条截图验证链路；工具链升级 |
| `server-build` | 产出后端 `server/dist`；绕开沙箱批量删除拦截 |
| `server-contract-verify` | 起独立验证库跑通契约脚本 |
| `commit-split-by-theme` | 工作区积压几十上百文件，按主题拆提交 |
| `repo-main-consolidate` | 收敛到单一 main、安全切分支、清误提交的缓存 |
| `crypto-release-pipeline` | 触发/诊断加密层发布流水线、读 CI 注解 |
| `upstream-protocol-probe` | 逆向接入第三方音源协议 |
| `github-pr-flow`（`plugins/` 下） | PR 六步流程与建 PR 失败排错 |

---

## 十、本次整合的订正与固化

### 修正的两处文档漂移

| 位置 | 过时说法 | 当前事实 |
| --- | --- | --- |
| `AGENTS.md` 历史段落 | "PR 要走 PAT，connector 只有读权限" | 本机**已装 `gh` v2.102.0** 并登录，PR 直接用 `gh`；PAT 只是兜底 |
| `RELEASE.md` §2② | "云端同样在递增 `version.properties`、会回写 music 仓库" | 单仓 core 时代**不回写**（`ci.yml:459`："version.properties 保持基线"），只需保证本地不回滚 |

### 固化为硬规则的两条

| 规则 | 原先状态 | 现在 |
| --- | --- | --- |
| **本地不构建、交给云端** | `MEMORY.md` 里的原则性约定 | 本文第三节，标注为**硬规则**；仅 Android Release APK 例外 |
| **PR 是云端验证闸门，merge 才是发布开关** | 只在 `ci.yml` 注释里，未成文 | 本文第一节，讲清 `pull_request`（只验证）与 `push`（才发布）的分流 |

这两处漂移已在本文对应章节给出订正口径。原文档尚未改写 —— 需要的话可以同步修一遍。
