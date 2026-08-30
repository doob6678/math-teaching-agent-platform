import { AlertCircle, Check, Loader2, RefreshCw, Search, ShieldCheck } from "lucide-react";
import {
  AgentModelHealthResponse,
  AgentRunExecuteResponse,
  AgentRunPlanResponse,
  AgentTraceDiagnosticSummaryResponse,
  AgentTraceResponse,
  AgentTraceUsageSummaryResponse,
} from "../../shared/api/textbookApi";
import { compactText, formatDateTime, StatusBadge, StatusLine } from "./panelShared";

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
          <p className="eyebrow">AI 运行预案</p>
          <h2>本次会调用什么能力</h2>
        </div>
        {plan ? <div className="strategy-pill">{agentName(plan.agentCode)}</div> : null}
      </div>

      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在生成运行预案" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}

      {plan ? (
        <div className="agent-plan-grid">
          <div className="profile-strip">
            <InfoCell label="服务商" value={providerLabel(plan.providerName)} />
            <InfoCell label="模型" value={plan.modelCode} />
            <InfoCell label="权限" value="已按当前用户会话验证" />
            <InfoCell label="预计用量" value={plan.estimatedTotalTokens.toLocaleString("zh-CN")} />
          </div>

          <div className="agent-execution-panel">
            <button type="button" className="btn btn-primary" onClick={onExecute} disabled={executing}>
              {executing ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>执行一次真实调用</span>
            </button>
            {execution ? (
              <div className="profile-strip">
                <InfoCell label="实际模型" value={`${providerLabel(execution.providerName)} / ${execution.modelCode}`} />
                <InfoCell label="状态" value={statusLabel(execution.status)} />
                <InfoCell label="用量" value={execution.actualUsage.totalTokens.toLocaleString("zh-CN")} />
                <InfoCell label="耗时阶段" value={`${execution.stageTimings.length} 个`} />
              </div>
            ) : null}
            {execution ? (
              <div className="execution-trace">
                <p>{compactText(traceMessage(execution.message), 140)}</p>
                <details className="review-details ai-run-disclosure">
                  <summary>查看阶段耗时</summary>
                  <div className="tool-decision-list compact">
                    {execution.stageTimings.map((timing) => (
                      <div className="tool-decision allowed" key={timing.stage}>
                        <strong>{stageLabel(timing.stage)}</strong>
                        <span>{timing.elapsedMs} ms</span>
                      </div>
                    ))}
                  </div>
                </details>
              </div>
            ) : null}
          </div>

          <details className="review-details ai-run-disclosure">
            <summary>工具和数据范围</summary>
            <div className="tool-decision-list">
              {plan.toolPolicyDecisions.map((decision) => (
                <div className={`tool-decision ${decision.decision.toLowerCase()}`} key={decision.scope}>
                  <strong>{toolScopeLabel(decision.scope)}</strong>
                  <span>{decisionLabel(decision.decision)}</span>
                  <p>{decisionReason(decision.reason)}</p>
                </div>
              ))}
            </div>
            <TraceBadgeRow label="可用数据" values={plan.allowedDataScopes.map(dataScopeLabel)} />
          </details>
        </div>
      ) : (
        <div className="empty-state compact">先选择模型并生成预案。这里会显示模型、授权、工具范围和预计用量。</div>
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
  const summary = totalCount > 0 ? `${reachableCount}/${totalCount} 可用` : "未检查";
  return (
    <div className="agent-health-panel">
      <div className="agent-health-head">
        <button type="button" className="inline-action compact btn btn-ghost btn-sm" onClick={onToggle}>
          <Check size={15} />
          <span>模型连通性</span>
          <strong>{summary}</strong>
        </button>
        <button type="button" className="inline-action btn btn-ghost btn-sm" onClick={onRefresh} disabled={loading}>
          {loading ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
          <span>{loading ? "检查中" : "检查"}</span>
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
                <strong>{providerLabel(result.providerName)} / {result.modelCode}</strong>
                <span>{result.reachable ? "真实请求可达" : compactText(result.safeReason, 80)}</span>
              </div>
              <em>{result.elapsedMs} ms</em>
            </div>
          ))}
          <span className="agent-health-time">检查时间：{formatDateTime(health.checkedAt)}</span>
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
          <p className="eyebrow">过程流</p>
          <h2>AI 运行、工具调用与恢复记录</h2>
        </div>
        <button type="button" className="inline-action btn btn-ghost btn-sm" onClick={onRefresh} disabled={loading}>
          {loading ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>刷新</span>
        </button>
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在读取可恢复记录" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}

      {usageSummary ? (
        <div className="agent-usage-summary conversation-summary">
          <div className="result-header compact">
            <div>
              <p className="eyebrow">用量汇总</p>
              <h3>{usageSummary.runCount} 次调用</h3>
            </div>
            <strong>用量 {usageSummary.totalUsage.totalTokens.toLocaleString("zh-CN")}</strong>
          </div>
          <TraceBadgeRow
            label="模型"
            values={usageSummary.modelUsages.map((usage) =>
              `${providerLabel(usage.providerName)} / ${usage.modelCode}: 用量 ${usage.totalTokens.toLocaleString("zh-CN")}`
            )}
          />
        </div>
      ) : null}

      {diagnosticSummary ? (
        <details className="review-details ai-run-disclosure">
          <summary>重试、解析和模型切换统计</summary>
          <div className="agent-usage-summary">
            <TraceBadgeRow
              label="恢复"
              values={[
                `JSON 解析失败 ${diagnosticSummary.jsonParseFailureCount} 次`,
                `重试恢复 ${diagnosticSummary.retryRecoveredCount} 次`,
                `模型切换 ${diagnosticSummary.providerRotationCount} 次`,
              ]}
            />
            <TraceBadgeRow
              label="网关"
              values={[
                `计划重试 ${diagnosticSummary.retryScheduledCount} 次`,
                `调用失败 ${diagnosticSummary.modelCallFailureCount} 次`,
              ]}
            />
          </div>
        </details>
      ) : null}

      {traces.length > 0 ? (
        <div className="agent-conversation-timeline">
          {traces.map((trace) => (
            <article className="agent-conversation-turn" key={trace.traceId}>
              <div className="conversation-avatar">{agentInitial(trace.agentCode)}</div>
              <div className="conversation-bubble">
                <div className="conversation-bubble-head">
                  <div>
                    <strong>{agentName(trace.agentCode)}</strong>
                    <span>{formatDateTime(trace.createdAt)}</span>
                  </div>
                  <StatusBadge status={trace.status} />
                </div>
                <p>{traceMessage(trace.message)}</p>
                <div className="conversation-meta-row">
                  <span>{providerLabel(trace.providerName)} / {trace.modelCode}</span>
                  <span>{subjectLabel(trace.subjectType)}:{trace.subjectId}</span>
                  <span>用量 {trace.actualUsage.totalTokens.toLocaleString("zh-CN")}</span>
                </div>
                <div className="agent-process-list">
                  {timelineEvents(trace).map((event) => (
                    <details className={`agent-process-block ai-run-disclosure ${event.tone}`} key={`${trace.traceId}:${event.eventId}`}>
                      <summary>
                        <span>{event.title}</span>
                        <em>{event.summary}</em>
                      </summary>
                      <div className="agent-process-body">
                        {event.detail ? <p>{event.detail}</p> : null}
                        {event.meta.length ? (
                          <div className="agent-process-tags">
                            {event.meta.map((item, index) => (
                              <strong key={scopedTraceTagKey(`${trace.traceId}:${event.eventId}`, index, item)}>{item}</strong>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    </details>
                  ))}
                </div>
              </div>
            </article>
          ))}
        </div>
      ) : !loading ? (
        <div className="empty-state compact">当前账号还没有 AI 调用记录。</div>
      ) : null}
    </section>
  );
}

type TimelineEvent = {
  eventId: string;
  title: string;
  summary: string;
  detail: string;
  tone: "neutral" | "running" | "success" | "warning" | "danger";
  meta: string[];
};

function timelineEvents(trace: AgentTraceResponse): TimelineEvent[] {
  const events: TimelineEvent[] = [
    {
      eventId: "plan",
      title: "明确运行边界",
      summary: `${trace.allowedToolScopes.length} 个工具 · ${trace.allowedDataScopes.length} 类数据`,
      detail: "后端按当前账号和 Agent 策略决定可用工具、数据范围和一次性授权，不信任前端自选身份。",
      tone: "neutral",
      meta: [
        `编号 ${compactText(trace.traceId, 18)}`,
        `会话 ${subjectLabel(trace.subjectType)}:${trace.subjectId}`,
        ...trace.allowedDataScopes.slice(0, 4).map(dataScopeLabel),
      ],
    },
  ];

  if (trace.allowedToolScopes.length) {
    events.push({
      eventId: "tools",
      title: "准备工具调用",
      summary: trace.allowedToolScopes.slice(0, 2).map(toolScopeLabel).join("、")
        + (trace.allowedToolScopes.length > 2 ? ` 等 ${trace.allowedToolScopes.length} 个` : ""),
      detail: "这些工具是本次运行实际暴露给 Agent 的能力范围，禁用或无权限的工具不会进入执行上下文。",
      tone: "neutral",
      meta: trace.allowedToolScopes.map(toolScopeLabel),
    });
  }

  if (trace.evidenceRefs.length) {
    events.push({
      eventId: "evidence",
      title: "查找并绑定证据",
      summary: `${trace.evidenceRefs.length} 条证据已记录`,
      detail: "证据只展示安全引用和摘要，教材 OCR、页图路径和题库答案不会直接暴露在过程流里。",
      tone: "success",
      meta: trace.evidenceRefs.slice(0, 6).map(evidenceLabel),
    });
  }

  if (trace.stageTimings.length) {
    events.push({
      eventId: "stages",
      title: "执行阶段记录",
      summary: `${trace.stageTimings.length} 个阶段`,
      detail: "阶段耗时用于恢复、排查和并发调度，不作为讲义正文内容。",
      tone: "neutral",
      meta: trace.stageTimings.slice(0, 8).map((timing) => `${stageLabel(timing.stage)} ${timing.elapsedMs} ms`),
    });
  }

  const diagnostics = trace.diagnosticEvents ?? [];
  if (diagnostics.length) {
    const hasFailure = diagnostics.some((event) => event.eventType.includes("FAILED") || event.eventType.includes("FAILURE"));
    events.push({
      eventId: "diagnostics",
      title: hasFailure ? "处理异常与恢复" : "结构化解析完成",
      summary: `${diagnostics.length} 条诊断事件`,
      detail: diagnostics.map((event) => diagnosticEventText(event)).join("；"),
      tone: hasFailure ? "warning" : "success",
      meta: diagnostics.slice(0, 6).map((event) => `${providerLabel(event.providerName)} / ${event.modelCode} · ${diagnosticEventLabel(event.eventType)}`),
    });
  }

  events.push({
    eventId: "final",
    title: "生成可恢复记录",
    summary: statusLabel(trace.status),
    detail: traceMessage(trace.message),
    tone: trace.status === "FAILED" ? "danger" : trace.status === "RUNNING" ? "running" : "success",
    meta: [
      `${providerLabel(trace.providerName)} / ${trace.modelCode}`,
      `官方用量 ${trace.actualUsage.totalTokens.toLocaleString("zh-CN")}`,
    ],
  });
  return events;
}

function InfoCell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function TraceBadgeRow({ label, values }: { label: string; values: string[] }) {
  return (
    <div className="trace-badge-row">
      <span>{label}</span>
      <div>
        {values.length > 0 ? values.map((value, index) => (
          <strong key={scopedTraceTagKey(label, index, value)}>{value}</strong>
        )) : <strong>无</strong>}
      </div>
    </div>
  );
}

/** Repeated evidence and retry events are legitimate; position keeps their React identities distinct and stable. */
export function scopedTraceTagKey(scope: string, index: number, value: string) {
  return `${scope}:${index}:${value}`;
}

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    openai: "OpenAI",
    dashscope: "通义千问",
    deepseek: "DeepSeek",
    glm: "智谱 GLM",
    ark: "火山方舟",
  };
  return labels[provider] ?? provider;
}

function agentName(agentCode: string) {
  const labels: Record<string, string> = {
    CoursewareAgent: "讲义生成",
    QualityCheckAgent: "质量审校",
    HandoutFormatterAgent: "排版整理",
  };
  return labels[agentCode] ?? agentCode;
}

function agentInitial(agentCode: string) {
  const name = agentName(agentCode);
  return name.slice(0, 1) || "AI";
}

function diagnosticEventLabel(eventType: string) {
  const labels: Record<string, string> = {
    MODEL_CALL_SUCCEEDED: "模型调用成功",
    MODEL_CALL_FAILED: "模型调用失败",
    JSON_PARSE_SUCCEEDED: "结构解析成功",
    JSON_PARSE_FAILED: "结构解析失败",
    RETRY_SCHEDULED: "已安排重试",
    PROVIDER_ROTATED: "已切换模型",
  };
  return labels[eventType] ?? eventType;
}

function diagnosticEventText(event: { eventType: string; providerName: string; modelCode: string; attemptNo: number; retryable: boolean; message: string }) {
  const retryText = event.retryable ? "可继续恢复" : "无需继续重试";
  return `${diagnosticEventLabel(event.eventType)}，${providerLabel(event.providerName)} / ${event.modelCode}，第 ${event.attemptNo + 1} 次，${retryText}`;
}

function toolScopeLabel(scope: string) {
  const labels: Record<string, string> = {
    "tool:courseware:generate": "生成讲义",
    "tool:search:private": "检索私有资料",
    "tool:search:textbook": "检索公开教材",
    "tool:textbook:search": "检索公开教材",
    "tool:quality:check": "质量审校",
    "tool:handout:format": "讲义排版",
  };
  return labels[scope] ?? scope;
}

function dataScopeLabel(scope: string) {
  const labels: Record<string, string> = {
    TEACHER_PRIVATE: "教师私有资料",
    CLASS_AUTHORIZED: "班级授权资料",
    PUBLIC_TEXTBOOK: "公开教材",
    "data:public_textbook": "公开教材",
    "data:student_memory": "学生记忆",
    "data:teacher_private": "教师私有资料",
    "data:class_authorized": "班级授权资料",
  };
  return labels[scope] ?? scope;
}

function evidenceLabel(value: string) {
  const text = (value ?? "").trim();
  const [scope, rest] = text.includes(":") ? text.split(/:(.+)/, 2) : ["", text];
  const scopeText = scope ? dataScopeLabel(scope) : "";
  const content = compactText((rest || text).replace(/\s+/g, " "), 34);
  return scopeText ? `${scopeText} · ${content}` : content;
}

function decisionLabel(decision: string) {
  const labels: Record<string, string> = {
    ALLOWED: "允许",
    DISABLED_BY_USER: "已关闭",
    DENIED_BY_AGENT_POLICY: "策略拒绝",
  };
  return labels[decision] ?? decision;
}

function decisionReason(reason: string) {
  if (reason.includes("not disabled by request preference")) {
    return "该工具在当前任务策略内，并且没有被本次请求关闭。";
  }
  return compactText(reason, 120);
}

function traceMessage(message: string) {
  const text = (message ?? "").trim();
  if (!text) {
    return "暂无说明";
  }
  if (/Live model response recorded with provider usage metadata/i.test(text)) {
    return "模型调用成功，已记录服务商返回的用量。";
  }
  const teachingDraft = text.match(/Teaching AI draft structured;\s*retry=(\d+)\/(\d+);\s*recovered=(true|false);\s*events=(\d+)/i);
  if (teachingDraft) {
    return `教学草稿已结构化解析，重试 ${teachingDraft[1]}/${teachingDraft[2]}，${teachingDraft[3] === "true" ? "已恢复" : "无需恢复"}，事件 ${teachingDraft[4]} 条。`;
  }
  if (/Structured teaching draft parsed/i.test(text)) {
    return "教学草稿结构化解析成功。";
  }
  if (/MODEL_CALL_SUCCEEDED/i.test(text)) {
    return "模型调用成功。";
  }
  if (/JSON_PARSE_SUCCEEDED/i.test(text)) {
    return "JSON 结构解析成功。";
  }
  return compactText(text.replace(/^Backend request failed:\s*/i, ""), 160);
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    CREATED: "已创建",
    RUNNING: "运行中",
    COMPLETED: "已完成",
    FAILED: "失败",
    SUCCESS: "成功",
  };
  return labels[status] ?? status;
}

function subjectLabel(subjectType: string) {
  const labels: Record<string, string> = {
    admin: "管理员",
    teacher: "教师",
    student: "学生",
  };
  return labels[subjectType] ?? subjectType;
}

function stageLabel(stage: string) {
  const labels: Record<string, string> = {
    model_call: "模型调用",
    capability_check: "授权校验",
    subject_policy_guard: "用户权限校验",
    concurrency_guard: "并发控制",
    trace_start: "开始记录",
    trace_finish: "完成记录",
    trace_persist: "记录保存",
    planning: "运行规划",
    execution: "执行",
    memory_reuse: "记忆复用",
    textbook_retrieval: "教材检索",
    react_trace: "推理轨迹",
    ai_draft: "AI 草稿",
    handout_generation: "讲义生成",
  };
  return labels[stage] ?? stage;
}
