import { useState, useEffect } from "react";
import { clearSession, restoreToken } from "./api";
import { AppProvider } from "./state/AppState";
import { Auth } from "./auth/Auth";
import { MainScreen } from "./main/MainScreen";
import "./App.css";

/** 根组件：启动恢复登录 → 未登录进 Auth，已登录进全局状态 + 主界面。 */
export function App() {
  const [token, setToken] = useState<string | null>(null);
  const [booting, setBooting] = useState(true);

  useEffect(() => {
    restoreToken().then(setToken).finally(() => setBooting(false));
  }, []);

  /** 会话过期与手动退出共用：清会话回登录页。 */
  function logout() {
    clearSession();
    setToken(null);
  }

  if (booting) {
    return (
      <div className="auth">
        <div className="brand-circle">♪</div>
        <p className="brand-sub" style={{ marginTop: 20 }}>正在恢复登录…</p>
      </div>
    );
  }
  if (!token) return <Auth onToken={setToken} />;
  return (
    <AppProvider onExpired={logout}>
      <MainScreen onLogout={logout} />
    </AppProvider>
  );
}
