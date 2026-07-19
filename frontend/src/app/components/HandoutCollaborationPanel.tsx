import katex from "katex";
import { FormEvent, useEffect, useRef, useState } from "react";
import { BookOpen, ChevronDown, Download, Eye, FileText, Loader2, Sparkles } from "lucide-react";
import {
  TeachingEvidence,
  TeachingHandoutVersion,
  TeachingStageTiming,
  TeachingTaskResponse,
  TeacherDocumentBlockResponse,
  TeachingWorkflowEvent,
  TeachingWorkflowNode,
} from "../../shared/api/textbookApi";
import { compactText } from "./panelShared";

const WORKFLOW_INSPECTOR_TEXT_LIMIT = 360;

/**
 * Relates persisted DAG codes to workflow event ids. This is deliberately explicit: event titles are editorial copy
 * and must never be used as an unstable or unsafe join key for trace inspection.
 */
const WORKFLOW_NODE_EVENT_IDS: Readonly<Record<string, readonly string[]>> = {
  LEARNING_GOAL: ["plan"],
  PUBLIC_TEXTBOOK_RETRIEVAL: ["evidence"],
  QUESTION_BANK_RETRIEVAL: ["evidence"],
  TEACHER_RESOURCE_RETRIEVAL: ["evidence"],
  REACT_SOLVE: ["outline"],
  HANDOUT_TEMPLATE: ["plan"],
  AI_DRAFT: ["generation"],
  LATEX_HANDOUT: ["render"],
  HUMAN_FEEDBACK: ["review"],
};

/** Retrieval nodes are allowed to reveal only evidence from their own backend-assigned scope. */
const WORKFLOW_NODE_EVIDENCE_SCOPE: Readonly<Record<string, string>> = {
  PUBLIC_TEXTBOOK_RETRIEVAL: "PUBLIC_TEXTBOOK",
  QUESTION_BANK_RETRIEVAL: "QUESTION_BANK",
  TEACHER_RESOURCE_RETRIEVAL: "TEACHER_RESOURCE",
};

export type WorkflowNodeInspection = {
  node: TeachingWorkflowNode;
  events: Array<Pick<TeachingWorkflowEvent, "eventId" | "eventType" | "status" | "title" | "summary" | "sourceName">>;
  evidence: TeachingEvidence[];
  templateName: string;
  sourceRefs: string[];
  reviewFindings: Array<{ severity: string; sectionCode: string; summary: string }>;
  versions: Array<{ label: string; ready: boolean }>;
};

export type HandoutCollaborationThreadItem =
  | {
      id: string;
      role: "user";
      createdAt: string;
      learningGoal: string;
      questionText?: string;
      templateName?: string;
      evidenceLimit: number;
    }
  | {
      id: string;
      role: "assistant";
      createdAt: string;
      taskId?: string;
      loading?: boolean;
      error?: string;
      task?: TeachingTaskResponse;
    };

