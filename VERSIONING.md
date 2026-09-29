# 版本规范（SemVer，按对后端接口的影响定档）

产品版本 `MAJOR.MINOR.PATCH`，档位由**变更类型**决定：

| 变更类型 | 版本位 | 示例 |
| --- | --- | --- |
| 接口大变化（破坏性，客户端必须同步升级） | **MAJOR** | 1.0.0 → 2.0.0 |
| 接口新增 / 兼容变化（旧客户端仍可用） | **MINOR** | 1.0.0 → 1.1.0 → 1.2.0 |
| 仅 UI / 前端变化（不动接口契约） | **PATCH** | 1.0.1 → 1.0.2 |

## 单一来源

- 仓库根 **`VERSION`** 只写 `MAJOR.MINOR`（如 `1.0`），**人工维护**：
  - 后端接口做**破坏性**改动 → 提 MAJOR（`1.x` → `2.0`）。
  - 后端接口**兼容新增/变化** → 提 MINOR（`1.0` → `1.1`）。
- **PATCH 由 CI 自动填**（`GITHUB_RUN_NUMBER`）：每次构建自增，天然覆盖「仅 UI/前端变化」这一档，无需手改。

所以最终版本 = `<VERSION>.<构建号>`，例如 `VERSION=1.1` 时构建出 `1.1.87`。

## 各端落地

- **桌面（Tauri）**：CI 构建前把 `tauri.conf.json` 的 `version` 写成 `<VERSION>.<run_number>`；
  自动更新（updater）据此判断新版本。
- **后端**：CI 把 `package.json` 的 `version` 写成 `<VERSION>.<run_number>`；Docker 镜像 tag 用它。
- **安卓**：`versionName` 应跟随同一个 `MAJOR.MINOR`（发版时对齐）；`versionCode` 仍是 Play 要求的
  单调整数，由 `incrementVersion` 维护，与产品版本号是两回事（见 RELEASE.md）。

## 发版动作

1. 若接口有破坏性/兼容变化，改根 `VERSION`（MAJOR 或 MINOR）；仅前端改动不用动它。
2. 提交推送 → CI 自动补 PATCH（构建号）出版本，发到各自滚动 Release / 触发自动更新。
