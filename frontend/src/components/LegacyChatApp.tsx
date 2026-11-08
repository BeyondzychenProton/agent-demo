import { CopilotChat, useCopilotChatConfiguration } from "@copilotkit/react-core/v2";
import { ApprovalBar } from "@/components/ApprovalBar";
import { ThreadListPanel } from "@/components/ThreadListPanel";
import { ServerViewPanel } from "@/components/ServerViewPanel";
import { CHAT_AGENT_ID } from "@/copilot/constants";

/** 兼容开关关闭窗口化展示时保留旧协议页面，不与新展示流同时挂载。 */
export default function LegacyChatApp() {
  const config = useCopilotChatConfiguration();
  const threadId = config?.threadId;
  return <main className="min-h-screen p-5"><div className="workspace-grid mx-auto max-w-[1580px]">
    <ThreadListPanel />
    <section className="glass-panel flex min-h-[80vh] flex-col rounded-3xl p-4">
      <div className="min-h-0 flex-1">
        <CopilotChat agentId={CHAT_AGENT_ID} labels={{ chatInputPlaceholder: "输入消息，回车发送…" }} />
      </div>
      {threadId ? <ApprovalBar threadId={threadId} /> : null}
    </section>
    <ServerViewPanel threadId={threadId} />
  </div></main>;
}
