# APK 加固（XopProtector）

发布 APK 在签名后追加一层 XopProtector 0.7.9 加固：业务 DEX 整体加密，核心业务类
使用 True-VMP（PVM2 解释器）执行，并启用 RASP。Android 源码、UI 层、热修复与更新
链路都不改；加固只发生在构建产物上。

## 1. 边界

- **做**：加固 `androidApp` 的 release/debug APK；输出独立的 `*-protected.apk`。
- **不做**：不覆盖 canonical APK。`patch/build.gradle.kts` 的 `printMethodKeys` 需要
  从未加固的 `androidApp-release.apk` 读取 `classes*.dex` 的插桩方法键，覆盖会让
  补丁工具失效。
- **不做**：不加密 SO、assets、res。`data/crypto` 的 JNI、Compose 资源、Room 预置库
  保持原样，避免加载路径和资源读取回归。
- **不做**：不把 `ui/`、`playerui/`、`hotfix/`、`update/`、`data/db/`、`data/im/`、
  `crypto/`（传输层加密）纳入 True-VMP；`data/crypto/HardwareDeviceId` 是设备标识
  派生的例外。`PlaybackService` 等组件类也不纳入（PVM2 不跳过组件）。

## 2. 工具与固定版本

| 项 | 值 |
| --- | --- |
| 上游 | <https://github.com/xopJack/XopProtector> |
| 版本 | 0.7.9 |
| 固定提交 | `b4b3c8ab3c47e536adb324cfafeb5e24964cc2bd` |
| 许可证 | Apache-2.0（第三方见 `THIRD_PARTY_NOTICES.md`） |
| 构建 JDK | 17 |
| NDK | 25.1.8937393 |
| CMake | 3.22.1 |
| ABI | 仅 `arm64-v8a` |

0.7.9 包含首页启动黑屏修复、16KB page 对齐和 `--dex-scan-compat` 默认开启；不要
回退到 0.7.2。`build-protector.sh` 会在固定提交检出后应用 `patches/` 下的本地补丁
（当前为 PVM2 装箱形参准入门禁），补丁目录参与引擎缓存 key。

## 3. 策略

`harden-apk.sh` 固定使用以下参数：

```text
--profile balanced
--auto-true-vmp off
--true-vmp-prefix <business-prefixes.txt 中的每一行>
--no-protect-so
--no-encrypt-assets
--no-res-protect
--dex-scan-compat
--risk-flags 50
--rasp-action 1
--report-enabled 1
--application com.taotao.music.TaotaoApplication
--keystore <release.jks> --alias <alias> --storepass <pass> --keypass <pass>
```

说明：

- **DEX 加密**由 balanced profile 自动完成；**不启用 hollow**，减少空方法/反射兼容问题。
- **True-VMP 白名单**在 `tools/apk-hardening/business-prefixes.txt`。前缀匹配是
  `startsWith`：不以 `;` 结尾会连带嵌套类、Kotlin 合成类和 suspend 状态机；以 `;`
  结尾只匹配该类本身，用于方法数特别大的类。
- **装箱形参保护**：PVM2 入口按运行时类型拆箱参数，引用形参若收到包装值会改变
  寄存器宽度（`Long`/`Double` 占两个），导致后续引用参数错位。packer 补丁会让
  形参含包装类、`Number`、`Comparable`、`Serializable` 的方法退出 True-VMP，
  仍由整体 DEX 加密保护；日志里会出现 `TRUE_VMP skip ...: boxed parameter`。
  `Object` 形参刻意不拦：Kotlin suspend 状态机的 `invokeSuspend` 依赖它；业务侧
  `Any` 形参需单独评估。
- **不加密 SO**：`libtaotao_crypto.so` 继续从 APK 的 `lib/arm64-v8a/` 加载，避免
  `NativeLibDirRedirect`/sokeys 与现有 JNI 初始化冲突。
- **不加密 assets**：壳不接管 `AssetManager`，应用读取 `assets/` 的方式不变。
- **RASP**：`risk_flags=50` 关闭 CRC、root、emulator 三项（50 = 32+16+2），保留
  Frida、anti-debug、Xposed、SO 完整性；`rasp_action=1` 是降级而非杀进程。命中后
  `pvm2_interp.cpp` 会抛 `SecurityException("VMP refused by RASP")`，VMP 方法失败但
  进程继续。若线上反馈 Xposed/LSPosed 用户异常，先评估把 `risk_flags` 再降或把
  `rasp_action` 调为 0。

## 4. 本地使用

以下命令在 **Git Bash** 中执行。首次或上游/脚本变更时构建引擎（需要 JDK 17、
Android SDK、网络；约 10–20 分钟）。主工程 Gradle 用 JDK 21，构建引擎前请把
`JAVA_HOME` 临时指向 JDK 17，构建完再切回：

