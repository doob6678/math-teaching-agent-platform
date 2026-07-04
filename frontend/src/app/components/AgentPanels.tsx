import { AlertCircle, Loader2, Search, ShieldCheck } from "lucide-react";
import {
  AgentModelHealthResponse,
  AgentRunExecuteResponse,
  AgentRunPlanResponse,
  AgentTraceDiagnosticSummaryResponse,
  AgentTraceResponse,
  AgentTraceUsageSummaryResponse,
} from "../../shared/api/textbookApi";
import { formatDateTime, StatusBadge, StatusLine } from "./panelShared";

export function AgentPlanPanel({
  plan,
  execution,
  loading,
  executing,
  error,
  onExecute,
}: {
  plan: AgentRunPlanResponse | null;
  execution: AgentRunExecuteResponse | null;
  loading: boolean;
  executing: boolean;
  error: string;
  onExecute: () => void;
}) {
  return (
    <section className="agent-plan-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Agent Policy</p>
          <h2>Dynamic tool injection</h2>
        </div>
        {plan ? <div className="strategy-pill">{plan.agentCode}</div> : null}
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="Planning agent tool policy" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {plan ? (
        <div className="agent-plan-grid">
          <div className="profile-strip">
            <div>
              <span>Provider</span>
              <strong>{plan.providerName}</strong>
            </div>
            <div>
              <span>Model</span>
              <strong>{plan.modelCode}</strong>
            </div>
            <div>
              <span>Capability</span>
              <strong>{plan.capabilityRequired ? plan.capabilityAction : "not required"}</strong>
            </div>
            <div>
              <span>Est. tokens</span>
              <strong>{plan.estimatedTotalTokens}</strong>
            </div>
          </div>
          <div className="tool-decision-list">
            {plan.toolPolicyDecisions.map((decision) => (
              <div className={`tool-decision ${decision.decision.toLowerCase()}`} key={decision.scope}>
                <strong>{decision.scope}</strong>
                <span>{decision.decision}</span>
                <p>{decision.reason}</p>
              </div>
            ))}
          </div>
          <div className="agent-execution-panel">
            <button type="button" onClick={onExecute} disabled={executing}>
              {executing ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>Execute model</span>
            </button>
            {execution ? (
              <div className="profile-strip">
                <div>
                  <span>Actual provider</span>
                  <strong>{execution.providerName}</strong>
                </div>
                <div>
                  <span>Actual model</span>
                  <strong>{execution.modelCode}</strong>
                </div>
                <div>
                  <span>Token usage</span>
                  <strong>
                    {execution.actualUsage.totalTokens} total / {execution.actualUsage.promptTokens} prompt /{" "}
                    {execution.actualUsage.completionTokens} completion
                  </strong>
                </div>
                <div>
                  <span>Status</span>
                  <strong>{execution.status}</strong>
                </div>
              </div>
            ) : null}
            {execution ? (
              <div className="execution-trace">
                <p>{execution.message}</p>
                <div className="tool-decision-list compact">
                  {execution.stageTimings.map((timing) => (
                    <div className="tool-decision allowed" key={timing.stage}>
                      <strong>{timing.stage}</strong>
                      <span>{timing.elapsedMs} ms</span>
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        </div>
      ) : (
        <div className="empty-state compact">Plan an agent run to see which tools the backend will inject.</div>
      )}
    </section>
  );
}

export function AgentModelHealthPanel({
  health,
  error,
  loading,
  expanded,
  onToggle,
  onRefresh,
}: {
  health: AgentModelHealthResponse | null;
  error: string;
  loading: boolean;
  expanded: boolean;
  onToggle: () => void;
  onRefresh: () => void;
}) {
  const reachableCount = health?.results.filter((result) => result.reachable).length ?? 0;
  const totalCount = health?.results.length ?? 0;
  const summary = totalCount > 0 ? `${reachableCount}/${totalCount} reachable` : "not checked";
  return (
    <div className="agent-health-panel">
      <div className="agent-health-head">
        <button type="button" className="inline-action compact" onClick={onToggle}>
          <ShieldCheck size={15} />
          <span>Model health</span>
          <strong>{summary}</strong>
        </button>
        <button type="button" className="inline-action icon-only" onClick={onRefresh} disabled={loading}>
          {loading ? <Loader2 className="spin" size={15} /> : <Search size={15} />}
        </button>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {expanded && health ? (
        <div className="agent-health-list">
          {health.results.map((result) => (
            <div
              className={`agent-health-row ${result.reachable ? "reachable" : "unreachable"}`}
              key={`${result.providerName}:${result.modelCode}`}
            >
              <div>
                <strong>
                  {result.providerName} / {result.modelCode}
                </strong>
                <span>{result.safeReason}</span>
              </div>
              <em>
                {result.statusCode ?? "n/a"} / {result.elapsedMs} ms
              </em>
            </div>
          ))}
          <span className="agent-health-time">{formatDateTime(health.checkedAt)}</span>
        </div>
      ) : null}
    </div>
  );
}

export function AgentTracePanel({
  traces,
  usageSummary,
  diagnosticSummary,
  loading,
  error,
  onRefresh,
}: {
  traces: AgentTraceResponse[];
  usageSummary: AgentTraceUsageSummaryResponse | null;
  diagnosticSummary: AgentTraceDiagnosticSummaryResponse | null;
  loading: boolean;
  error: string;
  onRefresh: () => void;
}) {
  return (
    <section className="agent-trace-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Run Recovery</p>
          <h2>Agent execution history</h2>
        </div>
        <button type="button" className="inline-action" onClick={onRefresh} disabled={loading}>
          {loading ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>Refresh</span>
        </button>
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="Loading recoverable traces" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {usageSummary ? (
        <div className="agent-usage-summary">
          <div className="result-header compact">
            <div>
              <p className="eyebrow">Usage summary</p>
              <h3>{usageSummary.runCount} runs</h3>
            </div>
            <strong>
              {usageSummary.totalUsage.totalTokens} total / {usageSummary.totalUsage.promptTokens} prompt /{" "}
              {usageSummary.totalUsage.completionTokens} completion
            </strong>
          </div>
          <div className="trace-badge-row">
            <span>Models</span>
            <div>
              {usageSummary.modelUsages.map((usage) => (
                <strong key={`${usage.providerName}:${usage.modelCode}`}>
                  {usage.providerName}/{usage.modelCode}: {usage.totalTokens} total, {usage.promptTokens} prompt,{" "}
                  {usage.completionTokens} completion
                </strong>
              ))}
            </div>
          </div>
        </div>
      ) : null}
      {diagnosticSummary ? (
        <div className="agent-usage-summary">
          <div className="result-header compact">
            <div>
              <p className="eyebrow">Diagnostics</p>
              <h3>{diagnosticSummary.diagnosticEventCount} events</h3>
            </div>
            <strong>
              {diagnosticSummary.jsonParseFailureCount} parse failed / {diagnosticSummary.retryRecoveredCount} recovered /{" "}
              {diagnosticSummary.providerRotationCount} fallback
            </strong>
          </div>
          <div className="trace-badge-row">
            <span>Recovery</span>
            <div>
              <strong>{diagnosticSummary.retryScheduledCount} retries</strong>
              <strong>{diagnosticSummary.modelCallFailureCount} gateway failures</strong>
            </div>
          </div>
          <div className="trace-badge-row">
            <span>Models</span>
            <div>
              {diagnosticSummary.modelDiagnostics.map((diagnostic) => (
                <strong key={`${diagnostic.providerName}:${diagnostic.modelCode}`}>
                  {diagnostic.providerName}/{diagnostic.modelCode}: {diagnostic.jsonParseFailureCount} parse failed,{" "}
                  {diagnostic.retryRecoveredCount} recovered, {diagnostic.totalTokens} total
                </strong>
              ))}
            </div>
          </div>
        </div>
      ) : null}
      {traces.length > 0 ? (
        <div className="agent-trace-list">
          {traces.map((trace) => (
            <article className="agent-trace-item" key={trace.traceId}>
              <div className="card-head">
                <div>
                  <h3>{trace.agentCode}</h3>
                  <p>{formatDateTime(trace.createdAt)}</p>
                </div>
                <StatusBadge status={trace.status} />
              </div>
              <div className="profile-strip">
                <div>
                  <span>Trace</span>
                  <strong>{trace.traceId}</strong>
                </div>
                <div>
                  <span>Model</span>
                  <strong>
                    {trace.providerName} / {trace.modelCode}
                  </strong>
                </div>
                <div>
                  <span>Backend subject</span>
                  <strong>
                    {trace.subjectType}:{trace.subjectId}
                  </strong>
                </div>
                <div>
                  <span>Token usage</span>
                  <strong>
                    {trace.actualUsage.totalTokens} total / {trace.actualUsage.promptTokens} prompt /{" "}
                    {trace.actualUsage.completionTokens} completion
                  </strong>
                </div>
              </div>
              <div className="execution-trace">
                <p>{trace.message}</p>
                <div className="tool-decision-list compact">
                  {trace.stageTimings.map((timing) => (
                    <div className="tool-decision allowed" key={`${trace.traceId}:${timing.stage}`}>
                      <strong>{timing.stage}</strong>
                      <span>{timing.elapsedMs} ms</span>
                    </div>
                  ))}
                </div>
                {trace.diagnosticEvents?.length ? (
                  <div className="diagnostic-event-list">
                    {trace.diagnosticEvents.map((event, index) => (
                      <div className="diagnostic-event" key={`${trace.traceId}:${event.eventType}:${index}`}>
                        <strong>{event.eventType}</strong>
                        <span>
                          {event.providerName}/{event.modelCode} attempt {event.attemptNo}
                        </span>
                        <p>{event.message}</p>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
              <TraceBadgeRow label="Tools" values={trace.allowedToolScopes} />
              <TraceBadgeRow label="Data" values={trace.allowedDataScopes} />
              <TraceBadgeRow label="Evidence" values={trace.evidenceRefs} />
            </article>
          ))}
        </div>
      ) : !loading ? (
        <div className="empty-state compact">No recoverable agent traces for the current backend session.</div>
      ) : null}
    </section>
  );
}

function TraceBadgeRow({ label, values }: { label: string; values: string[] }) {
  return (
    <div className="trace-badge-row">
      <span>{label}</span>
      <div>
        {values.length > 0 ? values.map((value) => <strong key={value}>{value}</strong>) : <strong>none</strong>}
      </div>
    </div>
  );
}
