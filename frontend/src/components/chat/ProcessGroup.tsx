import { useEffect, useId, useState, type ReactNode } from "react";
import {
  ChevronDown,
  ChevronUp,
  Code2,
  FileText,
  Globe,
  Link2,
  ListTodo,
  MessageCircleQuestion,
  Search,
  Sparkles,
  Terminal,
  Brain,
  Wrench,
  Users,
} from "lucide-react";

import { TextShimmer } from "@/components/chat/TextShimmer";
import { TurnProcessHeader } from "@/components/chat/TurnProcessHeader";
import { cn } from "@/lib/utils";

type ProcessTitleActivity =
  | "thinking"
  | "read"
  | "readImage"
  | "search"
  | "write"
  | "edit"
  | "commands"
  | "code"
  | "webSearch"
  | "webFetch"
  | "subagents"
  | "plan"
  | "questions"
  | "tools"
  | "context";

/**
 * Harness ChatGroupSeat process container.
 * Live (`!closed`): shimmer activity title + expanded members.
 * Closed: TurnProcess “已完成，用时 …” + foldable activity summary.
 */
export function ProcessGroup({
  title,
  closed = true,
  activity = "thinking",
  durationMs,
  failed,
  stopped,
  children,
}: {
  title: string;
  closed?: boolean;
  activity?: ProcessTitleActivity;
  durationMs?: number;
  failed?: boolean;
  stopped?: boolean;
  children: ReactNode;
}) {
  const [turnOpen, setTurnOpen] = useState(true);
  const [groupOpen, setGroupOpen] = useState(!closed);
  const bodyId = useId();

  useEffect(() => {
    if (closed) setGroupOpen(false);
  }, [closed]);

  if (!closed) {
    return (
      <div
        className="dsh-process-group max-w-[92%]"
        data-step-process
        data-group-open={groupOpen || undefined}
        data-live=""
      >
        <LiveProcessHeader
          title={title}
          activity={activity}
          open={groupOpen}
          bodyId={bodyId}
          onToggle={() => setGroupOpen((value) => !value)}
        />
        {groupOpen ? (
          <div id={bodyId} className="flex flex-col gap-1" data-step-process-body>
            {children}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <div
      className="dsh-process-group max-w-[92%]"
      data-step-process
      data-turn-open={turnOpen || undefined}
      data-group-open={groupOpen || undefined}
    >
      <TurnProcessHeader
        open={turnOpen}
        durationMs={durationMs}
        failed={failed}
        stopped={stopped}
        canCollapse
        onToggle={() => setTurnOpen((value) => !value)}
      />
      {turnOpen ? (
        <>
          <button
            type="button"
            className={cn(
              "group/process flex max-w-full items-center gap-1.5 border-0 bg-transparent p-0 text-left text-[13px] leading-6 text-muted-foreground transition-colors hover:text-foreground/80",
              groupOpen && "pb-2",
            )}
            aria-expanded={groupOpen}
            aria-controls={bodyId}
            onClick={(event) => {
              event.currentTarget.focus();
              setGroupOpen((value) => !value);
            }}
          >
            <span className="relative inline-flex size-4 shrink-0 items-center justify-center" aria-hidden>
              <FileText
                className={cn(
                  "size-3.5 transition-opacity",
                  groupOpen ? "opacity-0" : "opacity-100 group-hover/process:opacity-0",
                )}
              />
              <span className="absolute inset-0 flex items-center justify-center">
                {groupOpen ? (
                  <ChevronUp className="size-3.5 opacity-100" />
                ) : (
                  <ChevronDown className="size-3.5 opacity-0 group-hover/process:opacity-100" />
                )}
              </span>
            </span>
            <span className="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">{title}</span>
          </button>
          {groupOpen ? (
            <div id={bodyId} className="flex flex-col gap-1" data-step-process-body>
              {children}
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

function LiveProcessHeader({
  title,
  activity,
  open,
  bodyId,
  onToggle,
}: {
  title: string;
  activity: ProcessTitleActivity;
  open: boolean;
  bodyId: string;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      className={cn(
        "group/process flex max-w-full items-center gap-1.5 border-0 bg-transparent p-0 pb-1 text-left text-[13px] leading-6 text-muted-foreground transition-colors hover:text-foreground/80",
      )}
      aria-expanded={open}
      aria-controls={bodyId}
      data-process-activity={activity}
      onClick={(event) => {
        event.currentTarget.focus();
        onToggle();
      }}
    >
      <span className="relative inline-flex size-4 shrink-0 items-center justify-center" aria-hidden>
        <span
          className={cn(
            "transition-opacity",
            open ? "opacity-0" : "opacity-100 group-hover/process:opacity-0",
          )}
          data-step-process-icon
        >
          {activityIcon(activity)}
        </span>
        <span className="absolute inset-0 flex items-center justify-center" data-step-process-chevron>
          {open ? (
            <ChevronUp className="size-3.5 opacity-100" />
          ) : (
            <ChevronDown className="size-3.5 opacity-0 group-hover/process:opacity-100" />
          )}
        </span>
      </span>
      <TextShimmer active contentClassName="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">
        <span className="min-w-0 overflow-hidden text-ellipsis whitespace-nowrap">{title}</span>
      </TextShimmer>
    </button>
  );
}

function activityIcon(activity: ProcessTitleActivity): ReactNode {
  const className = "size-3.5";
  switch (activity) {
    case "thinking":
      return <Brain className={className} />;
    case "read":
    case "readImage":
    case "webFetch":
      return <Link2 className={className} />;
    case "search":
      return <Search className={className} />;
    case "write":
    case "edit":
      return <FileText className={className} />;
    case "commands":
      return <Terminal className={className} />;
    case "code":
      return <Code2 className={className} />;
    case "webSearch":
      return <Globe className={className} />;
    case "subagents":
      return <Users className={className} />;
    case "plan":
      return <ListTodo className={className} />;
    case "questions":
      return <MessageCircleQuestion className={className} />;
    case "context":
      return <Sparkles className={className} />;
    case "tools":
    default:
      return <Wrench className={className} />;
  }
}
