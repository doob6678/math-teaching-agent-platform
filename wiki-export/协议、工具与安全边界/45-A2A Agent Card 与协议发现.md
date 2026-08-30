> A2A Agent Card 控制器和响应模型提供 Agent 能力卡片，MCP 发现服务提供协议侧能力目录。

# A2A Agent Card 与协议发现

本页聚焦 `protocol` 模块中的协议发现能力：A2A 通过 Agent Card 对外描述平台级 Agent 能力，MCP 通过发现服务提供工具、Prompt 和资源等协议侧能力目录。两者都只负责公开元数据，不承担实际 Agent 执行或工具调用。

## 模块职责

### A2A Agent Card

`A2aAgentCardController` 提供标准发现路径：

```text
GET /api/a2a/.well-known/agent-card.json
```

控制器本身不拼装能力数据，而是将请求委托给 `ProtocolDiscoveryService.a2aAgentCard()`，并直接返回 `A2aAgentCardResponse`。

`A2aAgentCardResponse` 使用 Java record 表达公开卡片，包含：

- `name`：公开的 Agent 平台名称
- `description`：平台能力摘要
- `url`：相对 A2A 基础路径，避免泄露本地主机路径
- `protocolVersion`：对外声明的 A2A 兼容版本标签
- `preferredTransport`：未来 A2A 调用偏好的传输方式
- `capabilities`：协议能力开关
- `skills`：可发现的 Agent 技能元数据
- `securitySchemes`：认证和高价值操作所需的安全方案描述

其中 `Capabilities` 当前以布尔字段表达：

- `streaming`
- `pushNotifications`
- `stateTransitionHistory`

`Skill` 只描述稳定 ID、名称、摘要和标签；`SecurityScheme` 只描述方案 ID、类型和面向外部客户端的说明。响应模型没有执行凭证、模型密钥或本地路径等运行时秘密。

### MCP 协议发现

`McpDiscoveryController` 提供工具目录接口：

```text
GET /api/mcp/tools
```

该接口调用 `ProtocolDiscoveryService.mcpTools()`，返回 `List<McpToolDescriptor>`。控制器注释明确表明它只暴露元数据，不执行工具。

`ProtocolDiscoveryService` 同时提供 MCP 侧的其他发现模型：

- `mcpTools()`：工具描述
- `mcpPrompts()`：标准 Prompt 描述
- `mcpResources()`：应用拥有且可安全公开的资源描述
- `McpConfigurationResponse` 相关配置渲染能力

工具执行由其他协议入口负责，发现接口与执行接口保持分离。

## 调用链

```mermaid
flowchart LR
    A2AClient[A2A 外部客户端]
    MCPClient[MCP 外部客户端]

    A2AController[A2aAgentCardController]
    MCPDiscovery[McpDiscoveryController]
    Discovery[ProtocolDiscoveryService]

    A2AResponse[A2aAgentCardResponse]
    ToolDescriptors[List McpToolDescriptor]
    Prompts[McpPromptDescriptor]
    Resources[McpResourceDescriptor]

    A2AClient -->|GET /.well-known/agent-card.json| A2AController
    A2AController -->|a2aAgentCard()| Discovery
    Discovery --> A2AResponse

    MCPClient -->|GET /api/mcp/tools| MCPDiscovery
    MCPDiscovery -->|mcpTools()| Discovery
    Discovery --> ToolDescriptors

    Discovery -.-> Prompts
    Discovery -.-> Resources
```

关键节点：

- `A2aAgentCardController` 是 A2A 卡片的 HTTP 适配层。
- `McpDiscoveryController` 是 MCP 工具目录的 HTTP 适配层。
- `ProtocolDiscoveryService` 是两种协议共用的只读元数据中心。
- A2A 返回平台级能力卡片；MCP 返回可细化到具体工具、Prompt 和资源的协议目录。
- 发现链路不会进入 `McpJsonRpcService` 或 `McpToolExecutionService`，因此不会因为查询目录而触发实际执行。

## MCP 工具描述模型

`McpToolDescriptor` 将一个 MCP 工具的发现信息完整结构化，字段包括：

