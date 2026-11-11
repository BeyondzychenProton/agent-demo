import { useState, type ReactNode } from "react";
import { Brain } from "lucide-react";

import { DisclosureRow } from "@/components/chat/DisclosureRow";
import { reasoningSummary } from "@/components/chat/reasoningText";
import { cn } from "@/lib/utils";

export function SoftMessageBubble({
  role,
  eyebrow,
  showCaret,
  text,
  children,
}: {
  role: "user" | "assistant" | "reasoning";
  eyebrow: string;
  showCaret: boolean;
  /** Full reasoning source text for collapsed one-line preview. */
  text?: string;
  children: ReactNode;
}) {
  if (role === "reasoning") {
    return (
      <ReasoningRow running={showCaret} text={text ?? ""} title={eyebrow}>
        {children}
      </ReasoningRow>
    );
  }

  if (role === "user") {
    return (
      <article className="ml-auto max-w-[85%] sm:max-w-[75%]">
        <div className="rounded-2xl bg-secondary px-4 py-2.5 text-sm leading-6 text-foreground">
          <div className="break-words">{children}</div>
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

/** DeepSeek-harness ReasoningRow: collapsed = 思考 · first-line; expand = full body. */
function ReasoningRow({
  title,
  running,
  text,
  children,
}: {
  title: string;
  running: boolean;
  text: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const summary = reasoningSummary(text, running);

  return (
    <article className="max-w-[92%]" data-variant="think" data-state={running ? "running" : "ok"}>
      <DisclosureRow
        icon={<Brain className="size-3.5" />}
        title={title}
        open={open}
        expandable
        running={running}
        onToggle={() => setOpen(value => !value)}
        collapsedContent={
          summary ? (
            <>
              <span className="mx-2 size-0.5 shrink-0 rounded-full bg-muted-foreground/70" aria-hidden />
              <span
                className={cn(
                  "min-w-0 flex-1 truncate text-[13px] leading-6",
                  running && "[mask-image:linear-gradient(to_right,black_calc(100%-48px),transparent)]",
                )}
              >
                {summary}
              </span>
            </>
          ) : null
        }
      >
        <div className="min-w-0 py-1 pl-[22px] text-[13px] leading-5 text-muted-foreground">
          <div className="break-words">
            {children}
            {running ? <StreamingCaret /> : null}
          </div>
        </div>
      </DisclosureRow>
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
