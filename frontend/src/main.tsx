import { CopilotKit, CopilotChatConfigurationProvider } from "@copilotkit/react-core/v2";
import React from "react";
import ReactDOM from "react-dom/client";

import { AuthGate } from "@/auth/AuthGate";
import { fetchMe } from "@/auth/api";
import { CHAT_AGENT_ID } from "@/copilot/constants";

import App from "./App";
import "./styles.css";

const h5 = new URLSearchParams(window.location.search).get("view") === "h5";
const demo = h5 && import.meta.env.MODE === "h5-demo";
const demoLogin = demo ? signInDemo().catch(() => null) : undefined;

async function signInDemo() {
  const ticket = new URLSearchParams(location.hash.slice(1)).get("ticket");
  if (!ticket) return sessionStorage.getItem("h5-demo.pending-login") ? null : fetchMe();
  sessionStorage.setItem("h5-demo.pending-login", "true");
  history.replaceState(null, "", location.pathname + location.search);
  const response = await fetch("/api/auth/mantianxing/exchange", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ticket }),
  });
  if (!response.ok) return null;
  const user = await fetchMe();
  if (user) {
    sessionStorage.removeItem("session-chat.active-thread");
    sessionStorage.removeItem("h5-demo.pending-login");
  }
  return user;
}
if (h5) {
  const root = document.documentElement;
  root.dataset.view = "h5";
  document.title = "海卓大脑";
  const viewport = window.visualViewport;
  if (viewport) {
    const resize = () => root.style.setProperty("--h5-viewport-height", `${viewport.height}px`);
    viewport.addEventListener("resize", resize);
    resize();
  }
}

// 先过登录门禁，再挂载 CopilotKit，避免未认证时探测 /agui。
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <AuthGate demoLogin={demoLogin}>
      {(user, onLogout) => (
        <CopilotKit
          runtimeUrl="/agui/run"
          useSingleEndpoint={false}
          agent={CHAT_AGENT_ID}
          enableInspector={false}
        >
          <CopilotChatConfigurationProvider agentId={CHAT_AGENT_ID}>
            <App user={user} onLogout={onLogout} h5={h5} />
          </CopilotChatConfigurationProvider>
        </CopilotKit>
      )}
    </AuthGate>
  </React.StrictMode>,
);
