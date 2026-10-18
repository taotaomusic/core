//! JNI 绑定：供 Android 使用。
//!
//! ## ⚠️ Windows 侧的实际形态（2026-10-18 澄清）
//!
//! 本 crate **能**编译出 `x86_64-pc-windows-msvc` 的 `taotao_crypto.dll`，但
//! **真实 Windows 客户端不用它**：桌面端已从 Compose Desktop（JVM）换成 Tauri，
//! 而 Tauri 侧直接依赖 `taotao-crypto-core`（见 `desktop/src-tauri/Cargo.toml`
//! 的 `taotao-crypto-core = { path = "../../crypto-src/core" }`），走的是
//! `crypto_bridge.rs` 里的 `#[tauri::command]`，完全绕开 JNI。
//!
//! 也就是说：**Windows 的反调试接线在 `desktop/src-tauri`，不在本文件**。
//! 本文件里 `#[cfg(windows)]` 相关的路径（core 的 `antidebug` 探针）仍会生效，
//! 但那是给 core 的单元测试与「万一有人又走回 JNI」兜底的，不是主路径。
//!
//! ## 为什么 Android 也走 JNI
//!
//! Android 需要 `System.loadLibrary` 加载的 `.so`，JNI 是唯一通路。选纯 Rust 的
//! `jni` crate 而不是 JNA 的原因：后者要多维护一套 C 头文件和一层 C ABI 门面，
//! 而 `jni` crate 不需要 JDK 头文件、不需要 `javac`，交叉编译到
//! `aarch64-linux-android` 时省掉整个 NDK sysroot 里的 `jni.h`。
//!
//! ## jni 0.22 的 API 形态（升级时必读）
//!
//! 0.22 把 `JNIEnv` 拆成了两层，这是破坏性改动：
//!
//! - **`EnvUnowned<'local>`**：native 方法参数里收到的那个，只是 FFI 安全的裸指针
//!   包装，**没有任何 JNI 方法**。直接调 `env.new_string(...)` 会报
//!   「no method named `new_string` found for `&mut EnvUnowned`」。
//! - **`Env<'local>`**：真正的 JNI 方法都在这里，通过
//!   `EnvUnowned::with_env(|env| ...)` 临时借出来。
//!
//! 另外 `JNIEnv` 现在只是 `EnvUnowned` 的废弃别名，照着 0.21 的示例写会编译失败。
//!
//! ## 错误与 panic 的处理
//!
//! `with_env` 内部已经用 `catch_unwind` 包住了闭包，所以 panic 不会跨 FFI 边界
//! （跨 FFI 的 panic 是未定义行为）。但它的 `resolve::<P>()` 需要一个
//! `ErrorPolicy`，而内置的 `ThrowRuntimeExAndDefault` 要求返回值实现 `Default` ——
//! `jbyteArray` 是裸指针，没有 `Default`。
//!
//! 所以这里用 [`run`] 做了一层包装：闭包统一返回 `Result<()>`，
//! 真正的返回值通过外层局部变量带出来。这样 `resolve` 只需要处理 `()`，
//! 而我们的领域错误在闭包内部就被转成了 Java 异常。
//!
//! ## 反调试
//!
//! 这个 crate 开启 core 的 `antidebug` feature，在 [`JNI_OnLoad`] 与每个
//! 「建引擎」入口做检测，命中即 `abort()`（**不是** panic —— 那会被
//! `catch_unwind` 接住，Java 侧 catch 一下就能继续跑）。检测逻辑本身在
//! `core/src/antidebug.rs`，本文件只负责接线。debug 构建自动跳过。

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JString};
use jni::strings::JNIString;
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6};
use jni::{jni_str, Env, EnvUnowned};
use taotao_crypto_core::{aad, antidebug, ClientEngine, CryptoError, ServerEngine};

/// 编译期断言：本 crate 必须开启 core 的 `antidebug`。
///
/// `.so` 是分发给用户的产物，防护「静默消失」比「编译失败」糟得多 —— 见
/// `core/src/antidebug.rs` 的 `IS_ENABLED`。绑在这里（而不是靠人记住）是因为
/// 删掉 `Cargo.toml` 里那个 feature 之后，构建、单元测试、冒烟测试、发布
/// **全都正常**，只有真正去 attach 调试器的人才会发现检测没了。
const _: () = assert!(
    antidebug::IS_ENABLED,
    "taotao-crypto-jni 必须给 taotao-crypto-core 开 antidebug feature（见 jni/Cargo.toml）"
);

