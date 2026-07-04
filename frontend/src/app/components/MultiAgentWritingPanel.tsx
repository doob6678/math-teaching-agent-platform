import { FormEvent } from "react";
import { AlertCircle, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import { MultiAgentWritingResponse, MultiAgentWritingTraceResponse } from "../../shared/api/textbookApi";
import { statusClass, StatusBadge, StatusLine } from "./panelShared";

export function MultiAgentWritingPanel({
  workflow,
  traces,
  writingGoal,
  questionText,
  providerName,
  modelCode,
  modelReady,
  starting,
  resuming = false,
  polling,
  error,
  onWritingGoalChange,
  onQuestionTextChange,
  onSubmit,
  onResume,
  onRefresh,
}: {
  workflow: MultiAgentWritingResponse | null;
  traces: MultiAgentWritingTraceResponse | null;
  writingGoal: string;
  questionText: string;
  providerName: string;
  modelCode: string;
  modelReady: boolean;
  starting: boolean;
  resuming?: boolean;
  polling: boolean;
  error: string;
  onWritingGoalChange: (value: string) => void;
  onQuestionTextChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onResume?: () => void;
  onRefresh: () => void;
}) {
  const stageCodes = ["draft", "review", "format"];
  return (
    <section className="agent-trace-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Multi-Agent Writing</p>
          <h2>多 Agent 写作流</h2>
        </div>
        <button type="button" className="inline-action" onClick={onRefresh} disabled={!workflow || polling}>
          {polling ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>刷新</span>
        </button>
      </div>
      <form className="search-form agent-tool-form" onSubmit={onSubmit}>
        <label>
          <span>目标</span>
          <input
            value={writingGoal}
            onChange={(event) => onWritingGoalChange(event.target.value)}
            placeholder="例如：生成教师讲义 / 学生版讲义"
          />
        </label>
        <label>
          <span>问题</span>
          <input
            value={questionText}
            onChange={(event) => onQuestionTextChange(event.target.value)}
            placeholder="输入真实题目或课件写作要求"
          />
        </label>
        <button type="submit" disabled={starting || !modelReady}>
          {starting ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
          <span>启动异步流程</span>
        </button>
        {onResume ? (
          <button type="button" disabled={!workflow || resuming || polling} onClick={onResume}>
            {resuming ? <Loader2 className="spin" size={17} /> : <Database size={17} />}
            <span>恢复</span>
          </button>
        ) : null}
      </form>
      <div className="trace-badge-row">
        <span>Model</span>
        <div>
          <strong>
            {providerName} / {modelCode}
          </strong>
        </div>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {workflow ? (
        <div className="agent-usage-summary">
          <div className="result-header compact">
            <div>
              <p className="eyebrow">Workflow</p>
              <h3>{workflow.status}</h3>
            </div>
            <StatusBadge status={workflow.status} />
            <strong>
              {workflow.totalUsage.totalTokens} total / {workflow.totalUsage.promptTokens} prompt /{" "}
              {workflow.totalUsage.completionTokens} completion
            </strong>
          </div>
          <div className="trace-badge-row">
            <span>ID</span>
            <div>
              <strong>{workflow.workflowId}</strong>
            </div>
          </div>
          {workflow.message ? (
            <div className="trace-badge-row">
              <span>Status</span>
              <div>
                <strong>{workflow.message}</strong>
              </div>
            </div>
          ) : null}
          <div className="tool-decision-list compact">
            {stageCodes.map((stageCode) => {
              const stage = workflow.stages.find((candidate) => candidate.stageCode === stageCode);
              const stageClass = stage ? statusClass(stage.status) : workflow.status === "RUNNING" ? "running" : "failed";
              return (
                <div className={`tool-decision ${stageClass}`} key={stageCode}>
                  <strong>{stageCode}</strong>
                  <span>
                    {stage
                      ? `${stage.status} · ${stage.providerName}/${stage.modelCode} · ${stage.actualUsage.totalTokens} tokens`
                      : workflow.status === "RUNNING"
                        ? "waiting"
                        : "not completed"}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      ) : (
        <div className="empty-state compact">提交真实写作任务后，这里会显示可恢复的工作流和用量统计。</div>
      )}
      {traces ? (
        <div className="agent-trace-list">
          {traces.stages.map((trace) => (
            <article className="agent-trace-item" key={trace.traceId}>
              <div className="card-head">
                <div>
                  <h3>{trace.agentCode}</h3>
                  <p>{trace.planId}</p>
                </div>
                <StatusBadge status={trace.status} />
              </div>
              <div className="profile-strip">
                <div>
                  <span>Model</span>
                  <strong>
                    {trace.providerName} / {trace.modelCode}
                  </strong>
                </div>
                <div>
                  <span>Token usage</span>
                  <strong>{trace.actualUsage.totalTokens}</strong>
                </div>
                <div>
                  <span>Events</span>
                  <strong>{trace.diagnosticEvents?.length ?? 0}</strong>
                </div>
              </div>
            </article>
          ))}
        </div>
      ) : null}
    </section>
  );
}
