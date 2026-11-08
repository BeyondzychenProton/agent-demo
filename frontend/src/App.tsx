import { CopilotChatInput, useCopilotChatConfiguration } from "@copilotkit/react-core/v2";
import "@copilotkit/react-core/v2/styles.css";
import { lazy, Suspense, useState } from "react";
import type { AuthUser } from "@/auth/api";
import { apiFetch } from "@/auth/api";
import { ApprovalBar } from "@/components/ApprovalBar";
import { ThreadListPanel } from "@/components/ThreadListPanel";
import { WindowedTranscript } from "@/components/WindowedTranscript";
import { getThreadStore, useThreadView } from "@/readmodel/threadStore";

/** 公共输入组件与受控消息视图组合，历史加载不启动 Agent，也不再连接全量历史通道。 */
const LegacyChatApp = lazy(() => import("@/components/LegacyChatApp"));

export default function App({ user, onLogout }: { user?: AuthUser; onLogout?: () => void } = {}) {
  const windowed = import.meta.env.VITE_WINDOWED_TRANSCRIPT !== "false";
  if (!windowed) {
    return <Suspense fallback={<p>正在加载兼容页面…</p>}><LegacyChatApp /></Suspense>;
  }
  if (!user || !onLogout) return <p>未登录</p>;
  return <WindowedApp user={user} onLogout={onLogout} />;
}

function WindowedApp({ user, onLogout }: { user: AuthUser; onLogout: () => void }) {
  const config = useCopilotChatConfiguration();
  const threadId = config?.threadId;
  return <main className="h-screen w-screen p-4">
    <div className="mx-auto grid h-full max-w-[1700px] grid-cols-[260px_minmax(0,1fr)] gap-4 max-[760px]:grid-cols-1 max-[760px]:grid-rows-[180px_minmax(0,1fr)]">
      <ThreadListPanel user={user} onLogout={onLogout} />
      {threadId ? <section className="glass-panel flex min-h-0 flex-col overflow-hidden rounded-3xl p-4">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h1 className="text-lg font-semibold">Session Chat</h1>
        </div>
        <div className="grid min-h-0 flex-1 grid-cols-1 gap-4">
          <WindowedTranscript key={threadId} threadId={threadId} />
        </div>
        <ApprovalBar key={`approval-${threadId}`} threadId={threadId} />
        <ChatInput key={`input-${threadId}`} threadId={threadId} />
      </section> : <p>正在初始化会话…</p>}
    </div>
  </main>;
}

function ChatInput({ threadId }: { threadId: string }) {
  const [text, setText] = useState("");
  const view = useThreadView(threadId);
  const store = getThreadStore(threadId);
  const submission = view.submission;
  const send = async (message: string, retry = false) => {
    if (!message.trim()) return;
    const command = retry ? store.retrySubmission() : store.beginSubmission(message);
    if (!command) return;
    setText("");
    try {
      const response = await apiFetch(`/api/sessions/${encodeURIComponent(threadId)}/turns`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ request_id: command.requestId, message: command.text }),
        signal: AbortSignal.timeout(15000),
      });
      if (response.status === 400) {
        store.rejected(command.requestId, "消息未被接收，请修改后重新发送"); setText(command.text); return;
      }
      if (!response.ok) throw new Error("尚未确认任务接收结果，请使用同一请求重试");
      const receipt = await response.json() as { commandId?: string; turnId?: string };
      if (!receipt.commandId || !receipt.turnId) throw new Error("提交回执不完整，请使用同一请求确认结果");
      store.accepted(command.requestId, receipt.commandId, receipt.turnId);
      sessionStorage.setItem("session-chat.active-thread", threadId);
      window.dispatchEvent(new Event("thread-catalog-changed"));
      if (store.state.error) void store.reload();
      else void store.refresh();
    } catch (error) {
      store.uncertain(command.requestId, error instanceof Error ? error.message : "接收结果待确认");
    }
  };
  const notice = submission?.phase === "uncertain" || submission?.phase === "rejected" ? submission.error
    : submission?.phase === "sending" ? "正在提交…"
    : submission?.phase === "accepted" ? "任务已接收，正在等待回复…"
    : view.status?.pendingCount ? "请先处理待确认的操作"
    : view.submissionBlocked ? "正在处理当前会话，请稍候…" : "";
  return <div data-copilotkit className="mt-3 border-t border-border pt-3">
    {notice && <p role="status" className="mb-2 text-xs text-amber-700">{notice}</p>}
    {submission?.phase === "uncertain" && <button onClick={() => void send(submission.text, true)} className="mb-2 text-xs underline">重试同一请求</button>}
    <CopilotChatInput value={text} onChange={setText} onSubmitMessage={value => void send(value)}
      sendButton={{ disabled: view.submissionBlocked, "aria-label": "发送消息" }}
      textArea={{ disabled: view.submissionBlocked, placeholder: "输入消息，回车发送…" }} />
  </div>;
}
