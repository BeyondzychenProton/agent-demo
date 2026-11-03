/**
 * 前端视图对象与接口契约（对应大纲 6.1.7 / 6.1.8）。
 * 这些对象不落库；数据源头是 SessionLog，由后端 M4 现算组装。
 */

export type ToolCardStatus =
  | "generating_arguments"
  | "requested"
  | "running"
  | "completed"
  | "failed"
  | "suspended"
  | "interrupted";

export interface HitlBlock {
  requestId: string;
  askPrompt: string;
  payload?: unknown;
  decision: "approved" | "rejected" | null;
  decideAt?: string;
  auditRef?: string;
  interactionStatus?: "pending" | "processing" | "approved" | "rejected" | "cancelled" | "reconciling";
  reason?: string;
  commandId?: string;
  continuationRunId?: string;
  canRespond?: boolean;
  unavailableReason?: string;
}

export interface DiffStats {
  added: number;
  deleted: number;
}

export interface EvictionRef {
  head: string;
  tail: string;
  readFilePointer: string;
}

export interface ToolCardViewModel {
  kind: "tool-card";
  toolCallId: string;
  turnId: string;
  runId: string;
  name: string;
  displayTitle: string;
  status: ToolCardStatus;
  arguments?: unknown;
  result?: unknown;
  error?: string;
  progress?: string;
  diffStats?: DiffStats;
  evictionRef?: EvictionRef;
  hitl?: HitlBlock;
  startedAt?: string;
  endedAt?: string;
  durationMs?: number;
}

export interface MessageItem {
  kind: "message";
  messageId: string;
  turnId: string;
  runId: string;
  role: "user" | "assistant" | "reasoning";
  text: string;
  partial?: boolean;
}

export type ChatItem = MessageItem | ToolCardViewModel;

export interface PendingInteraction {
  requestId: string;
  toolCallId: string;
  turnId: string;
  runId: string;
  askPrompt: string;
  payload?: unknown;
}

export interface ActiveRun {
  runId: string;
  status: "running" | "suspended";
}

export interface SnapshotResponse {
  threadId: string;
  view: ChatItem[];
  pendingInteractions: PendingInteraction[];
  asOfSeq: number;
  cursor: string;
  activeRun?: ActiveRun | null;
}

export type IncrementalEvent =
  | {
      type: "message-delta";
      messageId: string;
      turnId: string;
      runId: string;
      // 契约外的最小扩展：增量通道需要知道这条消息的角色
      role?: "user" | "assistant" | "reasoning";
      chunk: string;
      partial: boolean;
    }
  | {
      type: "card-args";
      toolCallId: string;
      turnId: string;
      runId: string;
      name: string;
      displayTitle: string;
      argsChunk: unknown;
    }
  | {
      type: "card-status";
      toolCallId: string;
      status: ToolCardStatus;
      progress?: string;
    }
  | {
      type: "card-result";
      toolCallId: string;
      status: "completed" | "failed";
      result?: unknown;
      error?: string;
      endedAt?: string;
    }
  | {
      type: "hitl-pending";
      requestId: string;
      toolCallId: string;
      askPrompt: string;
      payload?: unknown;
    }
  | {
      type: "hitl-decided";
      requestId: string;
      decision: "approved" | "rejected";
      decideAt: string;
    }
  | { type: "run-finished"; runId: string; status: string };

const INTERMEDIATE: ToolCardStatus[] = [
  "generating_arguments",
  "requested",
  "running",
];

