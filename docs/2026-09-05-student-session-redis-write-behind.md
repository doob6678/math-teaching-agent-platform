# 学生讲解会话事件 Redis Write-Behind 改造（2026-09-05）

## 1. 背景与结论

老板担心学生讲解会话链路的 MySQL 高频写入耗尽 Hikari 连接池（上限 25，
`infrastructure/database/config/ApplicationDataSourceConfiguration.java:37`）。
本次评估后落地**方案 A（事件侧 Redis 缓冲 + 批量回写 + 故障直写降级）**，只覆盖
SSE 事件存储（热路径），消息/会话行保持 MySQL 直写（每轮仅 2~3 行，收益不足，理由见 §4）。

接口 `StudentExplanationWorkflowStore` 未改动，调用方（`StudentExplanationController`）零改动；
新装饰器以 `@Primary` 接管注入点，`enabled=false` 时行为回到原 MyBatis 直写。

## 2. 现状写入点与真实频率（复核证据）

| 写入点 | 文件:行号 | 触发时机 | 频率量级 |
| --- | --- | --- | --- |
| run 行 insert | `student/service/MyBatisStudentExplanationWorkflowStore.java:62` | 每次提交讲解 | 1 行/轮 |
| **SSE 事件 insert** | 同文件 `:86`（`append`→`eventMapper.insert`） | 控制器 `publish()` 每个公共事件都落一行：`progress`（阶段流转，约 10~20/轮）与 **`ai_delta`（Python worker 每个 provider delta 帧触发一次，见 `StudentExplanationAiCardService.java:122-131` → `PythonMigratedWorkloadClient.readWorkerEventStream:291` → `StudentExplanationController.onAiDelta:322-332` → `publish:366`）** | **数十~数百行/轮，模型流式期间峰值每秒几十次借还连接** |
| run 终态 update | 同文件 `:105/:114`（complete/fail） | 每轮终态 | 1 行/轮 |
| 会话消息 save | `MyBatisStudentExplanationHistoryStore.java:61,68,103` | 每轮讲解完成一次（session insert 0~1 + appendMessage update 1 + message insert 1），同一 `@Transactional` | 2~3 行/轮 |
| 上下文摘要 | 同文件 `:168` | 13 万 token 触发压缩时 | 极低频 |

断线重连读取：`StudentExplanationController.java:343-362`（`replayUntilTerminal` 每 100ms 按
`eventsAfter(runId, Last-Event-ID, 100)` keyset 补发）。

## 3. 方案对比

| 方案 | 连接占用改善 | 崩溃丢失窗口 | Redis 故障降级 | 顺序性 | 复杂度 | 选定 |
| --- | --- | --- | --- | --- | --- | --- |
| A. Redis 缓冲 + 定时/批量回写，重连读 Redis 优先合并 MySQL | 高：事件写入从"每事件一次借连接"降为"每 run 每批一次短事务"；重连读取也多数命中 Redis | JVM 崩溃**不丢**（缓冲在 Redis，重启 boot-flush 排空）；仅"Redis 数据丢失 + 未 flush"（默认 500ms 窗口）丢中间 delta，只影响重连补发文本，不影响终答与历史 | 捕获异常 → 直写 MySQL（同一 id 空间），用户侧无报错、不丢数据 | 单一 Redis 序列 + JVM high-water 重播种，缓冲/直写混合仍严格单调 | 中（装饰层 + 2 个 Lua 脚本） | ✅ |
| B. 短期事件只存 Redis（TTL），MySQL 只存终态 | 最高 | TTL 窗口外彻底无法重连补发；run 表无事件史，审计缺失 | 缓冲即写入路径，Redis 挂 = 事件全丢（无 MySQL 兜底就是丢功能） | 同 A | 低 | ❌（违反"不能丢"门槛） |
| C. 不引入 Redis，逐条 insert 攒批 | 中（减少往返，但仍在请求线程持有连接；异步攒批则要自建内存队列 + 重连进程内补发不可见） | JVM 崩溃丢整批内存队列（纯进程内缓冲） | 无 Redis 依赖 | 单进程内有序 | 低 | ❌（重连补发要读未落库事件，进程内队列跨不了 SSE 重连与多实例边界，收益/风险比差） |

