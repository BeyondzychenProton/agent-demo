import { useEffect, useRef, useState } from "react";

import { apiFetch } from "@/auth/api";
import { pickOldestPending } from "@/components/chat/pickOldestPending";
import { getThreadStore, useThreadView, type ViewItem } from "@/readmodel/threadStore";
import type { ToolCardViewModel } from "@/types/chat";

async function loadOldestActionable(threadId: string, signal?: AbortSignal): Promise<ViewItem | null> {
  let after = "0";
  for (;;) {
    const response = await apiFetch(
      `/api/v2/threads/${encodeURIComponent(threadId)}/interactions?after=${encodeURIComponent(after)}`,
      { signal },
    );
    if (!response.ok) throw new Error("读取待确认操作失败");
    const data = await response.json() as { items: ViewItem[]; nextCursor?: string | null };
    const picked = pickOldestPending(data.items ?? []);
    if (picked) return picked;
    if (!data.nextCursor || !(data.items?.length)) return null;
    after = data.nextCursor;
  }
}

export function ApprovalBar({ threadId }: { threadId: string }) {
  const view = useThreadView(threadId);
  const pendingCount = view.status?.pendingCount ?? 0;
  const [entry, setEntry] = useState<ViewItem | null>(null);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState("");
  const [retry, setRetry] = useState<{ decision: "approve" | "reject" | "cancel"; reason: string } | null>(null);
  const [error, setError] = useState("");
  const shownId = useRef<string | null>(null);

  const reload = async (signal?: AbortSignal) => {
    if (!pendingCount) {
      setEntry(null);
      return null;
    }
    const next = await loadOldestActionable(threadId, signal);
    if (signal?.aborted) return null;
    setEntry(next);
    return next;
  };

  useEffect(() => {
    if (!pendingCount) {
      setEntry(null); setBusy(false); setNotice(""); setRetry(null); setReason("");
      shownId.current = null;
      return;
    }
    const abort = new AbortController();
    void reload(abort.signal)
      .then(() => { if (!abort.signal.aborted) setError(""); })
      .catch(caught => { if (!abort.signal.aborted) setError(caught instanceof Error ? caught.message : "读取失败"); });
    return () => abort.abort();
  }, [threadId, pendingCount, view.status?.inboxSeq, view.status?.revision, view.status?.activeRun]);

  useEffect(() => {
    const id = entry?.itemId ?? null;
    if (id !== shownId.current) {
      shownId.current = id;
      setBusy(false);
      setReason("");
      setNotice("");
      setRetry(null);
    }
  }, [entry?.itemId]);

  if (!pendingCount) return null;
  const card = entry?.item.kind === "tool-card" ? entry.item as ToolCardViewModel : null;

  const act = async (decision: "approve" | "reject" | "cancel", retrying = false) => {
    if (!card?.hitl?.canRespond || (busy && !retrying)) return;
    const submittedReason = retrying && retry ? retry.reason : reason;
    setRetry(null);
    setBusy(true);
    setNotice(decision === "cancel" ? "正在取消" : "正在提交");
    const path = decision === "cancel"
      ? `/api/threads/${encodeURIComponent(threadId)}/turns/${encodeURIComponent(card.turnId)}/cancel`
      : `/api/threads/${encodeURIComponent(threadId)}/interactions/${encodeURIComponent(card.hitl.requestId)}/answer`;
    try {
      const response = await fetch(path, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: decision === "cancel" ? undefined : JSON.stringify({ decision, reason: submittedReason }),
        signal: AbortSignal.timeout(15000),
        credentials: "include",
      });
      if (!response.ok) {
        setNotice(response.status === 409 ? "状态已变化，请重新查询当前结果" : "未能确认提交结果，请重新查询");
        if (response.status >= 500) setRetry({ decision, reason: submittedReason });
        if (response.status === 400) setBusy(false);
      } else {
        setNotice(decision === "cancel" ? "取消结果已记录" : "答复已接收，正在确认处理进度");
      }
      await getThreadStore(threadId).refresh();
      await reload();
      // busy stays true until entry.itemId changes (same-decision lock), matching prior ToolCardView.
    } catch {
      setNotice("正在确认提交结果，请先查询；如需重试，只能重发同一决定");
      setRetry({ decision, reason: submittedReason });
      void getThreadStore(threadId).refresh();
    }
  };

  return (
    <div role="region" aria-label="审批" className="mb-3 rounded-2xl border border-border bg-card p-3 shadow-[0_1px_2px_oklch(0_0_0/0.04)]">
      {error ? <p className="text-sm text-amber-700">{error}</p> : null}
      {!error && !card ? <p className="text-sm text-muted-foreground">正在加载待确认操作…</p> : null}
      {card?.hitl ? <>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="text-sm font-medium text-foreground">{card.hitl.askPrompt}</p>
            <p className="mt-0.5 text-xs text-muted-foreground">{card.displayTitle}</p>
          </div>
          {pendingCount > 1 ? <p className="shrink-0 text-xs text-muted-foreground">还有 {pendingCount - 1} 条待确认</p> : null}
        </div>
        <label className="mt-3 block text-xs text-muted-foreground">说明（可选）
          <input
            aria-label="审批说明"
            value={reason}
            maxLength={2000}
            disabled={busy}
            onChange={event => setReason(event.target.value)}
            className="mt-1 w-full rounded-xl border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary focus:ring-2 focus:ring-ring/30"
          />
        </label>
        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={() => void act("approve")}
            className="rounded-xl bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground disabled:opacity-50"
          >
            批准
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void act("reject")}
            className="rounded-xl border border-border bg-background px-3 py-1.5 text-sm font-medium text-foreground disabled:opacity-50"
          >
            拒绝
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => void act("cancel")}
            className="rounded-xl px-3 py-1.5 text-sm text-muted-foreground underline-offset-2 hover:underline disabled:opacity-50"
          >
            取消任务
          </button>
          <button type="button" onClick={() => void reload()} className="ml-auto text-xs text-muted-foreground underline">
            查询当前结果
          </button>
        </div>
        {notice ? <p className="mt-2 text-xs text-muted-foreground" role="status">{notice}</p> : null}
        {retry && card.hitl.canRespond ? (
          <button type="button" onClick={() => void act(retry.decision, true)} className="mt-2 text-xs underline">
            重试同一提交
          </button>
        ) : null}
      </> : null}
    </div>
  );
}