/// `JNI_OnLoad` 的返回类型声明。
///
/// 参数用裸指针而不是 `jni::JavaVM`：这个函数只用来拿一个「够早的执行时机」做
/// 检测，参数本身一个都不用，所以没必要为它引入类型转换。
///
/// 库被加载时立刻做一次反调试检测。
///
/// 这是**最早**能拿到控制权的时机 —— 早于任何 `native*` 方法被调用、早于
/// `clientNew` 把 PSK 读进内存。放在这里比放在各入口更彻底：攻击者的
/// 「加载后立刻 patch 掉检测函数」这条路会变得更难，因为检测在
/// `System.loadLibrary` 返回之前就已经跑完了。
///
/// 返回 `JNI_VERSION_1_6`：与 Android 侧定下的最低 API（24）配套。返回 0 或
/// 不导出这个符号都会让 `System.loadLibrary` 报
/// `UnsatisfiedLinkError`，所以它必须存在且返回合法版本号。
///
/// # Safety
///
/// 由 JVM 调用，签名与 JNI 规范一致。这里**不使用**任何参数，所以没有
/// 解引用裸指针的风险。
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    _vm: *mut core::ffi::c_void,
    _reserved: *mut core::ffi::c_void,
) -> jint {
    enforce_antidebug();
    JNI_VERSION_1_6
}

/// 反调试检测的统一出口。
///
/// 为什么在 `JNI_OnLoad` 之外**还要**在各个入口查一次：`JNI_OnLoad` 只在库被
/// 加载时跑一次，而「先正常启动、之后再 attach 调试器」这条路径它完全看不见。
/// 入口处复查把检测点铺在攻击者实际要用的地方（握手、建引擎）。
///
/// 每次调用的开销是两次文件读取（安卓上 `/proc/self/status` 与 `maps`，各几 KB），
/// 落在建引擎 / 握手这类**低频冷路径**上，不在每请求的封帧路径上 —— 所以不需要
/// 做结果缓存，也就不会引入「缓存让检测只跑一次」这个可被利用的窗口。
///
/// `#[inline(always)]`：调用点有十来处，内联后省掉一次函数调用，并且让
/// 「未开启 feature 时彻底消失」这件事对编译器更明确（此时函数体是空的）。
#[inline(always)]
fn enforce_antidebug() {
    antidebug::enforce();
}

/// 领域错误统一转成这个 Java 异常类。
///
/// 类名必须与 Kotlin 侧 `CryptoException` 的全限定名一致。
/// **不一致时 `throw_new` 会静默失败** —— Rust 侧返回 null，
/// Kotlin 侧看到的是 `NullPointerException` 而不是真正的错误原因。
fn throw_crypto(env: &mut Env<'_>, message: &str) -> jni::errors::Result<()> {
    env.throw_new(
        jni_str!("com/taotao/music/crypto/CryptoException"),
        JNIString::new(message),
    )
}

