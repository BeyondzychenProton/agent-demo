import { useMemo, useState, type ReactNode } from "react";
import { PauseCircle, Sparkles, Terminal, Wrench } from "lucide-react";

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
  const expandable = !preparing && (inputRaw !== null || outputText !== null || !!card.diffStats);
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
          {(inputRaw !== null || outputText !== null) ? (
            <div className="dsh-io-card">
              {inputRaw !== null ? (
                <div className="dsh-io-section">
                  <span className="dsh-io-label">输入</span>
                  <span className="dsh-io-text">{inputRaw}</span>
                </div>
              ) : null}
              {inputRaw !== null && outputText !== null ? (
                <span className="dsh-io-divider" aria-hidden />
              ) : null}
              {outputText !== null ? (
                <div className="dsh-io-section">
                  <span className="dsh-io-label">输出</span>
                  <span className="dsh-io-text" data-error={failed || undefined}>{outputText}</span>
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
  if (card.name === "web_search") return "网页搜索";
  if (card.name === "web_fetch" || card.name === "fetch_url") return "网页获取";
  if (/bash|shell|command|pwsh|exec/i.test(card.name)) return "运行命令";
  if (/fetch|web|http/i.test(card.name)) return "网页获取";
  if (/search|grep|glob/i.test(card.name)) return "搜索";
  return "工具调用";
}

function toolIcon(card: ToolCardViewModel): ReactNode {
  const title = toolTitle(card);
  if (title === "运行命令") return <Terminal className="size-3.5" />;
  if (title === "网页搜索" || title === "网页获取" || title === "搜索") return <Sparkles className="size-3.5" />;
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

function pretty(value: unknown): string {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}