门槛核对："Redis 写 + write-behind 批量同步 + Redis 故障自动降级直写且不丢数据不报错" 可闭环
（终态事件恒同步直写，重连循环总能到达终态；降级路径共用同一显式 id 空间，不乱序），因此动手实现。

## 4. 实现

### 新增文件（`student/service`，除注明外）
- `StudentExplanationWriteBehindProperties`：`math-agent.redis.student-explanation-write-behind` 绑定，含默认值钳制。
- `StudentExplanationWorkflowEventRedisGateway`（接口）：append/readAfter/peekOldest/confirmFlushed/activeRuns 五个原语，测试可注入内存 fake。
- `StringRedisStudentExplanationWorkflowEventGateway`：StringRedisTemplate（与 `RedisStudentExplanationConversationContextCache` 同一接入方式）+ 2 个 Lua：
  - append：单 EVAL 原子完成"序列重播种 high-water → INCR → RPUSH → PEXPIRE → SADD"，每事件仅一次 Redis 往返、零 DB 连接；条目格式 `id\nname\njson`。
  - confirm：LTRIM 已落库头部片段，列表空则 DEL + SREM（崩溃后重放插入靠 event_id 冲突检测幂等）。
- `RedisWriteBehindStudentExplanationWorkflowStore`（`@Primary`，条件同上）：
  - `append`：非终态入缓冲；`completed`/`error` **先排空该 run 缓冲再同步直写**（重连终止性不依赖 Redis）。
  - `eventsAfter`：MySQL 已回写页 + Redis 未回写页按 id 归并去重（flush 竞态窗口两层都有同 id）。
  - `createOrLoad/complete/fail`：直委托（低频且是事件表 FK 前提）。
  - `@Scheduled(fixedDelay=flush-interval-ms)` 批量回写（每批一个事务、一次连接借还）；`@PostConstruct` 播种 high-water 并 boot-flush 上个进程残留；`@PreDestroy` 停机前排空。
  - 降级：缓冲 append/扫描/读取任一 Redis 异常 → 直写 MySQL（high-water 显式 id）+ 30s 限流 WARN；缓冲超 `max-buffered-events` 同样直写（防 Redis 内存无界）；MySQL 失败 → 事务回滚且不 LTRIM，下轮重试；id 撞到他 run → ERROR 并保留缓冲（不吞数据）。
- `StudentExplanationWriteBehindConfiguration`：启用属性绑定（对齐 context-cache 模式）。
- `resources/mapper/StudentExplanationWorkflowEventMapper.xml`：`insertWithExplicitId`（AUTO_INCREMENT 列显式写 id，真实 MySQL 已验证计数器会推进到 max+1）与 `selectMaxEventId`；遵循项目"mapper 层禁注解 SQL"的 `SqlInjectionGuardContractTest` 约定。

### 修改文件
- `student/mapper/StudentExplanationWorkflowEventMapper.java`：新增两个裸方法声明（无注解 SQL）。
- `application.yml`：`math-agent.redis.student-explanation-write-behind` 段（每项带环境变量覆盖与"为什么"注释）。

### 未改动（红线）
agent/worker outbox 全链路、teaching/讲义语义代码、`MyBatisStudentExplanationWorkflowStore`/
`MyBatisStudentExplanationHistoryStore` 本体、控制器。消息侧（`HistoryStore.save`）每轮仅 2~3 行且
同事务一次提交，与每秒几十~上百行的事件写入相比可忽略，为控制 diff 不做 write-behind。

## 5. 数据丢失窗口与降级路径（汇总）

