import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e", timeout: 45000, workers: 1,
  use: {
    baseURL: "http://127.0.0.1:15173", screenshot: "only-on-failure", trace: "retain-on-failure",
    channel: process.env.PLAYWRIGHT_CHANNEL ?? "chrome",
  },
  webServer: [
    { command: "node scripts/start-test-backend.mjs", url: "http://127.0.0.1:18087/api/v2/threads", reuseExistingServer: true, timeout: 120000 },
    { command: "npm run dev -- --host 127.0.0.1 --port 15173 --strictPort", url: "http://127.0.0.1:15173",
      env: { AGENT_SCOPE_BACKEND: "http://127.0.0.1:18087" }, reuseExistingServer: true, timeout: 120000 },
  ],
});