export function HandoutCollaborationPanel({
  learningGoal,
  questionText,
  evidenceLimit,
  watermarkText = "数学讲义",
  aiProviderName = "",
  aiModelCode = "",
  aiProviders = [],
  aiModels = [],
  selectedTemplateName: _selectedTemplateName,
  version,
  entries,
  loading,
  error,
  onLearningGoalChange,
  onQuestionTextChange,
  onEvidenceLimitChange,
  onWatermarkTextChange,
  onAiProviderChange,
  onAiModelChange,
  onSubmit,
  onPreviewPdf,
  onPreviewLatex,
  onExportPdf,
  onVersionChange,
  onInspectEvidence,
}: {
  learningGoal: string;
  questionText: string;
  evidenceLimit: number;
  watermarkText?: string;
  aiProviderName?: string;
  aiModelCode?: string;
  aiProviders?: string[];
  aiModels?: string[];
  /** Legacy compatibility field; templates are backend-owned and are not rendered as a user prompt. */
  selectedTemplateName?: string;
  version: TeachingHandoutVersion;
  entries: HandoutCollaborationThreadItem[];
  loading: boolean;
  error: string;
  onLearningGoalChange: (value: string) => void;
  onQuestionTextChange: (value: string) => void;
  onEvidenceLimitChange: (value: number) => void;
  onWatermarkTextChange?: (value: string) => void;
  onAiProviderChange?: (value: string) => void;
  onAiModelChange?: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onPreviewPdf: (task: TeachingTaskResponse) => void;
  onPreviewLatex: (task: TeachingTaskResponse) => void;
  onExportPdf: (task: TeachingTaskResponse) => void;
  onVersionChange: (version: TeachingHandoutVersion) => void;
  /** Loads a cited teacher document through the backend session; no local path or Feishu token reaches this panel. */
  onInspectEvidence?: (evidence: TeachingEvidence) => Promise<TeacherDocumentBlockResponse[]>;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const submitLabel = loading ? "生成中" : "开始生成";

  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
  }, [entries, loading]);

  return (
    <section className="teaching-live-shell handout-live-shell" aria-label="讲义工作台">
      <header className="teaching-live-header handout-live-header">
        <div className="teaching-live-brand">
          <div className="teaching-live-brand-icon handout-live-brand-icon">
            <BookOpen size={16} />
          </div>
          <div className="teaching-live-brand-copy">
            <strong>讲义工作台</strong>
            <span>只保留当前任务和当前结果。</span>
          </div>
        </div>
        <div className="teaching-live-toolbar handout-live-toolbar">
          <span className="teaching-history-chip handout-version-chip">{handoutVersionLabel(version)}</span>
        </div>
      </header>

      <form className="handout-brief-form" onSubmit={onSubmit}>
        <div className="handout-brief-grid">
          <label className="handout-brief-field">
            <span>主题</span>
            <input
              className="handout-composer-input"
              value={learningGoal}
              onChange={(event) => onLearningGoalChange(event.target.value)}
              placeholder="例如：双曲线专题讲评 / 反比例函数练习"
            />
          </label>
          <label className="handout-brief-field">
            <span>证据</span>
            <input
              type="number"
              min={3}
              max={12}
              value={evidenceLimit}
              onChange={(event) => onEvidenceLimitChange(Number(event.target.value))}
            />
          </label>
          <label className="handout-brief-field">
            <span>PDF 水印</span>
            <input
              className="handout-composer-input"
              value={watermarkText}
              maxLength={32}
              onChange={(event) => onWatermarkTextChange?.(event.target.value)}
              placeholder="数学讲义"
            />
          </label>
          {aiProviders.length ? <label className="handout-brief-field">
            <span>生成模型</span>
            <select className="handout-composer-input" value={aiProviderName} onChange={(event) => onAiProviderChange?.(event.target.value)}>
              {aiProviders.map((provider) => <option key={provider} value={provider}>{provider}</option>)}
            </select>
          </label> : null}
          {aiModels.length ? <label className="handout-brief-field">
            <span>模型版本</span>
            <select className="handout-composer-input" value={aiModelCode} onChange={(event) => onAiModelChange?.(event.target.value)}>
              {aiModels.map((model) => <option key={model} value={model}>{model}</option>)}
            </select>
          </label> : null}
        </div>
        <label className="handout-brief-field full">
          <span>补充要求</span>
          <textarea
            value={questionText}
            onChange={(event) => onQuestionTextChange(event.target.value)}
            placeholder="例如：偏基础、保留留白、课堂讲评口径。"
            rows={3}
          />
        </label>
        <div className="handout-brief-actions">
          <button className="teaching-send-btn handout-submit-btn" type="submit" disabled={loading || !learningGoal.trim()}>
            {loading ? <Loader2 className="spin" size={17} /> : <Sparkles size={17} />}
            <span>{submitLabel}</span>
          </button>
        </div>
      </form>

      <div className="teaching-live-scroll handout-live-scroll" ref={scrollRef}>
        {!entries.length ? (
          <section className="teaching-empty-state handout-empty-state">
            <div className="teaching-empty-badge">
              <Sparkles size={16} />
              <span>新讲义</span>
            </div>
            <h2>输入主题后开始</h2>
            <p>默认只看当前流程，其他内容按需再打开。</p>
          </section>
        ) : null}

        {entries.map((entry) => entry.role === "user" ? (
          <article className="teaching-user-row" key={entry.id}>
            <div className="teaching-user-bubble handout-user-bubble">
              <strong>{entry.learningGoal}</strong>
              {entry.questionText ? <p>{entry.questionText}</p> : null}
              <div className="handout-user-meta">
                <span>证据 {entry.evidenceLimit} 条</span>
                <span>{formatThreadTime(entry.createdAt)}</span>
              </div>
            </div>
          </article>
        ) : (
          <article className="teaching-assistant-thread handout-assistant-thread" key={entry.id}>
            <div className="teaching-assistant-avatar">讲义</div>
            <div className="teaching-assistant-flow">
              {entry.loading ? (
                <LoadingTaskCard />
              ) : entry.error ? (
                <section className="teaching-status-card error">
                  <div className="teaching-status-head">
                    <strong>本次生成未完成</strong>
                    <span>已停止</span>
                  </div>
                  <p>{entry.error}</p>
                </section>
              ) : entry.task ? (
                <TaskConversationCards
                  task={entry.task}
                  version={version}
                  onPreviewPdf={onPreviewPdf}
                  onPreviewLatex={onPreviewLatex}
                  onExportPdf={onExportPdf}
                  onVersionChange={onVersionChange}
                  onInspectEvidence={onInspectEvidence}
                />
              ) : null}
            </div>
          </article>
        ))}

        {error && !entries.some((entry) => entry.role === "assistant" && entry.error === error) ? (
          <div className="teaching-global-error">{error}</div>
        ) : null}
      </div>
    </section>
  );
}