```bash
bash tools/apk-hardening/build-protector.sh --out .xop-engine
```

加固；签名口令从 `TAOTAO_STORE_PASSWORD` / `TAOTAO_KEY_ALIAS` /
`TAOTAO_KEY_PASSWORD` 环境变量读取（与 `local.properties` 的变量名一致）：

```bash
bash tools/apk-hardening/harden-apk.sh \
  --input androidApp/build/outputs/apk/release/androidApp-release.apk \
  --engine .xop-engine \
  --keystore taotao-release.jks
```

输出：

- `androidApp/build/outputs/apk/release/androidApp-release-protected.apk`
- 同目录 `hardening-report.txt`（完整 packer 日志 + 校验汇总）

`.xop-engine/` 已在 `.gitignore` 中忽略，不入库。

## 5. CI

`.github/workflows/ci.yml` 新增 `protector-tool` job：

1. `actions/setup-java@v4` 安装 JDK 17；
2. 缓存 `.xop-engine`（key 含 0.7.9 提交、`build-protector.sh`、`patches/**` 与许可证哈希）；
3. 缓存未命中时执行 `build-protector.sh`，命中时直接复用；
4. 上传 `protector-engine` artifact。

`client-android`：

1. `needs` 增加 `protector-tool`；
2. 下载 `protector-engine` 到 `.xop-engine`；
3. 构建 canonical APK 后执行 `harden-apk.sh`；
4. 只把 `*-protected.apk`、`output-metadata.json`、`hardening-report.txt` 放进
   `android-apk` artifact；
5. `client-publish` 仍按第一个 APK 发布，因此线上拿到的是加固版。

`changes` 的 `client` 过滤器包含 `tools/apk-hardening/**`，改加固脚本会触发重建。

## 6. 校验

`harden-apk.sh` 在 packer 成功后自动检查：

- `apksigner verify --verbose --print-certs` 通过；
- `zipalign -c -P 16 4` 通过（旧 build-tools 不支持 `-P` 时退化为 `-c 4` 并告警）；
- APK 内存在 `assets/protector/dexes.zip`、`assets/protector/config.json`；
- 存在 `lib/arm64-v8a/{libprotector.so,libshadowhook.so,libc++_shared.so}`；
- 根 `classes.dex` 小于 4 MB，且根目录 `classes*.dex` 合计小于 2 MB（原业务 DEX
  在 9 MB 量级，防止原 DEX 未收进 `assets/protector/dexes.zip`）；
- `config.json` 的 `application_name`、`risk_flags`、`rasp_action`、`protect_so`、
  `encrypt_assets`、`app_sign_sha256` 与预期一致；
- Manifest 的 `application` 指向 `com.yqsh.protector.shell.ProxyApplication`；
- packer 日志中 `TRUE_VMP Lcom/` 数量大于 0，并记录 `PVM2 admission` 行；
- CI 另校验日志里至少有一条 `TRUE_VMP skip ...: boxed parameter`，防止引擎补丁
  未生效；业务侧若已消除装箱形参，连同补丁和这条校验一起移除。

发布前建议在真机做一次冷启动、登录、搜索、播放、下载、收藏、切换音质、更新检查
的冒烟。加固壳只支持 arm64，x86_64 模拟器无法验证。

## 7. 已知风险与回退

- **VMP 与热修复分发调用**：插桩在方法入口注入 `$$patchDispatcher` 静态字段与分发
  调用，PVM2 需要执行 GETSTATIC/INVOKE；未验证真机。若某方法 admission 失败，
  packer 会回退到非 VMP 并记录 `TRUE_VMP skip`。CI 报告中的 `vmp_methods` 与
  `vmp_skipped` 必须人工看一眼。
- **RASP 误报**：保留 Xposed 检测，LSPosed 用户可能被降级。先观察线上崩溃/反馈；
  回退只需调整 `risk_flags` 或 `rasp_action` 重新发版。
- **Application 替换**：壳在 Provider 安装前把 `ProxyApplication` 换回真实
  `TaotaoApplication`，`applicationContext as? TaotaoApplication` 预计仍成立；真机
  首启必须验证。
- **回退**：不发布 `*-protected.apk` 即可；canonical APK 始终保留，补丁工具不受影响。
  已发布版本可用后台 `rollout` 关闭。

## 8. 许可证

XopProtector 为 Apache-2.0。发布 APK 内随包分发其 LICENSE/NOTICE 与 Dobby、
bhook、LSS、nlohmann/json、shadowhook、libc++ 的许可证，位于
`androidApp/src/main/assets/third_party/xopprotector/`。完整清单见
`THIRD_PARTY_NOTICES.md`。升级上游版本时必须同步替换许可证副本。