function parseArgs(value: unknown): unknown {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function appendArgs(current: unknown, chunk: unknown): unknown {
  if (typeof chunk !== "string") return chunk;
  if (current != null && typeof current !== "string") return current;
  const raw = `${current ?? ""}${chunk}`;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function isCard(item: ChatItem): item is ToolCardViewModel {
  return item.kind === "tool-card";
}

function deriveDiffStats(result: unknown): DiffStats | undefined {
  const parsed = parseArgs(result);
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return undefined;
  const value = parsed as Record<string, unknown>;
  if (typeof value.added !== "number" || typeof value.deleted !== "number") return undefined;
  return { added: value.added, deleted: value.deleted };
}

/**
 * 按稳定键 upsert：messageId / toolCallId 去重，不重复渲染。
 * 语义：message-delta.partial=true 追加；false 表示落定全文，直接替换。
 */
export function upsertChatItem(items: ChatItem[], event: IncrementalEvent): ChatItem[] {
  switch (event.type) {
    case "message-delta": {
      const index = items.findIndex(
        (item) => item.kind === "message" && item.messageId === event.messageId,
      );
      if (index < 0) {
        return [
          ...items,
          {
            kind: "message",
            messageId: event.messageId,
            turnId: event.turnId,
            runId: event.runId,
            role: event.role ?? "assistant",
            text: event.chunk,
            partial: event.partial,
          },
        ];
      }
      const current = items[index] as MessageItem;
      const next: MessageItem = {
        ...current,
        text: event.partial ? `${current.text}${event.chunk}` : event.chunk,
        partial: event.partial,
      };
      return items.map((item, position) => (position === index ? next : item));
    }

    case "card-args": {
      const index = items.findIndex(
        (item) => isCard(item) && item.toolCallId === event.toolCallId,
      );
      if (index < 0) {
        return [
          ...items,
          {
            kind: "tool-card",
            toolCallId: event.toolCallId,
            turnId: event.turnId,
            runId: event.runId,
            name: event.name,
            displayTitle: event.displayTitle,
            status: "generating_arguments",
            arguments: parseArgs(event.argsChunk),
          },
        ];
      }
      const current = items[index] as ToolCardViewModel;
      const next: ToolCardViewModel = {
        ...current,
        name: event.name || current.name,
        displayTitle: event.displayTitle || current.displayTitle,
        status:
          current.status === "generating_arguments" ? current.status : "generating_arguments",
        arguments: appendArgs(current.arguments, event.argsChunk),
      };
      return items.map((item, position) => (position === index ? next : item));
    }

    case "card-status": {
      const index = items.findIndex(
        (item) => isCard(item) && item.toolCallId === event.toolCallId,
      );
      if (index < 0) return items;
      const current = items[index] as ToolCardViewModel;
      const next: ToolCardViewModel = {
        ...current,
        status: event.status,
        progress: event.progress ?? current.progress,
      };
      return items.map((item, position) => (position === index ? next : item));
    }

    case "card-result": {
      const index = items.findIndex(
        (item) => isCard(item) && item.toolCallId === event.toolCallId,
      );
      if (index < 0) return items;
      const current = items[index] as ToolCardViewModel;
      const next: ToolCardViewModel = {
        ...current,
        status: event.status,
        result: event.result ?? current.result,
        error: event.error ?? current.error,
        endedAt: event.endedAt ?? current.endedAt,
        diffStats: deriveDiffStats(event.result) ?? current.diffStats,
        arguments: parseArgs(current.arguments),
      };
      return items.map((item, position) => (position === index ? next : item));
    }

    case "hitl-pending": {
      const index = items.findIndex(
        (item) => isCard(item) && item.toolCallId === event.toolCallId,
      );
      if (index < 0) return items;
      const current = items[index] as ToolCardViewModel;
      const next: ToolCardViewModel = {
        ...current,
        status: "suspended",
        hitl: {
          requestId: event.requestId,
          askPrompt: event.askPrompt,
          payload: event.payload,
          decision: null,
        },
      };
      return items.map((item, position) => (position === index ? next : item));
    }

    case "hitl-decided": {
      return items.map((item) => {
        if (!isCard(item) || item.hitl?.requestId !== event.requestId) return item;
        return {
          ...item,
          hitl: {
            ...item.hitl,
            decision: event.decision,
            decideAt: event.decideAt,
          },
        };
      });
    }

    case "run-finished": {
      return items.map((item) => {
        if (!isCard(item) || item.runId !== event.runId) return item;
        if (!INTERMEDIATE.includes(item.status)) return item;
        return {
          ...item,
          status: event.status === "completed" ? "completed" : "interrupted",
        };
      });
    }

    default:
      return items;
  }
}
