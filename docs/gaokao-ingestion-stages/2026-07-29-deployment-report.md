# 2026-07-29 WSL Docker 部署报告

## 结果

Compose 完整服务栈运行于 WSL Ubuntu。MySQL、Redis、RabbitMQ、Milvus、ai-worker、backend 与 frontend 曾均报告 healthy；后端对 MySQL 的连接、Redis/Redisson、RabbitMQ 连接和题库预热均有真实启动日志。

## 数据库证据

后端重建并重启后，Flyway 的实时 MySQL 查询显示最新成功版本为 `27`（`question occurrence region identity`）。新表用于 import run、文件版本、规范题、来源出现、审计决策与 Luna 调用证据。

后续包含文件发现、PDFBox 页图渲染和 V27 的后端镜像已在 WSL 重建并滚动部署。真实 2024 入库运行在同一 Compose MySQL 中写入 6 个来源文件和 126 个待视觉审核 occurrence；数据库快照位于 `output/gaokao-evidence/2024/database-run-0ebc55dd.json`。

## 已知发现

- Redis 容器会提示 Linux `vm.overcommit_memory` 未启用；当前服务正常，但高负载持久化前应由 WSL 主机管理员设置该内核参数。
- Flyway 对 MySQL 8.4 输出“支持版本尚未测试”的升级建议；迁移已成功，建议在依赖升级时复测。
- Flyway 成功后，WSL 主机与容器内对 `127.0.0.1:8080` 的手工 HTTP 探测均连接失败，紧接着的 Docker 状态/日志读取也在 30 秒内超时。这与先前 Compose `healthy` 状态冲突，不能据此宣称 HTTP 可用；应优先排查 WSL/Docker Desktop 守护进程资源与端口转发。
- 本轮没有产生模型调用。唯一允许的实验模型仍为 `gpt-5.6-luna`；不存在非 Luna 回退。
