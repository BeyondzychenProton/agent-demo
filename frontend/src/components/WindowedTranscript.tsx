import { memo, useCallback, useEffect, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { CopilotChatAssistantMessage } from "@copilotkit/react-core/v2";
import { ToolCardView } from "@/components/ToolCardView";
import { useThreadView, type ViewItem } from "@/readmodel/threadStore";

/** 两个展示面消费同一个状态仓库；只挂载视口附近的动态高度消息行。 */
export function WindowedTranscript({ threadId, diagnostic = false }: { threadId: string; diagnostic?: boolean }) {
  const view = useThreadView(threadId);
  const scroll = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const anchor = useRef<{ id: string; offset: number } | null>(null);
  const scrollFrame = useRef<number | undefined>(undefined);
  const virtual = useVirtualizer({
    count: view.items.length, getScrollElement: () => scroll.current, estimateSize: () => 140,
    overscan: 4, getItemKey: index => view.items[index].itemId,
  });
  const totalHeight = virtual.getTotalSize();
  useEffect(() => {
    if (view.submission) {
      // 用户主动发送消息时回到尾部；读取旧历史时的新助手消息仍保持原来的不打断策略。
      following.current = true; anchor.current = null;
    }
  }, [view.submission?.requestId]);
  const load = useCallback(async () => {
    const first = virtual.getVirtualItems().find(item => item.end >= (scroll.current?.scrollTop ?? 0));
    if (first) anchor.current = { id: view.items[first.index].itemId, offset: (scroll.current?.scrollTop ?? 0) - first.start };
    following.current = false;
    await view.loadMore();
  }, [virtual, view.items, view.loadMore]);
  useEffect(() => {
    const saved = anchor.current;
    if (saved && !view.fetching) {
      const index = view.items.findIndex(item => item.itemId === saved.id);
      if (index >= 0) {
        const frame = requestAnimationFrame(() => {
          const offset = virtual.getOffsetForIndex(index, "start")?.[0] ?? 0;
          scroll.current?.scrollTo({ top: offset + saved.offset });
        });
        scrollFrame.current = frame;
        anchor.current = null;
        return () => cancelAnimationFrame(frame);
      }
      anchor.current = null;
    } else if (following.current) {
      // 使用原生滚动，避免动态测量的索引追踪器在用户手动滚动后仍反复拉回旧锚点。
      const frame = requestAnimationFrame(() => scroll.current?.scrollTo({ top: totalHeight }));
      scrollFrame.current = frame;
      return () => cancelAnimationFrame(frame);
    }
  }, [view.items, view.fetching, virtual, totalHeight]);
  return <div className="flex h-full min-h-0 flex-col gap-2">
    <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
      {view.hasMore && <button onClick={() => void load()} disabled={view.fetching} className="underline">{view.fetching ? "加载中…" : "加载更早"}</button>}
      <button onClick={() => { following.current = true; void view.reload(); }} className="underline">回到最新</button>
      <span>{view.status?.activeRun ? "正在执行" : view.status?.syncState === "syncing" ? "正在同步" : "已同步"}</span>
      {view.trimmed && <span>已释放远处历史，可重新加载</span>}
    </div>
    {diagnostic && <p className="break-all font-mono text-[10px] text-muted-foreground">
      执行水位 {view.status?.execSeq ?? 0} / 输入水位 {view.status?.inboxSeq ?? 0} / 展示版本 {view.status?.revision ?? 0}
    </p>}
    {view.loading && <p role="status">正在加载会话…</p>}
    {view.error && <p role="status" className="text-xs text-amber-300">{view.error} <button onClick={() => void view.reload()} className="underline">重新加载</button></p>}
    <div ref={scroll} style={{ overflowAnchor: "none" }} className="min-h-0 flex-1 overflow-auto" onWheel={() => {
      if (scrollFrame.current !== undefined) cancelAnimationFrame(scrollFrame.current);
      anchor.current = null; following.current = false;
    }} onTouchStart={() => {
      if (scrollFrame.current !== undefined) cancelAnimationFrame(scrollFrame.current);
      anchor.current = null; following.current = false;
    }} onScroll={() => {
      const element = scroll.current;
      if (element) following.current = element.scrollHeight - element.scrollTop - element.clientHeight < 100;
    }} aria-label={diagnostic ? "服务端历史视图" : "会话消息"}>
      {!view.loading && !view.items.length && <p className="p-4 text-sm text-muted-foreground">发送一条消息，开始会话。</p>}
      <div style={{ height: virtual.getTotalSize(), position: "relative" }}>
        {virtual.getVirtualItems().map(row => <div key={row.key} data-index={row.index} ref={virtual.measureElement}
          style={{ position: "absolute", top: 0, left: 0, width: "100%", transform: `translateY(${row.start}px)` }}>
          <div className="pb-3 pr-2"><TranscriptItem entry={view.items[row.index]} threadId={threadId} onChanged={view.refresh} /></div>
        </div>)}
      </div>
    </div>
  </div>;
}

export const TranscriptItem = memo(function TranscriptItem({ entry, threadId, onChanged }: {
  entry: ViewItem; threadId: string; onChanged: () => Promise<void>;
}) {
  const item = entry.item;
  // 普通文本及巨大正文预览无需进入 Markdown 解析/代码高亮链，减少滚动时新行的挂载成本。
  const plain = item.kind === "message" && (item.role === "user" || !!entry.details.text || !/[`*_~#<>\[\]$|]/.test(item.text));
  return <article className="rounded-2xl border border-border bg-secondary/30 p-3 text-sm">
    {item.kind === "message" ? <>
      <p className="mb-2 text-xs text-muted-foreground">{item.role === "user" ? "用户" : "助手"}{item.partial ? " · 正在生成" : ""}
        {entry.localPhase && ` · ${{ sending: "正在发送", accepted: "已接收，正在同步", uncertain: "接收结果待确认", completed: "已完成，正在同步", rejected: "提交未被接受" }[entry.localPhase]}`}
      </p>
      {plain ? <p className="whitespace-pre-wrap break-words">{item.text}</p>
        : <CopilotChatAssistantMessage.MarkdownRenderer content={item.text} mode={item.partial ? "streaming" : "static"} isAnimating={!!item.partial} className="break-words text-foreground" />}
    </> : <ToolCardView card={item} threadId={threadId} onChanged={() => void onChanged()} />}
    {Object.entries(entry.details).map(([field, detail]) =>
      <ContentDetail key={`${entry.contentVersion}-${field}`} threadId={threadId} entry={entry} field={field} bytes={detail.bytes} />)}
  </article>;
});

/** 巨大内容一次只保留一页；展开前不下载，不把全文隐藏在折叠 DOM 中。 */
function ContentDetail({ threadId, entry, field, bytes }: { threadId: string; entry: ViewItem; field: string; bytes: number }) {
  const [open, setOpen] = useState(false);
  const [cursor, setCursor] = useState(0);
  const [history, setHistory] = useState<number[]>([]);
  const [data, setData] = useState<{ blocks: string[]; nextCursor: string | null }>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController(); setData(undefined); setError(undefined);
    void fetch(`/api/v2/threads/${encodeURIComponent(threadId)}/items/${encodeURIComponent(entry.itemId)}/content?version=${entry.contentVersion}&field=${encodeURIComponent(field)}&cursor=${cursor}`, { signal: abort.signal })
      .then(async response => { if (!response.ok) throw new Error("该内容版本暂不可读，请刷新消息"); return response.json(); })
      .then(value => { if (!abort.signal.aborted) setData(value); })
      .catch(caught => { if (!abort.signal.aborted) setError(String(caught.message)); });
    return () => abort.abort();
  }, [open, cursor, entry.contentVersion, entry.itemId, field, threadId]);
  const names: Record<string, string> = { text: "正文", arguments: "参数", result: "结果", error: "错误详情", progress: "进度详情", hitl: "审批参数" };
  return <div className="mt-2 border-t border-border pt-2 text-xs">
    <button className="underline" onClick={() => setOpen(value => !value)}>{open ? "收起" : "展开"}{names[field] ?? field} · {Math.ceil(bytes / 1024)} KiB</button>
    {open && <>
      <p className="my-1 text-muted-foreground">大内容以纯文本分块显示，保留完整内容，可逐页查看。</p>
      {error ? <p>{error}</p> : !data ? <p>加载中…</p> : <>
        <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all rounded bg-background/70 p-2">{data.blocks.join("")}</pre>
        <div className="mt-1 flex gap-3">
          {!!history.length && <button className="underline" onClick={() => { setCursor(history[history.length - 1]); setHistory(value => value.slice(0, -1)); }}>上一页</button>}
          {data.nextCursor && <button className="underline" onClick={() => { setHistory(value => [...value, cursor]); setCursor(Number(data.nextCursor)); }}>下一页</button>}
        </div>
      </>}
    </>}
  </div>;
}
