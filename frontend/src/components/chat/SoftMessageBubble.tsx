import { useEffect, useState, type ReactNode } from "react";
import { Brain, ChevronRight } from "lucide-react";

import { reasoningPreview } from "@/components/chat/messageChrome";
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
  /** Reasoning source text. Used only to decide whether the L2 preview needs an L3 body. */
  text?: string;
  children: ReactNode;
}) {
  if (role === "reasoning") {
    return (
      <ReasoningTrace eyebrow={eyebrow} showCaret={showCaret} text={text ?? ""}>
        {children}
      </ReasoningTrace>
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

function ReasoningTrace({
  eyebrow,
  showCaret,
  text,
  children,
}: {
  eyebrow: string;
  showCaret: boolean;
  text: string;
  children: ReactNode;
}) {
  const { preview, needsDetail } = reasoningPreview(text);
  const [open, setOpen] = useState(showCaret);
  const [detailOpen, setDetailOpen] = useState(false);
  useEffect(() => {
    setOpen(showCaret);
  }, [showCaret]);

  const caret = showCaret ? <StreamingCaret /> : null;

  return (
    <article className="relative max-w-[92%] pl-4">
      <div className="absolute bottom-1 left-[7px] top-1 w-px bg-border/80" aria-hidden />
      <details
        className="group relative text-sm"
        open={open}
        onToggle={event => setOpen(event.currentTarget.open)}
      >
        <summary className="flex cursor-pointer list-none items-center gap-2 py-1.5">
          <span className="absolute left-0 top-2 z-10 flex size-3.5 -translate-x-[7px] items-center justify-center rounded-full bg-background">
            <Brain className={cn("size-3.5", showCaret ? "text-primary" : "text-sky-600")} />
          </span>
          <ChevronRight className="size-3.5 shrink-0 text-muted-foreground transition group-open:rotate-90" />
          <span className={cn("min-w-0 flex-1 truncate font-medium", showCaret ? "text-primary" : "text-foreground/95")}>
            {eyebrow}
          </span>
        </summary>
        {open ? (
          <div className="space-y-2 pb-2 pl-5 pt-1">
            {needsDetail ? (
              <>
                <p className="whitespace-pre-wrap break-words text-[13px] leading-6 text-muted-foreground">{preview}…{detailOpen ? null : caret}</p>
                <details
                  className="group/l3"
                  open={detailOpen}
                  onToggle={event => {
                    event.stopPropagation();
                    setDetailOpen(event.currentTarget.open);
                  }}
                >
                  <summary className="flex cursor-pointer list-none items-center gap-1.5 text-xs font-medium text-muted-foreground">
                    <ChevronRight className="size-3 shrink-0 transition group-open/l3:rotate-90" />
                    全文
                  </summary>
                  {detailOpen ? (
                    <div className="mt-1.5 max-h-56 overflow-auto rounded-xl bg-muted/60 p-2.5 text-[13px] leading-6 text-muted-foreground">
                      <div className="break-words">
                        {children}
                        {caret}
                      </div>
                    </div>
                  ) : null}
                </details>
              </>
            ) : (
              <div className="break-words text-[13px] leading-6 text-muted-foreground">
                {children}
                {caret}
              </div>
            )}
          </div>
        ) : null}
      </details>
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
