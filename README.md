# 高中数学教学 Agent 平台

这是一个面向高中数学教学场景的 Java Agent + RAG 工程。项目以后端服务为核心，覆盖教师资料管理、教材证据检索、教学任务编排、学生学习画像、权限限流、审计追踪、MCP/A2A 协议暴露和多 Agent 写作工作流。

## 工程结构

| 目录 | 说明 |
|---|---|
| `backend-java/` | Spring Boot 3 + Java 21 后端，承载业务接口、Agent 编排、数据库持久化、安全策略和协议服务。 |
| `frontend/` | 配套前端控制台，覆盖教材检索、学生画像、教师资料、Agent Trace 和多 Agent 写作状态面板。 |
| `文档/` | 产品方案、工程设计、开发进度、资料位置和交付记录。 |
| `docs/` | 工程计划与辅助说明。 |

## 核心能力

1. 教材与教师资料统一检索，保留来源、页码、片段和审计链路。
2. 教学任务 DAG/ReAct 编排，支持恢复、追踪、人工反馈和讲义导出。
3. 学生学习画像与知识图谱进度面板，支持快照刷新和权限隔离。
4. 高价值 AI 接口具备 Capability Token、限流、防重放、审计和访问策略。
5. MCP/A2A 只读协议发现、工具调用和外部集成边界清晰可控。
6. 多 Agent 写作 workflow 支持异步启动、持久化恢复和前端状态追踪。

## 运行入口

后端：

```powershell
cd backend-java
mvn spring-boot:run
```

配套前端：

```powershell
cd frontend
npm install
npm run dev
```

密钥和外部资源路径只从环境变量读取，不写入仓库。教材资源根目录通过 `MATH_AGENT_PROCESSED_BOOKS_ROOT` 配置。

## 提交记录

发布前本地历史提交数：77。完成本次 GitHub 发布提交后，仓库历史提交数为 78。
