//! 反调试 / 反注入：检测到调试或注入框架即终止进程。
//!
//! ## 为什么用 feature 门控，而不是无条件生效
//!
//! 与 `obfuscate` 同一个思路（见 [`crate::obf`]）：**只有对外分发的客户端产物
//! 才需要它**。
//!
//! | 产物 | 开 `antidebug`？ | 理由 |
//! | --- | --- | --- |
//! | Android `.so`（jni） | ✅ | 分发给用户，攻击者有物理设备 |
//! | Windows Tauri（desktop） | ✅ | 同上 |
//! | 服务端 `.node` | ❌ | 只在服务器上加载、不对外分发；而且**误伤**：在 Windows 上
//!   用调试器跟一个 node 进程是极常见的开发动作，无条件 abort 会砸掉后端排障 |
//! | Web `.wasm` | ❌ | 浏览器沙箱里没有等价的调试语义，且「检测 devtools」会砸掉
//!   正常的 F12 调试，收益远小于代价 |
//!
//! ## 这一层能做到什么，做不到什么
//!
//! 必须先说清楚：**反调试抬不高「密钥不可提取」这条线。** 有物理设备的人总能
//! 通过改代码、patch 二进制、内核级隐藏等方式绕过检测。它真正的作用是：
//!
//! 1. 把「随手 attach 一个 Frida 就能抓明文」的**成本**抬起来；
//! 2. 对**自动化批量抓包**（脚本化爬接口）这类低成本攻击有不小的劝退效果。
//!
//! 所以本节的意义是「提高门槛」，不是「建立防线」。任何把它宣传成后者的说法
//! 都是错的。§「已知限制」在 `SECURITY.md` 里同步了这条。
//!
//! ## 刻意**不**检测 root / 模拟器
//!
//! 只查「调试器」与「注入框架」，不查 root、不查 Magisk、不查模拟器。原因不是
//! 技术做不到，而是**误伤面太大**：模拟器是我们自己的测试环境，root 机型在
//! 安卓用户里占比不低，而这套判定一旦命中就是 `abort()`——不可恢复。用一个
//! 无法回滚的动作去拦一类**并不构成攻击**的环境，是把正常用户当攻击者处理。
//!
//! 要拦 root 机型应当先走「观测」（记录命中、继续服务），拿到真实比例再决定，
//! 那需要一条独立的上报链路，不在本模块范围内。
//!
//! ## 命中的动作是 abort，不是 panic
//!
//! `panic` 会被绑定层的 `catch_unwind` 接住并转成宿主语言异常（JNI 是
//! `CryptoException`、Tauri 是 `Err`）——**攻击者只要 catch 一下就能继续跑**。
//! 所以这里用 [`std::process::abort`]，进程直接收到 SIGABRT 退出，没有可恢复的
//! 路径。
//!
//! 刻意**不打印**任何关于命中原因的信息：那行日志会直接告诉逆向者「你在查什么」，
//! 等于把检测逻辑白送出去。安卓侧崩溃时系统 tombstone 里的 SIGABRT 与调用栈
//! 足够我们自己定位。

/// 本 crate 是否编译进了反调试能力。
///
/// 供上层做**编译期断言**用：客户端 crate（`jni` 的 `.so`、`desktop` 的 Tauri
/// 二进制）应当写
///
/// ```ignore
/// const _: () = assert!(taotao_crypto_core::antidebug::IS_ENABLED, "客户端必须开 antidebug");
/// ```
///
/// 这样「谁把 `features = ["antidebug"]` 从依赖声明里删掉」会**编译失败**，
/// 而不是让检测静默消失 —— 后者的症状是「构建、测试、发布全绿，只是防护没了」，
/// 与 `tools/check-obfuscated.sh` 要挡的那类失效模式完全一样。
///
/// 用 `cfg!` 而不是 `#[cfg]`：常量必须**在任何 feature 组合下都存在**，
/// 否则引用它的地方要跟着做条件编译，而那个引用点恰恰是用来兜住 feature 配错的。
pub const IS_ENABLED: bool = cfg!(feature = "antidebug");

