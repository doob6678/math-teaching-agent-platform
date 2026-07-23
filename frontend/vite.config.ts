import { defineConfig } from "vite";

// 测试阶段需要先补齐 pdfjs 依赖的最小浏览器几何对象，避免 Node 环境直接 import 组件时报错。
export default defineConfig({
  // Keep browser requests same-origin in local development. The Codex in-app
  // browser blocks direct cross-port localhost fetches, while Vite can proxy
  // the authenticated API without changing the backend contract.
  server: {
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
        secure: false,
      },
    },
  },
  test: {
    setupFiles: ["./src/test/setup.ts"],
  },
});
