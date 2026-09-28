import { useState, type CSSProperties } from "react";
import { invoke } from "@tauri-apps/api/core";
import "./App.css";

const ENDPOINT = "https://music.xydaigua.cn";

/** 从后端信封响应 {code,message,data} 取 data（兼容无信封），非 0 码抛错。 */
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
  if (!token) return <Auth onToken={setToken} />;
  return <Home token={token} onLogout={() => setToken(null)} />;
}

// PLACEHOLDER_REST
function Auth({ onToken }: { onToken: (t: string) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [codeBusy, setCodeBusy] = useState(false);
  const [err, setErr] = useState("");
  const [ok, setOk] = useState("");

  async function sendCode() {
    setErr("");
    setOk("");
    if (!email) return setErr("请先填邮箱");
    setCodeBusy(true);
    try {
      await postJson("/api/v1/auth/email-verification", { email });
      setOk("验证码已发送，请查收邮箱");
    } catch (e) {
      setErr(msg(e));
    } finally {
      setCodeBusy(false);
    }
  }

  async function submit() {
    setErr("");
    setOk("");
    setBusy(true);
    try {
      const data =
        mode === "login"
          ? await postJson("/api/v1/auth/login", { username, password })
          : await postJson("/api/v1/auth/register", { username, password, email, verificationCode: code });
      if (!data?.accessToken) throw new Error("响应缺少 accessToken");
      onToken(data.accessToken);
    } catch (e) {
      setErr(msg(e));
    } finally {
      setBusy(false);
    }
  }

  const canSubmit =
    mode === "login" ? username && password : username && password && email && code;

  return (
    <div className="auth-bg">
      <div className="auth-card">
        <div className="auth-brand">
          <div className="logo">♪</div>
          <div>
            <h1>桃桃音乐</h1>
            <p>桌面客户端</p>
          </div>
        </div>

        <div className="tabs">
          <button className={mode === "login" ? "active" : ""} onClick={() => { setMode("login"); setErr(""); setOk(""); }}>登录</button>
          <button className={mode === "register" ? "active" : ""} onClick={() => { setMode("register"); setErr(""); setOk(""); }}>注册</button>
        </div>

        <div className="field">
          <label>用户名</label>
          <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="用户名" />
        </div>

        {mode === "register" && (
          <div className="field">
            <label>邮箱</label>
            <div className="row">
              <div className="field" style={{ margin: 0 }}>
                <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="邮箱" />
              </div>
              <button className="btn btn-code" disabled={codeBusy || !email} onClick={sendCode}>
                {codeBusy ? "发送中" : "发送验证码"}
              </button>
            </div>
          </div>
        )}

        {mode === "register" && (
          <div className="field">
            <label>验证码</label>
            <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="邮箱验证码" />
          </div>
        )}

        <div className="field">
          <label>密码</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="密码"
            onKeyDown={(e) => e.key === "Enter" && canSubmit && submit()}
          />
        </div>

        <button className="btn" disabled={busy || !canSubmit} onClick={submit}>
          {busy ? "请稍候…" : mode === "login" ? "登录" : "注册并登录"}
        </button>

        {err && <p className="msg err">{err}</p>}
        {ok && <p className="msg ok">{ok}</p>}
      </div>
    </div>
  );
}

function Home({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [result, setResult] = useState("");
  const btn: CSSProperties = { display: "block", width: "100%", padding: 11, margin: "8px 0", border: 0, borderRadius: 10, cursor: "pointer", fontSize: 15, fontWeight: 600, color: "#fff", background: "#4f46e5" };

  async function testHandshake() {
    setResult("握手中…");
    try {
      const sid = await invoke<string>("crypto_handshake_demo", { endpoint: ENDPOINT, token });
      setResult(`加密握手成功，会话 ID：${sid}`);
    } catch (e) {
      setResult(`失败：${String(e)}`);
    }
  }

  return (
    <main className="home">
      <h1>桃桃音乐 · 桌面</h1>
      <p style={{ color: "#6b7280" }}>已登录。下面验证传输加密（设备绑定握手）。</p>
      <button style={btn} onClick={testHandshake}>测试加密握手</button>
      <button style={{ ...btn, background: "#f3f4f6", color: "#1f2430" }} onClick={onLogout}>退出登录</button>
      {result && <pre>{result}</pre>}
    </main>
  );
}

function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
