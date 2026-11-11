import { reasoningText } from "@/components/chat/reasoningText";
import type { ViewItem } from "@/readmodel/threadStore";

/** Port of deepseek-harness process-group activity kinds. */
export type ProcessActivity =
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
  | "context"
  | "thinking";

export type RunningProcessActivity = Exclude<ProcessActivity, "context" | "thinking">;

export interface ProcessActivitySummary {
  readonly counts: readonly { readonly kind: ProcessActivity; readonly count: number }[];
  readonly running: RunningProcessActivity | undefined;
  readonly runningDetail: string;
  readonly preparing?: true;
}

export function toolActivity(name: string): RunningProcessActivity {
  if (name === "read" || name === "read_file") return "read";
  if (name === "read_image") return "readImage";
  if (name === "grep" || name === "glob" || name.endsWith("_inspect")) return "search";
  if (name === "write" || name === "write_file") return "write";
  if (name === "edit" || name === "apply_patch" || name === "str_replace") return "edit";
  if (
    name === "bash"
    || name === "pwsh"
    || name === "shell"
    || name === "exec_command"
    || name === "write_stdin"
    || name.startsWith("terminal_")
  ) {
    return "commands";
  }
  if (name === "run_code") return "code";
  if (name === "web_search") return "webSearch";
  if (name === "web_fetch" || name === "fetch_url") return "webFetch";
  if (name === "subagent" || name.startsWith("subagent_")) return "subagents";
  if (["todo_write", "create_goal", "update_goal", "get_goal"].includes(name)) return "plan";
  if (name === "ask_user_question" || name === "request_user_input") return "questions";
  return "tools";
}

const LIVE_TOOL_DETAIL_MAX_CHARS = 160;
const LIVE_TOOL_DETAIL_KEYS = [
  "title", "description", "objective", "task", "task_name", "name", "question", "questions", "prompt", "message",
  "command", "cmd", "queries", "query", "pattern", "url", "uri", "file_path", "path", "target", "action", "status",
] as const;

function normalizeLiveToolText(text: string): string {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (normalized.length <= LIVE_TOOL_DETAIL_MAX_CHARS) return normalized;
  return `${normalized.slice(0, LIVE_TOOL_DETAIL_MAX_CHARS - 1).trimEnd()}…`;
}

function normalizeLiveToolDetail(value: unknown): string {
  const text = typeof value === "string"
    ? value
    : Array.isArray(value) && value.every((item) => typeof item === "string")
      ? value.join(", ")
      : "";
  return normalizeLiveToolText(text);
}

function questionDetail(value: unknown): string {
  if (!Array.isArray(value)) return "";
  for (const item of value) {
    if (item === null || typeof item !== "object") continue;
    const detail = normalizeLiveToolDetail(Reflect.get(item, "question"));
    if (detail !== "") return detail;
  }
  return "";
}

function argsRecord(args: unknown): Record<string, unknown> | null {
  if (args && typeof args === "object" && !Array.isArray(args)) {
    return args as Record<string, unknown>;
  }
  if (typeof args === "string") {
    try {
      const parsed = JSON.parse(args) as unknown;
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
    } catch {
      return null;
    }
  }
  return null;
}

/** One-line task detail from tool arguments (harness liveToolDetail, plain JSON args). */
export function liveToolDetail(name: string, args: unknown): string {
  const record = argsRecord(args);
  if (!record) return "";
  for (const key of LIVE_TOOL_DETAIL_KEYS) {
    if (!(key in record)) continue;
    const detail = key === "questions"
      ? questionDetail(record[key])
      : normalizeLiveToolDetail(record[key]);
    if (detail !== "") return detail;
  }
  return normalizeLiveToolDetail(name);
}

function liveReasoningDetail(items: readonly ViewItem[]): string {
  for (let index = items.length - 1; index >= 0; index -= 1) {
    const chat = items[index]!.item;
    if (chat.kind !== "message" || chat.role !== "reasoning") continue;
    if (!chat.partial) continue;
    const paragraphs = reasoningText(chat.text).split(/\r?\n[\t ]*\r?\n/);
    for (let paragraphIndex = paragraphs.length - 1; paragraphIndex >= 0; paragraphIndex -= 1) {
      const detail = normalizeLiveToolDetail(paragraphs[paragraphIndex]?.replace(/\*\*/g, ""));
      if (detail !== "") return detail;
    }
  }
  return "";
}

/** Rank categories by distinct tool-call count (reasoning alone does not add a count). */
export function processActivityFromItems(items: readonly ViewItem[]): ProcessActivitySummary {
  const counts = new Map<ProcessActivity, number>();
  let running: RunningProcessActivity | undefined;
  let runningDetail = "";
  let preparing: boolean | undefined;

  for (const entry of items) {
    const chat = entry.item;
    if (chat.kind !== "tool-card") continue;
    const kind = toolActivity(chat.name);
    counts.set(kind, (counts.get(kind) ?? 0) + 1);
    const live =
      chat.status === "generating_arguments"
      || chat.status === "requested"
      || chat.status === "running"
      || chat.status === "suspended";
    if (live) {
      running = kind;
      preparing = chat.status === "generating_arguments" || undefined;
      runningDetail = liveToolDetail(chat.name, chat.arguments);
    }
  }

  if (running === undefined) runningDetail = liveReasoningDetail(items);

  return {
    counts: [...counts]
      .map(([kind, count]) => ({ kind, count }))
      .sort((a, b) => b.count - a.count),
    running,
    runningDetail,
    ...(preparing ? { preparing: true as const } : {}),
  };
}
