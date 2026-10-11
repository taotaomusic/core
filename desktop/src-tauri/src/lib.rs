//! 桃桃音乐桌面（Tauri）Rust 侧。
//!
//! 传输加密（plans/009）采用「Rust 只做密码学、HTTP 留在 JS」的分层：
//! `crypto_bridge` 持有同仓 `taotao-crypto-core` 的 `ClientEngine`（与安卓/
//! 服务端同一份协议实现），通过 `crypto_seal` / `crypto_open` 等命令为前端
//! 加解密；取 PSK、握手上报与业务请求仍由前端经 `tauri-plugin-http` 直连后端。
//! 契约：AAD = method + pathAndQuery（含 query）；会话失效 409/4091、
//! 强制加密路径明文请求 426/4007；rekey = 重新建引擎 + 握手，无独立 API。

use std::sync::Mutex;

mod crypto_bridge;

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
        // 传输加密引擎的全局状态：由 crypto_bridge 的各命令维护（建/用/弃）。
        .manage(Mutex::new(crypto_bridge::CryptoState::empty()))
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
        .invoke_handler(tauri::generate_handler![
            crypto_bridge::crypto_session_new,
            crypto_bridge::crypto_client_hello,
            crypto_bridge::crypto_finish,
            crypto_bridge::crypto_seal,
            crypto_bridge::crypto_open,
            crypto_bridge::crypto_reset,
            crypto_bridge::crypto_device_id,
        ])
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
