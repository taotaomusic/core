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

fn fetch_psk(endpoint: &str, bearer: &str) -> Result<(String, String), String> {
    let url = format!("{endpoint}/api/v1/crypto/psk");
    let resp = ureq::get(&url)
        .set("Authorization", &format!("Bearer {bearer}"))
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
fn crypto_handshake_demo(endpoint: String, bearer: String) -> Result<String, String> {
    let endpoint = endpoint.trim_end_matches('/').to_string();
    let dev = device_id();
    let (psk_id, psk_hex) = fetch_psk(&endpoint, &bearer)?;

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
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        // 单实例：必须最先注册。二次启动时回调在新进程里执行，
        // 通过 app 句柄通知已有实例把主窗口唤到前台；回调返回后新进程自动退出。
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app);
        }))
        .setup(|app| {
            // 系统托盘：图标用应用图标；左键单击唤起主窗口，右键菜单提供
            // 「显示主窗口 / 退出」。图标缺失时降级为仅菜单行为，不影响主功能。
            let mut tray_builder = tauri::tray::TrayIconBuilder::with_id("main-tray")
                .tooltip("桃桃音乐")
                .menu(&tray_menu(app.handle())?)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "tray-show" => show_main(app),
                    "tray-quit" => {
                        // 标准退出：不走 CloseRequested，隐藏逻辑不会拦住它
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let tauri::tray::TrayIconEvent::Click {
                        button: tauri::tray::MouseButton::Left,
                        button_state: tauri::tray::MouseButtonState::Up,
                        ..
                    } = event
                    {
                        show_main(tray.app_handle());
                    }
                });
            if let Some(icon) = app.default_window_icon().cloned() {
                tray_builder = tray_builder.icon(icon);
            }
            if let Err(e) = tray_builder.build(app) {
                // 托盘建不起来（极端环境）不影响主功能，记日志即可
                eprintln!("托盘初始化失败：{e}");
            }

            // 启动时静默检查更新：有新版本就下载安装并重启。失败只记日志，不打断使用。
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = check_update(handle).await {
                    eprintln!("更新检查失败：{e}");
                }
            });
            Ok(())
        })
        .on_window_event(|window, event| {
            // 关窗 ≠ 退出：点 X 隐藏到托盘继续播放（后台播放），
            // 真正退出走托盘菜单「退出」。仅拦主窗口的关闭请求。
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    let _ = window.hide();
                    api.prevent_close();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![crypto_handshake_demo])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

/// 托盘菜单：显示主窗口 + 退出。
fn tray_menu(app: &tauri::AppHandle) -> Result<tauri::menu::Menu<tauri::Wry>, tauri::Error> {
    use tauri::menu::{MenuBuilder, MenuItemBuilder};
    let show = MenuItemBuilder::with_id("tray-show", "显示主窗口").build(app)?;
    let quit = MenuItemBuilder::with_id("tray-quit", "退出").build(app)?;
    MenuBuilder::new(app).items(&[&show, &quit]).build()
}

/// 把主窗口从隐藏/最小化状态唤回前台（托盘点击与单实例回调共用）。
fn show_main(app: &tauri::AppHandle) {
    use tauri::Manager;
    if let Some(win) = app.get_webview_window("main") {
        let _ = win.unminimize();
        let _ = win.show();
        let _ = win.set_focus();
    }
}

/// 检查并安装更新（签名由 tauri.conf.json 的 pubkey 校验）。
async fn check_update(handle: tauri::AppHandle) -> Result<(), Box<dyn std::error::Error>> {
    use tauri_plugin_updater::UpdaterExt;
    if let Some(update) = handle.updater()?.check().await? {
        update.download_and_install(|_, _| {}, || {}).await?;
        handle.restart();
    }
    Ok(())
}
