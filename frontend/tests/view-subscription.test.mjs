import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { stripTypeScriptTypes } from "node:module";
import test from "node:test";

// 直接执行生产组件中的订阅 effect，替换浏览器网络依赖，无须引入 DOM 或新的测试库。
// 需要支持内置 TypeScript 类型擦除的 Node；重构组件 effect 时同步调整入口定位。
const source = readFileSync(new URL("../src/components/ServerViewPanel.tsx", import.meta.url), "utf8");
const begin = source.indexOf("  useEffect(() => {");
const end = source.indexOf("  }, [threadId]);", begin);
assert.ok(begin >= 0 && end > begin, "应能定位真实会话订阅 effect");
const body = source.slice(begin + "  useEffect(() => {".length, end);
const code = stripTypeScriptTypes(`function effect() { ${body} }`, { mode: "strip" });
const execute = new Function("threadId", "refreshRef", "sourceRef", "setItems", "setSnapshot",
  "setStatus", "setError", "EventSource", "fetch", "upsertChatItem", `${code}; return effect();`);
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness() {
  const connections = [];
  let queries = 0;
  class FakeSource {
    handlers = new Map();
    closed = false;
    constructor(url) { this.url = url; connections.push(this); }
    addEventListener(name, callback) { this.handlers.set(name, callback); }
    emit(name) { this.handlers.get(name)?.({ data: "{}" }); }
    open() { this.onopen?.(); }
    close() { this.closed = true; }
  }
  const refreshRef = { current: null }, sourceRef = { current: null };
  const cleanup = execute("thread-1", refreshRef, sourceRef, () => {}, () => {}, () => {}, () => {},
    FakeSource, async () => {
      queries++;
      return { ok: true, json: async () => ({ view: [], asOfSeq: queries, cursor: `thread-1:${queries}` }) };
    }, items => items);
  return { connections, refreshRef, cleanup, queries: () => queries };
}

test("首次通知只读取一次快照，建立 after 连接不再重取快照", async () => {
  const h = harness();
  assert.equal(h.connections.length, 1);
  assert.equal(h.queries(), 0);
  h.connections[0].open();
  h.connections[0].emit("hitl-view-changed");
  await flush();
  assert.equal(h.queries(), 1);
  assert.equal(h.connections.length, 2);
  assert.match(h.connections[1].url, /view\/events\?after=/);
  h.connections[1].open();
  await flush();
  assert.equal(h.queries(), 1);
  h.cleanup();
});

test("审批回调只重查视图，复用已有两个 SSE 连接", async () => {
  const h = harness();
  h.connections[0].emit("hitl-view-changed");
  await flush();
  h.connections[1].open();
  h.refreshRef.current(); // 对应审批提交完成后的 onChanged，以及手动“重取”。
  await flush();
  assert.equal(h.queries(), 2);
  assert.equal(h.connections.length, 2);
  assert.ok(h.connections.every(connection => !connection.closed));
  h.connections[0].emit("hitl-view-changed");
  await flush();
  assert.equal(h.queries(), 3);
  assert.equal(h.connections.length, 2);
  h.cleanup();
  assert.equal(h.refreshRef.current, null);
  assert.ok(h.connections.every(connection => connection.closed));
});

test("真正断线重连仍重新核对视图，首次 open 不重复查询", async () => {
  const h = harness();
  h.connections[0].emit("hitl-view-changed");
  await flush();
  h.connections[1].open();
  assert.equal(h.queries(), 1);
  h.connections[1].open(); // 模拟浏览器对已有 EventSource 的自动重连。
  await flush();
  assert.equal(h.queries(), 2);
  h.connections[0].open();
  h.connections[0].emit("hitl-view-changed"); // 通知通道重连由服务端首条通知触发查询。
  await flush();
  assert.equal(h.queries(), 3);
  assert.equal(h.connections.length, 2);
  h.cleanup();
});