function LoadingTaskCard() {
  const steps = [
    "确认主题",
    "整理证据",
    "生成当前版本",
    "准备预览",
  ];
  return (
    <section className="teaching-status-card pending handout-status-card">
      <div className="teaching-status-head">
        <strong>正在编排讲义</strong>
        <span><Loader2 className="spin" size={12} />处理中</span>
      </div>
      <div className="teaching-loading-steps">
        {steps.map((step) => <div key={step}>{step}</div>)}
      </div>
    </section>
  );
}

function TaskConversationCards({
  task,
  version,
  onPreviewPdf,
  onPreviewLatex,
  onExportPdf,
  onVersionChange,
  onInspectEvidence,
}: {
  task: TeachingTaskResponse;
  version: TeachingHandoutVersion;
  onPreviewPdf: (task: TeachingTaskResponse) => void;
  onPreviewLatex: (task: TeachingTaskResponse) => void;
  onExportPdf: (task: TeachingTaskResponse) => void;
  onVersionChange: (version: TeachingHandoutVersion) => void;
  onInspectEvidence?: (evidence: TeachingEvidence) => Promise<TeacherDocumentBlockResponse[]>;
}) {
  const [selectedNodeCode, setSelectedNodeCode] = useState<string | null>(null);
  const [inspectedEvidence, setInspectedEvidence] = useState<TeachingEvidence | null>(null);
  const [inspectedBlock, setInspectedBlock] = useState<TeacherDocumentBlockResponse | null>(null);
  const [inspectingEvidence, setInspectingEvidence] = useState(false);
  const [inspectionError, setInspectionError] = useState("");
  const selectedDraft = handoutDraftForVersion(task, version);
  const taskCompleted = task.status === "COMPLETED";
  const stageTimings = task.stageTimings ?? [];
  // Every persisted hit is inspectable. Truncating to three made real source evidence look unavailable and prevented
  // a teacher from checking which exact material informed a generated section.
  const evidencePreview = task.evidence;
  const suggestions = (task.aiDraft?.followUpQuestions?.length ? task.aiDraft.followUpQuestions : task.interactiveSuggestions).slice(0, 4);
  const outline = buildAiOutline(task, version);
  const versionSummary = handoutVersionSummary(task);
  const versionArtifacts: Array<{ version: TeachingHandoutVersion; label: string; ready: boolean; detail: string }> = [
    { version: "teacher", label: "教师版", ready: Boolean(task.teacherHandoutLatex?.trim() || task.handoutLatex?.trim()), detail: "答案、来源与讲评" },
    { version: "student", label: "学生版", ready: Boolean(task.studentHandoutLatex?.trim()), detail: "练习、提示与留白" },
    { version: "lecture", label: "16:10 讲解版", ready: Boolean(task.lectureHandoutLatex?.trim()), detail: "投屏讲解三段卡" },
  ];

  return (
    <>
      <section className="teaching-response-card primary handout-task-card">
        <div className="teaching-response-head">
          <div>
            <strong>{displayTaskTitle(task)}</strong>
            <span className="teaching-response-mode">自动生成</span>
          </div>
          <span className={`handout-status-badge ${statusToneClass(task.status)}`}>{statusLabel(task.status)}</span>
        </div>
        <div className="handout-task-metrics compact">
          <MetricCard label="当前版本" value={handoutVersionLabel(version)} />
          <MetricCard label="已产出版本" value={versionSummary} />
          <MetricCard label="命中来源" value={`${task.evidence.length} 条`} />
        </div>
        <div className="handout-version-artifact-list" aria-label="三个讲义版本">
          {versionArtifacts.map((artifact) => (
            <button
              className={`handout-version-artifact${artifact.version === version ? " active" : ""}`}
              type="button"
              key={artifact.version}
              onClick={() => onVersionChange(artifact.version)}
              disabled={!artifact.ready}
            >
              <strong>{artifact.label}</strong>
              <span>{artifact.ready ? "已生成，可编辑" : taskCompleted ? "未生成" : "生成中"}</span>
              <em>{artifact.detail}</em>
            </button>
          ))}
        </div>
        {outline.length ? (
          <div className="handout-thread-outline">
            {outline.slice(0, 3).map((item, index) => (
              <div className="handout-thread-outline-item" key={`${item.title}-${index}`}>
                <strong>{item.title}</strong>
                <p><RichText text={item.summary} /></p>
              </div>
            ))}
          </div>
        ) : null}
        <div className="handout-task-actions">
          <button className="handout-action-btn primary" type="button" onClick={() => onPreviewPdf(task)} disabled={!selectedDraft}>
            <Eye size={15} />
            <span>{taskCompleted ? "预览 PDF" : "查看当前稿"}</span>
          </button>
          <button className="handout-action-btn" type="button" onClick={() => onPreviewLatex(task)} disabled={!selectedDraft}>
            <FileText size={15} />
            <span>结构校对</span>
          </button>
          <button className="handout-action-btn" type="button" onClick={() => onExportPdf(task)} disabled={!taskCompleted || !selectedDraft}>
            <Download size={15} />
            <span>下载 PDF</span>
          </button>
        </div>
      </section>

      {(task.nodes.length || evidencePreview.length || suggestions.length) ? (
        <section className="teaching-response-card handout-thread-card">
          <div className="teaching-response-head">
            <div>
              <strong>流程记录</strong>
              <span className="teaching-response-mode">只保留可读结果</span>
            </div>
          </div>
          <div className="handout-thread-feed">
            {task.nodes.map((node, index) => {
              const selected = selectedNodeCode === node.code;
              const inspection = buildWorkflowNodeInspection(task, node.code);
              const inspectorId = `workflow-node-inspector-${task.taskId}-${node.code}`;
              return (
                <div className="handout-thread-node-group" key={`${task.taskId}:${node.code}:${index}`}>
                  <button
                    className={`handout-thread-message handout-thread-node-control ${nodeToneClass(node.status)}${selected ? " selected" : ""}`}
                    type="button"
                    aria-expanded={selected}
                    aria-controls={inspectorId}
                    onClick={() => setSelectedNodeCode((current) => current === node.code ? null : node.code)}
                  >
                    <span className="handout-thread-message-index">{String(index + 1).padStart(2, "0")}</span>
                    <span className="handout-thread-message-body">
                      <span className="handout-thread-message-head">
                        <strong>{node.name}</strong>
                        <span className="handout-thread-message-meta">
                          <span>{statusLabel(node.status)}</span>
                          {stageElapsedForNode(node.code, stageTimings) ? <em>{stageElapsedForNode(node.code, stageTimings)}</em> : null}
                          <span className="handout-thread-node-action">查看节点详情 <ChevronDown size={14} aria-hidden="true" /></span>
                        </span>
                      </span>
                      <span className="handout-thread-message-summary"><RichText text={stageSummaryText(node.summary)} /></span>
                    </span>
                  </button>
                  {selected && inspection ? (
                    <WorkflowNodeInspector inspection={inspection} inspectorId={inspectorId} stageTimings={stageTimings} />
                  ) : null}
                </div>
              );
            })}

            {evidencePreview.length ? (
              <article className="handout-thread-message subtle">
                <div className="handout-thread-message-index">源</div>
                <div className="handout-thread-message-body">
                  <div className="handout-thread-message-head">
                    <strong>引用来源</strong>
                    <div className="handout-thread-message-meta">
                      <span>{task.evidence.length} 条</span>
                    </div>
                  </div>
                  <div className="handout-thread-source-list">
                    {evidencePreview.map((item) => (
                      <button
                        className="handout-thread-source-item handout-thread-source-button"
                        type="button"
                        key={`${task.taskId}:${item.chunkId}`}
                        disabled={inspectingEvidence || !onInspectEvidence}
                        onClick={() => {
                          if (!onInspectEvidence) return;
                          setInspectedEvidence(item);
                          setInspectedBlock(null);
                          setInspectionError("");
                          // Question-bank evidence intentionally has no browsable teacher document id. Its persisted
                          // source snippet is still shown below instead of turning the card into a dead control.
                          if (!item.sourceDocumentId) return;
                          setInspectingEvidence(true);
                          onInspectEvidence(item)
                            .then((blocks) => {
                              const block = blocks.find((candidate) => candidate.blockId === item.chunkId) ?? null;
                              if (!block) setInspectionError("该资料区块已更新或当前没有读取权限。");
                              setInspectedBlock(block);
                            })
                            .catch(() => setInspectionError("读取资料详情失败，请确认当前账号仍有该资料权限。"))
                            .finally(() => setInspectingEvidence(false));
                        }}
                      >
                        <strong>{compactText(cleanText(item.sourceTitle || scopeLabel(item.sourceScope)), 48)}</strong>
                        <span>
                          {item.sourceScope === "TEACHER_RESOURCE" ? "教师资料命中" : scopeLabel(item.sourceScope)}
                          {item.pageNo > 0 ? ` · 第 ${item.pageNo} 页` : ""}
                          {item.chunkId ? ` · 定位 ${compactText(item.chunkId, 36)}` : ""}
                        </span>
                        <p>{evidenceDisplaySummary(item)}</p>
                        <em>{item.sourceDocumentId ? "点击查看已授权原始命中内容" : "点击查看题库命中内容与来源"}</em>
                      </button>
                    ))}
                  </div>
                  {inspectedEvidence ? (
                    <section className="handout-evidence-inspector" aria-live="polite">
                      <div>
                        <strong>{compactText(cleanText(inspectedEvidence.sourceTitle), 80)}</strong>
                        <button type="button" onClick={() => { setInspectedEvidence(null); setInspectedBlock(null); setInspectionError(""); }}>关闭</button>
                      </div>
                      {inspectingEvidence ? <p>正在按当前账号权限读取资料原文…</p> : null}
                      {inspectionError ? <p>{inspectionError}</p> : null}
                      {inspectedBlock ? <>
                        <span>{inspectedBlock.chapter || "未标章节"}{inspectedBlock.pageNo ? ` · 第 ${inspectedBlock.pageNo} 页` : ""}</span>
                        <p>{inspectedBlock.rawText}</p>
                      </> : null}
                      {!inspectingEvidence && !inspectionError && !inspectedBlock && !inspectedEvidence.sourceDocumentId ? <>
                        <span>{scopeLabel(inspectedEvidence.sourceScope)} · {inspectedEvidence.pageNo ? `第 ${inspectedEvidence.pageNo} 页` : "原子题"}</span>
                        <p>{inspectedEvidence.snippet || "该题库条目未返回可展示正文。"}</p>
                      </> : null}
                    </section>
                  ) : null}
                </div>
              </article>
            ) : null}

            {suggestions.length ? (
              <article className="handout-thread-message subtle">
                <div className="handout-thread-message-index">改</div>
                <div className="handout-thread-message-body">
                  <div className="handout-thread-message-head">
                    <strong>下一轮可继续改</strong>
                  </div>
                  <div className="handout-chip-row">
                    {suggestions.map((item, index) => (
                      <span className="handout-suggestion-chip" key={`${task.taskId}:suggestion:${index}`}>
                        <RichText text={item} />
                      </span>
                    ))}
                  </div>
                </div>
              </article>
            ) : null}
          </div>
        </section>
      ) : null}
    </>
  );
}

