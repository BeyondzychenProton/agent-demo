import { CopilotChat, useCopilotChatConfiguration } from "@copilotkit/react-core/v2";
import { ThreadListPanel } from "@/components/ThreadListPanel";
import { ServerViewPanel } from "@/components/ServerViewPanel";
import { CHAT_AGENT_ID } from "@/copilot/constants";

/** 兼容开关关闭窗口化展示时保留旧协议页面，不与新展示流同时挂载。 */
export default function LegacyChatApp() {
  const config = useCopilotChatConfiguration();
  return <main className="min-h-screen p-5"><div className="workspace-grid mx-auto max-w-[1580px]">
    <ThreadListPanel />
    <section className="glass-panel min-h-[80vh] rounded-3xl p-4">
      <CopilotChat agentId={CHAT_AGENT_ID} labels={{ chatInputPlaceholder: "输入消息，回车发送…" }} />
    </section>
    <ServerViewPanel threadId={config?.threadId} />
  </div></main>;
}
