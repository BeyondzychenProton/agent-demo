import { test, expect } from "@playwright/test";

test("提交已接收但展示流延迟时立即显示消息并禁止再次提交", async ({ page }) => {
  const thread = "submit-delay-" + Date.now();
  await page.addInitScript(id => sessionStorage.setItem("session-chat.active-thread", id), thread);
  await page.route(`**/api/sessions/${thread}/turns`, route => route.fulfill({
    contentType: "application/json", body: JSON.stringify({ commandId: "accepted-command", turnId: "turn", runId: null, status: "queued" }),
  }));
  await page.goto("/");
  const input = page.getByPlaceholder("输入消息，回车发送…");
  await expect(page.getByText("发送一条消息，开始会话。", { exact: true })).toBeVisible();
  await input.fill("提交后应立即可见的消息");
  await input.press("Enter");
  await expect.soft(page.getByLabel("会话消息").getByText("提交后应立即可见的消息", { exact: true })).toBeVisible({ timeout: 1000 });
  await expect.soft(input).toBeDisabled({ timeout: 1000 });
  await expect(page.getByRole("button", { name: "发送消息", exact: true })).toBeDisabled();
  await page.reload();
  await expect(page.getByLabel("会话消息").getByText("提交后应立即可见的消息", { exact: true })).toBeVisible();
  await expect(input).toBeDisabled();
});

test("真实慢速回复期间锁定直到完成且本地消息与权威消息不重复", async ({ page }) => {
  const thread = "submit-running-" + Date.now();
  await page.addInitScript(id => sessionStorage.setItem("session-chat.active-thread", id), thread);
  await page.goto("/");
  const input = page.getByPlaceholder("输入消息，回车发送…");
  await input.fill("慢速验证消息"); await input.press("Enter");
  const bubble = page.getByLabel("会话消息").getByText("慢速验证消息", { exact: true });
  await expect(bubble).toBeVisible(); await expect(input).toBeDisabled();
  await page.reload();
  await expect(bubble).toHaveCount(1); await expect(input).toBeDisabled();
  await expect(page.getByLabel("会话消息").getByText("完成。", { exact: false })).toBeVisible({ timeout: 20000 });
  await expect(input).toBeEnabled({ timeout: 10000 }); await expect(bubble).toHaveCount(1);
});

test("网络结果不明时保留消息和锁且只重试同一请求", async ({ page }) => {
  const thread = "submit-unknown-" + Date.now(); const bodies: unknown[] = [];
  await page.addInitScript(id => sessionStorage.setItem("session-chat.active-thread", id), thread);
  await page.route(`**/api/sessions/${thread}/turns`, route => {
    bodies.push(route.request().postDataJSON());
    return bodies.length === 1 ? route.abort("failed") : route.fulfill({ contentType: "application/json",
      body: JSON.stringify({ commandId: "retried", turnId: "same-turn", runId: null, status: "queued" }) });
  });
  await page.goto("/"); const input = page.getByPlaceholder("输入消息，回车发送…");
  await input.fill("网络异常测试消息"); await input.press("Enter");
  await expect(page.getByLabel("会话消息").getByText("网络异常测试消息", { exact: true })).toBeVisible();
  await expect(input).toBeDisabled(); await page.getByRole("button", { name: "重试同一请求" }).click();
  await expect.poll(() => bodies.length).toBe(2); expect(bodies[1]).toEqual(bodies[0]);
  await expect(input).toBeDisabled();
});

test("阅读旧历史时主动发送也能立即看到自己的消息", async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem("session-chat.active-thread", "long-history"));
  await page.route("**/api/sessions/long-history/turns", route => route.fulfill({ contentType: "application/json",
    body: JSON.stringify({ commandId: route.request().postDataJSON().request_id, turnId: "new-turn", runId: null, status: "queued" }) }));
  await page.goto("/");
  const transcript = page.getByLabel("会话消息");
  await transcript.hover(); await page.mouse.wheel(0, -100000);
  await expect(page.getByText("历史消息 71", { exact: true })).toBeVisible();
  const input = page.getByPlaceholder("输入消息，回车发送…");
  await input.fill("从旧历史发送的新消息"); await input.press("Enter");
  await expect(transcript.getByText("从旧历史发送的新消息", { exact: true })).toBeVisible();
  await expect(input).toBeDisabled();
});
