//! 传输加密的 Tauri 桥接层（plans/009）。
//!
//! 职责边界：**这里只做密码学，不做 HTTP**。取 PSK、握手上报、业务请求等所有
//! 网络动作都留在前端 JS，经 `tauri-plugin-http` 直连后端——传输层的超时、
//! 域名白名单（capabilities ACL）仍收在一条通道里，本层只负责把字节加解密。
//!
//! 与安卓端（crypto-src 的 JNI 绑定）和服务端的契约对应关系：
//! - 引擎状态机同源：`taotao-crypto-core::ClientEngine`，四端一份协议实现；
//! - AAD = `method + pathAndQuery`（含 query），统一走 core 的 `aad()` 构造，
//!   与服务端 `CryptoTransportService.aad(originalUrl)`、安卓侧完全同构；
//! - `X-Taotao-Crypto` 头值来自 `last_sealed_header()`——**必须在 `seal()` 之后
//!   调用**（seal 推进发送序号，头取「刚发出的那一帧」的序号）；
//! - 错误前缀 `REKEY_REQUIRED` / `NO_SESSION` 是机器可判定的契约：JS 侧据此
//!   决定重新握手还是直接报错，加密失败**不允许回退明文**（服务端对强制加密
//!   路径的明文请求返回 426/4007，降级攻击面就是这么堵死的）；
//! - rekey 没有专门的 API：core 引擎没有 rekey 方法，重键 = 重新
//!   `crypto_session_new` + 握手（整体换会话，旧引擎直接丢弃）。

use std::sync::{Mutex, MutexGuard};

use serde::Serialize;
use tauri::State;
use taotao_crypto_core::ClientEngine;

/// Tauri managed state：全局唯一的加密引擎（未引导/已重置时为 None）。
///
/// 并发的 seal/open 由外层 `Mutex` 串行化——seal 会推进会话发送序号，本来
/// 就必须串行；每次请求两次短临界区，锁竞争可以忽略。
pub struct CryptoState {
    engine: Option<ClientEngine>,
}

impl CryptoState {
    /// 初始空 state：lib.rs 在 `.manage()` 时构造。
    pub fn empty() -> Self {
        Self { engine: None }
    }
}

/// 加锁。**poison 按空 state 处理**：某次加密操作 panic 之后，引擎的序号与
/// 中间状态不可信，与其带着损坏状态继续（发出去的会是乱码帧），不如当作
/// 未初始化——下一次 seal 自然返回 `REKEY_REQUIRED`，走重新握手自愈。
fn lock_state(m: &Mutex<CryptoState>) -> MutexGuard<'_, CryptoState> {
    m.lock().unwrap_or_else(|poisoned| {
        let mut inner = poisoned.into_inner();
        inner.engine = None;
        inner
    })
}

/// `crypto_seal` 的返回：`X-Taotao-Crypto` 头值 + 密文帧（base64）。
///
/// 字段名刻意保持 serde 默认的 snake_case，JS 侧按 `sealed.frame_b64` 取——
/// 两端以本注释为契约，任何一方都不要单方面改名。
#[derive(Serialize)]
pub struct SealedFrame {
    header: String,
    frame_b64: String,
}

/// 建引擎并替换 state 里可能存在的旧引擎（rekey 语义：整体换会话）。
#[tauri::command]
pub fn crypto_session_new(
    psk_id: String,
    psk_hex: String,
    device_id: String,
    state: State<'_, Mutex<CryptoState>>,
) -> Result<(), String> {
    // 反调试：这是 PSK 进入内存的唯一入口，检测放在参数落地之前。
    //
    // 为什么启动时（lib.rs 的 run）查过了这里还要再查一次：启动检测看不见
    // 「先正常启动、之后再 attach 调试器」这条路 —— 而那恰恰是逆向者跟
    // `crypto_seal` / 读 PSK 的实际姿势。入口复查把检测铺在他真正要经过的地方。
    //
    // 开销是两次 Win32 查询（`IsDebuggerPresent` + `CheckRemoteDebuggerPresent`，
    // 都是读进程内存里的字段，微秒级），而本命令只在建会话时调用一次，
    // 不在每请求的封帧路径上。
    super::enforce_antidebug();

    // device_id 折进握手密钥（协议 v2）：两端不一致握手必被 MAC 拒掉，
    // JS 侧传的是 localStorage 的 taotao.deviceId，与播放上报同源。
    let engine =
        ClientEngine::new(&psk_id, &psk_hex, &device_id).map_err(|e| format!("初始化加密引擎失败：{e}"))?;
    lock_state(&state).engine = Some(engine);
    Ok(())
}