/**
 * Produces one user-safe inspection model from the authoritative task snapshot. Evidence selection happens by the
 * immutable node/scope contract so a question-bank card can never display a teacher-private result by accident.
 */
export function buildWorkflowNodeInspection(task: TeachingTaskResponse, nodeCode: string): WorkflowNodeInspection | null {
  const node = task.nodes.find((item) => item.code === nodeCode);
  if (!node) return null;

  const eventIds = WORKFLOW_NODE_EVENT_IDS[node.code] ?? [];
  const events = (task.workflowEvents ?? [])
    .filter((item) => eventIds.includes(item.eventId))
    .map((item) => ({
      eventId: item.eventId,
      eventType: item.eventType,
      status: item.status,
      title: safeInspectorText(item.title, "本节点执行记录"),
      summary: safeInspectorText(item.summary, "当前没有可展示的执行摘要。"),
      sourceName: safeInspectorText(item.sourceName, "系统"),
    }));
  const scope = WORKFLOW_NODE_EVIDENCE_SCOPE[node.code];
  const evidence = scope
    ? task.evidence.filter((item) => item.sourceScope === scope)
    : node.code === "REACT_SOLVE" || node.code === "AI_DRAFT"
      ? task.evidence
      : [];
  const sourceRefs = node.code === "AI_DRAFT"
    ? (task.draftSections?.sourceRefs ?? []).map((item) => safeInspectorText(item, "")).filter(Boolean)
    : [];
  const reviewFindings = node.code === "HUMAN_FEEDBACK"
    ? (task.draftReview?.findings ?? []).map((item) => ({
      severity: safeInspectorText(item.severity, "待核对"),
      sectionCode: safeInspectorText(item.sectionCode, "讲义"),
      summary: safeInspectorText(item.summary, "当前没有可展示的审校结论。"),
    }))
    : [];

  return {
    node,
    events,
    evidence,
    templateName: "",
    sourceRefs,
    reviewFindings,
    versions: [
      { label: "教师版", ready: Boolean(task.teacherHandoutLatex?.trim() || task.handoutLatex?.trim()) },
      { label: "学生版", ready: Boolean(task.studentHandoutLatex?.trim()) },
      { label: "16:10 讲解版", ready: Boolean(task.lectureHandoutLatex?.trim()) },
    ],
  };
}

