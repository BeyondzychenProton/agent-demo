import { test } from "node:test";
import assert from "node:assert/strict";
import {
  firstLine,
  latestCompletedParagraphFirstLine,
  reasoningSummary,
} from "../src/components/chat/reasoningText.ts";

test("firstLine returns the head of settled text", () => {
  assert.equal(firstLine("只有一行"), "只有一行");
  assert.equal(firstLine("第一行\n第二行"), "第一行");
});

test("streaming summary advances on completed paragraph first lines", () => {
  assert.equal(latestCompletedParagraphFirstLine("未完"), "");
  assert.equal(latestCompletedParagraphFirstLine("第一行\n"), "第一行");
  assert.equal(
    latestCompletedParagraphFirstLine("第一段首行\n更多\n\n第二段首行\n继续"),
    "第二段首行",
  );
});

test("reasoningSummary strips bold markers", () => {
  assert.equal(reasoningSummary("**注意** 安装", false), "注意 安装");
  assert.equal(reasoningSummary("首行\n次行", false), "首行");
});
