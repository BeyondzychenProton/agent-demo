import { CopilotKit, CopilotChatConfigurationProvider } from "@copilotkit/react-core/v2";
import React from "react";
import ReactDOM from "react-dom/client";

import { AuthGate } from "@/auth/AuthGate";
import { CHAT_AGENT_ID } from "@/copilot/constants";

import App from "./App";
import "./styles.css";

// 先过登录门禁，再挂载 CopilotKit，避免未认证时探测 /agui。
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <AuthGate>
      {(user, onLogout) => (
        <CopilotKit
          runtimeUrl="/agui/run"
          useSingleEndpoint={false}
          agent={CHAT_AGENT_ID}
          enableInspector={false}
        >
          <CopilotChatConfigurationProvider agentId={CHAT_AGENT_ID}>
            <App user={user} onLogout={onLogout} />
          </CopilotChatConfigurationProvider>
        </CopilotKit>
      )}
    </AuthGate>
  </React.StrictMode>,
);
