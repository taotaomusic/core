import { useState, type CSSProperties } from "react";
import { invoke } from "@tauri-apps/api/core";

const ENDPOINT = "https://music.xydaigua.cn";

/** 从后端信封响应 {code,message,data} 取 data（兼容无信封）。 */
async function postJson(path: string, body: unknown): Promise<any> {
  const resp = await fetch(`${ENDPOINT}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  const json = await resp.json().catch(() => ({}));
  if (!resp.ok || (json && json.code !== undefined && json.code !== 0)) {
    throw new Error(json?.message || `HTTP ${resp.status}`);
  }
  return json?.data ?? json;
}

export function App() {
  const [token, setToken] = useState<string | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState("");

  async function login() {
    setBusy(true);
    setError("");
    try {
      const data = await postJson("/api/v1/auth/login", { username, password });
      if (!data?.accessToken) throw new Error("登录响应缺少 accessToken");
      setToken(data.accessToken);
    } catch (e) {
      setError(String(e instanceof Error ? e.message : e));
    } finally {
      setBusy(false);
    }
  }

  async function testHandshake() {
    setResult("握手中…");
    try {
      const sid = await invoke<string>("crypto_handshake_demo", { endpoint: ENDPOINT, token });
      setResult(`加密握手成功，会话 ID：${sid}`);
    } catch (e) {
      setResult(`失败：${String(e)}`);
    }
  }

  const box: CSSProperties = {
    fontFamily: "system-ui, sans-serif",
    padding: 32,
    maxWidth: 420,
    margin: "40px auto",
  };
  const input: CSSProperties = { width: "100%", padding: 8, margin: "6px 0", boxSizing: "border-box" };

  if (!token) {
    return (
      <main style={box}>
        <h1>桃桃音乐 · 登录</h1>
        <input style={input} placeholder="用户名" value={username} onChange={(e) => setUsername(e.target.value)} />
        <input
          style={input}
          type="password"
          placeholder="密码"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && login()}
        />
        <button style={{ ...input, cursor: "pointer" }} disabled={busy || !username || !password} onClick={login}>
          {busy ? "登录中…" : "登录"}
        </button>
        {error && <p style={{ color: "#c00" }}>{error}</p>}
      </main>
    );
  }

  return (
    <main style={box}>
      <h1>桃桃音乐 · 桌面</h1>
      <p style={{ color: "#666" }}>已登录。下面验证传输加密（设备绑定握手）。</p>
      <button style={{ ...input, cursor: "pointer" }} onClick={testHandshake}>
        测试加密握手
      </button>
      <button style={{ ...input, cursor: "pointer" }} onClick={() => { setToken(null); setResult(""); }}>
        退出登录
      </button>
      <pre style={{ background: "#f5f5f5", padding: 12, whiteSpace: "pre-wrap" }}>{result}</pre>
    </main>
  );
}
