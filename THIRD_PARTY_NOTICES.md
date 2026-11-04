# 第三方组件与许可证

本文档列出桃桃音乐发布 APK 中**随包分发**的第三方组件。源码依赖的许可证以其
仓库/构建缓存中的文本为准；发布 APK 内的加固壳许可证副本位于
`androidApp/src/main/assets/third_party/xopprotector/`，可直接从 APK 解包查看。

## 加固壳（仅发布 APK）

| 组件 | 版本/来源 | 许可证 | 说明 |
| --- | --- | --- | --- |
| XopProtector | 0.7.9，<https://github.com/xopJack/XopProtector>，提交 `b4b3c8ab3c47e536adb324cfafeb5e24964cc2bd` | Apache-2.0 | DEX 加密、True-VMP、RASP 壳 |
| Dobby | 随 XopProtector 分发 | Apache-2.0 | 原生 inline hook |
| bhook / bytehook | 随 XopProtector 分发 | MIT | linker/GOT hook |
| Linux Syscall Support (LSS) | 经 bhook 分发 | BSD-3-Clause | 系统调用封装 |
| nlohmann/json | 随 XopProtector 分发 | MIT | 原生 JSON |
| shadowhook | 2.0.1，随 XopProtector shell-files 分发 | MIT | 原生 inline hook |
| shadowhook 第三方组件 | queue.h / tree.h / LSS / xDL | BSD-3-Clause / BSD-2-Clause / BSD-3-Clause / MIT | 见 `shadowhook-THIRD-PARTY.txt` |
| libc++ | Android NDK 运行时，随 shell-files 分发 | Apache-2.0 WITH LLVM-exception | C++ 运行时 |

完整文本：

- `androidApp/src/main/assets/third_party/xopprotector/LICENSE`
- `androidApp/src/main/assets/third_party/xopprotector/NOTICE`
- `androidApp/src/main/assets/third_party/xopprotector/LICENSES/*.txt`

加固引擎由 `tools/apk-hardening/build-protector.sh` 从固定提交构建；CI 只在
`protector-tool` job 中拉取上游源码，产物经 artifact 交给 `client-android`。
加固后的 APK 命名为 `*-protected.apk`；仓库中的 canonical APK 不包含上述壳代码。

## 运行时依赖

Android 客户端通过 Gradle 依赖的 AndroidX、Compose、Media3、Coil、Room、SQLCipher、
OkHttp、kotlinx.serialization 等组件，其许可证随 Gradle 依赖分发，不在此重复列出。
Web 分享播放器与桌面端依赖的第三方组件分别见各自构建配置。

## 维护约定

- 升级 XopProtector 版本时，必须同步更新：
  1. `tools/apk-hardening/build-protector.sh` 中的 tag/commit；
  2. `androidApp/src/main/assets/third_party/xopprotector/` 下的许可证副本；
  3. 本文件的版本表。
- 升级 shadowhook 或 NDK 时，同步检查 `libc++` 与 shadowhook 第三方许可证。
- 不得把加固壳源码复制进本仓库；保持“固定提交 + 构建脚本”的可审计供应链。
