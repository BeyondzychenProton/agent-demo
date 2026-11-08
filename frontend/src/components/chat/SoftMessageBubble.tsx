import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

export function SoftMessageBubble({
  role,
  eyebrow,
  showCaret,
  children,
}: {
  role: "user" | "assistant" | "reasoning";
  eyebrow: string;
  showCaret: boolean;
  children: ReactNode;
}) {
  return (
    <article
      className={cn(
        "rounded-2xl border px-3.5 py-2.5 text-sm",
        role === "user"
          ? "border-border/50 bg-secondary/40"
          : "border-border/40 bg-secondary/25",
      )}
    >
      <p className="mb-1.5 text-[11px] text-muted-foreground">{eyebrow}</p>
      <div className="break-words text-foreground">
        {children}
        {showCaret ? (
          <span
            className="ml-0.5 inline-block h-[1em] w-1.5 translate-y-0.5 animate-pulse rounded-sm bg-champagne/80 align-baseline"
            aria-hidden
          />
        ) : null}
      </div>
    </article>
  );
}
