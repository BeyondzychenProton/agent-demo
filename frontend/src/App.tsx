import { CopilotChatInput, useCopilotChatConfiguration, useThreads } from "@copilotkit/react-core/v2";
import "@copilotkit/react-core/v2/styles.css";
import { lazy, Suspense, useEffect, useState } from "react";
import { Check, PanelLeftOpen, Pencil, X } from "lucide-react";
import type { AuthUser } from "@/auth/api";
import { apiFetch } from "@/auth/api";
import { AdminPage } from "@/components/AdminPage";
import { ApprovalBar } from "@/components/ApprovalBar";
import { friendlyThreadTitle, ThreadListPanel } from "@/components/ThreadListPanel";
import { WindowedTranscript } from "@/components/WindowedTranscript";
import { THREAD_AGENT_ID } from "@/copilot/constants";
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
  const [adminOpen, setAdminOpen] = useState(false);
  const [sidebarHidden, setSidebarHidden] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [titleError, setTitleError] = useState("");
  const [savingTitle, setSavingTitle] = useState(false);
  const config = useCopilotChatConfiguration();
  const threadId = config?.threadId;
  const { threads, refetchThreads } = useThreads({ agentId: THREAD_AGENT_ID, limit: 20 });
  const activeThread = threadId ? threads.find(thread => thread.id === threadId) : undefined;
  const headerTitle = activeThread
    ? friendlyThreadTitle(activeThread)
    : threadId
      ? "会话"
      : "正在初始化会话…";

  useEffect(() => { setEditingTitle(false); setTitleError(""); }, [threadId]);
  const saveTitle = async () => {
    const title = titleDraft.trim();
    if (!title || title.length > 100) { setTitleError("请输入 1 到 100 个字符的会话名称"); return; }
    if (!threadId || savingTitle) return;
    setSavingTitle(true); setTitleError("");
    try {
      const response = await apiFetch(`/agui/run/threads/${encodeURIComponent(threadId)}`, {
        method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name: title }),
      });
      if (!response.ok) throw new Error(response.status === 404 ? "会话不存在，请先发送消息" : "保存失败，请重试");
      setEditingTitle(false);
      refetchThreads();
      window.dispatchEvent(new Event("thread-catalog-changed"));
    } catch (error) { setTitleError(error instanceof Error ? error.message : "保存失败，请重试"); }
    finally { setSavingTitle(false); }
  };

  if (adminOpen && user.admin) return <AdminPage user={user} onBack={() => setAdminOpen(false)} />;

  return (
    <main className="flex h-screen w-screen overflow-hidden bg-background">
      <div className="flex h-full w-full min-w-0 max-[760px]:flex-col">
        <ThreadListPanel user={user} onLogout={onLogout} onAdmin={() => setAdminOpen(true)} hidden={sidebarHidden} onCollapse={() => setSidebarHidden(true)} />
        {threadId ? (
          <section className="flex min-h-0 min-w-0 flex-1 flex-col bg-card">
            <header className="relative flex h-12 shrink-0 items-center justify-center border-b border-border/70 px-4 sm:px-6">
              {sidebarHidden && <button type="button" onClick={() => setSidebarHidden(false)}
                aria-label="显示会话列表" title="显示会话列表"
                className="absolute left-3 flex size-8 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-accent hover:text-foreground">
                <PanelLeftOpen className="size-4" />
              </button>}
              {editingTitle ? (
                <form className="flex min-w-0 max-w-[75%] items-center gap-1" onSubmit={event => { event.preventDefault(); void saveTitle(); }}>
                  <input autoFocus aria-label="会话名称" maxLength={100} value={titleDraft}
                    onChange={event => setTitleDraft(event.target.value)} onKeyDown={event => {
                      if (event.key === "Escape") { setEditingTitle(false); setTitleError(""); }
                    }} className="h-8 min-w-0 rounded-md border border-border bg-background px-2 text-sm text-foreground outline-none focus:ring-2 focus:ring-primary/40" />
                  <button type="submit" disabled={savingTitle} aria-label="保存会话名称" title="保存" className="rounded-md p-1.5 hover:bg-accent disabled:opacity-50"><Check className="size-4" /></button>
                  <button type="button" disabled={savingTitle} aria-label="取消修改名称" title="取消" onClick={() => { setEditingTitle(false); setTitleError(""); }} className="rounded-md p-1.5 hover:bg-accent"><X className="size-4" /></button>
                </form>
              ) : (
                <button type="button" aria-label="修改会话名称" title="修改会话名称"
                  onClick={() => { setTitleDraft(headerTitle); setTitleError(""); setEditingTitle(true); }}
                  className="group flex max-w-[75%] items-center gap-2 rounded-md px-2 py-1 text-sm font-medium text-muted-foreground hover:bg-accent hover:text-foreground">
                  <span className="truncate">{headerTitle}</span><Pencil className="size-3.5 shrink-0 opacity-60 group-hover:opacity-100" />
                </button>
              )}
              {titleError && <span role="alert" className="absolute top-full z-10 rounded-md border border-border bg-card px-2 py-1 text-xs text-destructive shadow-sm">{titleError}</span>}
            </header>
            <div className="flex min-h-0 w-full min-w-0 flex-1 flex-col px-4 pt-3 pb-2 sm:px-6 lg:px-8">
              <WindowedTranscript key={threadId} threadId={threadId} />
            </div>
            <div className="w-full min-w-0 shrink-0 overflow-x-hidden px-4 pb-3 sm:px-6 lg:px-8">
              <ApprovalBar key={`approval-${threadId}`} threadId={threadId} />
              <ChatInput key={`input-${threadId}`} threadId={threadId} />
            </div>
          </section>
        ) : (
          <div className="relative flex flex-1 items-center justify-center text-sm text-muted-foreground">
            {sidebarHidden && <button type="button" onClick={() => setSidebarHidden(false)}
              aria-label="显示会话列表" title="显示会话列表"
              className="absolute top-2 left-3 flex size-8 items-center justify-center rounded-full hover:bg-accent">
              <PanelLeftOpen className="size-4" />
            </button>}
            正在初始化会话…
          </div>
        )}
      </div>
    </main>
  );
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
  return (
    <div data-copilotkit className="w-full min-w-0 pt-1">
      {notice && <p role="status" className="mb-2 text-xs text-amber-700">{notice}</p>}
      {submission?.phase === "uncertain" && (
        <button onClick={() => void send(submission.text, true)} className="mb-2 text-xs underline">
          重试同一请求
        </button>
      )}
      <div className="composer-shell">
        <CopilotChatInput
          value={text}
          onChange={setText}
          onSubmitMessage={value => void send(value)}
          sendButton={{ disabled: view.submissionBlocked, "aria-label": "发送消息" }}
          textArea={{ disabled: view.submissionBlocked, placeholder: "输入消息，回车发送…" }}
        />
      </div>
    </div>
  );
}
