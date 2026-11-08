import { test } from "node:test";
import assert from "node:assert/strict";
import { isNearBottom, stickScrollToBottom } from "../src/components/chat/scrollFollow.ts";

test("near-bottom uses live scroll metrics with threshold", () => {
  assert.equal(isNearBottom(900, 1000, 100, 100), true);
  assert.equal(isNearBottom(800, 1000, 100, 100), false);
});

test("stick uses scrollHeight not a stale virtual total", () => {
  const element = { scrollTop: 100, scrollHeight: 2000, clientHeight: 400 };
  stickScrollToBottom(element);
  assert.equal(element.scrollTop, 1600);
});

test("stick clamps when content fits in the viewport", () => {
  const element = { scrollTop: 0, scrollHeight: 300, clientHeight: 400 };
  stickScrollToBottom(element);
  assert.equal(element.scrollTop, 0);
});