/// 生成 ClientHello（base64），JS 侧 POST 给 `/crypto/handshake`。
#[tauri::command]
pub fn crypto_client_hello(
    now_ms: u64,
    state: State<'_, Mutex<CryptoState>>,
) -> Result<String, String> {
    let mut guard = lock_state(&state);
    let Some(engine) = guard.engine.as_mut() else {
        return Err("NO_SESSION：加密引擎未初始化，请先 crypto_session_new".into());
    };
    let hello = engine
        .handshake(now_ms)
        .map_err(|e| format!("生成 ClientHello 失败：{e}"))?;
    Ok(b64_encode(&hello))
}

/// 用 ServerHello 完成握手，建立会话。
#[tauri::command]
pub fn crypto_finish(
    server_hello_b64: String,
    now_ms: u64,
    state: State<'_, Mutex<CryptoState>>,
) -> Result<(), String> {
    let server_hello = b64_decode(&server_hello_b64)
        .map_err(|e| format!("ServerHello base64 解码失败：{e}"))?;
    let mut guard = lock_state(&state);
    let Some(engine) = guard.engine.as_mut() else {
        return Err("NO_SESSION：加密引擎未初始化，请先 crypto_session_new".into());
    };
    engine
        .finish(&server_hello, now_ms)
        .map_err(|e| format!("握手完成失败：{e}"))
}

/// 加密一个请求体，返回 `X-Taotao-Crypto` 头值 + 密文帧。
///
/// 无引擎、无可用会话或即将重键（`needs_rekey`：过期、快过期、帧数接近上限）
/// 一律返回 `REKEY_REQUIRED`——JS 侧收到后必须重新握手，不允许回退明文。
#[tauri::command]
pub fn crypto_seal(
    method: String,
    path: String,
    body_b64: String,
    now_ms: u64,
    state: State<'_, Mutex<CryptoState>>,
) -> Result<SealedFrame, String> {
    let plaintext = b64_decode(&body_b64).map_err(|e| format!("请求体 base64 解码失败：{e}"))?;
    let mut guard = lock_state(&state);
    let Some(engine) = guard.engine.as_mut() else {
        return Err("REKEY_REQUIRED".into());
    };
    if !engine.has_session(now_ms) || engine.needs_rekey(now_ms) {
        return Err("REKEY_REQUIRED".into());
    }
    // AAD = method + pathAndQuery（含 query），必须与服务端看到的 originalUrl
    // 逐字节一致；拼错的表现是服务端 400/4006 解密失败，所以统一走 core 构造。
    let aad = taotao_crypto_core::aad(&method, &path);
    let frame = engine
        .seal(&aad, &plaintext, now_ms)
        .map_err(|e| format!("加密请求失败：{e}"))?;
    // 契约：last_sealed_header 必须在 seal 之后调——seal 推进序号，
    // 头取的是「刚发出的那一帧」；调早了序号会差一。
    let header = engine
        .last_sealed_header(now_ms)
        .map_err(|e| format!("取加密头失败：{e}"))?;
    Ok(SealedFrame {
        header,
        frame_b64: b64_encode(&frame),
    })
}

/// 解密一个响应体，返回明文的 base64。解不开由 JS 侧按明文容错。
#[tauri::command]
pub fn crypto_open(
    method: String,
    path: String,
    frame_b64: String,
    now_ms: u64,
    state: State<'_, Mutex<CryptoState>>,
) -> Result<String, String> {
    let frame = b64_decode(&frame_b64).map_err(|e| format!("响应帧 base64 解码失败：{e}"))?;
    let mut guard = lock_state(&state);
    let Some(engine) = guard.engine.as_mut() else {
        return Err("NO_SESSION：加密引擎未初始化，请先 crypto_session_new".into());
    };
    let aad = taotao_crypto_core::aad(&method, &path);
    let plain = engine
        .open(&aad, &frame, now_ms)
        .map_err(|e| format!("解密响应失败：{e}"))?;
    Ok(b64_encode(&plain))
}

/// 丢弃引擎。JS 侧收到 409/4091（会话失效）时调用，随后重新走
/// `crypto_session_new` + 握手。
#[tauri::command]
pub fn crypto_reset(state: State<'_, Mutex<CryptoState>>) -> Result<(), String> {
    lock_state(&state).engine = None;
    Ok(())
}

/// 本机设备号（Windows 注册表 MachineGuid）。
///
/// 注意与握手的 deviceId 解耦：JS 侧握手用的是 localStorage 的
/// `taotao.deviceId`（与播放上报同源），此命令供需要硬件级稳定标识的
/// 场景（如诊断）预留。
#[tauri::command]
pub fn crypto_device_id() -> String {
    super::device_id()
}

// ---- base64（标准字母表 + padding，手写避免引入新依赖使 Cargo.lock 漂移） ----

