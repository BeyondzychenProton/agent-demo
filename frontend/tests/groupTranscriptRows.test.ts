import { test } from "node:test";
import assert from "node:assert/strict";
import { groupTranscriptRows } from "../src/components/chat/groupTranscriptRows.ts";
import type { ViewItem } from "../src/readmodel/threadStore.ts";
import type { ChatItem } from "../src/types/chat.ts";

function item(chat: ChatItem, orderKey: number): ViewItem {
  const itemId = chat.kind === "message" ? chat.messageId : chat.toolCallId;
  return { itemId, orderKey, contentVersion: 1, item: chat, details: {} };
}

test("wraps live process members under an open process group", () => {
  const items = [
    item({ kind: "message", messageId: "u1", turnId: "t1", runId: "r1", role: "user", text: "hi" }, 1),
    item({
      kind: "message",
      messageId: "th1",
      turnId: "t1",
      runId: "r1",
      role: "reasoning",
      text: "Let me first consider: Langfuse.",
      partial: true,
    }, 2),
    item({
      kind: "tool-card",
      toolCallId: "c1",
      turnId: "t1",
      runId: "r1",
      name: "web_search",
      displayTitle: "search",
      status: "running",
      arguments: { query: "Langfuse license MIT" },
    }, 3),
  ];
  const rows = groupTranscriptRows(items, "r1");
  assert.equal(rows.length, 2);
  assert.equal(rows[0]?.kind, "item");
  assert.equal(rows[1]?.kind, "process");
  if (rows[1]?.kind !== "process") throw new Error("expected process");
  assert.equal(rows[1].closed, false);
  assert.equal(rows[1].members.length, 2);
  assert.match(rows[1].title, /正在搜索网页/);
  assert.match(rows[1].title, /Langfuse license MIT/);
});

test("collapses closed process members under turn process", () => {
  const items = [
    item({ kind: "message", messageId: "u1", turnId: "t1", runId: "r1", role: "user", text: "hi" }, 1),
    item({ kind: "message", messageId: "th1", turnId: "t1", runId: "r1", role: "reasoning", text: "..." }, 2),
    item({
      kind: "tool-card",
      toolCallId: "c1",
      turnId: "t1",
      runId: "r1",
      name: "web_fetch",
      displayTitle: "fetch",
      status: "completed",
      startedAt: "2026-01-01T00:00:00.000Z",
      endedAt: "2026-01-01T00:16:19.000Z",
    }, 3),
    item({
      kind: "tool-card",
      toolCallId: "c2",
      turnId: "t1",
      runId: "r1",
      name: "lookup_order",
      displayTitle: "order",
      status: "completed",
      startedAt: "2026-01-01T00:00:10.000Z",
      endedAt: "2026-01-01T00:16:19.000Z",
    }, 4),
    item({
      kind: "tool-card",
      toolCallId: "c3",
      turnId: "t1",
      runId: "r1",
      name: "bash",
      displayTitle: "bash",
      status: "completed",
      startedAt: "2026-01-01T00:01:00.000Z",
      endedAt: "2026-01-01T00:16:19.000Z",
    }, 5),
    item({ kind: "message", messageId: "a1", turnId: "t1", runId: "r1", role: "assistant", text: "done" }, 6),
  ];
  const rows = groupTranscriptRows(items, null);
  assert.equal(rows.length, 3);
  assert.equal(rows[0]?.kind, "item");
  assert.equal(rows[1]?.kind, "process");
  if (rows[1]?.kind !== "process") throw new Error("expected process");
  assert.equal(rows[1].members.length, 4);
  assert.equal(rows[1].closed, true);
  assert.equal(rows[1].durationMs, 16 * 60_000 + 19_000);
  assert.match(rows[1].title, /已访问网页/);
  assert.equal(rows[2]?.kind, "item");
});
