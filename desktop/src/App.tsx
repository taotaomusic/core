import { useState, useEffect } from "react";
import { clearSession, restoreToken } from "./api";
import { Auth } from "./auth/Auth";
import { MainScreen } from "./main/MainScreen";
import "./App.css";

/** 根组件：启动恢复登录 → 未登录进 Auth，已登录进 MainScreen。 */
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
  return <MainScreen onLogout={() => { clearSession(); setToken(null); }} />;
}