/** Renders only summaries and filtered snippets; raw model output and internal review instructions never enter this panel. */
function WorkflowNodeInspector({
  inspection,
  inspectorId,
  stageTimings,
}: {
  inspection: WorkflowNodeInspection;
  inspectorId: string;
  stageTimings: TeachingStageTiming[];
}) {
  const elapsed = stageElapsedForNode(inspection.node.code, stageTimings);
  const shouldShowVersionRecord = inspection.node.code === "AI_DRAFT" || inspection.node.code === "LATEX_HANDOUT";
  return (
    <section className="handout-node-inspector" id={inspectorId} aria-label={`${inspection.node.name}详情`}>
      <div className="handout-node-inspector-head">
        <div>
          <span>节点详情</span>
          <strong>{inspection.node.name}</strong>
        </div>
        <div className="handout-node-inspector-meta">
          <span>{statusLabel(inspection.node.status)}</span>
          {elapsed ? <em>实际耗时 {elapsed}</em> : null}
        </div>
      </div>

      <div className="handout-node-inspector-section">
        <strong>本次结果</strong>
        <p><RichText text={stageSummaryText(inspection.node.summary)} /></p>
      </div>

      <div className="handout-node-inspector-section">
        <strong>执行记录</strong>
        {inspection.events.length ? inspection.events.map((event) => (
          <article className="handout-node-event" key={event.eventId}>
            <div>
              <strong>{event.title}</strong>
              <span>{workflowProducerLabel(event.eventType, event.sourceName)} · {statusLabel(event.status)}</span>
            </div>
            <p><RichText text={event.summary} /></p>
          </article>
        )) : <p>当前节点没有单独的事件记录，已保留上方真实执行结果。</p>}
      </div>

      {inspection.evidence.length ? (
        <div className="handout-node-inspector-section">
          <strong>命中资料</strong>
          <div className="handout-node-evidence-list">
            {inspection.evidence.map((item) => (
              <article className="handout-node-evidence" key={`${item.sourceScope}:${item.chunkId}`}>
                <div>
                  <strong>{safeInspectorText(item.sourceTitle || scopeLabel(item.sourceScope), "未命名资料")}</strong>
                  <span>
                    {scopeLabel(item.sourceScope)}
                    {item.pageNo > 0 ? ` · 第 ${item.pageNo} 页` : " · 页码未记录"}
                    {item.chunkId ? ` · 区块 ${safeInspectorText(item.chunkId, "未记录")}` : ""}
                  </span>
                </div>
                <p><b>具体命中内容：</b><RichText text={safeInspectorText(item.snippet, "该资料未返回可展示的正文片段。")} /></p>
              </article>
            ))}
          </div>
        </div>
      ) : null}

      {inspection.sourceRefs.length ? (
        <div className="handout-node-inspector-section">
          <strong>已纳入的资料标识</strong>
          <div className="handout-node-reference-list">
            {inspection.sourceRefs.map((sourceRef) => <code key={sourceRef}>{sourceRef}</code>)}
          </div>
        </div>
      ) : null}

      {shouldShowVersionRecord ? (
        <div className="handout-node-inspector-section">
          <strong>版本产物</strong>
          <div className="handout-node-version-list">
            {inspection.versions.map((version) => <span key={version.label}>{version.label}：{version.ready ? "已生成" : "未生成"}</span>)}
          </div>
        </div>
      ) : null}

      {inspection.reviewFindings.length ? (
        <div className="handout-node-inspector-section">
          <strong>审校记录</strong>
          {inspection.reviewFindings.map((finding, index) => (
            <article className="handout-node-event" key={`${finding.sectionCode}:${index}`}>
              <div><strong>{finding.sectionCode}</strong><span>{finding.severity}</span></div>
              <p><RichText text={finding.summary} /></p>
            </article>
          ))}
        </div>
      ) : null}
    </section>
  );
}

