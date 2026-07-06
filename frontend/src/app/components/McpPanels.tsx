import { FormEvent } from "react";
import { AlertCircle, Loader2, ShieldCheck } from "lucide-react";
import { McpConfigurationResponse } from "../../shared/api/textbookApi";
import {
  MCP_PROMPT_OPTIONS,
  MCP_PROTECTED_TOOL_OPTIONS,
  MCP_TOOL_OPTION_META,
  MCP_TOOL_OPTIONS,
} from "../mcpExposureSelection";
import { PanelTitle, StatusLine } from "./panelShared";

export function McpConfigurationForm({
  url,
  secretKey,
  secretEnvName,
  selectedTools,
  selectedPrompts,
  building,
  error,
  onUrlChange,
  onSecretKeyChange,
  onSecretEnvNameChange,
  onToolToggle,
  onPromptToggle,
  onSubmit,
}: {
  url: string;
  secretKey: string;
  secretEnvName: string;
  selectedTools: string[];
  selectedPrompts: string[];
  building: boolean;
  error: string;
  onUrlChange: (value: string) => void;
  onSecretKeyChange: (value: string) => void;
  onSecretEnvNameChange: (value: string) => void;
  onToolToggle: (option: string, checked: boolean) => void;
  onPromptToggle: (option: string, checked: boolean) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <section className="mcp-config-form">
      <PanelTitle icon={<ShieldCheck size={18} />} title="MCP 一键配置" />
      <form className="search-form" onSubmit={onSubmit}>
        <label>
          <span>MCP 服务地址</span>
          <input value={url} onChange={(event) => onUrlChange(event.target.value)} />
        </label>
        <label>
          <span>访问密钥</span>
          <input
            type="password"
            value={secretKey}
            onChange={(event) => onSecretKeyChange(event.target.value)}
            placeholder="mcp_secret_..."
          />
        </label>
        <label>
          <span>环境变量名</span>
          <input value={secretEnvName} onChange={(event) => onSecretEnvNameChange(event.target.value)} />
        </label>
        <McpOptionGroup title="开放工具" options={MCP_TOOL_OPTIONS} selected={selectedTools} onToggle={onToolToggle} />
        <McpProtectedToolGroup />
        <McpOptionGroup
          title="开放提示词"
          options={MCP_PROMPT_OPTIONS}
          selected={selectedPrompts}
          onToggle={onPromptToggle}
        />
        <button type="submit" className="btn btn-primary" disabled={building}>
          {building ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
          <span>生成可复制配置</span>
        </button>
      </form>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
    </section>
  );
}

function McpOptionGroup({
  title,
  options,
  selected,
  onToggle,
}: {
  title: string;
  options: readonly string[];
  selected: string[];
  onToggle: (option: string, checked: boolean) => void;
}) {
  return (
    <fieldset className="mcp-option-group">
      <legend>{title}</legend>
      {options.map((option) => (
        <label className="toggle-row" key={option}>
          <input
            type="checkbox"
            checked={selected.includes(option)}
            onChange={(event) => onToggle(option, event.target.checked)}
          />
          <span className="mcp-option-text">
            <strong>{optionLabel(option)}</strong>
            {MCP_TOOL_OPTION_META[option] ? (
              <em>{MCP_TOOL_OPTION_META[option].badge}</em>
            ) : null}
            {MCP_TOOL_OPTION_META[option]?.note ? <small>{MCP_TOOL_OPTION_META[option].note}</small> : null}
          </span>
        </label>
      ))}
    </fieldset>
  );
}

function McpProtectedToolGroup() {
  if (!MCP_PROTECTED_TOOL_OPTIONS.length) {
    return null;
  }
  return (
    <details className="mcp-option-group mcp-protected-tools">
      <summary>受保护工具</summary>
      <div className="mcp-protected-tool-list">
        {MCP_PROTECTED_TOOL_OPTIONS.map((option) => {
          const meta = MCP_TOOL_OPTION_META[option];
          return (
            <div className="mcp-protected-tool" key={option}>
              <strong>{meta?.label ?? option}</strong>
              <span>{meta?.badge ?? "需授权"}</span>
              <em>{meta?.note ?? "由后端权限策略保护。"}</em>
            </div>
          );
        })}
      </div>
    </details>
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
          <p className="eyebrow">MCP</p>
          <h2>外部客户端配置</h2>
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
              <span>密钥</span>
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
              </div>
            ))}
          </div>
          <div className="mcp-json-head">
            <strong>配置 JSON</strong>
            <button className="btn btn-secondary btn-sm" type="button" onClick={onCopy}>复制</button>
          </div>
          {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} /> : null}
          <details className="review-details">
            <summary>查看可复制 JSON</summary>
            <pre className="formula-block mcp-json">{configuration.configJson}</pre>
          </details>
        </div>
      ) : (
        <div className="empty-state compact">
          生成配置后，这里会展示后端过滤后的工具、提示词和可复制 JSON。
        </div>
      )}
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
          <em>{optionMeta(item)}</em>
        </span>
      ))}
    </div>
  );
}

function optionLabel(option: string) {
  const promptLabels: Record<string, string> = {
    teacher_handout_writer: "教师讲义生成",
    student_blank_handout_writer: "学生填空讲义",
    solution_reviewer: "解答审校",
  };
  return MCP_TOOL_OPTION_META[option]?.label ?? promptLabels[option] ?? option;
}

function optionMeta(option: string) {
  return MCP_TOOL_OPTION_META[option]?.badge ?? "已开放";
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
