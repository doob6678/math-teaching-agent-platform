import { defineConfig } from "vite";

// 测试阶段需要先补齐 pdfjs 依赖的最小浏览器几何对象，避免 Node 环境直接 import 组件时报错。
export default defineConfig({
  test: {
    setupFiles: ["./src/test/setup.ts"],
  },
});