function buildAiOutline(task: TeachingTaskResponse, version: TeachingHandoutVersion) {
  const source = version === "student"
    ? task.aiDraft?.studentHint ?? ""
    : version === "lecture"
      ? task.lectureHandoutLatex ?? task.teacherHandoutLatex ?? ""
      : task.aiDraft?.teacherExplanation ?? "";
  const matches = [...source.matchAll(/【([^】]{2,18})】([^【]+)/g)];
  if (!matches.length) {
    const fallback = cleanText(source || task.learningGoal || task.questionText);
    return fallback ? [{ title: version === "student" ? "学习提示" : "讲解主线", summary: compactText(fallback, 140) }] : [];
  }
  return matches.slice(0, 5).map((match, index) => ({
    title: compactText(match[1] || `要点 ${index + 1}`, 24),
    summary: compactText(cleanText(match[2] || ""), 140),
  })).filter((item) => item.summary);
}

function handoutDraftForVersion(task: TeachingTaskResponse, version: TeachingHandoutVersion) {
  if (version === "lecture") {
    return task.lectureHandoutLatex ?? "";
  }
  if (version === "student") {
    return task.studentHandoutLatex ?? "";
  }
  return task.teacherHandoutLatex ?? task.handoutLatex ?? "";
}

function handoutVersionLabel(version: TeachingHandoutVersion) {
  if (version === "lecture") return "16:10 讲解版";
  if (version === "student") return "学生版";
  return "教师版";
}

