import { AlertCircle, Copy, KeyRound, Loader2, Network, ShieldCheck } from "lucide-react";
import {
  McpClientKeyCreatedResponse,
  McpClientKeyResponse,
  McpConfigurationResponse,
  McpConnectionTestResult,
} from "../../shared/api/textbookApi";
import { StatusLine } from "./panelShared";

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

/** 把后端 ISO 时间戳压缩成 "YYYY-MM-DD HH:mm"，解析失败时原样返回，仅供展示。 */
function formatKeyTime(value: string | null | undefined, fallback: string) {
  if (!value) return fallback;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 账号概览：DeepSeek 平台式大数字信息卡，凭证边界折叠在右侧说明里。 */
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
    <section className="mcp-overview" aria-label="当前账号边界">
      <div className="mcp-overview-stats">
        <div className="mcp-stat">
          <span>当前账号</span>
          <strong>{username || "未登录"}</strong>
        </div>
        <div className="mcp-stat">
          <span>角色 / 租户</span>
          <strong>{roleLabel}<em>{tenantId || "-"}</em></strong>
        </div>
        <div className="mcp-stat mcp-stat--wide">
          <span>用户 ID</span>
          <strong className="mono">{userId || "-"}</strong>
        </div>
      </div>
      <details className="mcp-overview-note">
        <summary>
          <ShieldCheck size={14} />
          <span>凭证边界与审计</span>
        </summary>
        <div className="mcp-overview-note-body">
          <p>外部客户端只能拿到后端根据当前登录态生成的 MCP 配置。角色、租户、主体和工具白名单都由后端解析，前端不传身份参数。</p>
          <div className="mcp-overview-policies">
            <span><strong>租户隔离</strong><em>教材、教师资源、题库和任务都按 tenantId 隔离。</em></span>
            <span><strong>身份绑定</strong><em>MCP key 绑定当前账号，不能切换成别人的身份。</em></span>
            <span><strong>最小暴露</strong><em>只暴露当前账号真实可执行的工具和提示词。</em></span>
            <span><strong>审计回溯</strong><em>密钥、调用和导出都能按账号和任务追踪。</em></span>
          </div>
        </div>
      </details>
    </section>
  );
}

/** Key 创建块：说明压成一行，动作按钮右对齐，贴近 DeepSeek 的操作条布局。 */
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
    <section className="mcp-create">
      <div className="mcp-create-copy">
        <div className="mcp-create-title">
          <KeyRound size={15} />
          <strong>创建新的 MCP Key</strong>
        </div>
        <p>
          由后端按当前登录态生成，真实 secret 只在创建时返回一次；
          最新预览 <code>{latestCreatedKey?.secretKeyPreview ?? "尚未创建"}</code>。
        </p>
      </div>
      <div className="mcp-create-actions">
        <button type="button" className="mcp-btn mcp-btn--primary" disabled={creating} onClick={onCreate}>
          {creating ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
          <span>{creating ? "创建中…" : "创建 MCP Key"}</span>
        </button>
        <button type="button" className="mcp-btn" disabled={loadingKeys} onClick={onRefresh}>
          {loadingKeys ? <Loader2 className="spin" size={15} /> : <Network size={15} />}
          <span>刷新</span>
        </button>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
    </section>
  );
}

