import type { ReactNode } from "react";
import { FileText, PauseCircle } from "lucide-react";

import { TraceToolStep } from "@/components/chat/TraceToolStep";
import { mapToolStatus } from "@/components/chat/toolStatusMap";
import type { ToolCardViewModel } from "@/types/chat";

export function ToolCardView({ card }: {
  card: ToolCardViewModel; threadId?: string | null; onChanged?: () => void;
}) {
  if (card.status === "suspended") {
    return (
      <div className="flex items-center gap-2 py-1.5 pl-1 text-sm text-muted-foreground">
        <PauseCircle className="size-3.5 shrink-0 text-primary" />
        <span>等待你确认 · {card.displayTitle}</span>
      </div>
    );
  }

  const presentation = mapToolStatus(card.status);
  return (
    <TraceToolStep
      title={card.displayTitle}
      status={card.status}
      durationMs={card.durationMs}
      defaultOpen={presentation.defaultOpen}
    >
      {card.diffStats ? (
        <p className="font-mono text-[11px]">
          <span className="text-emerald-700">+{card.diffStats.added}</span>{" "}
          <span className="text-red-700">-{card.diffStats.deleted}</span>
        </p>
      ) : null}

      <Field label="参数">
        <pre className="max-h-40 overflow-auto rounded-xl bg-muted/60 p-2 font-mono text-[11px] leading-5">
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
            <pre className="max-h-56 overflow-auto rounded-xl bg-muted/60 p-2 font-mono text-[11px] leading-5">
              {pretty(card.result)}
            </pre>
          </Field>
        )
      ) : null}

      {card.error ? (
        <p className="rounded-xl border border-red-200 bg-red-50 p-2 text-xs leading-5 text-red-700">
          {card.error}
        </p>
      ) : null}

      {card.name === "write_note" && card.result ? (
        <p className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
          <FileText className="size-3.5" />
          文件卡片：{pathOf(card.result)}
        </p>
      ) : null}

      <div className="flex flex-wrap gap-x-3 gap-y-1 font-mono text-[10px] text-muted-foreground">
        <span>toolCallId: {card.toolCallId}</span>
        {card.runId ? <span>run: {card.runId}</span> : null}
      </div>
    </TraceToolStep>
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
        <pre className="rounded-xl bg-muted/60 p-2 font-mono text-[11px]">
          {pretty(result)}
        </pre>
      </Field>
    );
  }
  const items = Array.isArray(order.items) ? order.items : [];
  return (
    <Field label="订单">
      <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-2.5">
        <div className="flex items-center justify-between gap-2">
          <span className="font-mono text-xs font-semibold text-emerald-800">
            {String(order.orderId ?? "—")}
          </span>
          <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[11px] text-emerald-800">
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
          <ul className="mt-2 space-y-1 border-t border-emerald-200 pt-2 text-[11px] text-muted-foreground">
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
