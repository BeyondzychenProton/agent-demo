import { test } from "node:test";
import assert from "node:assert/strict";
import { messageChrome } from "../src/components/chat/messageChrome.ts";

test("partial assistant keeps quiet eyebrow and caret", () => {
  const chrome = messageChrome({ role: "assistant", partial: true });
  assert.equal(chrome.eyebrow, "助手");
  assert.equal(chrome.showCaret, true);
});

test("user eyebrow stays user-labeled", () => {
  assert.equal(messageChrome({ role: "user" }).eyebrow, "用户");
  assert.equal(messageChrome({ role: "user" }).showCaret, false);
});
