import { Database, RefreshCcw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { PanelTitle } from "@/components/PanelTitle";
import { ToolCardView } from "@/components/ToolCardView";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { cn } from "@/lib/utils";
import {
  upsertChatItem,
  type IncrementalEvent,
  type ChatItem,
  type MessageItem,
  type SnapshotResponse,
  type ToolCardViewModel,
} from "@/types/chat";

/**
 * B 路线可观察面板：只吃服务端快照 + IncrementalEvent，不订阅 AG-UI 帧。
 * 用来与左侧 CopilotChat（live A 路线）并排对照。
 */
export function ServerViewPanel({ threadId }: { threadId?: string | null }) {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [snapshot, setSnapshot] = useState<SnapshotResponse | null>(null);
  const [status, setStatus] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [error, setError] = useState<string | null>(null);
  // 手动查询及审批回调只更新视图，不改变会话订阅的生命周期。
  const refreshRef = useRef<(() => void) | null>(null);
  const sourceRef = useRef<EventSource | null>(null);

  const reload = useCallback(() => refreshRef.current?.(), []);

  useEffect(() => {
    let cancelled = false;
    refreshRef.current = null;
    sourceRef.current?.close();
    sourceRef.current = null;

    if (!threadId) {
      setItems([]);
      setSnapshot(null);
      setStatus("idle");
      setError(null);
      return;
    }

    setStatus("loading");
    setError(null);

    // 先订阅再查询；查询期间的新通知合并为一次补查，旧响应不能覆盖新会话。
    let incremental: EventSource | null = null;
    let incrementalOpened = false;
    let appliedSeq = 0;
    let reading = false;
    let dirty = false;
    const refresh = async () => {
      dirty = true;
      if (reading) return;
      reading = true;
      try {
        while (dirty && !cancelled) {
          dirty = false;
          const response = await fetch(`/api/threads/${encodeURIComponent(threadId)}/view`, { credentials: "include" });
          if (!response.ok) throw new Error("读取当前交互状态失败，请重新查询");
          const value = (await response.json()) as SnapshotResponse;
          if (cancelled) return;
          appliedSeq = value.asOfSeq;
          // 消息和普通工具进度仍走原增量通道；审批事实变化再重查联合视图。
          if (!incremental) {
            incremental = new EventSource(`/api/threads/${encodeURIComponent(threadId)}/view/events?after=${encodeURIComponent(value.cursor)}`);
            incremental.addEventListener("view", event => {
              if (cancelled) return;
              const message = event as MessageEvent;
              const position = Number(message.lastEventId.split(":").slice(-1)[0]);
              if (position <= appliedSeq) return;
              if (reading) { dirty = true; return; }
              const data = JSON.parse(message.data) as IncrementalEvent;
              if (data.type === "hitl-pending" || data.type === "hitl-decided" || data.type === "run-finished") {
                void refresh();
              } else setItems(current => upsertChatItem(current, data));
            });
            incremental.onopen = () => {
              // 首次连接以已有快照和 after 补读即可；真正重连才重新核对视图。
              if (incrementalOpened) void refresh();
              incrementalOpened = true;
            };
          }
          setSnapshot(value);
          setItems(value.view);
          setStatus("ready");
          setError(null);
        }
      } catch (caught) {
        if (!cancelled) {
          setStatus("error");
          setError(caught instanceof Error ? caught.message : "暂时无法确认状态");
        }
      } finally { reading = false; }
    };
    refreshRef.current = () => void refresh();
    const source = new EventSource(`/api/threads/${encodeURIComponent(threadId)}/interactions/events`);
    sourceRef.current = source;
    source.addEventListener("hitl-view-changed", () => void refresh());
    // 后端每次建立订阅都会发第一条变化通知，由它触发首次/重连查询。
    // 不再同时由 onopen、立即 refresh 和首条通知重复发起全量查询。
    source.onerror = () => {
      // 断线时保留已有内容；重连后主动重查持久事实。
    };

    return () => {
      cancelled = true;
      refreshRef.current = null;
      incremental?.close();
      sourceRef.current?.close();
      sourceRef.current = null;
    };
  }, [threadId]);

  return (
    <aside
      className="glass-panel grid min-h-[calc(100vh-8rem)] grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden rounded-[1.75rem] p-4 max-[1260px]:min-h-0"
      aria-label="服务端视图"
    >
      <div className="flex items-start justify-between gap-2">
        <PanelTitle
          eyebrow="M4 / committed 重建"
          title="服务端视图"
          icon={<Database className="size-5" />}
        />
        <Button
          type="button"
          size="sm"
          variant="outline"
          className="rounded-full"
          onClick={reload}
          disabled={!threadId}
        >
          <RefreshCcw className="size-3.5" />
          重取
        </Button>
      </div>

      <div className="mb-3 flex flex-wrap gap-1.5 px-1 text-[10px] font-medium text-muted-foreground">
        <Chip label="B 路线" tone="muted" />
        <Chip label={`asOfSeq ${snapshot?.asOfSeq ?? "—"}`} />
        <Chip label={`cursor ${shortCursor(snapshot?.cursor)}`} />
        {snapshot?.activeRun ? (
          <Chip label={`activeRun ${shortId(snapshot.activeRun.runId)}`} tone="amber" />
        ) : null}
        {items.length ? <Chip label={`${items.length} 项`} /> : null}
      </div>

      <ScrollArea className="min-h-0">
        <div className="grid gap-2.5 pr-3">
          {status === "idle" ? (
            <Hint text="选择一个会话后，这里会显示服务端从 SessionLog committed 事实重建的页面视图。" />
          ) : status === "loading" ? (
            <Hint text="正在读取快照…" />
          ) : status === "error" ? (
            <Hint text={`快照读取失败：${error ?? "未知错误"}`} tone="error" />
          ) : items.length === 0 ? (
            <Hint text="快照为空。发送一条消息后，这里会同时出现实时增量与历史重建结果。" />
          ) : (
            items.map((item) =>
              item.kind === "message" ? (
                <MessageBubble key={`m-${item.messageId}`} message={item} />
              ) : (
                <ToolCardView key={`t-${threadId}-${item.toolCallId}`} card={item as ToolCardViewModel} threadId={threadId} onChanged={reload} />
              ),
            )
          )}
        </div>
      </ScrollArea>
    </aside>
  );
}

function MessageBubble({ message }: { message: MessageItem }) {
  const user = message.role === "user";
  return (
    <div
      className={cn(
        "rounded-2xl border px-3 py-2.5 text-sm leading-6",
        user
          ? "border-champagne/25 bg-champagne/10 text-foreground"
          : "border-border bg-secondary/30 text-foreground/90",
      )}
    >
      <div className="mb-1 flex items-center gap-2 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
        <span>{user ? "用户" : message.role === "reasoning" ? "思考" : "助手"}</span>
        {message.partial ? (
          <span className="rounded-full bg-amber-400/15 px-1.5 py-0.5 text-[9px] text-amber-300">
            partial
          </span>
        ) : null}
        <span className="font-mono normal-case tracking-normal text-muted-foreground/70">
          {shortId(message.messageId)}
        </span>
      </div>
      <p className="whitespace-pre-wrap break-words">{message.text}</p>
    </div>
  );
}

function Chip({ label, tone }: { label: string; tone?: "muted" | "amber" }) {
  return (
    <span
      className={cn(
        "rounded-full border px-2 py-0.5 font-mono",
        tone === "amber"
          ? "border-amber-400/30 bg-amber-400/10 text-amber-300"
          : tone === "muted"
            ? "border-champagne/25 bg-champagne/10 text-champagne"
            : "border-border bg-secondary/40 text-muted-foreground",
      )}
    >
      {label}
    </span>
  );
}

function Hint({ text, tone }: { text: string; tone?: "error" }) {
  return (
    <div
      className={cn(
        "rounded-2xl border border-dashed p-4 text-sm leading-6",
        tone === "error"
          ? "border-red-400/30 bg-red-400/5 text-red-300"
          : "border-border bg-secondary/20 text-muted-foreground",
      )}
    >
      {text}
    </div>
  );
}

function shortId(value?: string) {
  if (!value) return "—";
  return value.length > 12 ? `${value.slice(0, 8)}…` : value;
}

function shortCursor(value?: string) {
  if (!value) return "—";
  const index = value.lastIndexOf(":");
  return index < 0 ? value : `…:${value.slice(index + 1)}`;
}
