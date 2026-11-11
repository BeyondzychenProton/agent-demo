import { test } from "node:test";
import assert from "node:assert/strict";
import { processActivityFromItems } from "../src/components/chat/processActivity.ts";
import type { ViewItem } from "../src/readmodel/threadStore.ts";
import type { ChatItem } from "../src/types/chat.ts";

function item(chat: ChatItem, id = chat.kind === "message" ? chat.messageId : chat.toolCallId): ViewItem {
  return {
    itemId: id,
    orderKey: 1,
    contentVersion: 1,
    item: chat,
    details: {},
  };
}

test("ranks tool categories by distinct call count", () => {
  const summary = processActivityFromItems([
    item({
      kind: "tool-card",
      toolCallId: "1",
      turnId: "t1",
      runId: "r1",
      name: "web_fetch",
      displayTitle: "fetch",
      status: "completed",
    }),
    item({
      kind: "tool-card",
      toolCallId: "2",
      turnId: "t1",
      runId: "r1",
      name: "web_fetch",
      displayTitle: "fetch2",
      status: "completed",
    }),
    item({
      kind: "tool-card",
      toolCallId: "3",
      turnId: "t1",
      runId: "r1",
      name: "bash",
      displayTitle: "bash",
      status: "completed",
    }),
    item({
      kind: "message",
      messageId: "m1",
      turnId: "t1",
      runId: "r1",
      role: "reasoning",
      text: "thinking",
    }),
  ]);
  assert.deepEqual(
    summary.counts.map((c) => c.kind),
    ["webFetch", "commands"],
  );
  assert.equal(summary.counts[0]?.count, 2);
});

test("unknown tools fall into tools", () => {
  const summary = processActivityFromItems([
    item({
      kind: "tool-card",
      toolCallId: "1",
      turnId: "t1",
      runId: "r1",
      name: "lookup_order",
      displayTitle: "order",
      status: "completed",
    }),
  ]);
  assert.deepEqual(summary.counts, [{ kind: "tools", count: 1 }]);
});

test("extracts runningDetail from live tool arguments", () => {
  const summary = processActivityFromItems([
    item({
      kind: "tool-card",
      toolCallId: "1",
      turnId: "t1",
      runId: "r1",
      name: "web_search",
      displayTitle: "search",
      status: "running",
      arguments: { query: "Langfuse vs Opik comparison 2026" },
    }),
  ]);
  assert.equal(summary.running, "webSearch");
  assert.equal(summary.runningDetail, "Langfuse vs Opik comparison 2026");
});

test("uses latest reasoning paragraph when no tool is running", () => {
  const summary = processActivityFromItems([
    item({
      kind: "message",
      messageId: "m1",
      turnId: "t1",
      runId: "r1",
      role: "reasoning",
      text: "First paragraph.\n\nLet me first consider: Langfuse is an LLM platform.",
      partial: true,
    }),
  ]);
  assert.equal(summary.running, undefined);
  assert.match(summary.runningDetail, /Let me first consider/);
});

test("marks preparing while generating arguments", () => {
  const summary = processActivityFromItems([
    item({
      kind: "tool-card",
      toolCallId: "1",
      turnId: "t1",
      runId: "r1",
      name: "bash",
      displayTitle: "bash",
      status: "generating_arguments",
      arguments: { command: "ls" },
    }),
  ]);
  assert.equal(summary.running, "commands");
  assert.equal(summary.preparing, true);
  assert.equal(summary.runningDetail, "ls");
});