/// 标准 base64 字母表（RFC 4648，含 `+` `/`，尾部补 `=`）。
const B64_ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// 标准 base64 编码：3 字节一组 → 4 字符，余数按 1/2 字节补 `==`/`=`。
pub(crate) fn b64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = chunk.get(1).copied().unwrap_or(0) as u32;
        let b2 = chunk.get(2).copied().unwrap_or(0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64_ALPHABET[((n >> 18) & 63) as usize] as char);
        out.push(B64_ALPHABET[((n >> 12) & 63) as usize] as char);
        out.push(if chunk.len() > 1 {
            B64_ALPHABET[((n >> 6) & 63) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            B64_ALPHABET[(n & 63) as usize] as char
        } else {
            '='
        });
    }
    out
}

/// 标准 base64 解码：严格校验长度（4 的倍数）、字符集与填充位置，非法输入报错。
pub(crate) fn b64_decode(input: &str) -> Result<Vec<u8>, String> {
    fn value_of(c: u8) -> Result<u32, String> {
        match c {
            b'A'..=b'Z' => Ok((c - b'A') as u32),
            b'a'..=b'z' => Ok((c - b'a' + 26) as u32),
            b'0'..=b'9' => Ok((c - b'0' + 52) as u32),
            b'+' => Ok(62),
            b'/' => Ok(63),
            _ => Err(format!("base64 含非法字符：{}", c as char)),
        }
    }
    let s = input.as_bytes();
    if s.len() % 4 != 0 {
        return Err(format!("base64 长度 {} 不是 4 的倍数", s.len()));
    }
    let mut out = Vec::with_capacity(s.len() / 4 * 3);
    for (gi, group) in s.chunks_exact(4).enumerate() {
        let is_last = (gi + 1) * 4 == s.len();
        let pad = group.iter().filter(|&&c| c == b'=').count();
        if pad > 2 {
            return Err("base64 填充符最多两个".into());
        }
        if pad > 0 && !is_last {
            return Err("base64 填充符只允许出现在末尾".into());
        }
        // '=' 必须连续占据组尾： pad 位，前 4-pad 位必须是字母表字符。
        for (k, &c) in group.iter().enumerate() {
            let expect_pad = k >= 4 - pad;
            if (c == b'=') != expect_pad {
                return Err("base64 填充符位置非法".into());
            }
        }
        let mut n: u32 = 0;
        for &c in &group[..4 - pad] {
            n = (n << 6) | value_of(c)?;
        }
        n <<= 6 * pad as u32;
        match pad {
            0 => out.extend_from_slice(&[(n >> 16) as u8, (n >> 8) as u8, n as u8]),
            1 => out.extend_from_slice(&[(n >> 16) as u8, (n >> 8) as u8]),
            _ => out.push((n >> 16) as u8),
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn b64_roundtrip_empty() {
        assert_eq!(b64_encode(b""), "");
        assert_eq!(b64_decode("").unwrap(), Vec::<u8>::new());
    }

    #[test]
    fn b64_known_vectors() {
        // RFC 4648 的标准测试向量，锁字母表与 padding 行为。
        assert_eq!(b64_encode(b"f"), "Zg==");
        assert_eq!(b64_encode(b"fo"), "Zm8=");
        assert_eq!(b64_encode(b"foo"), "Zm9v");
        assert_eq!(b64_encode(b"foob"), "Zm9vYg==");
        assert_eq!(b64_encode(b"fooba"), "Zm9vYmE=");
        assert_eq!(b64_encode(b"foobar"), "Zm9vYmFy");
        assert_eq!(b64_decode("Zg==").unwrap(), b"f");
        assert_eq!(b64_decode("Zm8=").unwrap(), b"fo");
        assert_eq!(b64_decode("Zm9v").unwrap(), b"foo");
    }

    #[test]
    fn b64_roundtrip_all_lengths() {
        // 0..=255 全字节 + 各类长度：0..=97 覆盖空串与 1/2 字节余数两类 padding。
        let all_bytes: Vec<u8> = (0..=255u8).collect();
        assert_eq!(b64_decode(&b64_encode(&all_bytes)).unwrap(), all_bytes);
        for len in 0..=97usize {
            let data: Vec<u8> = (0..len).map(|i| (i * 37 + 11) as u8).collect();
            assert_eq!(b64_decode(&b64_encode(&data)).unwrap(), data, "len={len}");
        }
    }

    #[test]
    fn b64_decode_rejects_invalid() {
        assert!(b64_decode("A").is_err(), "长度不是 4 的倍数");
        assert!(b64_decode("ABCDEFA").is_err(), "长度不是 4 的倍数");
        assert!(b64_decode("Zm9v!===").is_err(), "非法字符");
        assert!(b64_decode("Zm+v").is_ok(), "+ 合法");
        assert!(b64_decode("====").is_err(), "填充超过两个");
        assert!(b64_decode("=m9v").is_err(), "填充出现在组首");
        assert!(b64_decode("Z=g=").is_err(), "填充没有贴住组尾");
        assert!(b64_decode("Zg==Zg==").is_err(), "填充出现在非末尾组");
        assert!(b64_decode("Z===").is_err(), "整组全是填充");
    }
}
