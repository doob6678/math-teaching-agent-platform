# Java Backend

Spring Boot 3.x + Java 21 后端，承载高中数学教学 Agent 平台的业务接口、Agent 编排、资料检索、学生画像、权限限流、审计追踪、MCP/A2A 协议服务和多 Agent 写作 workflow。

## 主要模块

1. `/api/system/health`：系统健康检查。
2. `/api/resources/textbooks/summary`：教材资源摘要。
3. `teaching`：教学任务 DAG/ReAct 编排、恢复、反馈和讲义导出。
4. `agent`：Agent 执行、Trace 查询、模型调用诊断和多 Agent 写作。
5. `teacher`：教师资料同步、飞书发现下载、资料块检索和审计。
6. `student` / `memory`：学生学习画像、学习快照和记忆复用。
7. `securityrisk` / `infrastructure.security`：Capability Token、权限分级、限流和访问策略。
8. `protocol`：MCP/A2A 发现端点和工具执行服务。

## 资源路径

教材资源来自外部目录，不复制进仓库：

```text
C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books
```

通过环境变量传入：

```text
MATH_AGENT_PROCESSED_BOOKS_ROOT
```

PowerShell 示例：

```powershell
$env:MATH_AGENT_PROCESSED_BOOKS_ROOT = "C:\Users\doob\Desktop\个人资料\高中数学\下载课本代码\tchMaterial-parser-main\tchMaterial-parser-main\processed_books"
mvn spring-boot:run
```