| 字段 | 作用 |
| --- | --- |
| `name` | 稳定的工具名称 |
| `title` | 面向人的工具标题 |
| `description` | 不包含秘密或本地路径的能力说明 |
| `readOnly` | 标识底层操作是否只读 |
| `executionEndpointEnabled` | 是否暴露直接执行入口 |
| `requiredRoles` | 后端允许使用该能力的角色 |
| `requiredScope` | 外部客户端执行前需要申请的逻辑权限范围 |
| `costLevel` | `low`、`medium` 或 `high` 成本等级 |
| `auditRequired` | 执行是否必须写入审计日志 |
| `inputSchema` | 面向未来 MCP 参数调用的 JSON Schema |

工具发现因此不只是名称列表，还提前表达了执行边界。例如源码中的工具描述体现出以下能力类别：

- 多来源证据搜索
- 教材证据搜索
- 教师资源搜索和资源列表
- 多智能体写作流程查询、导出和恢复
- 飞书资源发现与下载

这些工具的参数约束也通过 `inputSchema` 公开。例如搜索工具可以声明 `query`、`limit`、`library` 或 `libraries` 等字段；部分工具要求调用方显式选择逻辑资源库，避免后端隐式扩展搜索语料范围。

## 权限与执行边界

发现数据同时携带角色和 scope 信息，使外部客户端可以在执行前判断能力是否适用：

- 面向学生、教师和管理员的能力使用 `student`、`teacher`、`admin` 角色集合。
- 教师资源和写作相关能力使用 `teacher`、`admin`。
- scope 进一步区分读取、执行和导出，例如：
  - `PUBLIC_TEXTBOOK`
  - `teacher-resource:read`
  - `teacher-resource:sync-execute`
  - `agent-writing:read`
  - `agent-writing:execute`
  - `agent-writing:export`

这些字段是发现元数据，不等于授权已经完成。真正的访问控制仍位于 MCP 客户端解析、客户端密钥和访问策略相关服务，以及具体工具执行链路中。发现接口不能替代执行时的认证、角色检查、scope 校验和审计。

工具描述中的 `readOnly` 也不能单独作为安全判断依据。源码同时提供 `executionEndpointEnabled`、`requiredRoles`、`requiredScope` 和 `auditRequired`，外部客户端应综合这些字段理解能力的风险和使用条件。

## 关键状态

协议发现本身是无状态读取：

1. 请求进入发现控制器。
2. 控制器调用 `ProtocolDiscoveryService`。
3. 服务根据固定的能力定义构造响应对象。
4. Spring 将响应模型序列化为 JSON。
5. 请求结束后不保留发现会话或执行状态。

`ProtocolDiscoveryService` 使用无参数构造器，并明确避免强制初始化 `McpClientResolver`。源码说明，发现元数据是静态的；如果发现服务在启动阶段依赖客户端解析、会话主体解析或 MCP 密钥服务，可能重新引入循环启动依赖，甚至导致应用上下文初始化问题。

因此当前设计的关键状态边界是：

- 能力目录可在密钥管理 Bean 初始化期间独立启动。
- Agent Card 和 MCP 目录不依赖当前调用者会话。
- 目录响应不携带客户端密钥、访问令牌或运行时执行结果。
- 工具是否真正可执行，需要在后续执行请求中重新解析身份和权限。

## 与 MCP JSON-RPC 入口的边界

MCP 标准调用入口是：

```text
POST /api/mcp
```

`McpJsonRpcController` 将该地址作为外部 MCP 客户端配置的单一入口，并处理：

- `Authorization`
- `Accept`
- `MCP-Protocol-Version`
- JSON-RPC 请求体解析
- HTTP 信封校验
- 无效 JSON 和无效 JSON-RPC 对象的错误响应

它随后将消息交给 `McpJsonRpcService`。这条链路负责协议消息处理和能力调用，与只返回工具描述的 `McpDiscoveryController` 是不同职责：

```text
目录查询：McpDiscoveryController
          -> ProtocolDiscoveryService
          -> McpToolDescriptor 列表

实际协议调用：McpJsonRpcController
              -> McpJsonRpcService
              -> MCP 能力分发或工具执行链路
```