/// 检测到的威胁种类。
///
/// 变体刻意保持粗粒度：细分到「哪个框架的哪个版本」会让逆向者一眼看出检测
/// 覆盖范围。真正需要区分时应当走观测上报，而不是写进崩溃日志。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Threat {
    /// 有调试器附加（安卓 `TracerPid` 非 0；Windows `IsDebuggerPresent`）。
    DebuggerAttached,

    /// 有调试器从**另一个进程**远程附加（Windows `CheckRemoteDebuggerPresent`）。
    ///
    /// 单列出来是因为它绕过 `IsDebuggerPresent`：那个 API 只看本进程的 PEB，
    /// 而「进程启动后再 attach 调试器」这条路它看不见。
    RemoteDebugger,

    /// 进程映像里出现了注入框架（Frida / Xposed / Substrate 之类）。
    Instrumentation,
}

/// 从 `/proc/self/status` 的内容里取 `TracerPid`。
///
/// 返回 `None` 表示**没找到这个字段**；找到时把值原样返回——值为 `0` 才是
/// 「没有调试器」。两者必须区分开：把「字段不存在」当成 0（未附加）是对的
/// （某些受限内核对非应用进程会省略该字段），但把「字段不存在」当成非 0 会造
/// 成大面积误杀。
///
/// 抽成纯函数是为了能直接喂 fixture 字符串做测试 —— 在本机（非安卓）跑不到
/// `/proc/self/status` 的真实内容。
pub fn parse_tracer_pid(status: &str) -> Option<u32> {
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix("TracerPid:") {
            // 值后面可能跟着空白；解析失败按「字段存在但读不懂」处理，
            // 返回 None 而不是 0 —— 由调用方决定怎么对待。
            return rest.trim().parse().ok();
        }
    }
    None
}

/// 在给定的 `markers` 里找出第一个出现在 `haystack` 中的，返回命中的那个。
///
/// `markers` 由调用方传入而不是写死在这里：生产路径传的是经 [`crate::obf::obf`]
/// 混淆后的串（不让检测特征以明文躺在二进制里），测试路径传明文即可 ——
/// 匹配逻辑本身与「串从哪来」无关，所以可以分开。
///
/// 大小写敏感：`/proc/self/maps` 里的路径大小写是确定的，而放宽成不敏感会
/// 把 `Frida` 这种正常的类名/文件名也拉进来，误伤面反而变大。
pub fn find_marker(haystack: &str, markers: &[String]) -> Option<String> {
    markers
        .iter()
        .find(|marker| haystack.contains(marker.as_str()))
        .cloned()
}

/// 注入框架的特征串清单。
///
/// 只收**明确指向注入框架**的串，不收 `su` / `magisk` / `root` 这类 —— 理由见
/// 模块文档的「刻意不检测 root」。
///
/// 每个串都经 `obf!` 混淆，避免「把检测清单以明文写在产物里」。这里的混淆
/// **不涉及**跨端一致性（不像 `kdf` 的标签需要客户端与服务端逐字节相同），
/// 它只在本地做字符串比较，所以开/不开 `obfuscate` 都不影响正确性。
///
/// # 为什么是 `pub`
///
/// 为了让单元测试能**直接钉住生产用的那份清单**。清单里任何一个串被删错或拼
/// 错，检测会**静默失效**（匹配不到就永远返回「干净」），构建、测试、发布全绿，
/// 只有真正去抓包的人才会发现 —— 这种失效模式必须用测试挡住，而把清单复制一份
/// 进测试等于什么都没测。所以它是公开的，不是内部实现细节。
///
/// 暴露它没有额外泄露：清单本来就以（混淆后的）形式躺在产物里，能跑这段代码
/// 的人本来就能自己调它。
#[cfg(feature = "antidebug")]
pub fn instrumentation_markers() -> Vec<String> {
    use crate::obf::obf;

    vec![
        // Frida 的几个注入形态：gadget 是重打包嵌进去的，agent/server 是运行时附加的。
        obf!("frida-agent"),
        obf!("frida-gadget"),
        obf!("frida-server"),
        obf!("libfrida"),
        // 连 Frida 的调试器名，出现在部分注入路径的映射名里。
        obf!("re.frida."),
        obf!("gadget"),
        // Substrate / Cydia 系注入框架。
        obf!("libsubstrate"),
        // Xposed / LSPosed。
        obf!("xposed"),
    ]
}

