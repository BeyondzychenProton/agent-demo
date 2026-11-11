import { test } from "node:test";
import assert from "node:assert/strict";
import { liveProcessTitle } from "../src/components/chat/processTitle.ts";
import type { ProcessActivitySummary } from "../src/components/chat/processActivity.ts";

test("defaults to analyzing-request when nothing is running", () => {
  const summary: ProcessActivitySummary = { counts: [], running: undefined, runningDetail: "" };
  assert.equal(liveProcessTitle(summary), "正在分析请求");
});

test("uses running activity label", () => {
  const summary: ProcessActivitySummary = {
    counts: [{ kind: "webSearch", count: 1 }],
    running: "webSearch",
    runningDetail: "",
  };
  assert.equal(liveProcessTitle(summary), "正在搜索网页");
});

test("appends detail after separator", () => {
  const summary: ProcessActivitySummary = {
    counts: [{ kind: "webSearch", count: 1 }],
    running: "webSearch",
    runningDetail: "Langfuse vs Arize Phoenix",
  };
  assert.equal(liveProcessTitle(summary), "正在搜索网页 · Langfuse vs Arize Phoenix");
});

test("uses prepare label while generating arguments", () => {
  const summary: ProcessActivitySummary = {
    counts: [{ kind: "webFetch", count: 1 }],
    running: "webFetch",
    runningDetail: "https://example.com",
    preparing: true,
  };
  assert.equal(liveProcessTitle(summary), "准备访问网页 · https://example.com");
});

test("prepare falls back to tools when only thinking", () => {
  const summary: ProcessActivitySummary = {
    counts: [],
    running: undefined,
    runningDetail: "Let me first consider",
    preparing: true,
  };
  assert.equal(liveProcessTitle(summary), "准备调用工具 · Let me first consider");
});