这种分离使客户端可以先读取能力目录，再根据工具名称、输入 schema、角色和 scope 发起实际调用；目录查询本身不会产生工具副作用。

## 主要文件

- [A2aAgentCardController.java](backend-java/src/main/java/com/doob/mathagent/protocol/controller/A2aAgentCardController.java)：A2A Agent Card HTTP 入口。
- [A2aAgentCardResponse.java](backend-java/src/main/java/com/doob/mathagent/protocol/vo/A2aAgentCardResponse.java)：A2A 公开卡片响应模型及嵌套能力、技能、安全方案模型。
- [McpDiscoveryController.java](backend-java/src/main/java/com/doob/mathagent/protocol/controller/McpDiscoveryController.java)：MCP 工具发现 HTTP 入口。
- [ProtocolDiscoveryService.java](backend-java/src/main/java/com/doob/mathagent/protocol/service/ProtocolDiscoveryService.java)：A2A 与 MCP 的只读发现元数据服务。
- [McpToolDescriptor.java](backend-java/src/main/java/com/doob/mathagent/protocol/vo/McpToolDescriptor.java)：MCP 工具描述模型。
- [McpJsonRpcController.java](backend-java/src/main/java/com/doob/mathagent/protocol/controller/McpJsonRpcController.java)：MCP Streamable HTTP JSON-RPC 入口，与发现接口形成执行边界。

## 边界条件

- Agent Card 使用相对 URL，避免把本地主机路径暴露给外部客户端。
- 公开描述只能包含能力元数据，不能包含执行秘密、密钥或本地文件路径。
- MCP 发现接口只返回目录，不负责工具执行。
- 工具参数中的资源库选择可能是必填项，调用方不能假定后端会自动扩大搜索范围。
- `requiredRoles` 和 `requiredScope` 只描述访问前提，执行时仍必须进行实际授权。
- 高成本或有副作用的工具会通过 `costLevel`、`auditRequired` 和 scope 表达额外约束。
- 发现服务不依赖 MCP 客户端解析器，避免启动期间的循环依赖。
- MCP JSON-RPC 请求还受协议版本、`Accept`、JSON 格式和 JSON-RPC 对象结构约束；这些校验不属于 Agent Card 或工具目录接口。

## 扩展点

新增协议能力时，优先在 `ProtocolDiscoveryService` 中增加对应的结构化描述，并同步提供稳定名称、说明、角色、scope、成本等级、审计要求和输入 schema。

可按以下方向扩展：

- 在 `A2aAgentCardResponse.Skill` 中增加新的平台级技能元数据。
- 扩展 `Capabilities`，声明新的 A2A 协议能力开关。
- 在 `mcpTools()` 中增加新的 `McpToolDescriptor`。
- 在 `mcpPrompts()` 或 `mcpResources()` 中增加 Prompt 或应用资源目录项。
- 为新增工具明确区分只读操作、执行入口和高成本操作。
- 保持发现服务与密钥解析、会话解析和工具执行服务解耦。
- 若新增字段影响外部客户端，应保持稳定字段语义，并同步调整执行侧的授权和审计实现，避免目录声明与实际行为不一致。

Sources: [A2aAgentCardController.java](backend-java/src/main/java/com/doob/mathagent/protocol/controller/A2aAgentCardController.java#L1-L33), [A2aAgentCardResponse.java](backend-java/src/main/java/com/doob/mathagent/protocol/vo/A2aAgentCardResponse.java#L1-L68), [McpDiscoveryController.java](backend-java/src/main/java/com/doob/mathagent/protocol/controller/McpDiscoveryController.java#L1-L35), [ProtocolDiscoveryService.java](backend-java/src/main/java/com/doob/mathagent/protocol/service/ProtocolDiscoveryService.java#L1-L260), [ProtocolDiscoveryService.java](backend-java/src/main/java/com/doob/mathagent/protocol/service/ProtocolDiscoveryService.java#L250-L614), [McpToolDescriptor.java](backend-java/src/main/java/com/doob/mathagent/protocol/vo/McpToolDescriptor.java#L1-L31), [McpJsonRpcController.java](backend-java/src/main/java/com/doob/mathagent/protocol/controller/McpJsonRpcController.java#L1-L100)
