import {
  useCopilotChatConfiguration,
  useThreads,
  type Thread,
} from "@copilotkit/react-core/v2";
import { MessageSquarePlus, RefreshCcw, Trash2 } from "lucide-react";
import { useEffect, useRef } from "react";

import type { AuthUser } from "@/auth/api";
import { THREAD_AGENT_ID } from "@/copilot/constants";
import { PanelTitle } from "@/components/PanelTitle";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";

const ACTIVE_THREAD_KEY = "session-chat.active-thread";

export function ThreadListPanel({ user, onLogout }: { user?: AuthUser; onLogout?: () => void } = {}) {
  const chatConfig = useCopilotChatConfiguration();
  const restoredThreadRef = useRef(false);
  const {
    threads,
    isLoading,
    error,
    hasMoreThreads,
    isFetchingMoreThreads,
    fetchMoreThreads,
    refetchThreads,
    startNewThread,
    archiveThread,
    deleteThread,
  } = useThreads({ agentId: THREAD_AGENT_ID, limit: 20 });

  useRefreshThreadsAfterRun(refetchThreads);

  const activeThreadId = chatConfig?.threadId;

  useEffect(() => {
    if (!chatConfig || restoredThreadRef.current) return;
    restoredThreadRef.current = true;
    const saved = sessionStorage.getItem(ACTIVE_THREAD_KEY);
    if (saved) chatConfig.setActiveThreadId(saved, { explicit: true });
  }, [chatConfig]);

  const handleNewThread = () => {
    sessionStorage.removeItem(ACTIVE_THREAD_KEY);
    startNewThread();
    chatConfig?.startNewThread();
  };

  const handleSelectThread = (threadId: string) => {
    sessionStorage.setItem(ACTIVE_THREAD_KEY, threadId);
    chatConfig?.setActiveThreadId(threadId, { explicit: true });
  };

  const handleArchiveThread = async (threadId: string) => {
    await archiveThread(threadId);
    if (threadId === activeThreadId) {
      handleNewThread();
    }
  };

  const handleDeleteThread = async (threadId: string) => {
    await deleteThread(threadId);
    if (threadId === activeThreadId) {
      handleNewThread();
    }
  };

  return (
    <aside
      className="glass-panel grid min-h-0 grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden rounded-[1.75rem] p-4 max-[520px]:rounded-2xl"
      aria-label="会话列表"
    >
      <PanelTitle
        eyebrow="useThreads"
        title="会话"
        icon={<MessageSquarePlus className="size-5" />}
      />
      {user && onLogout && (
        <div className="mb-3 flex items-center justify-between gap-2 text-xs text-muted-foreground">
          <span className="truncate">{user.name || user.phone}</span>
          <button type="button" className="shrink-0 underline" onClick={onLogout}>退出</button>
        </div>
      )}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" className="rounded-full" onClick={handleNewThread}>
          <MessageSquarePlus className="size-4" />
          新会话
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="rounded-full"
          onClick={refetchThreads}
        >
          <RefreshCcw className="size-4" />
          刷新
        </Button>
      </div>

      <ScrollArea className="min-h-0">
        <div className="grid gap-2 pr-3">
          {isLoading ? (
            <ThreadListHint text="正在加载会话..." />
          ) : error ? (
            <ThreadListHint text={`会话列表不可用：${error.message}`} />
          ) : threads.length === 0 ? (
            <ThreadListHint text="还没有会话。发送第一条消息后会自动刷新这里。" />
          ) : (
            threads.map((thread) => (
              <ThreadRow
                key={thread.id}
                thread={thread}
                active={thread.id === activeThreadId}
                onSelect={() => handleSelectThread(thread.id)}
                onArchive={() => void handleArchiveThread(thread.id)}
                onDelete={() => void handleDeleteThread(thread.id)}
              />
            ))
          )}

          {hasMoreThreads ? (
            <Button
              type="button"
              variant="outline"
              disabled={isFetchingMoreThreads}
              onClick={fetchMoreThreads}
              className="mt-2"
            >
              {isFetchingMoreThreads ? "加载中..." : "加载更多"}
            </Button>
          ) : null}
        </div>
      </ScrollArea>
    </aside>
  );
}

function ThreadRow({
  thread,
  active,
  onSelect,
  onArchive,
  onDelete,
}: {
  thread: Thread;
  active: boolean;
  onSelect: () => void;
  onArchive: () => void;
  onDelete: () => void;
}) {
  return (
    <div
      className={cn(
        "group flex items-start justify-between gap-2 rounded-2xl border p-3 transition-all duration-300",
        active
          ? "border-champagne/40 bg-champagne/10 text-foreground shadow-[0_12px_32px_oklch(0_0_0/0.28)]"
          : "border-border bg-secondary/30 text-foreground hover:border-champagne/25 hover:bg-secondary/60 hover:shadow-[0_12px_32px_oklch(0_0_0/0.25)]",
      )}
    >
      <button type="button" onClick={onSelect} className="min-w-0 flex-1 text-left">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">
            {thread.name || friendlyThreadTitle(thread)}
          </p>
          <p className="mt-1 text-xs font-medium text-muted-foreground">
            {formatThreadTime(thread.lastRunAt ?? thread.updatedAt)}
          </p>
        </div>
      </button>
      <div className="flex shrink-0 gap-1 opacity-0 transition group-hover:opacity-100">
        <button
          type="button"
          title="归档"
          onClick={onArchive}
          className="rounded-lg px-2 py-1 text-xs font-semibold text-muted-foreground hover:bg-background/60 hover:text-champagne"
        >
          归档
        </button>
        <button
          type="button"
          title="删除"
          onClick={onDelete}
          className="rounded-lg px-2 py-1 text-destructive hover:bg-background/60"
        >
          <Trash2 className="size-3.5" />
        </button>
      </div>
    </div>
  );
}

function ThreadListHint({ text }: { text: string }) {
  return (
    <div className="rounded-2xl border border-dashed border-border bg-secondary/20 p-4 text-sm leading-6 text-muted-foreground">
      {text}
    </div>
  );
}

/** 会话目录只响应已接收提交和执行收口，不再订阅另一份完整消息历史。 */
function useRefreshThreadsAfterRun(refetchThreads: () => void) {
  useEffect(() => {
    const refresh = () => refetchThreads();
    window.addEventListener("thread-catalog-changed", refresh);
    return () => window.removeEventListener("thread-catalog-changed", refresh);
  }, [refetchThreads]);
}

function friendlyThreadTitle(thread: Thread) {
  if (thread.name?.trim()) return thread.name.trim();
  const stamp = thread.createdAt ?? thread.updatedAt ?? thread.lastRunAt;
  if (stamp) {
    const date = new Date(stamp);
    if (!Number.isNaN(date.getTime())) {
      return `会话 · ${date.toLocaleString(undefined, {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })}`;
    }
  }
  const short = thread.id.length > 14 ? `${thread.id.slice(0, 8)}…` : thread.id;
  return `会话 ${short}`;
}

function formatThreadTime(value?: string) {
  if (!value) return "暂无运行记录";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}
