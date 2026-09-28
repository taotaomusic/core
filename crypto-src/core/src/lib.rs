//! # taotao-crypto-core
//!
//! 桃桃音乐传输层加密协议的纯 Rust 实现。**不依赖任何 C 库**，这是它能同时
//! 交叉编译到 Android / Windows / Node / WebAssembly 四个目标的前提。
//!
//! ## 分层
//!
//! ```text
//!   ┌─────────────────────────────────────────────────┐
//!   │ 绑定门面（只做类型转换，不含协议逻辑）              │
//!   │  jni/  .so + .dll    node/  .node   wasm/  .wasm │
//!   └───────────────────────┬─────────────────────────┘
//!                           │
//!   ┌───────────────────────▼─────────────────────────┐
//!   │ 本 crate（taotao-crypto-core）                   │
//!   │  protocol → handshake → session → frame → aead   │
//!   └─────────────────────────────────────────────────┘
//! ```
//!
//! 协议逻辑只有这一份实现，四个语言侧拿到的是同一套字节格式。任何一侧出现
//! 「只有我这边解不开」的问题，一定是绑定层或调用方拼 AAD 的方式错了 ——
//! 这正是把逻辑全部收进 core 的价值。
//!
//! ## 最小用法
//!
//! 调用顺序如下。**完整可运行的版本**是文件末尾的 `end_to_end_through_public_api`
//! 单元测试 —— 那里会真的握手、真的加密解密并断言结果。
//!
//! ```text
//! let psk = Psk::from_hex("prod-v1", "<64 个 hex 字符>")?;
//! let now = 1_700_000_000_000u64;
//! let mut rng = OsRandom;
//!
//! // 1. 客户端发起握手（device_id 折进握手密钥，无设备号传空串 b""）
//! let (handshake, hello_bytes) = ClientHandshake::start(psk, b"<device_id>", now, &mut rng)?;
//! // ...把 hello_bytes 发出去，拿回 server_hello...
//! let mut session = handshake.finish(&server_hello, now)?;
//!
//! // 2. 加密请求
//! let aad = protocol::aad_context("GET", "/api/v1/favorites");
//! let frame = session.seal(&aad, b"{}", now)?;
//! // frame.bytes 作为请求体；session.header_value(frame.seq) 作为 X-Taotao-Crypto 头
//! ```
//!
//! > 这段刻意写成 `text` 而不是可编译的 doctest。
//! > `cargo test --doc` 需要为每个示例再拉起一个 rustc 进程，在 Windows 上会
//! > 稳定撞到 `ERROR_NO_DATA`（"所有的管道范例都在使用中"），
//! > 让整个测试套件永远不绿。文档示例的正确性由单元测试保证，而不是靠 doctest。
//!
//! ## 明确不做的事
//!
//! - **不加密音频流**。`/songs/:id/play` 这类代理的是几百 MB 的音频流，
//!   逐块 AEAD 会让 CPU 占用翻倍而收益极低（音频本身没有秘密，直链才是）。
//! - **不加密大文件上传**（APK / jar / 补丁包）。同样是吞吐换不到安全收益。
//! - **不替代 TLS**。这是应用层的第二道防线，用来抬高抓包和重放的门槛，
//!   不是用来在明文 HTTP 上裸奔的。
//!
//! 详细边界见 `docs/design.md` 的「目标与边界」章节。

#![deny(unsafe_op_in_unsafe_fn)]
#![warn(missing_debug_implementations)]

pub mod aead;
pub mod engine;
pub mod error;
pub mod frame;
pub mod handshake;
pub mod kdf;
pub mod obf;
pub mod protocol;
pub mod psk;
pub mod session;

pub use engine::{aad, ClientEngine, ServerEngine};
pub use error::{CryptoError, Result};
pub use frame::{OpenedFrame, ReplayWindow, SealedFrame};
pub use handshake::{
    accept_client_hello, peek_psk_id, AcceptedHandshake, ClientHandshake, HelloReplayCache,
};
pub use kdf::{random_array, OsRandom, RandomSource};
pub use psk::{Psk, PskStore};
pub use session::Session;

/// 协议版本，绑定层用它做能力声明。
pub const PROTOCOL_VERSION: u8 = protocol::PROTOCOL_VERSION;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_version_is_exposed() {
        // v2：握手密钥绑定设备号（见 kdf::derive_handshake_key）。
        assert_eq!(PROTOCOL_VERSION, 2);
    }

    #[test]
    fn end_to_end_through_public_api() {
        // 走一遍完整链路，确认公开 API 的签名组合是可用的。
        let psk = Psk::from_hex("test", &"11".repeat(32)).unwrap();
        let now = 1_700_000_000_000u64;
        let mut rng = OsRandom;

        let (handshake, hello) =
            ClientHandshake::start(psk.clone(), b"dev-01", now, &mut rng).unwrap();

        let mut store = PskStore::new();
        store.insert(psk);
        let mut cache = HelloReplayCache::new();
        let accepted =
            accept_client_hello(&store, &hello, b"dev-01", now, &mut cache, &mut rng).unwrap();

        let mut client = handshake.finish(&accepted.response, now).unwrap();
        let mut server = accepted.session;

        let aad = protocol::aad_context("POST", "/api/v1/playlists");
        let frame = client
            .seal(&aad, r#"{"name":"夜跑"}"#.as_bytes(), now)
            .unwrap();

        // HTTP 头 → 会话 ID 的往返。
        let header = client.header_value(frame.seq);
        let (sid, seq) = Session::parse_header_value(&header).unwrap();
        assert_eq!(sid, server.id());
        assert_eq!(seq, frame.seq);

        let opened = server.open(&aad, &frame.bytes, now).unwrap();
        assert_eq!(opened.plaintext, r#"{"name":"夜跑"}"#.as_bytes());
    }
}
