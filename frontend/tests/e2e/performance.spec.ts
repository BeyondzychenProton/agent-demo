import { test, expect } from "@playwright/test";
import { writeFile } from "node:fs/promises";

test("隔离长会话HTTP读取与活动读取测量", async ({ request }, info) => {
  const measure = async (path: string) => {
    const start = performance.now(); const response = await request.get(path);
    expect(response.ok()).toBe(true); const body = await response.body();
    return { ms: performance.now() - start, bytes: body.length, value: JSON.parse(body.toString()) };
  };
  const full = await measure("/api/threads/long-history/view");
  const first = await measure("/api/v2/threads/long-history/view");
  const samples: number[] = [];
  for (let i = 0; i < 30; i++) samples.push((await measure("/api/v2/threads/long-history/view")).ms);
  const oldPage = await measure(`/api/v2/threads/long-history/view?before=${encodeURIComponent(first.value.page.nextCursor)}`);
  expect(first.bytes).toBeLessThan(256 * 1024); expect(oldPage.bytes).toBeLessThan(256 * 1024);
  expect(first.value.page.items.length).toBe(50);
  const thread = "performance-" + Date.now();
  await request.get(`/api/v2/threads/${thread}/view`);
  const receipt = await request.post(`/api/sessions/${thread}/turns`, { data: { request_id: "one", message: "输出流式测量" } });
  expect(receipt.ok()).toBe(true);
  const active: number[] = [];
  for (let i = 0; i < 20; i++) {
    active.push((await measure(`/api/v2/threads/${thread}/view`)).ms);
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  const p95 = (values: number[]) => [...values].sort((a, b) => a - b)[Math.ceil(values.length * .95) - 1];
  const report = { kind: "隔离脚本模型HTTP端到端，不含公网和真实模型", sourceEvents: first.value.status.execSeq,
    fullSnapshot: { ms: full.ms, bytes: full.bytes }, firstWindow: { ms: first.ms, bytes: first.bytes },
    warmWindowP95Ms: p95(samples), oldPage: { ms: oldPage.ms, bytes: oldPage.bytes }, activeReadP95Ms: p95(active) };
  await writeFile(info.outputPath("http-benchmark.json"), JSON.stringify(report, null, 2));
});
