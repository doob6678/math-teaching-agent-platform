# 分布式 Agent Worker 架构

## 目标

控制面负责权限校验、工作流状态和阶段依赖；独立 Java Worker 负责领取 RabbitMQ 阶段任务并调用已有 Agent 规划/执行能力。Python Worker 保持向量、重排和视觉专用，不参与业务 Agent 调度。

## 数据流

1. 用户调用多 Agent 写作异步接口，控制面校验 Capability Token 并持久化 workflow。
2. 控制面只为当前依赖已满足的阶段创建 `agent_worker_task`，再向 `agent.worker` direct exchange 发送任务引用。
3. Worker 按 Agent 角色路由领取消息，并使用 MySQL compare-and-set 将任务从 `QUEUED` 租约化为 `RUNNING`。
4. Worker 读取受控任务载荷、执行一个阶段、写回 Trace/工作流快照；当前阶段组全部完成时，控制面释放下一阶段组。
5. 成功任务标记 `COMPLETED`。失败任务在最大次数内重新排队，超过次数标记 `FAILED` 并进入 DLQ。Worker 失联时，过期租约被回收重投。

## 安全边界

- RabbitMQ 消息只包含任务、工作流、阶段和租约引用；不携带 API Key、Capability Token 或用户原始提示词。
- Worker 注册 API 使用 `X-Agent-Worker-Key`；它和面向用户的 Capability Token 完全独立。
- 任务载荷仅保存在 MySQL，由成功领取租约的 Worker 读取。

## 运行

先启动 MySQL、Redis、RabbitMQ 与控制面；再启动独立 Worker：

```powershell
./scripts/local/start-agent-worker.ps1 -WorkerId worker-a
```

常用环境变量：

| 变量 | 作用 |
| --- | --- |
| `MATH_AGENT_AGENT_WORKER_ID` | 稳定 Worker 标识。 |
| `MATH_AGENT_AGENT_WORKER_MAX_CONCURRENCY` | Worker 对外声明的最大并发。 |
| `MATH_AGENT_AGENT_WORKER_LEASE_SECONDS` | 单次任务租约。 |
| `MATH_AGENT_AGENT_WORKER_HEARTBEAT_MILLISECONDS` | Worker 心跳间隔。 |
| `MATH_AGENT_AGENT_WORKER_HEARTBEAT_TIMEOUT_MILLISECONDS` | 超过该时间将节点标记为离线。 |
| `MATH_AGENT_AGENT_WORKER_MAXIMUM_ATTEMPTS` | 失败后允许的总尝试次数。 |
| `MATH_AGENT_AGENT_WORKER_SHARED_KEY` | 注册/心跳接口 Worker 密钥。 |

## 观测与恢复

- `GET /api/agents/workers` 查看 Worker 在线、心跳、负载及成功/失败计数。
- RabbitMQ 查看 `agent.worker.courseware.q` 与 `agent.worker.courseware.dlq` 的积压。
- MySQL 查询 `agent_worker_task` 的 `status`、`attempt`、`lease_expires_at` 和 `error_summary`。
- 调度器定期标记过期心跳节点为 `OFFLINE`，并重新投递过期租约任务。
