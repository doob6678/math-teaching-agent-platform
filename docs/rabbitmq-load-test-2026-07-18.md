# RabbitMQ 隔离压测记录（2026-07-18）

## 目的

验证本机真实 RabbitMQ 对教师资料同步命令的传输能力。测试使用独立、exclusive、auto-delete 队列，不向 `teacher.source-sync.execute.q` 写入任何消息，也不触发下载、OCR、模型调用或 Milvus 重建。

## 环境

| 项目 | 实际值 |
| --- | --- |
| Broker | RabbitMQ 3.13.7（Docker/WSL） |
| AMQP 地址 | `127.0.0.1:5672` |
| 测试客户端 | Python 3 + Pika 1.4.1 |
| 可靠性模式 | 每条消息持久化、publisher confirm、手动 ACK |
| 消费者 | 1 个独立 AMQP 消费者 |

## 实测结果

执行命令：

```powershell
python benchmarks/rabbitmq_queue_load.py --messages 100 --payload-bytes 128 --consumers 1 --timeout-seconds 10
```

| 指标 | 实测值 |
| --- | ---: |
| 消息数 | 100 |
| 负载 | 128 B |
| 总耗时 | 4.466 s |
| 端到端吞吐 | 22.39 msg/s |
| P50 延迟 | 44.228 ms |
| P95 延迟 | 48.423 ms |
| P99 延迟 | 48.914 ms |

## 结论

该吞吐是逐条等待 publisher confirm 的可靠投递结果，适合当前“教师资料同步”这类低频、高价值命令：它将一次 HTTP 鉴权后的任务可靠交给后台，而非用于高频日志流。

当前业务的限制应保持为 `consumer-concurrency=1`、`prefetch=1`。真正昂贵的阶段是后续 PDF/DOCX 解析、Python OCR、GPU 和 Milvus，而不是 RabbitMQ；盲目增加消费者会放大这些资源的竞争。只有当生产监控观察到持续积压时，才应在真实资料样本上将消费者提高到 2，并观察 Python Worker 和 Milvus 的 P95。

## 复现

```powershell
python benchmarks/rabbitmq_queue_load.py --messages 1000 --payload-bytes 512 --consumers 1 --timeout-seconds 120
```

该脚本不调用任何 LLM 或 Spring AI 模型，不消耗模型 token/API 额度。
