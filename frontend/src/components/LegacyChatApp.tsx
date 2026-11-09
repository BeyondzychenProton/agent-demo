import { CopilotChat, useCopilotChatConfiguration, useThreads } from "@copilotkit/react-core/v2";
import { ApprovalBar } from "@/components/ApprovalBar";
import { friendlyThreadTitle, ThreadListPanel } from "@/components/ThreadListPanel";
import { ServerViewPanel } from "@/components/ServerViewPanel";
import { CHAT_AGENT_ID, THREAD_AGENT_ID } from "@/copilot/constants";

/** 兼容开关关闭窗口化展示时保留旧协议页面，不与新展示流同时挂载。 */
export default function LegacyChatApp() {
  const config = useCopilotChatConfiguration();
  const threadId = config?.threadId;
  const { threads } = useThreads({ agentId: THREAD_AGENT_ID, limit: 20 });
  const activeThread = threadId ? threads.find(thread => thread.id === threadId) : undefined;
  const headerTitle = activeThread ? friendlyThreadTitle(activeThread) : threadId ? "会话" : "正在初始化会话…";
  return (
    <main className="flex min-h-screen overflow-hidden bg-background">
      <div className="workspace-grid mx-auto w-full max-w-[1580px]">
        <ThreadListPanel />
        <section className="flex min-h-[80vh] flex-col border-x border-border/70 bg-card">
          <header className="flex h-12 shrink-0 items-center justify-center border-b border-border/70 px-4">
            <h1 className="max-w-full truncate text-sm font-medium text-muted-foreground" title={headerTitle}>
              {headerTitle}
            </h1>
          </header>
          <div className="min-h-0 flex-1 px-4 pt-3">
            <CopilotChat agentId={CHAT_AGENT_ID} labels={{ chatInputPlaceholder: "输入消息，回车发送…" }} />
          </div>
          {threadId ? (
            <div className="px-4 pb-3">
              <ApprovalBar threadId={threadId} />
            </div>
          ) : null}
        </section>
        <ServerViewPanel threadId={threadId} />
      </div>
    </main>
  );
}