function handoutVersionSummary(task: TeachingTaskResponse) {
  const versions = [
    task.teacherHandoutLatex ? "教师" : "",
    task.studentHandoutLatex ? "学生" : "",
    task.lectureHandoutLatex ? "讲解" : "",
  ].filter(Boolean);
  return versions.length ? versions.join("/") : "草稿";
}

function MetricCard({ label, value }: { label: string; value: string }) {
  return <div className="handout-metric-card"><span>{label}</span><strong>{value}</strong></div>;
}

function RichText({ text }: { text: string }) {
  return (
    <>
      {(text || "").split(/\n+/).filter(Boolean).map((line, lineIndex) => (
        <span className="teaching-rich-line" key={`line-${lineIndex}`}>
          {splitMathText(line).map((segment) => {
            if (!segment.math) return <span key={segment.key}>{segment.text}</span>;
            const expression = normalizeLatex(segment.text);
            if (hasUnbalancedBraces(expression)) return null;
            const html = katex.renderToString(expression, { displayMode: segment.display, throwOnError: false, strict: false, trust: false });
            return <span className={`math-render ${segment.display ? "display" : "inline"}`} dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
          })}
        </span>
      ))}
    </>
  );
}

function splitMathText(text: string) {
  const segments: Array<{ key: string; text: string; math: boolean; display: boolean }> = [];
  let index = 0;
  let key = 0;
  const pattern = /(\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]|\$[^$]+?\$|\\\([^)]+?\\\)|[A-Za-z0-9|()[\]{}_^+\-=<>.,\s]*\\(?:frac|sqrt|cdot|times|div|leq?|geq?|neq|pm|mp|sin|cos|tan|theta|alpha|beta|gamma|Delta|pi|angle|overline|vec|left|right|infty|circ)[A-Za-z0-9|()[\]{}_^+\-=<>.,\\\s]*|[A-Za-z0-9][A-Za-z0-9(){}_^+\-*/\\\s]*[\/^][A-Za-z0-9(){}_^+\-*/\\\s]*=[A-Za-z0-9(){}_^+\-*/\\\s]+)/g;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > index) segments.push({ key: `text-${key++}`, text: text.slice(index, start), math: false, display: false });
    const raw = match[0];
    const display = raw.startsWith("$$") || raw.startsWith("\\[");
    const expression = raw.replace(/^\$\$|\$\$$/g, "").replace(/^\\\[|\\\]$/g, "").replace(/^\$|\$$/g, "").replace(/^\\\(|\\\)$/g, "").trim();
    if (expression) segments.push({ key: `math-${key++}`, text: expression, math: true, display });
    index = start + raw.length;
  }
  if (index < text.length) segments.push({ key: `text-${key++}`, text: text.slice(index), math: false, display: false });
  return segments.length ? segments : [{ key: "text-0", text, math: false, display: false }];
}

function normalizeLatex(value: string) {
  return normalizeAsciiFractions(value.replace(/\\\\(?=[A-Za-z])/g, "\\"));
}

function normalizeAsciiFractions(value: string) {
  return value.replace(
    /([A-Za-z](?:\^\{?\d+\}?)?|\d+(?:\.\d+)?)\s*\/\s*([A-Za-z](?:\^\{?\d+\}?)?|\d+(?:\.\d+)?)/g,
    "\\frac{$1}{$2}",
  );
}