/// 读取 `/proc/self/maps` 与 `/proc/self/status` 做检测（仅安卓）。
///
/// `/proc` 在安卓上是应用可见的，不需要额外权限 —— 内核按 uid 过滤，读到的就是
/// 本进程自己的映射。
#[cfg(all(feature = "antidebug", target_os = "android"))]
fn probe() -> Option<Threat> {
    // 1) 调试器附加。TracerPid 非 0 = 有进程在 ptrace 我们。
    if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
        if parse_tracer_pid(&status).is_some_and(|pid| pid != 0) {
            return Some(Threat::DebuggerAttached);
        }
    }

    // 2) 注入框架。看的是**已映射进本进程**的模块名，而不是「设备上装了什么」——
    //    后者（扫 /data/app 找 Frida 服务端）会把「设备上装着但没对我们用」也算命中，
    //    误伤面大得多。
    if let Ok(maps) = std::fs::read_to_string("/proc/self/maps") {
        if find_marker(&maps, &instrumentation_markers()).is_some() {
            return Some(Threat::Instrumentation);
        }
    }

    None
}

// Windows 的检测走 Win32 API，不额外引入 crate —— 直接声明用到的两个函数。
//
// 为什么不用 `windows-sys` / `winapi`：core 要被 wasm32-unknown-unknown 消费，
// 而这两个 crate 的默认特性会带上 std 依赖，在 wasm 目标上编译失败（与
// core/Cargo.toml 顶部「依赖一律 default-features = false」同一条约束）。
// 我们自己只用到两个 kernel32 导出，手写 extern 块比接一个大依赖更可控。
#[cfg(all(feature = "antidebug", windows))]
#[link(name = "kernel32")]
extern "system" {
    /// 本进程是否已被附加调试器。读的是当前进程的 PEB。
    fn IsDebuggerPresent() -> i32;

    /// 当前进程的伪句柄（`(HANDLE)-1`），无需释放。
    fn GetCurrentProcess() -> *mut core::ffi::c_void;

    /// 是否有调试器从另一个进程远程附加。`out` 非 0 即命中。
    fn CheckRemoteDebuggerPresent(process: *mut core::ffi::c_void, out: *mut i32) -> i32;
}

/// 进程内调试器（PEB）+ 远程调试器（另一个进程 attach）双查（仅 Windows）。
///
/// 两个都要：`IsDebuggerPresent` 只在调试器**先于进程启动**或通过常规方式附加时
/// 为真，而「进程跑起来之后从另一个进程 attach」这类场景要靠
/// `CheckRemoteDebuggerPresent` 才看得见。
#[cfg(all(feature = "antidebug", windows))]
fn probe() -> Option<Threat> {
    // SAFETY：三个函数都是无副作用的 Win32 查询；`GetCurrentProcess` 返回的是
    // 伪句柄（常量 -1），不会泄漏句柄，也不需要关闭。`remote` 是我们自己的栈
    // 变量，指针有效且已初始化。
    unsafe {
        if IsDebuggerPresent() != 0 {
            return Some(Threat::DebuggerAttached);
        }
        let mut remote: i32 = 0;
        if CheckRemoteDebuggerPresent(GetCurrentProcess(), &mut remote) != 0 && remote != 0 {
            return Some(Threat::RemoteDebugger);
        }
    }
    None
}

/// 其余平台（Linux 宿主机、服务端、wasm）不做检测。
///
/// 这个分支不是「忘了实现」：它存在是为了让 `antidebug` feature 被 workspace
/// 统一开启时（`cargo clippy --workspace` 会把 jni 的 feature 并到 core 上）
/// 仍能编译通过，而不是缺一个函数。
#[cfg(all(feature = "antidebug", not(any(target_os = "android", windows))))]
fn probe() -> Option<Threat> {
    None
}

