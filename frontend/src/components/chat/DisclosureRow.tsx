import { memo, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";

import { TextShimmer } from "@/components/chat/TextShimmer";
import { cn } from "@/lib/utils";

/** Port of deepseek-harness `DisclosureRow` (ui-primitives). */
export const DisclosureRow = memo(function DisclosureRow({
  icon,
  title,
  open,
  expandable,
  onToggle,
  running = false,
  expandOnRowClick = false,
  previewChevron = expandable,
  keepContentWhenOpen = false,
  collapsedContent,
  headerAccessory,
  children,
  className,
  rowClassName,
  titleClassName,
}: {
  icon: ReactNode;
  title: string;
  open: boolean;
  expandable: boolean;
  onToggle: () => void;
  running?: boolean;
  expandOnRowClick?: boolean;
  previewChevron?: boolean;
  keepContentWhenOpen?: boolean;
  collapsedContent?: ReactNode;
  headerAccessory?: ReactNode;
  children?: ReactNode;
  className?: string;
  rowClassName?: string;
  titleClassName?: string;
}) {
  const rowExpands = expandable && expandOnRowClick;
  const toggleFromLeading = (event: MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    onToggle();
  };
  const toggleFromKeyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!rowExpands || (event.key !== "Enter" && event.key !== " ")) return;
    event.preventDefault();
    onToggle();
  };

  const collapsedLeading = previewChevron ? (
    <>
      <span className="inline-flex opacity-100 transition-opacity group-hover/dsh:opacity-0">{icon}</span>
      <ChevronDown className="absolute inset-0 m-auto size-3.5 opacity-0 transition-opacity group-hover/dsh:opacity-100" />
    </>
  ) : (
    icon
  );
  const leading = open ? <ChevronUp className="size-3.5" /> : collapsedLeading;

  return (
    <div className={cn("flex w-full min-w-0 flex-col", className)} data-open={open || undefined}>
      <div
        className={cn("flex min-w-0 items-center", headerAccessory ? "gap-2" : undefined)}
        data-disclosure-header
      >
        <div
          className={cn(
            "group/dsh relative flex h-6 min-w-0 flex-1 items-center overflow-hidden text-[13px] leading-6 text-muted-foreground transition-colors",
            rowExpands && "cursor-pointer hover:text-foreground/80",
            rowClassName,
          )}
          data-disclosure-row
          data-expandable={rowExpands || undefined}
          role={rowExpands ? "button" : undefined}
          tabIndex={rowExpands ? 0 : undefined}
          aria-expanded={rowExpands ? open : undefined}
          onClick={rowExpands ? onToggle : undefined}
          onKeyDown={rowExpands ? toggleFromKeyboard : undefined}
        >
          {expandable && !rowExpands ? (
            <button
              type="button"
              className="relative mr-1.5 inline-flex size-4 shrink-0 items-center justify-center border-0 bg-transparent p-0 text-inherit"
              aria-label={title}
              aria-expanded={open}
              onClick={toggleFromLeading}
            >
              {leading}
            </button>
          ) : (
            <span className="relative mr-1.5 inline-flex size-4 shrink-0 items-center justify-center">
              {leading}
            </span>
          )}
          <TextShimmer active={running} contentClassName="min-w-0 flex-1">
            <span className={cn("shrink-0 font-normal", titleClassName)}>{title}</span>
            {(keepContentWhenOpen || !open) ? collapsedContent : null}
          </TextShimmer>
        </div>
        {headerAccessory}
      </div>
      {open ? children : null}
    </div>
  );
});
