import test from "node:test";
import assert from "node:assert/strict";
import { toolLoadLabel, healthLabel, mcpTime, operationLines } from "../src/components/mcpApi.ts";

test("removed tools take precedence over loaded state", () => {
  assert.equal(toolLoadLabel({ present: false, loadedInCurrentInstance: true, definitionChanged: false }), "已从远端移除");
});
test("discovery is distinguished from runtime loading", () => {
  assert.equal(toolLoadLabel({ present: true, loadedInCurrentInstance: false, definitionChanged: false }), "待重启加载");
  assert.equal(toolLoadLabel({ present: true, loadedInCurrentInstance: true, definitionChanged: true }), "待重启更新");
  assert.equal(toolLoadLabel({ present: true, loadedInCurrentInstance: true, definitionChanged: false }), "已加载");
});
test("unknown connection health never appears healthy", () => {
  assert.equal(healthLabel("unknown-value"), "尚未验证");
  assert.equal(healthLabel("INTERRUPTED"), "检测中断");
  assert.equal(mcpTime(null), "—");
});
test("management history shows changes without internal detection sequence", () => {
  const lines = operationLines({ id: "1", action: "CONNECTION_VERIFIED", actorName: "管理员", result: "SUCCESS",
    createdAt: "", detail: { added: 1, removed: 0, changed: 0, checkRevision: 55 } });
  assert.match(lines[0], /新增 1 个工具/); assert.doesNotMatch(lines.join(""), /55|checkRevision/);
});
