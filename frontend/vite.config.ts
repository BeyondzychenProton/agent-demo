import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import { h5Demo } from "./scripts/h5-demo.ts";

// 最小聊天窗开发配置：/agui 默认代理到本地 8087；隔离联调可覆盖目标。
export default defineConfig(({ command, mode }) => {
  const demo = command === "serve" && mode === "h5-demo";
  return {
    plugins: [react(), tailwindcss(), ...(demo ? [h5Demo()] : [])],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    build: {
      outDir: "dist",
      emptyOutDir: true,
    },
    server: {
      port: 5173,
      proxy: demo ? undefined : {
        "/agui": {
          target: process.env.AGENT_SCOPE_BACKEND ?? "http://127.0.0.1:8087",
          changeOrigin: true,
        },
        "/api": {
          target: process.env.AGENT_SCOPE_BACKEND ?? "http://127.0.0.1:8087",
          changeOrigin: true,
        },
      },
    },
  };
});
