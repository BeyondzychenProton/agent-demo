import { CopilotKit, CopilotChatConfigurationProvider } from "@copilotkit/react-core/v2";
import React from "react";
import ReactDOM from "react-dom/client";

import { CHAT_AGENT_ID } from "@/copilot/constants";

import App from "./App";
import "./styles.css";

// 会话目录沿用 AG-UI 接入；正文与审批展示共用 v2 分页及增量读模型。
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <CopilotKit
      runtimeUrl="/agui/run"
      useSingleEndpoint={false}
      agent={CHAT_AGENT_ID}
      enableInspector={false}
    >
      <CopilotChatConfigurationProvider agentId={CHAT_AGENT_ID}><App /></CopilotChatConfigurationProvider>
    </CopilotKit>
  </React.StrictMode>,
);
