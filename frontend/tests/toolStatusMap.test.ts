import { test } from "node:test";
import assert from "node:assert/strict";
import { mapToolStatus } from "../src/components/chat/toolStatusMap.ts";

test("completed collapses; failed and suspended expand", () => {
  assert.equal(mapToolStatus("completed").defaultOpen, false);
  assert.equal(mapToolStatus("failed").defaultOpen, true);
  assert.equal(mapToolStatus("suspended").defaultOpen, true);
});

test("intermediate statuses stay open and running/pending", () => {
  for (const status of ["generating_arguments", "requested", "running"] as const) {
    assert.equal(mapToolStatus(status).defaultOpen, true);
    assert.ok(["pending", "running"].includes(mapToolStatus(status).visual));
  }
});

test("interrupted is muted and collapsed", () => {
  assert.deepEqual(
    { visual: mapToolStatus("interrupted").visual, defaultOpen: mapToolStatus("interrupted").defaultOpen },
    { visual: "muted", defaultOpen: false },
  );
});
