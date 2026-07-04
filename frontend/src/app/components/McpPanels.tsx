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
      <PanelTitle icon={<ShieldCheck size={18} />} title="MCP config" />
      <form className="search-form" onSubmit={onSubmit}>
        <label>
          <span>URL</span>
          <input value={url} onChange={(event) => onUrlChange(event.target.value)} />
        </label>
        <label>
          <span>secretKey</span>
          <input
            type="password"
            value={secretKey}
            onChange={(event) => onSecretKeyChange(event.target.value)}
            placeholder="mcp_secret_..."
          />
        </label>
        <label>
          <span>Env name</span>
          <input value={secretEnvName} onChange={(event) => onSecretEnvNameChange(event.target.value)} />
        </label>
        <McpOptionGroup title="Tools" options={MCP_TOOL_OPTIONS} selected={selectedTools} onToggle={onToolToggle} />
        <McpProtectedToolGroup />
        <McpOptionGroup
          title="Prompts"
          options={MCP_PROMPT_OPTIONS}
          selected={selectedPrompts}
          onToggle={onPromptToggle}
        />
        <button type="submit" disabled={building}>
          {building ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
          <span>Generate JSON</span>
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
          <span>
            {MCP_TOOL_OPTION_META[option]?.label ?? option}
            {MCP_TOOL_OPTION_META[option] ? (
              <em>
                {option} / {MCP_TOOL_OPTION_META[option].badge}
              </em>
            ) : null}
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
      <summary>Protected tools</summary>
      <div className="mcp-protected-tool-list">
        {MCP_PROTECTED_TOOL_OPTIONS.map((option) => {
          const meta = MCP_TOOL_OPTION_META[option];
          return (
            <div className="mcp-protected-tool" key={option}>
              <strong>{meta?.label ?? option}</strong>
              <span>{option}</span>
              <em>{meta?.note ?? "Protected by backend policy."}</em>
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
          <h2>External client config</h2>
        </div>
        {configuration ? <div className="strategy-pill">{configuration.keyProfile}</div> : null}
      </div>
      {configuration ? (
        <div className="mcp-config-grid">
          <div className="profile-strip">
            <div>
              <span>URL</span>
              <strong>{configuration.url}</strong>
            </div>
            <div>
              <span>Secret</span>
              <strong>{configuration.secretKeyPreview}</strong>
            </div>
            <div>
              <span>Env</span>
              <strong>{configuration.secretEnvName}</strong>
            </div>
          </div>
          <div className="mcp-exposure-list">
            <McpExposureColumn title="Tools" items={configuration.exposedTools} />
            <McpExposureColumn title="Prompts" items={configuration.exposedPrompts} />
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
            <strong>config.json</strong>
            <button type="button" onClick={onCopy}>
              Copy
            </button>
          </div>
          {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} /> : null}
          <pre className="formula-block mcp-json">{configuration.configJson}</pre>
        </div>
      ) : (
        <div className="empty-state compact">
          Generate a config to view backend-filtered tools, prompts, and copyable JSON.
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
        <span key={item}>{item}</span>
      ))}
    </div>
  );
}
