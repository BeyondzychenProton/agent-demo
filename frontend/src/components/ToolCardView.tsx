import { useState, type ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  FileText,
  Loader2,
  PauseCircle,
  Wrench,
  XCircle,
} from "lucide-react";

import { cn } from "@/lib/utils";
import type { ToolCardStatus, ToolCardViewModel } from "@/types/chat";

const STATUS_LABEL: Record<ToolCardStatus, string> = {
  generating_arguments: "接收参数",
  requested: "已请求",
  running: "执行中",
  completed: "完成",
  failed: "失败",
  suspended: "待审批",
  interrupted: "已中断",
};

const STATUS_STYLE: Record<ToolCardStatus, string> = {
  generating_arguments: "border-sky-400/30 bg-sky-400/10 text-sky-300",
  requested: "border-sky-400/30 bg-sky-400/10 text-sky-300",
  running: "border-amber-400/30 bg-amber-400/10 text-amber-300",
  completed: "border-emerald-400/30 bg-emerald-400/10 text-emerald-300",
  failed: "border-red-400/30 bg-red-400/10 text-red-300",
  suspended: "border-violet-400/30 bg-violet-400/10 text-violet-300",
  interrupted: "border-border bg-secondary text-muted-foreground",
};

function StatusIcon({ status }: { status: ToolCardStatus }) {
  if (status === "completed") return <CheckCircle2 className="size-4 text-emerald-400" />;
  if (status === "failed") return <XCircle className="size-4 text-red-400" />;
  if (status === "suspended") return <PauseCircle className="size-4 text-violet-400" />;
  if (status === "interrupted") return <AlertTriangle className="size-4 text-muted-foreground" />;
  if (status === "running") return <Loader2 className="size-4 animate-spin text-amber-400" />;
  return <Wrench className="size-4 text-sky-400" />;
}

export function ToolCardView({ card, threadId, onChanged }: {
  card: ToolCardViewModel; threadId?: string | null; onChanged?: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState("");
  const [notice, setNotice] = useState("");
  const [retry, setRetry] = useState<{ decision: "approve" | "reject" | "cancel"; reason: string } | null>(null);
  const act = async (decision: "approve" | "reject" | "cancel", retrying = false) => {
    if (!threadId || !card.hitl?.canRespond || (busy && !retrying)) return;
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
      } else setNotice(decision === "cancel" ? "取消结果已记录" : "答复已接收，正在确认处理进度");
      onChanged?.();
    } catch {
      // 超时不能推断未接收，保持按钮锁定，通过查询找回结果。
      setNotice("正在确认提交结果，请先查询；如需重试，只能重发同一决定");
      setRetry({ decision, reason: submittedReason });
      onChanged?.();
    }
  };
  const labels: Record<string, string> = {
    pending: "等待确认", processing: "答复已接收，等待处理", approved: "已批准",
    rejected: "已拒绝", cancelled: "任务已取消", reconciling: "需要核对",
  };
  return (
    <details className="group rounded-2xl border border-border bg-secondary/30 text-sm transition-colors open:bg-secondary/50">
      <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5">
        <ChevronRight className="size-3.5 shrink-0 text-muted-foreground transition group-open:rotate-90" />
        <StatusIcon status={card.status} />
        <span className="min-w-0 flex-1 truncate font-semibold">{card.displayTitle}</span>
        {card.diffStats ? (
          <span className="shrink-0 font-mono text-[11px]">
            <span className="text-emerald-400">+{card.diffStats.added}</span>{" "}
            <span className="text-red-400">-{card.diffStats.deleted}</span>
          </span>
        ) : null}
        <span
          className={cn(
            "shrink-0 rounded-full border px-2 py-0.5 text-[11px] font-semibold",
            STATUS_STYLE[card.status],
          )}
        >
          {STATUS_LABEL[card.status]}
        </span>
      </summary>

      <div className="space-y-3 border-t border-border/60 px-3 py-3">
        <Field label="参数">
          <pre className="max-h-40 overflow-auto rounded-xl bg-background/60 p-2 font-mono text-[11px] leading-5">
            {pretty(card.arguments)}
          </pre>
        </Field>

        {card.progress ? (
          <Field label="进度">
            <p className="text-xs leading-5 text-muted-foreground">{card.progress}</p>
          </Field>
        ) : null}

        {card.result !== undefined && card.result !== null ? (
          card.name === "lookup_order" ? (
            <OrderResult result={card.result} />
          ) : (
            <Field label="结果">
              <pre className="max-h-56 overflow-auto rounded-xl bg-background/60 p-2 font-mono text-[11px] leading-5">
                {pretty(card.result)}
              </pre>
            </Field>
          )
        ) : null}

        {card.error ? (
          <p className="rounded-xl border border-red-400/30 bg-red-400/10 p-2 text-xs leading-5 text-red-300">
            {card.error}
          </p>
        ) : null}

        {card.name === "write_note" && card.result ? (
          <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
            <FileText className="size-3.5" />
            文件卡片：{pathOf(card.result)}
          </p>
        ) : null}

        {card.hitl ? (
          <div className="rounded-xl border border-violet-400/30 bg-violet-400/10 p-2.5">
            <p className="text-xs font-semibold text-violet-200">{card.hitl.askPrompt}</p>
            <p className="mt-1 text-xs" role="status">{labels[card.hitl.interactionStatus ?? "reconciling"]}</p>
            {card.hitl.reason ? <p className="mt-1 text-xs">说明：{card.hitl.reason}</p> : null}
            {card.hitl.canRespond && threadId ? <>
              <label className="mt-2 block text-xs">说明（可选）
                <input aria-label="审批说明" value={reason} maxLength={2000} disabled={busy}
                  onChange={event => setReason(event.target.value)}
                  className="mt-1 w-full rounded border border-border bg-background p-2" />
              </label>
              <div className="mt-2 flex gap-2">
                {([['approve', '批准'], ['reject', '拒绝'], ['cancel', '取消任务']] as const).map(([action, label]) =>
                  <button key={action} type="button" disabled={busy} onClick={() => void act(action)}
                    className="rounded border border-border px-3 py-1 text-xs disabled:opacity-50">{label}</button>)}
              </div>
            </> : null}
            {notice ? <p className="mt-2 text-xs" role="status">{notice}</p> : null}
            {retry && card.hitl.canRespond ? <button type="button" onClick={() => void act(retry.decision, true)}
              className="mt-2 mr-3 text-xs underline">重试同一提交</button> : null}
            {onChanged ? <button type="button" onClick={onChanged} className="mt-2 text-xs underline">查询当前结果</button> : null}
          </div>
        ) : null}

        <div className="flex flex-wrap gap-x-3 gap-y-1 font-mono text-[10px] text-muted-foreground">
          <span>toolCallId: {card.toolCallId}</span>
          {card.runId ? <span>run: {card.runId}</span> : null}
          {card.durationMs != null ? <span>{card.durationMs}ms</span> : null}
        </div>
      </div>
    </details>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
        {label}
      </p>
      {children}
    </div>
  );
}

