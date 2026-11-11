import { test } from "node:test";
import assert from "node:assert/strict";
import { messageChrome, reasoningPreview } from "../src/components/chat/messageChrome.ts";

test("partial assistant keeps quiet eyebrow and caret", () => {
  const chrome = messageChrome({ role: "assistant", partial: true });
  assert.equal(chrome.eyebrow, "助手");
  assert.equal(chrome.showCaret, true);
});

test("user eyebrow stays user-labeled", () => {
  assert.equal(messageChrome({ role: "user" }).eyebrow, "用户");
  assert.equal(messageChrome({ role: "user" }).showCaret, false);
});

test("reasoning chrome uses thinking copy", () => {
  assert.equal(messageChrome({ role: "reasoning", partial: true }).eyebrow, "深度思考中…");
  assert.equal(messageChrome({ role: "reasoning", partial: true }).showCaret, true);
  assert.equal(messageChrome({ role: "reasoning", partial: false }).eyebrow, "已思考");
  assert.equal(messageChrome({ role: "reasoning", partial: false }).showCaret, false);
});

test("short reasoning fits the preview", () => {
  const fitted = reasoningPreview("只用两行内的短思考");
  assert.equal(fitted.needsDetail, false);
  assert.equal(fitted.preview, "只用两行内的短思考");

  const twoLines = reasoningPreview("第一行\n第二行");
  assert.equal(twoLines.needsDetail, false);
  assert.equal(twoLines.preview, "第一行\n第二行");

  const exact = reasoningPreview("思".repeat(120));
  assert.equal(exact.needsDetail, false);
  assert.equal(exact.preview, "思".repeat(120));
});

test("long reasoning keeps a two-line preview", () => {
  const chars = reasoningPreview("思".repeat(121));
  assert.equal(chars.needsDetail, true);
  assert.equal(chars.preview, "思".repeat(120));

  const lines = reasoningPreview("一\n二\n三");
  assert.equal(lines.needsDetail, true);
  assert.equal(lines.preview, "一\n二");

  const trailing = reasoningPreview("一\n二\n");
  assert.equal(trailing.needsDetail, false);
  assert.equal(trailing.preview, "一\n二");
});