function hasUnbalancedBraces(value: string) {
  let depth = 0;
  for (const char of value) {
    if (char === "{") depth += 1;
    if (char === "}") depth -= 1;
    if (depth < 0) return true;
  }
  return depth !== 0;
}

function displayTaskTitle(task: TeachingTaskResponse) {
  return compactText(cleanText(task.learningGoal || task.questionText || task.aiDraft?.studentHint || task.aiDraft?.teacherExplanation || `讲义任务 ${task.taskId.slice(0, 8)}`), 34);
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    CREATED: "已创建",
    RUNNING: "生成中",
    COMPLETED: "已完成",
    FAILED: "失败",
    PENDING: "等待中",
    completed: "已完成",
    running: "生成中",
    pending: "等待中",
    failed: "失败",
  };
  return labels[status] ?? status;
}

function statusToneClass(status: string) {
  const normalized = status.toUpperCase();
  if (normalized === "FAILED") return "failed";
  if (normalized === "RUNNING" || normalized === "CREATED") return "running";
  return "completed";
}

function nodeToneClass(status: string) {
  const normalized = status.toUpperCase();
  if (normalized === "FAILED") return "failed";
  if (normalized === "RUNNING") return "running";
  if (normalized === "PENDING" || normalized === "CREATED") return "pending";
  return "completed";
}

function normalizeStageKey(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function stageElapsedForNode(nodeCode: string, stageTimings: TeachingStageTiming[]) {
  const key = normalizeStageKey(nodeCode);
  const direct = stageTimings.find((item) => normalizeStageKey(item.stage) === key);
  if (direct) return formatElapsed(direct.elapsedMs);
  const fuzzy = stageTimings.find((item) => {
    const stageKey = normalizeStageKey(item.stage);
    return stageKey.includes(key) || key.includes(stageKey);
  });
  return fuzzy ? formatElapsed(fuzzy.elapsedMs) : "";
}

function formatElapsed(value: number) {
  if (value >= 1000) return value >= 10_000 ? `${Math.round(value / 1000)} 秒` : `${(value / 1000).toFixed(1)} 秒`;
  return `${value} ms`;
}

function scopeLabel(scope: string) {
  const labels: Record<string, string> = {
    PUBLIC_TEXTBOOK: "公开教材",
    QUESTION_BANK: "题库",
    TEACHER_RESOURCE: "教师资料",
    TEACHER_PRIVATE: "教师资料",
    MATH_VIP: "教研共享",
  };
  return labels[scope] ?? scope;
}

function cleanText(value: string | undefined) {
  return (value ?? "").replace(/!\[[^\]]*]\([^)]*\)/g, " ").replace(/[#*_`>$]/g, " ").replace(/\s+/g, " ").trim();
}

function stageSummaryText(value: string | undefined) {
  const text = cleanText(value);
  if (!text || isInternalDisplayText(text)) return "阶段已完成，结果已经整理到当前讲义。";
  return compactText(text, 120);
}

/** Applies the same prompt/debug guard to expandable records and keeps long source snippets readable. */
function safeInspectorText(value: string | undefined, fallback: string) {
  const text = cleanText(value);
  if (!text || isInternalDisplayText(text)) return fallback;
  return compactText(text, WORKFLOW_INSPECTOR_TEXT_LIMIT);
}

/** Converts execution categories to user-facing labels without exposing internal agent class names as primary copy. */
function workflowProducerLabel(eventType: string, sourceName: string) {
  const labels: Record<string, string> = {
    plan: "任务规划",
    evidence: "资料检索",
    outline: "大纲编排",
    generation: "讲义生成",
    render: "讲义排版",
    review: "人工审校",
  };
  return labels[eventType] ?? sourceName;
}

function evidenceDisplaySummary(item: TeachingTaskResponse["evidence"][number]) {
  const source = scopeLabel(item.sourceScope);
  const page = item.pageNo > 0 ? `第 ${item.pageNo} 页` : "页码未记录";
  const role = item.sourceScope === "QUESTION_BANK"
    ? "补充题型"
    : item.sourceScope === "PUBLIC_TEXTBOOK"
      ? "校准知识点"
      : "补充教师资料";
  return `${source} · ${page} · ${role}`;
}

function isInternalDisplayText(value: string) {
  return /MODEL_CALL|JSON_PARSE|promptTokens|completionTokens|tokens=|debug|系统提示|提示词|PDF\s*版式|页眉|页脚|documentclass|usepackage/i.test(value);
}

function formatThreadTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
}