function OrderResult({ result }: { result: unknown }) {
  const order = objectOf(result);
  if (!order) {
    return (
      <Field label="结果">
        <pre className="rounded-xl bg-background/60 p-2 font-mono text-[11px]">
          {pretty(result)}
        </pre>
      </Field>
    );
  }
  const items = Array.isArray(order.items) ? order.items : [];
  return (
    <Field label="订单">
      <div className="rounded-xl border border-emerald-400/20 bg-emerald-400/5 p-2.5">
        <div className="flex items-center justify-between gap-2">
          <span className="font-mono text-xs font-semibold text-emerald-200">
            {String(order.orderId ?? "—")}
          </span>
          <span className="rounded-full bg-emerald-400/15 px-2 py-0.5 text-[11px] text-emerald-300">
            {String(order.status ?? "未知")}
          </span>
        </div>
        <dl className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px]">
          <Row label="客户" value={order.customer} />
          <Row label="承运" value={order.carrier} />
          <Row label="运单" value={order.trackingNo} />
          <Row label="预计" value={order.eta} />
          <Row label="金额" value={order.total != null ? `¥${order.total}` : undefined} />
        </dl>
        {items.length ? (
          <ul className="mt-2 space-y-1 border-t border-emerald-400/15 pt-2 text-[11px] text-muted-foreground">
            {items.map((item, index) => {
              const line = objectOf(item);
              return (
                <li key={index} className="flex justify-between gap-2">
                  <span className="truncate">{String(line?.sku ?? "商品")}</span>
                  <span className="shrink-0 font-mono">×{String(line?.qty ?? 1)}</span>
                </li>
              );
            })}
          </ul>
        ) : null}
      </div>
    </Field>
  );
}

function Row({ label, value }: { label: string; value: unknown }) {
  if (value == null || value === "") return null;
  return (
    <>
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="truncate font-mono text-foreground/90">{String(value)}</dd>
    </>
  );
}

function objectOf(value: unknown): Record<string, unknown> | null {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
    } catch {
      return null;
    }
  }
  return null;
}

function pathOf(value: unknown): string {
  const object = objectOf(value);
  return object?.path ? String(object.path) : "—";
}

function pretty(value: unknown): string {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}