/// 做一次检测；命中即终止进程。
///
/// 调用时机应当是**加解密路径上最早能拿到控制权的地方**（见 jni 的 `JNI_OnLoad`
/// 与 desktop 的启动钩子），让进程在建立会话、把 PSK 读进内存之前就死掉。
///
/// # debug 构建直接放行
///
/// `cfg!(debug_assertions)` 为真时不做任何检测。这是**必要的逃生阀**：开了检测
/// 就必然砸掉自己在本机用调试器排障的能力（Android Studio attach、Windows 上
/// VSCode 调试 Tauri）。发布产物走 release，不受影响。
///
/// 同时意味着：**不要用 release 产物做本机排障** —— 一旦 attach 调试器就会
/// 直接 abort，症状是「应用刚起来就没了」，很容易被误判成别的问题。
#[cfg(feature = "antidebug")]
pub fn enforce() {
    if cfg!(debug_assertions) {
        return;
    }
    if probe().is_some() {
        abort_now();
    }
}

/// 关闭 `antidebug` feature 时的空实现。
///
/// 签名与开启时**完全一致**，调用点不需要任何条件编译 —— 与 `obf` 两个分支
/// 返回同一类型是同一条理由：让调用点不随 feature 漂移，避免「只有某一种 CI
/// 配置才编译失败」这类问题。
#[cfg(not(feature = "antidebug"))]
pub fn enforce() {}

