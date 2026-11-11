import {
  processActivityFromItems,
  type ProcessActivity,
  type RunningProcessActivity,
} from "@/components/chat/processActivity";
import { liveProcessTitle, processTitle } from "@/components/chat/processTitle";
import type { ViewItem } from "@/readmodel/threadStore";

export type TranscriptRow =
  | { readonly kind: "item"; readonly entry: ViewItem }
  | {
      readonly kind: "process";
      readonly key: string;
      readonly turnId: string;
      readonly members: readonly ViewItem[];
      readonly title: string;
      readonly closed: boolean;
      readonly activity: RunningProcessActivity | ProcessActivity | "thinking";
      readonly durationMs: number | undefined;
      readonly failed: boolean;
      readonly stopped: boolean;
    };

function isProcessMember(entry: ViewItem): boolean {
  const item = entry.item;
  if (item.kind === "tool-card") return true;
  return item.kind === "message" && item.role === "reasoning";
}

function isLiveMember(entry: ViewItem): boolean {
  const item = entry.item;
  if (item.kind === "message") return !!item.partial;
  return (
    item.status === "generating_arguments"
    || item.status === "requested"
    || item.status === "running"
    || item.status === "suspended"
  );
}

function processClosed(members: readonly ViewItem[], activeRun: string | null | undefined): boolean {
  if (members.some(isLiveMember)) return false;
  if (!activeRun) return true;
  return !members.some((entry) => entry.item.runId === activeRun);
}

function processDurationMs(members: readonly ViewItem[]): number | undefined {
  let start = Number.POSITIVE_INFINITY;
  let end = Number.NEGATIVE_INFINITY;
  for (const entry of members) {
    const item = entry.item;
    if (item.kind !== "tool-card") continue;
    if (item.startedAt) {
      const ms = Date.parse(item.startedAt);
      if (Number.isFinite(ms)) start = Math.min(start, ms);
    }
    if (item.endedAt) {
      const ms = Date.parse(item.endedAt);
      if (Number.isFinite(ms)) end = Math.max(end, ms);
    }
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return undefined;
  return Math.max(1000, end - start);
}

/**
 * Wrap consecutive reasoning+tool items for a turn into one harness-style process row.
 * Closed vs live only changes the header title / fold policy — members stay grouped.
 */
export function groupTranscriptRows(
  items: readonly ViewItem[],
  activeRun: string | null | undefined,
): TranscriptRow[] {
  const rows: TranscriptRow[] = [];
  let index = 0;
  while (index < items.length) {
    const entry = items[index]!;
    if (!isProcessMember(entry)) {
      rows.push({ kind: "item", entry });
      index += 1;
      continue;
    }
    const turnId = entry.item.turnId;
    const members: ViewItem[] = [];
    while (index < items.length) {
      const next = items[index]!;
      if (!isProcessMember(next) || next.item.turnId !== turnId) break;
      members.push(next);
      index += 1;
    }
    const closed = processClosed(members, activeRun);
    const summary = processActivityFromItems(members);
    const failed = members.some(
      (member) => member.item.kind === "tool-card" && member.item.status === "failed",
    );
    const stopped = members.some(
      (member) => member.item.kind === "tool-card" && member.item.status === "interrupted",
    );
    rows.push({
      kind: "process",
      key: `process:${turnId}:${members[0]!.itemId}`,
      turnId,
      members,
      closed,
      activity: closed
        ? (summary.counts[0]?.kind ?? "thinking")
        : (summary.running ?? "thinking"),
      title: closed ? processTitle(summary) : liveProcessTitle(summary),
      durationMs: closed ? processDurationMs(members) : undefined,
      failed,
      stopped,
    });
  }
  return rows;
}
