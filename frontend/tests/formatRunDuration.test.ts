import { test } from "node:test";
import assert from "node:assert/strict";
import { formatRunDuration, formatRunDurationLabel } from "../src/components/chat/formatRunDuration.ts";

test("clamps negative and fractional ms", () => {
  assert.deepEqual(
    formatRunDuration(-10).map((p) => p.text),
    ["0", "秒"],
  );
  assert.deepEqual(
    formatRunDuration(1999).map((p) => p.text),
    ["1", "秒"],
  );
});

test("formats minutes and seconds like harness", () => {
  assert.deepEqual(
    formatRunDuration(16 * 60_000 + 19_000).map((p) => p.text),
    ["16", "分", "19", "秒"],
  );
});

test("includes hours when needed", () => {
  assert.deepEqual(
    formatRunDuration(3661_000).map((p) => p.text),
    ["1", "小时", "1", "分", "1", "秒"],
  );
});

test("completion label with duration", () => {
  assert.equal(formatRunDurationLabel(16 * 60_000 + 19_000), "已完成，用时 16分19秒");
});

test("completion label without duration", () => {
  assert.equal(formatRunDurationLabel(undefined), "已完成");
  assert.equal(formatRunDurationLabel(null), "已完成");
});
