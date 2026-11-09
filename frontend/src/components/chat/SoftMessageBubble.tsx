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
  if (role === "reasoning") {
    return (
      <article className="max-w-[92%] py-1">
        <p className="mb-1.5 text-[12px] font-medium text-primary/90">{eyebrow}</p>
        <div className="border-l-2 border-border pl-3 text-[13px] leading-6 text-muted-foreground">
          <div className="break-words">
            {children}
            {showCaret ? <StreamingCaret /> : null}
          </div>
        </div>
      </article>
    );
  }

  if (role === "user") {
    return (
      <article className="ml-auto max-w-[85%] sm:max-w-[75%]">
        <div className="rounded-2xl bg-secondary px-4 py-2.5 text-sm leading-6 text-foreground">
          <div className="break-words">
            {children}
          </div>
        </div>
        {eyebrow !== "用户" ? (
          <p className="mt-1 text-right text-[11px] text-muted-foreground">{eyebrow}</p>
        ) : null}
      </article>
    );
  }

  return (
    <article className={cn("max-w-[92%] py-0.5 text-sm leading-7 text-foreground")}>
      <p className="mb-1 text-[11px] text-muted-foreground">{eyebrow}</p>
      <div className="break-words">
        {children}
        {showCaret ? <StreamingCaret /> : null}
      </div>
    </article>
  );
}

function StreamingCaret() {
  return (
    <span
      className="ml-0.5 inline-block h-[1em] w-1.5 translate-y-0.5 animate-pulse rounded-sm bg-foreground/50 align-baseline"
      aria-hidden
    />
  );
}
