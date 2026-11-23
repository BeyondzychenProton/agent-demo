import {
  useCopilotChatConfiguration,
  useThreads,
  type Thread,
} from "@copilotkit/react-core/v2";
import { ChevronLeft, LogOut, MessageSquare, MessageSquarePlus, PanelLeftClose, RefreshCcw, Search, Settings, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import type { AuthUser } from "@/auth/api";
import { ChangePasswordButton } from "@/auth/ChangePasswordButton";
import { THREAD_AGENT_ID } from "@/copilot/constants";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";

const ACTIVE_THREAD_KEY = "session-chat.active-thread";

/** 优先展示可读姓名（中文或纯 ASCII）；乱码名回退手机号。 */
function accountLabel(user: AuthUser) {
  const name = user.name?.trim();
  if (!name) return user.phone;
  if (/[\u4e00-\u9fff]/.test(name) || /^[A-Za-z0-9 _.-]+$/.test(name)) return name;
  return user.phone;
}

export function ThreadListPanel({ user, onLogout, onAdmin, hidden = false, onCollapse, onSelect, onNewThread, h5 = false }: {
  user?: AuthUser; onLogout?: () => void; onAdmin?: () => void; hidden?: boolean; onCollapse?: () => void;
  onSelect?: () => void; onNewThread?: () => void; h5?: boolean;
} = {}) {
  const [query, setQuery] = useState("");
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
    if (onNewThread) {
      onNewThread();
      return;
    }
    sessionStorage.removeItem(ACTIVE_THREAD_KEY);
    startNewThread();
    chatConfig?.startNewThread();
  };

  const handleSelectThread = (threadId: string) => {
    sessionStorage.setItem(ACTIVE_THREAD_KEY, threadId);
    chatConfig?.setActiveThreadId(threadId, { explicit: true });
    onSelect?.();
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

  if (h5) {
    const search = query.trim().toLowerCase();
    const results = threads.filter(thread => friendlyThreadTitle(thread).toLowerCase().includes(search));
    return <aside className="h5-history" aria-label="历史对话" hidden={hidden}>
      <header className="h5-header">
        <button type="button" onClick={onCollapse}><ChevronLeft size={18} aria-hidden />返回</button>
        <h1>历史对话</h1>
      </header>
      <div className="h5-account">
        {user && <span>{accountLabel(user)}</span>}
        {user && <ChangePasswordButton/>}
        {onLogout && <button type="button" onClick={onLogout}>退出登录</button>}
      </div>
      <label className="h5-search">
        <Search size={18} aria-hidden />
        <input type="search" aria-label="搜索已加载对话" placeholder="搜索已加载对话" value={query}
          onChange={event => setQuery(event.target.value)} />
      </label>
      <div className="h5-history-list">
        {isLoading && <ThreadListHint text="正在加载会话…" />}
        {error && <button type="button" onClick={refetchThreads}>会话列表加载失败，点击重试</button>}
        {!isLoading && !error && results.length === 0 && <ThreadListHint text={search ? "没有匹配的对话" : "还没有历史对话"} />}
        {results.map(thread => <button type="button" className="h5-thread" key={thread.id}
          aria-current={activeThreadId === thread.id ? "true" : undefined} onClick={() => handleSelectThread(thread.id)}>
          <MessageSquare size={19} aria-hidden />
          <span><strong>{friendlyThreadTitle(thread)}</strong><small>{formatThreadTime(thread.lastRunAt ?? thread.updatedAt)}</small></span>
        </button>)}
        {hasMoreThreads && <button type="button" className="h5-load-more" onClick={fetchMoreThreads} disabled={isFetchingMoreThreads}>
          {isFetchingMoreThreads ? "加载中…" : "加载更多"}
        </button>}
      </div>
      <footer className="h5-history-footer">
        <button type="button" onClick={handleNewThread} disabled={!chatConfig}>新建对话</button>
      </footer>
    </aside>;
  }

  return (
    <aside
      className={cn("grid h-full w-[260px] min-w-0 max-w-[260px] shrink-0 grid-rows-[auto_auto_minmax(0,1fr)_auto] overflow-hidden border-r border-border/80 bg-secondary/40 max-[760px]:h-[180px] max-[760px]:w-full max-[760px]:max-w-none max-[760px]:border-r-0 max-[760px]:border-b", hidden && "hidden")}
      aria-label="会话列表"
    >
      <div className="flex min-w-0 items-center gap-2 px-4 pt-4 pb-2">
        <MessageSquarePlus className="size-4 shrink-0 text-primary" aria-hidden />
        <h2 className="truncate text-[15px] font-semibold tracking-tight text-foreground">海卓大脑</h2>
      </div>

      <div className="flex min-w-0 flex-col gap-2 px-3 pb-2">
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="h-9 w-full max-w-full justify-start !rounded-full !border-border !bg-card !shadow-none hover:!bg-accent"
          onClick={handleNewThread}
        >
          <MessageSquarePlus className="size-4" />
          新会话
        </Button>
        <div className="flex items-center gap-1">
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="h-8 min-w-0 flex-1 justify-start !rounded-full text-muted-foreground"
          onClick={refetchThreads}
        >
          <RefreshCcw className="size-3.5" />
          刷新
        </Button>
        {onCollapse && <Button type="button" size="sm" variant="ghost" onClick={onCollapse}
          aria-label="隐藏会话列表" title="隐藏会话列表" className="h-8 w-8 shrink-0 !rounded-full p-0 text-muted-foreground">
          <PanelLeftClose className="size-4" />
        </Button>}
        </div>
      </div>

      <ScrollArea className="min-h-0 min-w-0 overflow-hidden px-2">
        <div className="grid w-full max-w-full min-w-0 gap-0.5 pb-3">
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
              variant="ghost"
              disabled={isFetchingMoreThreads}
              onClick={fetchMoreThreads}
              className="mt-1 h-8 w-full max-w-full text-xs text-muted-foreground"
            >
              {isFetchingMoreThreads ? "加载中..." : "加载更多"}
            </Button>
          ) : null}
        </div>
      </ScrollArea>

      {user && onLogout ? (
        <div className="flex items-center gap-2 border-t border-border/70 px-3 py-3">
          <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
            {accountLabel(user).slice(0, 1)}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-foreground">{accountLabel(user)}</p>
            <p className="truncate text-[11px] text-muted-foreground">{user.phone}</p>
          </div>
          {user.admin && onAdmin && <button type="button" onClick={onAdmin} title="管理界面" aria-label="管理界面" className="rounded-full p-2 text-muted-foreground hover:bg-accent hover:text-foreground"><Settings className="size-4" /></button>}
          <Button
            type="button"
            size="sm"
            variant="ghost"
            className="shrink-0 !rounded-full text-muted-foreground"
            onClick={onLogout}
            aria-label="退出登录"
          >
            <LogOut className="size-3.5" />
          </Button>
        </div>
      ) : null}
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
        "group relative w-full max-w-full min-w-0 overflow-hidden rounded-xl px-2.5 py-2 transition-colors duration-150",
        active
          ? "bg-accent text-foreground"
          : "text-foreground hover:bg-secondary",
      )}
    >
      <button type="button" onClick={onSelect} className="block w-full min-w-0 overflow-hidden pr-1 text-left group-hover:pr-14">
        <p className={cn("truncate text-sm", active ? "font-medium" : "font-normal")}>
          {thread.name || friendlyThreadTitle(thread)}
        </p>
        <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
          {formatThreadTime(thread.lastRunAt ?? thread.updatedAt)}
        </p>
      </button>
      <div className="pointer-events-none absolute top-1/2 right-1.5 flex -translate-y-1/2 items-center gap-0.5 rounded-md bg-inherit opacity-0 transition group-hover:pointer-events-auto group-hover:opacity-100">
        <button
          type="button"
          title="归档"
          onClick={onArchive}
          className="rounded-md px-1.5 py-1 text-[11px] text-muted-foreground hover:bg-card hover:text-foreground"
        >
          归档
        </button>
        <button
          type="button"
          title="删除"
          onClick={onDelete}
          className="rounded-md p-1 text-muted-foreground hover:bg-card hover:text-destructive"
        >
          <Trash2 className="size-3.5" />
        </button>
      </div>
    </div>
  );
}

function ThreadListHint({ text }: { text: string }) {
  return (
    <div className="w-full max-w-full min-w-0 rounded-xl px-2.5 py-3 text-sm leading-6 break-words text-muted-foreground">
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

/** 与侧栏会话行同一套标题规则，供主栏 header 复用。 */
export function friendlyThreadTitle(thread: Thread) {
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