/// 立即终止进程。
///
/// 用 `abort()` 而不是 `panic!`：后者会被绑定层的 `catch_unwind` 接住并转成
/// 可捕获的宿主语言异常，攻击者 catch 一下就能继续。`abort()` 触发 SIGABRT，
/// 没有从 Rust 侧恢复的路径（release profile 的 `panic = "unwind"` 对这个函数
/// 无效，见 Cargo.toml 的说明）。
#[cfg(feature = "antidebug")]
fn abort_now() -> ! {
    std::process::abort()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 真实的 `/proc/self/status` 片段（保留几条无关字段用于确认解析不会串行）。
    const STATUS_ATTACHED: &str = "\
Name:\tcom.taotao.music
State:\tS (sleeping)
Tgid:\t12345
Pid:\t12345
PPid:\t1000
TracerPid:\t6789
Uid:\t10123\t10123\t10123\t10123
Gid:\t10123\t10123\t10123\t10123
";

    const STATUS_CLEAN: &str = "\
Name:\tcom.taotao.music
State:\tS (sleeping)
Tgid:\t12345
Pid:\t12345
PPid:\t1000
TracerPid:\t0
Uid:\t10123\t10123\t10123\t10123
";

    #[test]
    fn tracer_pid_parses_attached_and_clean() {
        assert_eq!(parse_tracer_pid(STATUS_ATTACHED), Some(6789));
        assert_eq!(parse_tracer_pid(STATUS_CLEAN), Some(0));
    }

    #[test]
    fn tracer_pid_missing_field_is_none_not_zero() {
        // 关键区分：字段不存在 → None（不构成命中），而不是 0。
        // 反过来的话，在省略该字段的内核上所有用户都会被判定为「已附加」。
        assert_eq!(parse_tracer_pid("Name:\tfoo\nPid:\t1\n"), None);

        // 空输入、只有前缀没有值的行，同样不能误判成命中。
        assert_eq!(parse_tracer_pid(""), None);
        assert_eq!(parse_tracer_pid("TracerPid:\n"), None);
        assert_eq!(parse_tracer_pid("TracerPid:\tnot-a-number\n"), None);
    }

    #[test]
    fn tracer_pid_ignores_lookalike_lines() {
        // 前缀式匹配：`XTracerPid:` 这种不该被当成字段（`strip_prefix` 保证）。
        let tricky = "XTracerPid:\t42\nTracerPid:\t7\n";
        assert_eq!(parse_tracer_pid(tricky), Some(7));
    }

    #[test]
    fn find_marker_matches_known_injection_libraries() {
        // 取自真实 Frida 注入后的 maps 形态。
        let maps = "\
7f8a100000-7f8a101000 r-xp 00000000 00:00 0  [vdso]
7f9b200000-7f9b210000 r-xp 00000000 103:02 1234  /data/local/tmp/frida-agent-64.so
7fa0000000-7fa0010000 rw-p 00000000 00:00 0  [anon:libc_malloc]
";
        let markers = vec!["frida-agent".to_string(), "xposed".to_string()];
        assert_eq!(find_marker(maps, &markers), Some("frida-agent".to_string()));
    }

    #[test]
    fn find_marker_is_none_on_clean_maps() {
        // 干净进程的 maps：只有系统库和本应用，不应命中。
        let maps = "\
7f8a100000-7f8a101000 r-xp 00000000 00:00 0  [vdso]
7f9b200000-7f9b210000 r-xp 00000000 103:02 1234  /apex/com.android.runtime/lib64/bionic/libc.so
7fa0000000-7fa0010000 rw-p 00000000 00:00 0  [anon:libc_malloc]
";
        let markers = vec![
            "frida-agent".to_string(),
            "xposed".to_string(),
            "libsubstrate".to_string(),
        ];
        assert_eq!(find_marker(maps, &markers), None);
    }

    #[test]
    fn find_marker_is_case_sensitive_to_avoid_false_positives() {
        // 大小写敏感是刻意的：这类相近词若被不敏感匹配吞进来，会把正常应用误杀。
        let maps = "/data/app/com.example/libFridaDemo.so\n/assets/Frida.png\n";
        let markers = vec!["frida-agent".to_string(), "libfrida".to_string()];
        assert_eq!(find_marker(maps, &markers), None);
    }

    /// 钉住**生产用的那份清单**至少覆盖这些形态。
    ///
    /// 写成断言而不是靠注释：清单里的串一旦被删或拼错，检测会**静默失效**
    /// （匹配不到就永远返回「干净」），构建 / 测试 / 发布全绿，只有真正去抓包的
    /// 人才会发现。这是本模块最危险的失效模式，必须用测试挡住。
    ///
    /// 直接调 [`instrumentation_markers`] 而不是在测试里复制一份清单 ——
    /// 复制的那份就算写对了也证明不了生产清单是对的。
    ///
    /// 用 `#[cfg]` 门控：这个函数只在开启 `antidebug` 时存在，而
    /// `cargo test -p taotao-crypto-core` 不带任何 feature 也会跑这个测试模块。
    #[cfg(feature = "antidebug")]
    #[test]
    fn production_markers_cover_the_common_injection_frameworks() {
        let markers = instrumentation_markers();
        for needle in [
            "frida-agent",
            "frida-gadget",
            "frida-server",
            "libfrida",
            "gadget",
            "libsubstrate",
            "xposed",
        ] {
            assert!(
                markers.iter().any(|m| m == needle),
                "特征清单缺少 {needle}，该形态的注入会静默漏检"
            );
        }
    }

    /// 清单里不该出现 root / 模拟器相关的串。
    ///
    /// 这条断言守的是**误伤面**：一旦有人「顺手」把 `su` / `magisk` 加进来，
    /// 命中的动作是 `abort()`，等于把所有 root 机型的正常用户直接打死。
    /// 那是不可恢复的，所以用一个反向断言把意图钉死。
    #[cfg(feature = "antidebug")]
    #[test]
    fn production_markers_exclude_root_and_emulator_signals() {
        let markers = instrumentation_markers();
        for forbidden in [
            "magisk",
            "supersu",
            "root",
            "qemu",
            "goldfish",
            "bluestacks",
        ] {
            assert!(
                !markers.iter().any(|m| m.contains(forbidden)),
                "清单里出现了 {forbidden} —— 那属于 root / 模拟器检测，会大面积误杀（详见模块文档）"
            );
        }
    }
}