/// 统一的 FFI 入口包装。
///
/// 三件事：借出 `Env`、把领域错误转成 Java 异常、给 panic 兜底。
/// `fallback` 是「没能产出值」时返回给 Java 的东西（通常是 null / false / 0）。
///
/// 闭包返回 `Result<()>` 而不是 `Result<T>` 是刻意的：`resolve` 要求的
/// `Default` 约束对裸指针不成立，把真正的返回值放到闭包外面带出来就绕开了它。
///
/// 参数取 `&mut EnvUnowned` 而不是按值消费：`EnvUnowned` 不是 `Copy`，
/// 而调用方（native 方法）拿到的就是它本身，按值传会让每个入口都要多写一次
/// 所有权转移。`with_env` 本来就只需要 `&mut self`。
fn run<'local, T>(
    env: &mut EnvUnowned<'local>,
    fallback: T,
    body: impl FnOnce(&mut Env<'local>) -> Result<T, CryptoError>,
) -> T {
    let mut produced: Option<T> = None;

    env.with_env(|env| -> jni::errors::Result<()> {
        match body(env) {
            Ok(value) => produced = Some(value),
            Err(err) => {
                // 只把 Display 传出去。协议内部细节（期望的 MAC、密钥字节）
                // 不进异常消息 —— 异常消息会进日志和崩溃上报。
                throw_crypto(env, &err.to_string())?;
            }
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();

    // panic 时 produced 仍是 None，走 fallback。
    produced.unwrap_or(fallback)
}

// ---------------------------------------------------------------------------
// 句柄注册表
// ---------------------------------------------------------------------------

/// 注册表条目。
enum Entry {
    Client(ClientEngine),
    Server(ServerEngine),
}

static REGISTRY: OnceLock<Mutex<HashMap<i64, Entry>>> = OnceLock::new();
/// 句柄分配器。从 1 开始，0 保留给「空句柄」。
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn registry() -> &'static Mutex<HashMap<i64, Entry>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn insert(entry: Entry) -> jlong {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    // Mutex 中毒说明另一个线程在持锁时 panic 了。这里选择恢复而不是传播：
    // 注册表的数据结构没有被破坏，继续用比让整个加密层瘫痪要好。
    let mut guard = registry().lock().unwrap_or_else(|e| e.into_inner());
    guard.insert(handle, entry);
    handle
}

/// 在注册表上执行一段操作，拿到对应句柄的条目。
///
/// 句柄无效时返回 `None`，由调用方决定降级行为（通常是返回错误）。
fn with_entry<T>(handle: jlong, f: impl FnOnce(&mut Entry) -> T) -> Option<T> {
    let mut guard = registry().lock().unwrap_or_else(|e| e.into_inner());
    guard.get_mut(&handle).map(f)
}

fn remove_entry(handle: jlong) -> bool {
    let mut guard = registry().lock().unwrap_or_else(|e| e.into_inner());
    guard.remove(&handle).is_some()
}

// ---------------------------------------------------------------------------
// 类型转换辅助
// ---------------------------------------------------------------------------

/// Java String → Rust String。JString 的 Display 实现不会失败。
fn read_string(value: &JString<'_>) -> String {
    value.to_string()
}

/// Java byte[] → Vec<u8>。空数组或 null 都返回空 Vec。
fn read_bytes(env: &mut Env<'_>, value: JByteArray<'_>) -> Vec<u8> {
    env.convert_byte_array(value).unwrap_or_default()
}

/// Vec<u8> → Java byte[]。
fn write_bytes(env: &mut Env<'_>, value: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(value) {
        Ok(array) => array.into_raw(),
        Err(err) => {
            // 这里的失败是 JNI 层面的（比如内存不足），抛出去让 Java 侧看到。
            let _ = throw_crypto(env, &format!("构造字节数组失败：{err}"));
            core::ptr::null_mut()
        }
    }
}

/// Rust String → Java String。
fn write_string(env: &mut Env<'_>, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(err) => {
            let _ = throw_crypto(env, &format!("构造字符串失败：{err}"));
            core::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// 元信息
// ---------------------------------------------------------------------------

/// 协议版本。Kotlin 侧据此判断 native 库是否与当前 App 版本匹配。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_nativeVersion<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jint {
    taotao_crypto_core::PROTOCOL_VERSION as jint
}

/// 构造 AAD 上下文。各语言侧必须走这个函数，不要自己拼字符串。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_nativeAad<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    path_and_query: JString<'local>,
) -> jbyteArray {
    let method = read_string(&method);
    let path = read_string(&path_and_query);
    run(&mut env, core::ptr::null_mut(), |env| {
        Ok(write_bytes(env, &aad(&method, &path)))
    })
}

// ---------------------------------------------------------------------------
// 客户端
// ---------------------------------------------------------------------------

/// 创建客户端引擎。`pskHex` 必须是 64 个十六进制字符。`deviceId` 是本机稳定标识
/// （Android ANDROID_ID），折进握手密钥；无设备号传空串即不绑定。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientNew<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    psk_id: JString<'local>,
    psk_hex: JString<'local>,
    device_id: JString<'local>,
) -> jlong {
    // 反调试：这是 PSK 进入内存的唯一入口，放在读取参数之前 ——
    // 命中时进程直接 abort，PSK 连被读进来都不会发生。
    enforce_antidebug();
    let psk_id = read_string(&psk_id);
    let psk_hex = read_string(&psk_hex);
    let device_id = read_string(&device_id);
    run(&mut env, 0, |_env| {
        Ok(insert(Entry::Client(ClientEngine::new(
            &psk_id, &psk_hex, &device_id,
        )?)))
    })
}

