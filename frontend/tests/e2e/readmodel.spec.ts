import { test, expect } from "@playwright/test";
import { writeFile } from "node:fs/promises";

test("历史窗口、长正文及诊断面板只使用同一展示流", async ({ page, request }, info) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const urls: string[] = [];
  page.on("request", request => urls.push(request.url()));
  await page.addInitScript(() => sessionStorage.setItem("session-chat.active-thread", "long-history"));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "展开正文", exact: false })).toBeVisible({ timeout: 30000 });
  const articles = page.getByLabel("会话消息").locator("article");
  expect(await articles.count()).toBeLessThan(30);
  await page.getByRole("button", { name: "展开正文", exact: false }).click();
  await expect(page.getByText("大内容以纯文本分块显示", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "下一页", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("button", { name: "上一页", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "打开服务端视图" }).click();
  await expect(page.getByLabel("服务端历史视图")).toBeVisible();
  expect(urls.filter(url => url.includes("/api/v2/threads/long-history/events")).length).toBe(1);
  expect(urls.some(url => url.includes("/agent/chat/connect"))).toBe(false);
  expect(errors).toEqual([]);
  await page.screenshot({ path: "test-results/long-history.png", fullPage: true });
  await page.getByRole("button", { name: "关闭服务端视图" }).click();
  await page.getByRole("button", { name: "收起正文", exact: false }).click();
  const older = page.waitForResponse(response => response.url().includes("/long-history/view?before="));
  await page.getByRole("button", { name: "加载更早", exact: true }).click();
  expect((await (await older).json()).page.items.length).toBe(50);
  await expect(page.getByRole("button", { name: "加载更早", exact: true })).toBeEnabled();
  await page.getByLabel("会话消息").hover();
  await page.mouse.wheel(0, -100000);
  await expect(page.getByText("历史消息 21", { exact: true })).toBeVisible();
  const scrollMetrics = await page.getByLabel("会话消息").evaluate(async element => {
    const frames: number[] = []; let previous = performance.now(), maxMounted = 0;
    for (let i = 0; i < 60; i++) {
      await new Promise<void>(resolve => requestAnimationFrame(now => {
        frames.push(now - previous); previous = now; element.scrollTop += 20;
        maxMounted = Math.max(maxMounted, element.querySelectorAll("article").length); resolve();
      }));
    }
    frames.sort((a, b) => a - b);
    return { p95FrameMs: frames[56], maxMounted, framesOver50Ms: frames.filter(value => value > 50).length };
  });
  expect(scrollMetrics.maxMounted).toBeLessThan(30);
  await writeFile(info.outputPath("scroll-benchmark.json"), JSON.stringify(scrollMetrics, null, 2));
  const response = await request.get("/api/v2/threads/long-history/view");
  const body = await response.body(); expect(body.length).toBeLessThan(256 * 1024);
});

test("窄屏可发送且会话切换后旧响应不能覆盖新会话", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => sessionStorage.setItem("session-chat.active-thread", "long-history"));
  await page.route("**/api/v2/threads/long-history/view", async route => {
    await new Promise(resolve => setTimeout(resolve, 500)); await route.continue();
  });
  await page.goto("/");
  await page.getByRole("button", { name: "新会话", exact: true }).click();
  await expect(page.getByPlaceholder("输入消息，回车发送…")).toBeVisible();
  await expect(page.getByText("发送一条消息，开始会话。", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "展开正文", exact: false })).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: "test-results/mobile.png", fullPage: true });
});

test("新会话发送和审批答复跟随增量更新", async ({ page }) => {
  const thread = "browser-" + Date.now();
  await page.addInitScript(id => sessionStorage.setItem("session-chat.active-thread", id), thread);
  await page.goto("/");
  const input = page.getByPlaceholder("输入消息，回车发送…");
  await expect(input).toBeVisible({ timeout: 30000 });
  await input.fill("请写入审批测试笔记"); await input.press("Enter");
  await expect(page.getByRole("button", { name: "待处理交互", exact: false })).toBeVisible({ timeout: 30000 });
  await page.getByRole("button", { name: "待处理交互", exact: false }).click();
  const card = page.getByRole("region", { name: "待处理交互", exact: true }).locator("details").first();
  await card.locator("summary").click();
  await card.getByRole("button", { name: "拒绝", exact: true }).click();
  await expect(page.getByLabel("会话消息").getByText("已拒绝", { exact: true })).toBeAttached({ timeout: 30000 });
  await expect(page.getByLabel("会话消息").getByText("完成。", { exact: false })).toBeVisible({ timeout: 30000 });
});
