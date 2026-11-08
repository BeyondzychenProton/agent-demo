import { test } from "node:test";
import assert from "node:assert/strict";
import { pickOldestPending } from "../src/components/chat/pickOldestPending.ts";
import type { ViewItem } from "../src/readmodel/threadStore.ts";

function tool(id: string, canRespond: boolean, status: "suspended" | "completed" = "suspended"): ViewItem {
  return {
    itemId: id, orderKey: 1, contentVersion: 1, details: {},
    item: {
      kind: "tool-card", toolCallId: id, turnId: "t", runId: "r", name: "write_note",
      displayTitle: id, status,
      hitl: canRespond
        ? { requestId: "req-" + id, askPrompt: "确认？", decision: null, canRespond: true }
        : { requestId: "req-" + id, askPrompt: "确认？", decision: null, canRespond: false },
    },
  };
}

test("picks first suspended canRespond item", () => {
  const picked = pickOldestPending([
    tool("a", false), tool("b", true), tool("c", true),
  ]);
  assert.equal(picked?.itemId, "b");
});

test("returns null when none actionable", () => {
  assert.equal(pickOldestPending([tool("a", false), tool("b", false, "completed")]), null);
});
