import { AlertCircle, Copy, Loader2, Network, ShieldCheck } from "lucide-react";
import {
  McpClientKeyCreatedResponse,
  McpClientKeyResponse,
  McpConfigurationResponse,
  McpConnectionTestResult,
} from "../../shared/api/textbookApi";
import { PanelTitle, StatusLine } from "./panelShared";

const TOOL_LABELS: Record<string, string> = {
  search_multi_source_evidence: "多源证据检索",
  search_textbook_evidence: "教材证据检索",
  search_teacher_resource_evidence: "教师资源检索",
  get_teaching_ai_trace: "教学任务追踪",
  get_ai_diagnostic_summary: "AI 诊断汇总",
  get_multi_agent_writing_trace: "讲义协作追踪",
  plan_agent_run: "智能体预案",
  start_multi_agent_writing: "启动讲义协作",
  get_multi_agent_writing_status: "讲义流程状态",
  get_multi_agent_writing_artifact: "读取讲义成果",
  export_multi_agent_writing_artifact: "导出讲义成果",
  resume_multi_agent_writing: "恢复讲义流程",
  discover_feishu_resources: "查找飞书资源",
  download_feishu_resource: "下载飞书资源",
  teacher_handout_writer: "教师讲义生成",
  student_blank_handout_writer: "学生填空讲义",
  solution_reviewer: "解答审校",
};

const TOOL_BADGES: Record<string, string> = {
  start_multi_agent_writing: "高价值",
  export_multi_agent_writing_artifact: "导出",
  resume_multi_agent_writing: "恢复",
  download_feishu_resource: "执行",
};

export function McpIdentityBoundaryCard({
  username,
  userId,
  roleLabel,
  tenantId,
}: {
  username?: string | null;
  userId?: string | null;
  roleLabel: string;
  tenantId?: string | null;
}) {
  return (
    <section className="mcp-boundary-panel">
      <div className="mcp-identity-strip">
        <div>
          <span>账号</span>
          <strong>{username || "未登录"}</strong>
        </div>
        <div>
          <span>用户 ID</span>
          <strong>{userId || "-"}</strong>
        </div>
        <div>
          <span>角色</span>
          <strong>{roleLabel}</strong>
        </div>
        <div>
          <span>租户</span>
          <strong>{tenantId || "-"}</strong>
        </div>
      </div>
      <details className="mcp-policy-note ai-run-disclosure">
        <summary>权限说明</summary>
        <p>外部客户端只能拿到后端根据当前登录态生成的 MCP 配置。角色、租户、主体和工具白名单都由后端解析，前端不传身份参数。</p>
      </details>
      <details className="mcp-policy-note mcp-sandbox-note ai-run-disclosure">
        <summary>隔离与审计</summary>
        <div className="mcp-sandbox-grid" aria-label="MCP 隔离策略">
          <span>
            <strong>租户隔离</strong>
            <em>教材、教师资源、题库和任务都按 tenantId 隔离。</em>
          </span>
          <span>
            <strong>身份绑定</strong>
            <em>MCP key 绑定当前账号，不能切换成别人的身份。</em>
          </span>
          <span>
            <strong>最小暴露</strong>
            <em>只暴露当前账号真实可执行的工具和提示词。</em>
          </span>
          <span>
            <strong>审计回溯</strong>
            <em>密钥、调用和导出都能按账号和任务追踪。</em>
          </span>
        </div>
      </details>
    </section>
  );
}