| 故障 | 结果 |
| --- | --- |
| JVM 崩溃/重启 | 不丢：缓冲在 Redis（TTL 6h 内），启动 boot-flush 先排空再放行新事件 |
| Redis 挂（append 时） | 不丢：该事件直写 MySQL（high-water 显式 id，序列恢复后重播种，不乱序）；WARN 限流 |
| Redis 挂（重连读取时） | 只读到已回写行，客户端最多滞后一个 flush 间隔，无报错 |
| MySQL 挂（flush 时） | 不丢：事务回滚、缓冲保留，下一轮重试；缓冲满则新事件直写（同样失败则按原行为向用户报错，等同改造前） |
| Redis 数据丢失（AOF/RDB 双失） | 丢"未 flush 窗口（默认 500ms）"内的中间 delta：仅影响断线重连补发的文本完整性；终态事件、run 行、会话消息均直写 MySQL 不受影响 |
| 多实例部署 | 序列与缓冲按单写入进程假设；扩容必须先按 run 分片（代码注释与属性注释均标注） |

## 6. 配置

```yaml
math-agent.redis.student-explanation-write-behind:
  enabled: ${MATH_AGENT_STUDENT_EXPLANATION_WRITE_BEHIND_ENABLED:true}
  key-prefix: math-agent:student:explanation-events:v1
  flush-interval-ms: ${...:500}      # 即"Redis 丢数据"场景的丢失窗口
  batch-size: ${...:200}             # 每轮每 run 批量行数 = 每轮连接借还次数上限
  max-buffered-events: ${...:5000}   # 单 run 缓冲硬顶，超出直写
  buffer-ttl: ${...:6h}              # 只需覆盖 JVM 重启间隔（重连窗口 5 分钟）
```
`enabled=false` 时装饰器 bean 不注册，回到纯 MySQL 直写，无需回滚代码。

## 7. 测试证据（本机真实运行，2026-09-05）

- 单测 `RedisWriteBehindStudentExplanationWorkflowStoreTest`（无 Mockito，手写动态代理 fake）：
  **Tests run: 15, Failures: 0, Errors: 0**——覆盖缓冲+批量阈值排空、每批一事务计数、终态旁路缓冲直写、
  Redis 故障直写降级与恢复重播种、缓冲满直写、读合并去重、读降级、MySQL 失败保缓冲重试、
  崩溃重放幂等（DuplicateKey→同 run 同 json 跳过）、异 run 撞 id 保留缓冲、boot-flush、停机 flush、
  混合路径 31 事件 id 严格单调。
- 真实基础设施探针 `StudentExplanationWriteBehindRealInfrastructureTest`（compose Redis 6380 / MySQL 3307，
  默认跳过，`-Dmathagent.writebehind=true` 开启）：**Tests run: 3, Failures: 0**——
  真实 Lua append/confirm（含列表 DEL、runs SREM、seq 键删除后重播种单调）、中文+换行事件经真实
  Redis 往返与 TTL 校验、真实 MySQL AUTO_INCREMENT 列显式 id 插入且生成计数器推进、FK 级联清理。
- 全上下文冒烟 `SpringContextStartupSmokeTest`（真实 MySQL+Redis）：**1/1 通过**——证明 @Primary 装饰器、
  XML 语句、启动播种与 boot-flush 在完整 Spring 装配下工作。
- 全量回归 `mvn test`（2026-09-05 21:4x 本机原文汇总）：
  `Tests run: 761, Failures: 0, Errors: 0, Skipped: 5` → `BUILD SUCCESS`
  （5 个跳过为本探针 3 个 + 上下文冒烟 1 个等 `-D` 门控用例；默认套件离线可跑）。
- 交付过程中 `SqlInjectionGuardContractTest` 曾拦截 mapper 注解 SQL（2/2 恢复绿），据此把两条语句
  迁移到 `resources/mapper/StudentExplanationWorkflowEventMapper.xml`，符合项目既有 XML 约定。

## 8. 遗留风险

- 事件表 id 空间假设单写入进程；多实例部署前必须按 run 分片序列与缓冲（代码内已注释）。
- Redis 6h TTL 内既丢缓冲又丢 run 行的极端组合（双故障）会留下无终态事件的 RUNNING run，与改造前的
  进程崩溃表现一致（重连侧等待至超时），未新增恢复器（超本次范围）。
- 探针不写真实 MySQL 事件表（仅验证 SQL 语义与清理），长期共存 AUTO_INCREMENT 与显式 id 的
  生产计数行为已在探针中验证"显式插入推高计数器"。
