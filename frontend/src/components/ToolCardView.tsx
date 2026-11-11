import { useState, type ReactNode } from "react";
import { FileText, PauseCircle, Terminal, Wrench } from "lucide-react";

import { DisclosureRow } from "@/components/chat/DisclosureRow";
import { formatDuration } from "@/components/chat/formatDuration";
import { mapToolStatus } from "@/components/chat/toolStatusMap";
import type { ToolCardViewModel } from "@/types/chat";

/**
 * DeepSeek-harness ToolRow shape: collapsed `工具调用 · summary`, expand → 输入/输出.
 * Preparing/empty rows stay non-expandable. Suspended stays status-only (actions in ApprovalBar).
 */
export function ToolCardView({ card }: {
  card: ToolCardViewModel; threadId?: string | null; onChanged?: () => void;
}) {
  if (card.status === "suspended") {
    return (
      <div className="flex h-6 max-w-[92%] items-center gap-1.5 text-[13px] text-muted-foreground">
        <PauseCircle className="size-3.5 shrink-0 text-primary" />
        <span className="shrink-0">等待你确认</span>
        <span className="mx-2 size-0.5 shrink-0 rounded-full bg-muted-foreground/70" aria-hidden />
        <span className="min-w-0 truncate">{card.displayTitle}</span>
      </div>
    );
  }

  return <ToolProcessRow card={card} />;
}

function ToolProcessRow({ card }: { card: ToolCardViewModel }) {
  const presentation = mapToolStatus(card.status);
  const running = card.status === "generating_arguments" || card.status === "requested" || card.status === "running";
  const summary = toolSummary(card);
  const inputRaw = card.arguments !== undefined && card.arguments !== null ? pretty(card.arguments) : null;
  const outputText =
    card.error ? card.error
      : card.result !== undefined && card.result !== null ? pretty(card.result)
        : null;
  const hasCard = card.name === "lookup_order" && card.result != null;
  const expandable = inputRaw !== null || outputText !== null || hasCard || !!card.diffStats;
  // DeepSeek: disclosure starts collapsed; running uses shimmer, not forced open.
  const [open, setOpen] = useState(false);
  const durationLabel = formatDuration(card.durationMs);
  const title = toolTitle(card);

  return (
    <div className="max-w-[92%]" data-state={running ? "running" : presentation.visual}>
      <DisclosureRow
        icon={toolIcon(card)}
        title={title}
        open={open && expandable}
        expandable={expandable}
        running={running}
        onToggle={() => setOpen(value => !value)}
        collapsedContent={
          summary ? (
            <>
              <span className="mx-2 size-0.5 shrink-0 rounded-full bg-muted-foreground/70" aria-hidden />
              <span className="min-w-0 flex-1 truncate text-[13px] leading-6">{summary}</span>
              {card.diffStats ? (
                <span className="ml-2 shrink-0 font-mono text-[11px]">
                  <span className="text-emerald-700">+{card.diffStats.added}</span>{" "}
                  <span className="text-red-700">-{card.diffStats.deleted}</span>
                </span>
              ) : null}
              {durationLabel ? (
                <span className="ml-2 shrink-0 font-mono text-[11px] text-muted-foreground/80">{durationLabel}</span>
              ) : null}
              <span className="ml-2 shrink-0 text-[11px] text-muted-foreground">{presentation.label}</span>
            </>
          ) : (
            <>
              {durationLabel ? (
                <span className="ml-2 shrink-0 font-mono text-[11px] text-muted-foreground/80">{durationLabel}</span>
              ) : null}
              <span className="ml-2 shrink-0 text-[11px] text-muted-foreground">{presentation.label}</span>
            </>
          )
        }
      >
        <div className="space-y-2 py-1 pl-[22px]">
          {card.progress ? (
            <p className="text-xs leading-5 text-muted-foreground">{card.progress}</p>
          ) : null}
          <div className="rounded-xl bg-muted/60 p-2.5">
            <IoBlock label="输入">
              {inputRaw ? (
                <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-all font-mono text-[11px] leading-5">
                  {inputRaw}
                </pre>
              ) : (
                <p className="text-xs text-muted-foreground">—</p>
              )}
            </IoBlock>
            <div className="my-2.5 border-t border-border/80" role="separator" />
            <IoBlock label="输出">
              {hasCard ? <OrderResult result={card.result} /> : null}
              {!hasCard && outputText ? (
                <pre className={`max-h-56 overflow-auto whitespace-pre-wrap break-all font-mono text-[11px] leading-5 ${card.error ? "text-red-700" : ""}`}>
                  {outputText}
                </pre>
              ) : null}
              {!hasCard && !outputText ? <p className="text-xs text-muted-foreground">—</p> : null}
              {card.name === "write_note" && card.result ? (
                <p className="mt-2 flex items-center gap-1.5 text-[11px] text-muted-foreground">
                  <FileText className="size-3.5" />
                  文件卡片：{pathOf(card.result)}
                </p>
              ) : null}
            </IoBlock>
          </div>
        </div>
      </DisclosureRow>
    </div>
  );
}

function IoBlock({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-1 text-[11px] font-medium text-muted-foreground">{label}</p>
      {children}
    </div>
  );
}

function toolTitle(card: ToolCardViewModel): string {
  if (card.name === "write_note" || /bash|shell|command|pwsh/i.test(card.name)) {
    return "运行命令";
  }
  if (/fetch|web|http/i.test(card.name)) return "网页获取";
  return "工具调用";
}

function toolIcon(card: ToolCardViewModel): ReactNode {
  if (toolTitle(card) === "运行命令") return <Terminal className="size-3.5" />;
  return <Wrench className="size-3.5" />;
}

function toolSummary(card: ToolCardViewModel): string {
  if (card.error) return firstLine(card.error);
  if (card.progress) return firstLine(card.progress);
  if (card.displayTitle && card.displayTitle !== card.name) return card.displayTitle;
  return card.name;
}

function firstLine(text: string): string {
  const newline = text.indexOf("\n");
  return (newline === -1 ? text : text.slice(0, newline)).trim();
}

function OrderResult({ result }: { result: unknown }) {
  const order = objectOf(result);
  if (!order) {
    return <pre className="font-mono text-[11px]">{pretty(result)}</pre>;
  }
  const items = Array.isArray(order.items) ? order.items : [];
  return (
    <div className="rounded-lg border border-emerald-200 bg-emerald-50 p-2.5">
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
