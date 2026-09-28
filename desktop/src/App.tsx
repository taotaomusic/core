import { useState, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { ENDPOINT, postJson, saveSession, clearSession, restoreToken } from "./api";
import "./App.css";

// 与服务端 /auth/register 一致：3–32 位中英文/数字/下划线。
const USERNAME_PATTERN = /^[\w一-龥]{3,32}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD = 6;

function readableError(e: unknown): string {
  const m = e instanceof Error ? e.message : String(e);
  if (/Failed to fetch|NetworkError|load failed/i.test(m)) return "网络连接失败，请检查网络后重试";
  return m || "操作失败，请稍后重试";
}

export function App() {
  const [token, setToken] = useState<string | null>(null);
  const [booting, setBooting] = useState(true);

  useEffect(() => {
    restoreToken().then(setToken).finally(() => setBooting(false));
  }, []);

  if (booting) {
    return (
      <div className="auth">
        <div className="brand-circle">♪</div>
        <p className="brand-sub" style={{ marginTop: 20 }}>正在恢复登录…</p>
      </div>
    );
  }
  if (!token) return <Auth onToken={setToken} />;
  return <Home token={token} onLogout={() => { clearSession(); setToken(null); }} />;
}

// PLACEHOLDER_AUTH
function Auth({ onToken }: { onToken: (t: string) => void }) {
  const [register, setRegister] = useState(false);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [showPwd, setShowPwd] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [ok, setOk] = useState("");

  const usernameErr = username && !USERNAME_PATTERN.test(username) ? "用户名需为 3 至 32 位" : "";
  const passwordErr = password && password.length < MIN_PASSWORD ? `密码至少 ${MIN_PASSWORD} 位` : "";
  const confirmErr = register && confirm && confirm !== password ? "两次输入的密码不一致" : "";
  const emailOk = EMAIL_PATTERN.test(email.trim());
  const emailErr = register && email && !emailOk ? "请输入有效的邮箱地址" : "";
  const codeErr = register && code && code.length !== 6 ? "请输入 6 位验证码" : "";

  const canSubmit =
    !loading &&
    USERNAME_PATTERN.test(username) &&
    password.length >= MIN_PASSWORD &&
    (!register || (confirm === password && emailOk && code.length === 6));

  async function sendCode() {
    setError("");
    setOk("");
    setLoading(true);
    try {
      await postJson("/api/v1/auth/email-verification", { email: email.trim() });
      setOk("验证码已发送，请查收邮箱");
    } catch (e) {
      setError(readableError(e));
    } finally {
      setLoading(false);
    }
  }

  async function submit() {
    if (!canSubmit) return;
    setError("");
    setOk("");
    setLoading(true);
    try {
      const data = register
        ? await postJson("/api/v1/auth/register", { username, password, email: email.trim(), verificationCode: code })
        : await postJson("/api/v1/auth/login", { username, password });
      if (!data?.accessToken) throw new Error("响应缺少 accessToken");
      saveSession(data);
      onToken(data.accessToken);
    } catch (e) {
      setError(readableError(e));
    } finally {
      setLoading(false);
    }
  }

  // PLACEHOLDER_AUTH_JSX
  return (
    <div className="auth">
      <div className="brand-circle">♪</div>
      <div className="brand-title">桃桃音乐</div>
      <p className="brand-sub">{register ? "注册后即可收藏和离线下载" : "登录后同步你的收藏与下载"}</p>

      <div className="card">
        <div className="field">
          <label>用户名</label>
          <input
            className={usernameErr ? "err" : ""}
            value={username}
            onChange={(e) => { setUsername(e.target.value.replace(/\s/g, "").slice(0, 32)); setError(""); }}
            placeholder="用户名"
          />
          <div className={`hint ${usernameErr ? "err" : ""}`}>{usernameErr || "支持中英文、数字和下划线"}</div>
        </div>

        <div className="field">
          <label>密码</label>
          <div className="input-wrap">
            <input
              className={passwordErr ? "err" : ""}
              type={showPwd ? "text" : "password"}
              value={password}
              onChange={(e) => { setPassword(e.target.value); setError(""); }}
              placeholder="密码"
              onKeyDown={(e) => e.key === "Enter" && !register && submit()}
            />
            <button type="button" className="toggle-eye" onClick={() => setShowPwd(!showPwd)}>
              {showPwd ? "隐藏" : "显示"}
            </button>
          </div>
          <div className={`hint ${passwordErr ? "err" : ""}`}>{passwordErr || `至少 ${MIN_PASSWORD} 位`}</div>
        </div>

        {register && (
          <>
            <div className="field">
              <label>确认密码</label>
              <input
                className={confirmErr ? "err" : ""}
                type={showPwd ? "text" : "password"}
                value={confirm}
                onChange={(e) => { setConfirm(e.target.value); setError(""); }}
                placeholder="再次输入密码"
              />
              <div className={`hint ${confirmErr ? "err" : ""}`}>{confirmErr || "再次输入以确认"}</div>
            </div>

            <div className="field">
              <label>邮箱</label>
              <div className="code-row">
                <div className="field">
                  <input
                    className={emailErr ? "err" : ""}
                    value={email}
                    onChange={(e) => { setEmail(e.target.value.trim()); setError(""); }}
                    placeholder="邮箱"
                  />
                </div>
                <button className="btn-code" disabled={loading || !emailOk} onClick={sendCode}>发送验证码</button>
              </div>
              <div className={`hint ${emailErr ? "err" : ""}`}>{emailErr || "用于验证账号与找回凭据"}</div>
            </div>

            <div className="field">
              <label>邮箱验证码</label>
              <input
                className={codeErr ? "err" : ""}
                value={code}
                onChange={(e) => { setCode(e.target.value.replace(/\D/g, "").slice(0, 6)); setError(""); }}
                placeholder="6 位验证码"
                onKeyDown={(e) => e.key === "Enter" && submit()}
              />
              <div className={`hint ${codeErr ? "err" : ""}`}>{codeErr || "验证码有效期 10 分钟"}</div>
            </div>
          </>
        )}

        {error && <div className="error-banner">⚠ {error}</div>}
        {ok && <div className="ok-banner">{ok}</div>}

        <button className="btn-primary" disabled={!canSubmit} onClick={submit}>
          {loading ? <span className="spinner" /> : register ? "注册并登录" : "登录"}
        </button>
      </div>

      <button
        className="toggle-mode"
        disabled={loading}
        onClick={() => { setRegister(!register); setConfirm(""); setEmail(""); setCode(""); setError(""); setOk(""); }}
      >
        {register ? "已有账号？返回登录" : "还没有账号？立即注册"}
      </button>
    </div>
  );
}

function Home({ token, onLogout }: { token: string; onLogout: () => void }) {
  const [result, setResult] = useState("");
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
      <p style={{ color: "#8a8791" }}>已登录（授权已保存，下次启动免登录）。</p>
      <button className="btn-primary" onClick={testHandshake}>测试加密握手</button>
      <button className="toggle-mode" onClick={onLogout}>退出登录</button>
      {result && <pre>{result}</pre>}
    </main>
  );
}
