import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";

/**
 * 桌面端最小骨架：验证 Tauri(Rust) ↔ crypto-src/core 打通。
 * 点击后调用后端 `crypto_handshake_demo` 命令——它用后端下发的 PSK 完成一次
 * 设备绑定握手并返回会话 ID。真正的业务 UI（React 客户端）后续在此之上搭，
 * 并计划与浏览器版客户端 web 融合；与分享播放器（webApp/）无关。
 */
export function App() {
  const [endpoint, setEndpoint] = useState("https://music.xydaigua.cn");
  const [token, setToken] = useState("");
  const [result, setResult] = useState<string>("");

  async function handshake() {
    setResult("握手中…");
    try {
      const sid = await invoke<string>("crypto_handshake_demo", { endpoint, token });
      setResult(`握手成功，会话 ID：${sid}`);
    } catch (e) {
      setResult(`失败：${String(e)}`);
    }
  }

  return (
    <main style={{ fontFamily: "system-ui", padding: 24, maxWidth: 560 }}>
      <h1>桃桃音乐 · 桌面（Tauri + React）</h1>
      <p style={{ color: "#666" }}>骨架验证：Rust 端直接调用 crypto core 完成设备绑定握手。</p>
      <label>后端地址</label>
      <input value={endpoint} onChange={(e) => setEndpoint(e.target.value)} style={{ width: "100%" }} />
      <label>登录令牌（Bearer）</label>
      <input value={token} onChange={(e) => setToken(e.target.value)} style={{ width: "100%" }} />
      <button onClick={handshake} style={{ marginTop: 12 }}>测试加密握手</button>
      <pre style={{ background: "#f5f5f5", padding: 12, marginTop: 12 }}>{result}</pre>
    </main>
  );
}