export function McpConfigurationForm({
  creating,
  loadingKeys,
  error,
  latestCreatedKey,
  onCreate,
  onRefresh,
}: {
  creating: boolean;
  loadingKeys: boolean;
  error: string;
  latestCreatedKey: McpClientKeyCreatedResponse | null;
  onCreate: () => void;
  onRefresh: () => void;
}) {
  return (
    <section className="mcp-config-form">
      <PanelTitle icon={<ShieldCheck size={18} />} title="后端生成 MCP Key" />
      <div className="search-form">
        <p>这里不再输入 secret。点击后由后端按当前 Sa-Token 登录态创建个人 MCP key，并只返回一次真实 secret。</p>
        <div className="profile-strip">
          <div>
            <span>生成方式</span>
            <strong>后端生成 / 个人可见</strong>
          </div>
          <div>
            <span>最新预览</span>
            <strong>{latestCreatedKey?.secretKeyPreview ?? "尚未创建"}</strong>
          </div>
        </div>
        <div className="button-row">
          <button type="button" className="btn btn-primary" disabled={creating} onClick={onCreate}>
            {creating ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
            <span>{creating ? "创建中" : "创建我的 MCP Key"}</span>
          </button>
          <button type="button" className="btn btn-secondary" disabled={loadingKeys} onClick={onRefresh}>
            {loadingKeys ? <Loader2 className="spin" size={17} /> : <Network size={17} />}
            <span>{loadingKeys ? "刷新中" : "刷新列表和配置"}</span>
          </button>
        </div>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
    </section>
  );
}

export function McpKeyVaultPanel({
  keys,
  latestCreatedKey,
  revokingKeyId,
  loading,
  copyMessage,
  onCopyLatestSecret,
  onRevokeKey,
}: {
  keys: McpClientKeyResponse[];
  latestCreatedKey: McpClientKeyCreatedResponse | null;
  revokingKeyId: string;
  loading: boolean;
  copyMessage: string;
  onCopyLatestSecret: () => void;
  onRevokeKey: (keyId: string) => void;
}) {
  return (
    <section className="mcp-config-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">我的密钥</p>
          <h2>MCP Key 列表</h2>
        </div>
      </div>
      {latestCreatedKey ? (
        <div className="profile-strip">
          <div>
            <span>一次性真实 Secret</span>
            <strong>{latestCreatedKey.secretKey}</strong>
          </div>
          <div>
            <span>当前预览</span>
            <strong>{latestCreatedKey.secretKeyPreview}</strong>
          </div>
          <div>
            <span>操作</span>
            <button className="btn btn-secondary btn-sm" type="button" onClick={onCopyLatestSecret}>
              <Copy size={14} />
              <span>复制 Secret</span>
            </button>
          </div>
        </div>
      ) : null}
      {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} /> : null}
      {keys.length ? (
        <div className="mcp-layer-list">
          {keys.map((key) => (
            <div className="mcp-layer" key={key.keyId}>
              <strong>{key.name}</strong>
              <span>{key.secretKeyPreview} · {key.keyProfile} · {key.status}</span>
              <span>创建于 {key.createdAt ?? "-"}</span>
              <span>最近使用 {key.lastUsedAt ?? "未使用"}</span>
              <button
                className="btn btn-secondary btn-sm"
                type="button"
                disabled={loading || revokingKeyId === key.keyId || key.status !== "active"}
                onClick={() => onRevokeKey(key.keyId)}
              >
                {revokingKeyId === key.keyId ? <Loader2 className="spin" size={14} /> : null}
                <span>{key.status === "active" ? "吊销" : "已停用"}</span>
              </button>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-state compact">
          当前账号还没有 MCP key。创建后只会返回一次真实 secret，之后列表只显示预览值。
        </div>
      )}
    </section>
  );
}

export function McpConfigurationPanel({
  configuration,
  copyMessage,
  onCopy,
}: {
  configuration: McpConfigurationResponse | null;
  copyMessage: string;
  onCopy: () => void;
}) {
  return (
    <section className="mcp-config-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">外部客户端</p>
          <h2>MCP 配置</h2>
        </div>
        {configuration ? <div className="strategy-pill">{keyProfileLabel(configuration.keyProfile)}</div> : null}
      </div>
      {configuration ? (
        <div className="mcp-config-grid">
          <div className="profile-strip">
            <div>
              <span>地址</span>
              <strong>{configuration.url}</strong>
            </div>
            <div>
              <span>密钥预览</span>
              <strong>{configuration.secretKeyPreview}</strong>
            </div>
            <div>
              <span>环境变量</span>
              <strong>{configuration.secretEnvName}</strong>
            </div>
          </div>
          <div className="mcp-exposure-list">
            <McpExposureColumn title="工具" items={configuration.exposedTools} />
            <McpExposureColumn title="提示词" items={configuration.exposedPrompts} />
          </div>
          <div className="mcp-layer-list">
            {configuration.layers.map((layer) => (
              <div className="mcp-layer" key={layer.code}>
                <strong>{layer.name}</strong>
                <span>{layer.description}</span>
                <span>凭据: {layer.requiredCredential}</span>
              </div>
            ))}
          </div>
          <div className="mcp-json-head">
            <strong>配置 JSON</strong>
            <button className="btn btn-secondary btn-sm" type="button" onClick={onCopy}>
              <Copy size={14} />
              <span>复制</span>
            </button>
          </div>
          {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} /> : null}
          <details className="review-details">
            <summary>查看可复制 JSON</summary>
            <pre className="formula-block mcp-json">{configuration.configJson}</pre>
          </details>
        </div>
      ) : (
        <div className="empty-state compact">
          当前账号还没有可用的 MCP 配置。先创建 key，再由后端按当前登录态生成配置。
        </div>
      )}
    </section>
  );
}

export function McpConnectionTestPanel({
  testing,
  result,
  error,
  onTest,
  ready,
}: {
  testing: boolean;
  result: McpConnectionTestResult | null;
  error: string;
  onTest: () => void;
  ready: boolean;
}) {
  return (
    <section className="mcp-test-panel">
      <div className="mcp-test-head">
        <div>
          <p className="eyebrow">连接测试</p>
          <h2>标准 MCP 握手</h2>
        </div>
        <button className="btn btn-secondary btn-sm" type="button" onClick={onTest} disabled={testing || !ready}>
          {testing ? <Loader2 className="spin" size={16} /> : <Network size={16} />}
          <span>{testing ? "测试中" : "测试连接"}</span>
        </button>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {result ? (
        <div className="mcp-test-result">
          <StatusLine icon={<ShieldCheck size={16} />} text={`连接成功，可见工具 ${result.toolCount} 个`} />
          <div className="mcp-test-meta">
            <div>
              <span>服务</span>
              <strong>{result.serverName}</strong>
            </div>
            <div>
              <span>版本</span>
              <strong>{result.serverVersion}</strong>
            </div>
            <div>
              <span>协议</span>
              <strong>{result.protocolVersion}</strong>
            </div>
          </div>
          <div className="mcp-tool-chip-list">
            {result.tools.map((tool) => (
              <span key={tool}>{optionLabel(tool)}</span>
            ))}
          </div>
        </div>
      ) : !error ? (
        <div className="empty-state compact">
          {ready
            ? "这里会使用本次创建时返回的真实 secret 执行 initialize 和 tools/list，确认外部客户端可以真实接通。"
            : "先创建新的 MCP key，这里才能用本次返回的真实 secret 做握手测试。"}
        </div>
      ) : null}
    </section>
  );
}

function McpExposureColumn({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="mcp-exposure-column">
      <strong>{title}</strong>
      {items.map((item) => (
        <span key={item}>
          {optionLabel(item)}
          <em>{TOOL_BADGES[item] ?? "已开放"}</em>
        </span>
      ))}
    </div>
  );
}

function optionLabel(option: string) {
  return TOOL_LABELS[option] ?? option;
}

function keyProfileLabel(profile: string) {
  const labels: Record<string, string> = {
    teacher: "教师配置",
    admin: "管理员配置",
    student: "学生配置",
    readonly: "只读配置",
    default: "默认配置",
  };
  return labels[profile.trim().toLowerCase()] ?? "已生成";
}
