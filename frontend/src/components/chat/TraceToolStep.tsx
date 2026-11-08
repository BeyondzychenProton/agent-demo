import { useEffect, useState, type ReactNode } from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Loader2,
  PauseCircle,
  Wrench,
  XCircle,
} from "lucide-react";

import { cn } from "@/lib/utils";
import type { ToolCardStatus } from "@/types/chat";
import { mapToolStatus, type TraceVisual } from "@/components/chat/toolStatusMap";

function VisualIcon({ visual }: { visual: TraceVisual }) {
  if (visual === "done") return <CheckCircle2 className="size-3.5 text-emerald-600" />;
  if (visual === "error") return <XCircle className="size-3.5 text-red-600" />;
  if (visual === "hitl") return <PauseCircle className="size-3.5 text-primary" />;
  if (visual === "muted") return <AlertTriangle className="size-3.5 text-muted-foreground" />;
  if (visual === "running") return <Loader2 className="size-3.5 animate-spin text-amber-600" />;
  return <Wrench className="size-3.5 text-sky-600" />;
}

const LABEL_STYLE: Record<TraceVisual, string> = {
  pending: "text-sky-700",
  running: "text-amber-700",
  done: "text-emerald-700",
  error: "text-red-700",
  hitl: "text-primary",
  muted: "text-muted-foreground",
};

export function TraceToolStep({
  title,
  status,
  durationMs,
  defaultOpen,
  children,
}: {
  title: string;
  status: ToolCardStatus;
  durationMs?: number;
  defaultOpen: boolean;
  children?: ReactNode;
}) {
  const presentation = mapToolStatus(status);
  const [open, setOpen] = useState(defaultOpen);
  useEffect(() => {
    setOpen(defaultOpen);
  }, [defaultOpen, status]);

  return (
    <div className="relative pl-4">
      <div className="absolute bottom-1 left-[7px] top-1 w-px bg-border/80" aria-hidden />
      <details
        className="group relative text-sm"
        open={open}
        onToggle={event => setOpen(event.currentTarget.open)}
      >
        <summary className="flex cursor-pointer list-none items-center gap-2 py-1.5">
          <span className="absolute left-0 top-2 z-10 flex size-3.5 -translate-x-[7px] items-center justify-center rounded-full bg-background">
            <VisualIcon visual={presentation.visual} />
          </span>
          <ChevronRight className="size-3.5 shrink-0 text-muted-foreground transition group-open:rotate-90" />
          <span className="min-w-0 flex-1 truncate font-medium text-foreground/95">{title}</span>
          {durationMs != null ? (
            <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{durationMs}ms</span>
          ) : null}
          <span className={cn("shrink-0 text-[11px] font-medium", LABEL_STYLE[presentation.visual])}>
            {presentation.label}
          </span>
        </summary>
        {children ? <div className="space-y-3 pb-2 pl-5 pt-1">{children}</div> : null}
      </details>
    </div>
  );
}
