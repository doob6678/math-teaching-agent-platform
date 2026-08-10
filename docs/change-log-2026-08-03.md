# 2026-08-03 修复记录

## 2026-08-03 05:05 UTC（Asia/Shanghai 13:05）

- 阅读根目录 `README.md`、`TODO.md`，并以 TODO 的源码位置复核实现。
- 修复学生讲题把“抛物线”从“二次函数”召回中误过滤的问题。
- tokenizer 不可用时启用 4096 token 的保守上下文上限并记录告警，避免长历史绕过保护。
- ReAct 决策调用按已启用 provider 顺序进行有界回退；所有 provider 失败时返回受限的后端恢复决策。
- 入库解析完成改为 `PARSED_AWAITING_REVIEW`，不再把待视觉审核批次标记为失败。
- 学生长期记忆读写失败与飞书资源绑定的瞬态数据库失败均增加可观测日志；飞书绑定最多重试一次。
- 授权图片证据现在携带同一资料块的已解析相邻文本，且不从路径/文件名伪造视觉描述。
- 知识图谱空主干会记录告警，教材和教师资料检索统一采用可观测降级策略。

## 2026-08-03 14:03 CST（Asia/Shanghai）

- 记录 14:03 的中间配置：当时曾将运行时默认模型切到 Terra（`gpt-5.6-terra`），Spring 配置、Java provider 默认、Python worker、Docker Compose、Windows 启动脚本、题库入库默认值和评测默认值均同步；该中间状态已由 14:38 的修正恢复为 Luna。
- 保留 Luna/Terra 的显式模型选项与历史验收记录，不改写历史证据；Luna 专用验收脚本仍要求显式 Luna。
- 修复 `MultiAgentWritingArtifactExportServiceTest` 受控 gateway 对新增六个阶段缺少响应的问题。
- 修正 `SystemRuntimeStatusServiceTest` 的默认模型断言，补充知识图谱“语义命中”和“向量异常词面降级”回归测试。

## 待继续跟踪

- B2：图片资产可直接作为多模态上下文传递，但独立的可见信息描述仍依赖已解析资料的替代文本或后续受控视觉适配器；不会从文件名或路径伪造描述。
- B4：知识图谱语义匹配与向量异常降级已接入；仍需在真实 Milvus/embedding 部署上做一次端到端分数校准。

## 2026-08-03 14:28 CST（Asia/Shanghai）

- 后端 Java 21 真实编译成功；定向相关测试最终为 38 个通过、1 个跳过（真实 Noto CJK/XeLaTeX 条件未启用）。
- 前端真实 `vitest` 为 21 个测试文件、102 个测试通过；`tsc && vite build` 成功，仅保留 bundle 大小提示。
- 全量后端真实 `mvn test` 结果为 756 tests、52 failures、15 errors、8 skipped。失败集中在已有 Windows 中文编码、`fake-xelatex` 非 Win32 可执行文件、XeLaTeX/Noto CJK 环境、教师检索旧排序断言和测试数据/异步时序耦合；当时新增 Multi-Agent、语义图谱、模型默认相关测试均已通过，未将全量结果伪报为通过。
- 修正知识图谱控制器测试的过期租户与节点数量断言，使其与当前默认租户和 141 节点种子图一致。
- B7：教材检索失败会中断讲解，教师资料检索则允许降级；这是当前的业务策略，需产品确认后再统一。

## 2026-08-03 14:38 CST（Asia/Shanghai）

- 按产品边界撤回未获授权的多 Agent 写作拓扑扩展；执行链恢复为 `resource_curation` 加教师、学生、16:10 三个并行写作阶段，共四个实际模型调用阶段，保持节省 token 和低延迟目标。
- 六个扩展阶段提示词契约继续作为兼容/规划定义保留，但未标记为已实现，也未接入当前执行或恢复链；旧 artifact 的解析分支只用于兼容已有记录，不会新增模型调用；TODO 与 README 已同步说明该边界。
- 将当前默认模型从 Terra 改回 Luna（`gpt-5.6-luna`），覆盖 Spring/Java、Python worker、Compose、Windows 启动脚本、入库默认值、评测默认值、示例环境变量和本地 `.env` 的实际覆盖值；环境变量和显式模型参数仍优先，历史验收记录不改写，未触碰密钥/地址/端口。

## 2026-08-03 14:55 CST（Asia/Shanghai）

- 四阶段写作相关定向测试真实通过：37 tests、0 failures、0 errors、1 skipped；跳过项仅因本机未启用真实 Noto CJK/XeLaTeX 条件。
- 使用现有 Windows JDK 21 完成 `mvn -DskipTests test-compile`；未安装依赖。根目录 `.env` 的实际 `OPENAI_CHAT_MODEL` 已核对为 `gpt-5.6-luna`，没有改动密钥、地址或端口。
