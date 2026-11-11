import { test } from "node:test";
import assert from "node:assert/strict";
import { formatDuration } from "../src/components/chat/formatDuration.ts";

test("hides missing duration", () => {
  assert.equal(formatDuration(undefined), null);
  assert.equal(formatDuration(null), null);
});

test("formats ms, seconds, and分秒", () => {
  assert.equal(formatDuration(840), "840ms");
  assert.equal(formatDuration(12400), "12.4s");
  assert.equal(formatDuration(541000), "9分1秒");
});
