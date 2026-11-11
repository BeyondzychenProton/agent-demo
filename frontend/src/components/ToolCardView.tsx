import { useMemo, useState, type ReactNode } from "react";
import { FileText, PauseCircle, Sparkles, Terminal, Wrench } from "lucide-react";

import { DisclosureRow } from "@/components/chat/DisclosureRow";
import { TextShimmer } from "@/components/chat/TextShimmer";
import { formatDuration } from "@/components/chat/formatDuration";
import { mapToolStatus } from "@/components/chat/toolStatusMap";
import type { ToolCardViewModel } from "@/types/chat";

/**
 * Port of deepseek-harness ToolRow:
 * collapsed `工具调用 · summary` (kept while open via keepContentWhenOpen),
 * expand → IO card with sticky 输入/输出 gutters.
 */
export function ToolCardView({ card }: {
  card: ToolCardViewModel; threadId?: string | null; onChanged?: () => void;
}) {
  if (card.status === "suspended") {
    return (
      <div className="flex h-6 max-w-[92%] items-center text-[13px] leading-6 text-muted-foreground">
        <PauseCircle className="mr-1.5 size-3.5 shrink-0 text-primary" />
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
  const preparing = card.status === "generating_arguments";
  const running =
    card.status === "generating_arguments"
    || card.status === "requested"
    || card.status === "running";
  const failed = card.status === "failed";
  const title = toolTitle(card);
  const summary = toolSummary(card);
  const inputRaw = card.arguments !== undefined && card.arguments !== null ? pretty(card.arguments) : null;
  const outputText = card.error
    ? card.error
    : card.result !== undefined && card.result !== null
      ? pretty(card.result)
      : null;
  const hasOrder = card.name === "lookup_order" && card.result != null;
  const expandable = !preparing && (inputRaw !== null || outputText !== null || hasOrder || !!card.diffStats);
  const [expanded, setExpanded] = useState(false);
  const open = expanded && expandable;
  const durationLabel = formatDuration(card.durationMs);
  const statusLabel = stateStatus(card.status === "interrupted" ? "stopped" : failed ? "error" : running ? (preparing ? "preparing" : "running") : "ok");

  const collapsedContent = useMemo(() => (
    <>
      {summary ? (
        <>
          <span className="mx-2 size-0.5 shrink-0 rounded-full bg-muted-foreground/70" data-shimmer-decoration aria-hidden />
          <span
            className={cnSummary(failed)}
          >
            <TextShimmer active={running}>{summary}</TextShimmer>
          </span>
        </>
      ) : null}
      {card.diffStats && !failed ? (
        <span className="ml-2.5 shrink-0 font-mono text-[11px] leading-6">
          <TextShimmer active={running}>
            <span className="text-emerald-700">+{card.diffStats.added}</span>
            {" "}
            <span className="text-red-700">-{card.diffStats.deleted}</span>
          </TextShimmer>
        </span>
      ) : null}
      {durationLabel ? (
        <span className="ml-2 shrink-0 font-mono text-[11px] text-muted-foreground/80">{durationLabel}</span>
      ) : null}
      {statusLabel ? (
        <span className="sr-only">{statusLabel}</span>
      ) : null}
      <span className="ml-2 shrink-0 text-[11px] text-muted-foreground">{presentation.label}</span>
    </>
  ), [summary, card.diffStats, failed, running, durationLabel, statusLabel, presentation.label]);

  return (
    <div
      className="max-w-[92%]"
      data-state={running ? "running" : failed ? "error" : card.status === "interrupted" ? "stopped" : "ok"}
    >
      <DisclosureRow
        icon={toolIcon(card)}
        title={title}
        running={running}
        open={open}
        expandable={expandable}
        expandOnRowClick
        keepContentWhenOpen
        onToggle={() => setExpanded(value => !value)}
        collapsedContent={collapsedContent}
      >
        <div className="flex flex-col">
          {card.progress ? (
            <p className="mb-1 pl-1 text-xs leading-5 text-muted-foreground">{card.progress}</p>
          ) : null}
          {(inputRaw !== null || outputText !== null || hasOrder) ? (
            <div className="dsh-io-card">
              {inputRaw !== null ? (
                <div className="dsh-io-section">
                  <span className="dsh-io-label">输入</span>
                  <span className="dsh-io-text">{inputRaw}</span>
                </div>
              ) : null}
              {inputRaw !== null && (outputText !== null || hasOrder) ? (
                <span className="dsh-io-divider" aria-hidden />
              ) : null}
              {(outputText !== null || hasOrder) ? (
                <div className="dsh-io-section">
                  <span className="dsh-io-label">输出</span>
                  <div className="min-w-0">
                    {hasOrder ? <OrderResult result={card.result} /> : null}
                    {!hasOrder && outputText !== null ? (
                      <span className="dsh-io-text" data-error={failed || undefined}>{outputText}</span>
                    ) : null}
                    {card.name === "write_note" && card.result ? (
                      <p className="mt-2 flex items-center gap-1.5 text-[11px] text-muted-foreground">
                        <FileText className="size-3.5" />
                        文件卡片：{pathOf(card.result)}
                      </p>
                    ) : null}
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      </DisclosureRow>
    </div>
  );
}

function cnSummary(failed: boolean): string {
  return [
    "min-w-0 flex-1 overflow-hidden text-ellipsis whitespace-nowrap text-[13px] leading-6",
    failed ? "text-red-700" : "",
  ].filter(Boolean).join(" ");
}

function stateStatus(state: "preparing" | "running" | "error" | "stopped" | "ok"): string | null {
  switch (state) {
    case "preparing": return "正在准备";
    case "running": return "正在运行";
    case "error": return "失败";
    case "stopped": return "已中断";
    default: return null;
  }
}

function toolTitle(card: ToolCardViewModel): string {
  if (/bash|shell|command|pwsh|exec/i.test(card.name) || card.name === "write_note") return "运行命令";
  if (/fetch|web|http/i.test(card.name)) return "网页获取";
  if (/search|grep|glob/i.test(card.name)) return "搜索";
  return "工具调用";
}

function toolIcon(card: ToolCardViewModel): ReactNode {
  const title = toolTitle(card);
  if (title === "运行命令") return <Terminal className="size-3.5" />;
  if (title === "网页获取" || title === "搜索") return <Sparkles className="size-3.5" />;
  return <Wrench className="size-3.5" />;
}

/** Prefer description-like displayTitle; fall back to wire name (DeepSeek generic row). */
function toolSummary(card: ToolCardViewModel): string {
  if (card.status === "failed" && card.error) return firstLine(card.error);
  if (card.progress) return firstLine(card.progress);
  if (card.displayTitle && card.displayTitle !== card.name) {
    // `plugin_manager · install_bundle` style when display already includes name
    return card.displayTitle;
  }
  return card.name;
}

function firstLine(text: string): string {
  const newline = text.indexOf("\n");
  return (newline === -1 ? text : text.slice(0, newline)).trim();
}

function OrderResult({ result }: { result: unknown }) {
  const order = objectOf(result);
  if (!order) return <span className="dsh-io-text">{pretty(result)}</span>;
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