/// 发起握手，返回 ClientHello 字节。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientHandshake<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now_ms: jlong,
) -> jbyteArray {
    // 反调试：握手是攻击者最想跟进的路径（跟进去就能读到 PSK 派生的握手密钥）。
    enforce_antidebug();
    run(&mut env, core::ptr::null_mut(), |env| {
        let now = now_ms.max(0) as u64;
        let hello = with_entry(handle, |entry| match entry {
            Entry::Client(client) => client.handshake(now),
            Entry::Server(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_bytes(env, &hello))
    })
}

/// 处理 ServerHello，建立会话。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientFinish<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    server_hello: JByteArray<'local>,
    now_ms: jlong,
) -> jboolean {
    run(&mut env, JNI_FALSE, |env| {
        let hello = read_bytes(env, server_hello);
        let now = now_ms.max(0) as u64;
        // 握手失败必须让 Java 侧看到异常，否则调用方会以为会话建好了，
        // 后续每个请求都拿到「会话未建立」这种更远的错误。
        //
        // `?` 在这里就完成了这件事：失败会被 `run` 的包装转成
        // CryptoException 抛回 Java。成功时 `finish` 返回的就是 `()`，
        // 所以不需要（也不该）再绑定一个变量出来 —— 那只会让读者以为
        // 这个返回值后面还要用到。
        with_entry(handle, |entry| match entry {
            Entry::Client(client) => client.finish(&hello, now),
            Entry::Server(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(JNI_TRUE)
    })
}

/// 加密请求体，返回帧字节。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientSeal<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    aad: JByteArray<'local>,
    plaintext: JByteArray<'local>,
    now_ms: jlong,
) -> jbyteArray {
    run(&mut env, core::ptr::null_mut(), |env| {
        let aad = read_bytes(env, aad);
        let plaintext = read_bytes(env, plaintext);
        let now = now_ms.max(0) as u64;
        let frame = with_entry(handle, |entry| match entry {
            Entry::Client(client) => client.seal(&aad, &plaintext, now),
            Entry::Server(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_bytes(env, &frame))
    })
}

/// 解密响应体。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientOpen<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    aad: JByteArray<'local>,
    frame: JByteArray<'local>,
    now_ms: jlong,
) -> jbyteArray {
    run(&mut env, core::ptr::null_mut(), |env| {
        let aad = read_bytes(env, aad);
        let frame = read_bytes(env, frame);
        let now = now_ms.max(0) as u64;
        let plaintext = with_entry(handle, |entry| match entry {
            Entry::Client(client) => client.open(&aad, &frame, now),
            Entry::Server(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_bytes(env, &plaintext))
    })
}

/// 从帧反推 `X-Taotao-Crypto` 头值。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientHeaderForFrame<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    frame: JByteArray<'local>,
) -> jstring {
    run(&mut env, core::ptr::null_mut(), |env| {
        let frame = read_bytes(env, frame);
        let header = with_entry(handle, |entry| match entry {
            Entry::Client(client) => client.header_for_frame(&frame),
            Entry::Server(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_string(env, &header))
    })
}

/// 会话 ID（十六进制）。无会话时返回空串。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientSessionId<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let id = with_entry(handle, |entry| match entry {
        Entry::Client(client) => client.session_id_hex(),
        Entry::Server(_) => String::new(),
    })
    .unwrap_or_default();
    run(&mut env, core::ptr::null_mut(), |env| {
        Ok(write_string(env, &id))
    })
}

/// 是否需要重新握手。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientNeedsRekey<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now_ms: jlong,
) -> jboolean {
    let now = now_ms.max(0) as u64;
    let needs = with_entry(handle, |entry| match entry {
        Entry::Client(client) => client.needs_rekey(now),
        // 句柄无效时保守地返回 true：调用方会重新握手，
        // 而不是拿着一个坏会话继续跑。
        Entry::Server(_) => true,
    })
    .unwrap_or(true);
    if needs {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 会话剩余有效毫秒数。无会话返回 0。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientSessionRemainingMs<
    'local,
>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now_ms: jlong,
) -> jlong {
    let now = now_ms.max(0) as u64;
    with_entry(handle, |entry| match entry {
        Entry::Client(client) => client.session_remaining_ms(now) as jlong,
        Entry::Server(_) => 0,
    })
    .unwrap_or(0)
}

/// 释放客户端引擎。必须调用，否则泄漏。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_clientFree<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    if remove_entry(handle) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// 服务端
// ---------------------------------------------------------------------------

/// 创建服务端引擎。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverNew<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> jlong {
    // 服务端入口同样查：本项目后端主要走 node（不开 antidebug），但 JNI 侧
    // 的 server* 是给「后端也用 JVM 跑」准备的，那条路径上是客户端产物，
    // 检测必须一致 —— 否则同一份 .so 会随调用方式改变行为。
    enforce_antidebug();
    insert(Entry::Server(ServerEngine::new()))
}

/// 加入 / 更新一条 PSK。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverPutPsk<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    psk_id: JString<'local>,
    psk_hex: JString<'local>,
) -> jboolean {
    let psk_id = read_string(&psk_id);
    let psk_hex = read_string(&psk_hex);
    run(&mut env, JNI_FALSE, |_env| {
        with_entry(handle, |entry| match entry {
            Entry::Server(server) => server.put_psk(&psk_id, &psk_hex),
            Entry::Client(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(JNI_TRUE)
    })
}

/// 移除一条 PSK。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverRemovePsk<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    psk_id: JString<'local>,
) -> jboolean {
    let id = read_string(&psk_id);
    let removed = with_entry(handle, |entry| match entry {
        Entry::Server(server) => server.remove_psk(&id),
        Entry::Client(_) => false,
    })
    .unwrap_or(false);
    if removed {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 处理 ClientHello，返回 ServerHello。新会话会自动登记进会话表。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverAccept<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    client_hello: JByteArray<'local>,
    device_id: JString<'local>,
    now_ms: jlong,
) -> jbyteArray {
    enforce_antidebug();
    let device_id = read_string(&device_id);
    run(&mut env, core::ptr::null_mut(), |env| {
        let hello = read_bytes(env, client_hello);
        let now = now_ms.max(0) as u64;
        let response = with_entry(handle, |entry| match entry {
            Entry::Server(server) => server.accept(&hello, device_id.as_bytes(), now),
            Entry::Client(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_bytes(env, &response))
    })
}

/// 加密响应体。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverSeal<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    session_id_hex: JString<'local>,
    aad: JByteArray<'local>,
    plaintext: JByteArray<'local>,
    now_ms: jlong,
) -> jbyteArray {
    let sid = read_string(&session_id_hex);
    run(&mut env, core::ptr::null_mut(), |env| {
        let aad = read_bytes(env, aad);
        let plaintext = read_bytes(env, plaintext);
        let now = now_ms.max(0) as u64;
        let frame = with_entry(handle, |entry| match entry {
            Entry::Server(server) => server.seal(&sid, &aad, &plaintext, now),
            Entry::Client(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_bytes(env, &frame))
    })
}

/// 解密请求体。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverOpen<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    session_id_hex: JString<'local>,
    aad: JByteArray<'local>,
    frame: JByteArray<'local>,
    now_ms: jlong,
) -> jbyteArray {
    let sid = read_string(&session_id_hex);
    run(&mut env, core::ptr::null_mut(), |env| {
        let aad = read_bytes(env, aad);
        let frame = read_bytes(env, frame);
        let now = now_ms.max(0) as u64;
        let plaintext = with_entry(handle, |entry| match entry {
            Entry::Server(server) => server.open(&sid, &aad, &frame, now),
            Entry::Client(_) => Err(CryptoError::SessionNotReady),
        })
        .unwrap_or(Err(CryptoError::SessionNotReady))?;
        Ok(write_bytes(env, &plaintext))
    })
}

/// 会话是否仍然有效。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverHasSession<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    session_id_hex: JString<'local>,
    now_ms: jlong,
) -> jboolean {
    let sid = read_string(&session_id_hex);
    let now = now_ms.max(0) as u64;
    let has = with_entry(handle, |entry| match entry {
        Entry::Server(server) => server.has_session(&sid, now),
        Entry::Client(_) => false,
    })
    .unwrap_or(false);
    if has {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 主动丢弃一条会话。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverDropSession<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    session_id_hex: JString<'local>,
) -> jboolean {
    let sid = read_string(&session_id_hex);
    let dropped = with_entry(handle, |entry| match entry {
        Entry::Server(server) => server.drop_session(&sid),
        Entry::Client(_) => false,
    })
    .unwrap_or(false);
    if dropped {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// 当前会话数量，用于监控与容量告警。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverSessionCount<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jlong {
    with_entry(handle, |entry| match entry {
        Entry::Server(server) => server.session_count() as jlong,
        Entry::Client(_) => 0,
    })
    .unwrap_or(0)
}

/// 清理过期会话，返回清理掉的条数。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverSweepExpired<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now_ms: jlong,
) -> jlong {
    let now = now_ms.max(0) as u64;
    with_entry(handle, |entry| match entry {
        Entry::Server(server) => server.sweep_expired(now) as jlong,
        Entry::Client(_) => 0,
    })
    .unwrap_or(0)
}

/// 释放服务端引擎。
#[no_mangle]
pub extern "system" fn Java_com_taotao_music_crypto_NativeCrypto_serverFree<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    if remove_entry(handle) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
