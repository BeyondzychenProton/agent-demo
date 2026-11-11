import type { KeyboardEvent, ReactNode } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import { cn } from "@/lib/utils";

/**
 * DeepSeek-harness style process row: 24px tertiary header, icon→chevron on hover,
 * controlled expand body. Port of `DisclosureRow` from dsh-client-ui-primitives.
 */
export function DisclosureRow({
  icon,
  title,
  open,
  expandable,
  onToggle,
  running = false,
  collapsedContent,
  children,
  className,
}: {
  icon: ReactNode;
  title: string;
  open: boolean;
  expandable: boolean;
  onToggle: () => void;
  running?: boolean;
  collapsedContent?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!expandable || (event.key !== "Enter" && event.key !== " ")) return;
    event.preventDefault();
    onToggle();
  };

  return (
    <div className={cn("flex w-full min-w-0 flex-col", className)} data-open={open || undefined}>
      <div
        className={cn(
          "group/row relative flex h-6 min-w-0 cursor-default items-center overflow-hidden text-[13px] leading-6 text-muted-foreground transition-colors",
          expandable && "cursor-pointer hover:text-foreground/80",
          running && "animate-pulse",
        )}
        role={expandable ? "button" : undefined}
        tabIndex={expandable ? 0 : undefined}
        aria-expanded={expandable ? open : undefined}
        onClick={expandable ? onToggle : undefined}
        onKeyDown={expandable ? onKeyDown : undefined}
      >
        <span className="relative mr-1.5 inline-flex size-4 shrink-0 items-center justify-center">
          {open ? (
            <ChevronUp className="size-3.5" />
          ) : (
            <>
              <span className="inline-flex opacity-100 transition-opacity group-hover/row:opacity-0">{icon}</span>
              {expandable ? (
                <ChevronDown className="absolute inset-0 m-auto size-3.5 opacity-0 transition-opacity group-hover/row:opacity-100" />
              ) : null}
            </>
          )}
        </span>
        <span className="shrink-0 font-normal">{title}</span>
        {!open ? collapsedContent : null}
      </div>
      {open ? children : null}
    </div>
  );
}
