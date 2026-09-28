//! 桃桃音乐桌面（Tauri）Rust 侧。
//!
//! 传输加密直接调用同仓 `taotao-crypto-core`（不走 JNI/.dll）。PSK 由后端**动态下发**，
//! 与安卓客户端同一套模型：取 `/crypto/psk` → 用设备号做设备绑定握手 → seal/open。
//! 这里先提供一个握手自检命令，业务 UI（React）在其上迭代。

use base64::Engine as _;
use serde::Serialize;
use std::time::{SystemTime, UNIX_EPOCH};
use taotao_crypto_core::ClientEngine;

const B64: base64::engine::general_purpose::GeneralPurpose = base64::engine::general_purpose::STANDARD;

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// 设备号：Windows 取注册表 MachineGuid；其它平台退回占位。折进握手密钥做设备绑定。
fn device_id() -> String {
    #[cfg(windows)]
    {
        use winreg::enums::{HKEY_LOCAL_MACHINE, KEY_READ, KEY_WOW64_64KEY};
        use winreg::RegKey;
        let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
        if let Ok(k) = hklm.open_subkey_with_flags(
            r"SOFTWARE\Microsoft\Cryptography",
            KEY_READ | KEY_WOW64_64KEY,
        ) {
            if let Ok(v) = k.get_value::<String, _>("MachineGuid") {
                let t = v.trim();
                if !t.is_empty() {
                    return t.to_string();
                }
            }
        }
        "unknown-windows-device".to_string()
    }
    #[cfg(not(windows))]
    {
        "unknown-desktop-device".to_string()
    }
}

/// 从信封响应 `{code,message,data}` 里取 data 层（兼容无信封）。
fn envelope_data(body: &str) -> Result<serde_json::Value, String> {
    let v: serde_json::Value = serde_json::from_str(body).map_err(|e| e.to_string())?;
    Ok(v.get("data").cloned().unwrap_or(v))
}

fn fetch_psk(endpoint: &str, token: &str) -> Result<(String, String), String> {
    let url = format!("{endpoint}/api/v1/crypto/psk");
    let resp = ureq::get(&url)
        .set("Authorization", &format!("Bearer {token}"))
        .set("Accept", "application/json")
        .call()
        .map_err(|e| format!("取 PSK 失败：{e}"))?;
    let body = resp.into_string().map_err(|e| e.to_string())?;
    let data = envelope_data(&body)?;
    let psk_id = data.get("pskId").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let psk_hex = data.get("pskHex").and_then(|x| x.as_str()).unwrap_or("").to_string();
    if psk_id.is_empty() || psk_hex.is_empty() {
        return Err("PSK 响应缺少字段".into());
    }
    Ok((psk_id, psk_hex))
}

#[derive(Serialize)]
struct HandshakeBody {
    #[serde(rename = "clientHello")]
    client_hello: String,
    #[serde(rename = "deviceId")]
    device_id: String,
}

/// 握手自检：取 PSK → 设备绑定握手 → 返回会话 ID（十六进制）。
#[tauri::command]
fn crypto_handshake_demo(endpoint: String, token: String) -> Result<String, String> {
    let endpoint = endpoint.trim_end_matches('/').to_string();
    let dev = device_id();
    let (psk_id, psk_hex) = fetch_psk(&endpoint, &token)?;

    let mut client = ClientEngine::new(&psk_id, &psk_hex, &dev).map_err(|e| e.to_string())?;
    let now = now_ms();
    let hello = client.handshake(now).map_err(|e| e.to_string())?;

    let body = HandshakeBody { client_hello: B64.encode(&hello), device_id: dev };
    let resp = ureq::post(&format!("{endpoint}/api/v1/crypto/handshake"))
        .set("Content-Type", "application/json")
        .set("Accept", "application/json")
        .send_json(&body)
        .map_err(|e| format!("握手请求失败：{e}"))?;
    let rbody = resp.into_string().map_err(|e| e.to_string())?;
    let data = envelope_data(&rbody)?;
    let server_hello_b64 = data.get("serverHello").and_then(|x| x.as_str()).unwrap_or("");
    if server_hello_b64.is_empty() {
        return Err("握手响应缺少 serverHello".into());
    }
    let server_hello = B64.decode(server_hello_b64).map_err(|e| e.to_string())?;
    client.finish(&server_hello, now_ms()).map_err(|e| e.to_string())?;
    Ok(client.session_id_hex())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![crypto_handshake_demo])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
