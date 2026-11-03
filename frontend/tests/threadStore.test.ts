import { test } from "node:test";
import assert from "node:assert/strict";
import { mergeItems, displayItems, submissionBlocked, getThreadStore, type ViewItem, type ThreadState } from "../src/readmodel/threadStore.ts";

function item(id: string, version: number, order: number, text = id): ViewItem {
  return { itemId: id, orderKey: order, contentVersion: version, details: {},
    item: { kind: "message", messageId: id, role: "assistant", turnId: "turn", runId: "run", text } };
}
test("旧历史页不能覆盖实时版本，重复事件不重复追加", () => {
  const current = [item("m1", 4, 1, "最终正文")];
  const merged = mergeItems(current, [item("m1", 2, 1, "旧正文"), item("m1", 4, 1, "最终正文")]);
  assert.equal(merged.length, 1); assert.equal((merged[0].item as { text: string }).text, "最终正文");
});
test("补读与历史倒序输入统一按首次出现顺序排列", () => {
  const merged = mergeItems([item("m3", 3, 3)], [item("m2", 2, 2), item("m1", 1, 1)]);
  assert.deepEqual(merged.map(item => item.itemId), ["m1", "m2", "m3"]);
});

function ready(): ThreadState {
  return { items: [], loading: false, fetching: false, trimmed: false, hasMore: false,
    status: { syncState: "ready", activeRun: null, execSeq: 0, inboxSeq: 0, revision: 0, pendingCount: 0, busy: false, commands: [] } };
}
test("已接收但尚未生成run时显示本地消息且提交锁不释放", () => {
  const store = getThreadStore("unit-submit"); store.state = ready();
  const submission = store.beginSubmission("立即回显")!;
  assert.equal(displayItems(store.state)[0].item.kind, "message");
  assert.equal(store.beginSubmission("同帧第二次"), undefined);
  store.accepted(submission.requestId, "command", "turn");
  assert.equal(submissionBlocked(store.state), true);
  store.state = { ...store.state, items: [{ ...item("real", 1, 1, "立即回显"), item: {
    kind: "message", role: "user", turnId: "turn", runId: "run", messageId: "real", text: "立即回显" } }] };
  assert.equal(displayItems(store.state).length, 1);
  assert.equal(submissionBlocked(store.state), true);
});
test("只有同一turn的权威用户消息可以替换本地气泡", () => {
  const state = ready(); state.submission = { requestId: "id", text: "相同文本", phase: "accepted", turnId: "current" };
  state.items = [{ ...item("old", 1, 1), item: { kind: "message", role: "user", text: "相同文本", messageId: "old", turnId: "other", runId: "" } }];
  assert.equal(displayItems(state).length, 2);
  state.status!.pendingCount = 1; state.submission = undefined;
  assert.equal(submissionBlocked(state), true);
});
test("命令完成并收到权威消息后解除锁，网络不明只能重试原请求", async () => {
  const store = getThreadStore("unit-complete"); store.state = ready();
  const original = store.beginSubmission("测试消息")!;
  store.uncertain(original.requestId, "网络中断");
  assert.equal(store.beginSubmission("不得新提交"), undefined);
  const retry = store.retrySubmission()!; assert.equal(retry.requestId, original.requestId);
  assert.equal(store.retrySubmission(), undefined);
  store.accepted(retry.requestId, "command", "turn");
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ page: { items: [{ ...item("real", 3, 1),
    item: { kind: "message", messageId: "real", turnId: "turn", runId: "run", role: "user", text: "测试消息" } } ] },
    status: { ...ready().status, revision: 3, commands: [{ commandId: "command", turnId: "turn", runId: "run", status: "completed" }] } }));
  try { await store.refresh(); } finally { globalThis.fetch = previousFetch; }
  assert.equal(store.state.submission, undefined);
  assert.equal(submissionBlocked(store.state), false);
  assert.equal(displayItems(store.state).length, 1);
});

test("刷新丢失HTTP回执后按requestId从服务端命令事实找回完成状态", async () => {
  const store = getThreadStore("unit-lost-receipt"); store.state = ready();
  const submission = store.beginSubmission("刷新前的消息")!;
  store.uncertain(submission.requestId, "回执已中断");
  const previousFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ page: { items: [{ ...item("real", 4, 1),
    item: { kind: "message", messageId: "real", turnId: "actual-turn", runId: "run", role: "user", text: submission.text } }] },
    status: { ...ready().status, commands: [{ commandId: submission.requestId, turnId: "actual-turn", runId: "run", status: "completed" }] } }));
  try { await store.refresh(); } finally { globalThis.fetch = previousFetch; }
  assert.equal(store.state.submission, undefined); assert.equal(submissionBlocked(store.state), false);
});

test("本地长消息只渲染预览且不截断实际提交正文", () => {
  const state = ready(); const text = "🙂中文".repeat(10000);
  state.submission = { requestId: "long", text, phase: "sending" };
  const displayed = displayItems(state)[0].item as { text: string };
  assert.ok(displayed.text.length < 1200); assert.equal(state.submission.text, text);
  assert.equal(displayed.text.includes("�"), false);
});
