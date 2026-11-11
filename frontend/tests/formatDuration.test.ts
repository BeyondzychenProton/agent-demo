import { test } from "node:test";
import assert from "node:assert/strict";
import { formatDuration } from "../src/components/chat/formatDuration.ts";

test("hides missing duration", () => {
  assert.equal(formatDuration(undefined), null);
  assert.equal(formatDuration(null), null);
});

test("hides non-finite and negative duration", () => {
  assert.equal(formatDuration(Number.NaN), null);
  assert.equal(formatDuration(Number.POSITIVE_INFINITY), null);
  assert.equal(formatDuration(Number.NEGATIVE_INFINITY), null);
  assert.equal(formatDuration(-1), null);
});

test("formats ms, seconds, and分秒", () => {
  assert.equal(formatDuration(840), "840ms");
  assert.equal(formatDuration(12400), "12.4s");
  assert.equal(formatDuration(541000), "9分1秒");
});