/** 密钥列表：DeepSeek 式分隔行 + 状态徽标，revoked 占多数时限制高度滚动。 */
export function McpKeyVaultPanel({
  keys,
  latestCreatedKey,
  revokingKeyId,
  deletingKeyId,
  loading,
  copyMessage,
  onCopyLatestSecret,
  onRevokeKey,
  onDeleteKey,
}: {
  keys: McpClientKeyResponse[];
  latestCreatedKey: McpClientKeyCreatedResponse | null;
  revokingKeyId: string;
  deletingKeyId: string;
  loading: boolean;
  copyMessage: string;
  onCopyLatestSecret: () => void;
  onRevokeKey: (keyId: string) => void;
  onDeleteKey: (keyId: string) => void;
}) {
  return (
    <section className="mcp-vault" aria-label="我的密钥">
      <div className="mcp-vault-head">
        <h3>我的密钥 <em>{keys.length}</em></h3>
      </div>
      {latestCreatedKey ? (
        <div className="mcp-secret-callout">
          <div className="mcp-secret-callout-copy">
            <span>刚创建的 secret（仅此一次展示机会，请立即保存）</span>
            <code>{latestCreatedKey.secretKey}</code>
          </div>
          <button className="mcp-btn mcp-btn--small" type="button" onClick={onCopyLatestSecret}>
            <Copy size={13} />
            <span>复制</span>
          </button>
        </div>
      ) : null}
      {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} tone="success" /> : null}
      {keys.length ? (
        <div className="mcp-key-list">
          {keys.map((key) => (
            <div className={`mcp-key-row${key.status === "active" ? " active" : ""}`} key={key.keyId}>
              <div className="mcp-key-row-main">
                <strong>{key.name}</strong>
                <span className={`mcp-key-status ${key.status === "active" ? "ok" : "off"}`}>
                  <i />
                  {key.status === "active" ? "启用中" : "已停用"}
                </span>
                {key.status === "active" ? (
                  <button
                    className="mcp-btn mcp-btn--small mcp-btn--danger-ghost"
                    type="button"
                    disabled={loading || revokingKeyId === key.keyId}
                    onClick={() => onRevokeKey(key.keyId)}
                  >
                    {revokingKeyId === key.keyId ? <Loader2 className="spin" size={13} /> : null}
                    <span>吊销</span>
                  </button>
                ) : (
                  // 已停用的 key 只能物理删除（后端同样只放行 revoked），用于清理验收脚本留下的历史 key。
                  <button
                    className="mcp-btn mcp-btn--small mcp-btn--danger-ghost"
                    type="button"
                    disabled={loading || deletingKeyId === key.keyId}
                    onClick={() => onDeleteKey(key.keyId)}
                  >
                    {deletingKeyId === key.keyId ? <Loader2 className="spin" size={13} /> : null}
                    <span>删除</span>
                  </button>
                )}
              </div>
              <div className="mcp-key-row-meta">
                <code>{key.secretKeyPreview}</code>
                <span>{key.keyProfile}</span>
                <em>创建 {formatKeyTime(key.createdAt, "-")}</em>
                <em>最近使用 {formatKeyTime(key.lastUsedAt, "未使用")}</em>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="mcp-empty">
          当前账号还没有 MCP key。创建后只会返回一次真实 secret，之后列表只显示预览值。
        </div>
      )}
    </section>
  );
}

/** 连接配置：地址 / 环境变量行 + 深色配置 JSON，工具与提示词白名单平铺。 */
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
    <section className="mcp-config" aria-label="MCP 配置">
      <div className="mcp-config-head">
        <h3>MCP 配置</h3>
        {configuration ? <span className="mcp-profile-pill">{keyProfileLabel(configuration.keyProfile)}</span> : null}
      </div>
      {configuration ? (
        <div className="mcp-config-body">
          <div className="mcp-kv">
            <div>
              <span>服务地址</span>
              <code>{configuration.url}</code>
            </div>
            <div>
              <span>密钥预览</span>
              <code>{configuration.secretKeyPreview}</code>
            </div>
            <div>
              <span>环境变量</span>
              <code>{configuration.secretEnvName}</code>
            </div>
          </div>

          <div className="mcp-exposure">
            <McpExposureColumn title="可用工具" items={configuration.exposedTools} />
            <McpExposureColumn title="可用提示词" items={configuration.exposedPrompts} />
          </div>

          {configuration.layers.length ? (
            <div className="mcp-layers">
              {configuration.layers.map((layer) => (
                <div className="mcp-layer-row" key={layer.code}>
                  <strong>{layer.name}</strong>
                  <span>{layer.description}</span>
                  <em>凭据 {layer.requiredCredential}</em>
                </div>
              ))}
            </div>
          ) : null}

          <div className="mcp-code-block">
            <div className="mcp-code-head">
              <span className="mcp-code-dot" />
              <strong>标准配置 JSON</strong>
              <button className="mcp-btn mcp-btn--small mcp-btn--on-dark" type="button" onClick={onCopy}>
                <Copy size={13} />
                <span>复制</span>
              </button>
            </div>
            {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} tone="success" /> : null}
            <pre className="mcp-code">{configuration.configJson}</pre>
          </div>
        </div>
      ) : (
        <div className="mcp-empty">
          当前账号还没有可用的 MCP 配置。先创建 key，再由后端按当前登录态生成配置。
        </div>
      )}
    </section>
  );
}

/** 握手测试：标题行 + 主按钮，结果以kv行和工具徽标呈现。 */
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
    <section className="mcp-test" aria-label="连接测试">
      <div className="mcp-test-head">
        <div>
          <h3>连接测试</h3>
          <p>使用创建时返回的真实 secret 执行标准 MCP 握手（initialize + tools/list）。</p>
        </div>
        <button className="mcp-btn mcp-btn--primary" type="button" onClick={onTest} disabled={testing || !ready}>
          {testing ? <Loader2 className="spin" size={15} /> : <Network size={15} />}
          <span>{testing ? "测试中…" : "测试连接"}</span>
        </button>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {result ? (
        <div className="mcp-test-result">
          <div className="mcp-test-ok">
            <ShieldCheck size={15} />
            <span>握手成功，可见工具 {result.toolCount} 个</span>
          </div>
          <div className="mcp-kv mcp-kv--inline">
            <div>
              <span>服务</span>
              <code>{result.serverName}</code>
            </div>
            <div>
              <span>版本</span>
              <code>{result.serverVersion}</code>
            </div>
            <div>
              <span>协议</span>
              <code>{result.protocolVersion}</code>
            </div>
          </div>
          <div className="mcp-tool-chips">
            {result.tools.map((tool) => (
              <span key={tool}>{optionLabel(tool)}</span>
            ))}
          </div>
        </div>
      ) : !error ? (
        <div className="mcp-empty">
          {ready
            ? "点击「测试连接」验证外部客户端可以真实接通。"
            : "先创建新的 MCP key，这里才能用本次返回的真实 secret 做握手测试。"}
        </div>
      ) : null}
    </section>
  );
}

function McpExposureColumn({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="mcp-exposure-column">
      <strong>{title}<em>{items.length}</em></strong>
      {items.length ? (
        items.map((item) => (
          <span key={item}>
            {optionLabel(item)}
            {TOOL_BADGES[item] ? <em>{TOOL_BADGES[item]}</em> : null}
          </span>
        ))
      ) : (
        <span className="none">暂无</span>
      )}
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
