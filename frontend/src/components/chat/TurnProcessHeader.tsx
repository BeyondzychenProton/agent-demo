import { ChevronDown } from "lucide-react";

import { formatRunDuration } from "@/components/chat/formatRunDuration";
import { cn } from "@/lib/utils";

/** Port of deepseek-harness `TurnProcessNodeView` completion chrome. */
export function TurnProcessHeader({
  open,
  durationMs,
  failed = false,
  stopped = false,
  canCollapse,
  onToggle,
}: {
  open: boolean;
  durationMs?: number;
  failed?: boolean;
  stopped?: boolean;
  canCollapse: boolean;
  onToggle: () => void;
}) {
  const duration =
    durationMs == null || stopped || failed
      ? undefined
      : formatRunDuration(Math.max(1000, durationMs));
  const label = stopped
    ? "已停止"
    : failed
      ? "处理失败"
      : duration === undefined
        ? "已完成"
        : "已完成，用时 ";
  const announcement = stopped ? "已停止" : failed ? "处理失败" : "已完成";

  return (
    <>
      <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {announcement}
      </span>
      <button
        type="button"
        className={cn(
          "dsh-turn-process flex w-full min-w-0 items-center border-0 border-b border-border/70 bg-transparent py-0 pb-2 text-left text-sm text-muted-foreground transition-colors",
          canCollapse && "cursor-pointer hover:text-foreground/80",
          !canCollapse && "cursor-default",
        )}
        data-open={open || undefined}
        disabled={!canCollapse}
        aria-expanded={canCollapse ? open : undefined}
        onClick={(event) => {
          event.currentTarget.focus();
          if (canCollapse) onToggle();
        }}
      >
        <span className="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap leading-6">
          {label}
          {duration?.map((part, index) => (
            <span
              key={index}
              className={part.numeric ? "font-mono tabular-nums" : undefined}
            >
              {part.text}
            </span>
          ))}
        </span>
        {canCollapse ? (
          <ChevronDown
            className={cn(
              "ml-1 size-3.5 shrink-0 transition-transform",
              open && "rotate-180",
            )}
          />
        ) : null}
      </button>
    </>
  );
}
