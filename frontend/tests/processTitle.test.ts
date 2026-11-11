import { test } from "node:test";
import assert from "node:assert/strict";
import { processTitle } from "../src/components/chat/processTitle.ts";
import type { ProcessActivitySummary } from "../src/components/chat/processActivity.ts";

test("defaults to thinking done label", () => {
  const summary: ProcessActivitySummary = { counts: [], running: undefined, runningDetail: "" };
  assert.equal(processTitle(summary), "已完成分析");
});

test("joins two shared-prefix labels", () => {
  const summary: ProcessActivitySummary = {
    counts: [
      { kind: "webFetch", count: 2 },
      { kind: "tools", count: 1 },
    ],
    running: undefined,
    runningDetail: "",
  };
  assert.equal(processTitle(summary), "已访问网页并调用工具");
});

test("joins three labels and appends 等 when more", () => {
  const summary: ProcessActivitySummary = {
    counts: [
      { kind: "webFetch", count: 3 },
      { kind: "tools", count: 2 },
      { kind: "commands", count: 1 },
      { kind: "search", count: 1 },
    ],
    running: undefined,
    runningDetail: "",
  };
  assert.equal(processTitle(summary), "已访问网页，已调用工具，执行了命令等");
});
