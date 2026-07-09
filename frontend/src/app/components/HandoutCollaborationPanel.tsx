import katex from "katex";
import { FormEvent, useEffect, useMemo, useRef } from "react";
import { BookOpen, Download, Eye, FileText, Loader2, Sparkles } from "lucide-react";
import { TeachingHandoutVersion, TeachingStageTiming, TeachingTaskResponse } from "../../shared/api/textbookApi";
import { compactText } from "./panelShared";

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
  selectedTemplateName,
  currentTaskId,
  version,
  entries,
  history,
  loading,
  loadingHistory,
  error,
  onLearningGoalChange,
  onQuestionTextChange,
  onEvidenceLimitChange,
  onSubmit,
  onSelectHistory,
  onPreviewPdf,
  onPreviewLatex,
  onExportPdf,
}: {
  learningGoal: string;
  questionText: string;
  evidenceLimit: number;
  selectedTemplateName: string;
  currentTaskId: string;
  version: TeachingHandoutVersion;
  entries: HandoutCollaborationThreadItem[];
  history: TeachingTaskResponse[];
  loading: boolean;
  loadingHistory: boolean;
  error: string;
  onLearningGoalChange: (value: string) => void;
  onQuestionTextChange: (value: string) => void;
  onEvidenceLimitChange: (value: number) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onSelectHistory: (task: TeachingTaskResponse) => void;
  onPreviewPdf: (task: TeachingTaskResponse) => void;
  onPreviewLatex: (task: TeachingTaskResponse) => void;
  onExportPdf: (task: TeachingTaskResponse) => void;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const latestHistory = useMemo(() => buildVisibleHistory(history, currentTaskId), [history, currentTaskId]);
  const hasEntries = entries.length > 0;
  const submitLabel = loading ? "生成中" : hasEntries ? "继续生成" : "生成讲义";

  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
  }, [entries, loading]);

  return (
    <section className="teaching-live-shell handout-live-shell" aria-label="讲义协作">
      <header className="teaching-live-header handout-live-header">
        <div className="teaching-live-brand">
          <div className="teaching-live-brand-icon handout-live-brand-icon">
            <BookOpen size={16} />
          </div>
          <div className="teaching-live-brand-copy">
            <strong>讲义协作</strong>
            <span>先确定主题，再生成教师版、学生版和 16:10 讲解版。</span>
          </div>
        </div>
        <div className="teaching-live-toolbar handout-live-toolbar">
          <span className="teaching-history-chip handout-template-chip">{selectedTemplateName}</span>
          <span className="teaching-history-chip handout-version-chip">{handoutVersionLabel(version)}</span>
        </div>
      </header>

      <form className="handout-brief-form" onSubmit={onSubmit}>
        <div className="handout-brief-grid">
          <label className="handout-brief-field">
            <span>讲义主题</span>
            <input
              className="handout-composer-input"
              value={learningGoal}
              onChange={(event) => onLearningGoalChange(event.target.value)}
              placeholder="例如：双曲线专题讲解 / 反比例函数基础练习"
            />
          </label>
          <label className="handout-brief-field">
            <span>证据条数</span>
            <input
              type="number"
              min={3}
              max={12}
              value={evidenceLimit}
              onChange={(event) => onEvidenceLimitChange(Number(event.target.value))}
            />
          </label>
        </div>
        <label className="handout-brief-field full">
          <span>补充要求</span>
          <textarea
            value={questionText}
            onChange={(event) => onQuestionTextChange(event.target.value)}
            placeholder="可选：指定课堂风格、难度、题型范围，或说明只做学生留白版。"
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

      {latestHistory.length ? (
        <section className="handout-history-strip-panel" aria-label="最近讲义">
          <div className="handout-history-strip-head">
            <strong>最近讲义</strong>
            {loadingHistory ? <span><Loader2 className="spin" size={12} />同步中</span> : <span>点击可继续预览或审查</span>}
          </div>
          <div className="teaching-history-strip handout-history-strip-row">
            {latestHistory.map((item) => (
              <button
                className={`handout-history-chip${item.taskId === currentTaskId ? " active" : ""}`}
                key={item.taskId}
                type="button"
                onClick={() => onSelectHistory(item)}
              >
                <span>{displayTaskTitle(item)}</span>
                <em>{statusLabel(item.status)}</em>
              </button>
            ))}
          </div>
        </section>
      ) : null}

      <div className="teaching-live-scroll handout-live-scroll" ref={scrollRef}>
        {!entries.length ? (
          <section className="teaching-empty-state handout-empty-state">
            <div className="teaching-empty-badge">
              <Sparkles size={16} />
              <span>新建讲义</span>
            </div>
            <h2>输入主题后开始生成讲义</h2>
            <p>支持教师讲义、学生留白讲义、真实 PDF 预览和后续人工审查。</p>
          </section>
        ) : null}

        {entries.map((entry) => entry.role === "user" ? (
          <article className="teaching-user-row" key={entry.id}>
            <div className="teaching-user-bubble handout-user-bubble">
              <strong>{entry.learningGoal}</strong>
              {entry.questionText ? <p>{entry.questionText}</p> : null}
              <div className="handout-user-meta">
                <span>{entry.templateName || "标准讲义模板"}</span>
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
                <LoadingTaskCard selectedTemplateName={selectedTemplateName} />
              ) : entry.error ? (
                <section className="teaching-status-card error">
                  <div className="teaching-status-head">
                    <strong>本次讲义任务未完成</strong>
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

function LoadingTaskCard({ selectedTemplateName }: { selectedTemplateName: string }) {
  const steps = [
    "确认主题",
    "检索教材与题库",
    "调用模型生成草稿",
    "整理教师版、学生版和讲解版",
    "准备预览与下载",
  ];
  return (
    <section className="teaching-status-card pending handout-status-card">
      <div className="teaching-status-head">
        <strong>正在编排讲义</strong>
        <span><Loader2 className="spin" size={12} />处理中</span>
      </div>
      <div className="handout-status-summary">
        <span>当前模板</span>
        <strong>{selectedTemplateName}</strong>
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
}: {
  task: TeachingTaskResponse;
  version: TeachingHandoutVersion;
  onPreviewPdf: (task: TeachingTaskResponse) => void;
  onPreviewLatex: (task: TeachingTaskResponse) => void;
  onExportPdf: (task: TeachingTaskResponse) => void;
}) {
  const selectedDraft = handoutDraftForVersion(task, version);
  const taskCompleted = task.status === "COMPLETED";
  const modelLine = task.aiDraft?.enabled ? `${providerLabel(task.aiDraft.providerName)} / ${task.aiDraft.modelCode}` : "模板生成";
  const stageTimings = task.stageTimings ?? [];
  const evidencePreview = task.evidence.slice(0, 3);
  const suggestions = (task.aiDraft?.followUpQuestions?.length ? task.aiDraft.followUpQuestions : task.interactiveSuggestions).slice(0, 4);
  const outline = buildAiOutline(task, version);

  return (
    <>
      <section className="teaching-response-card primary handout-task-card">
        <div className="teaching-response-head">
          <div>
            <strong>{displayTaskTitle(task)}</strong>
            <span className="teaching-response-mode">{task.selectedTemplate?.displayName || "标准讲义模板"}</span>
          </div>
          <span className={`handout-status-badge ${statusToneClass(task.status)}`}>{statusLabel(task.status)}</span>
        </div>
        <div className="handout-task-metrics">
          <MetricCard label="当前版本" value={handoutVersionLabel(version)} />
          <MetricCard label="当前模型" value={modelLine} />
          <MetricCard label="命中来源" value={`${task.evidence.length} 条`} />
          <MetricCard label="Token" value={`${task.aiDraft?.totalTokens ?? 0}`} />
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
          <button className="btn btn-primary btn-sm" type="button" onClick={() => onPreviewPdf(task)} disabled={!selectedDraft}>
            <Eye size={15} />
            <span>{taskCompleted ? "查看 PDF" : "预览草稿"}</span>
          </button>
          <button className="btn btn-secondary btn-sm" type="button" onClick={() => onPreviewLatex(task)} disabled={!selectedDraft}>
            <FileText size={15} />
            <span>审查结构</span>
          </button>
          <button className="btn btn-secondary btn-sm" type="button" onClick={() => onExportPdf(task)} disabled={!taskCompleted || !selectedDraft}>
            <Download size={15} />
            <span>下载 PDF</span>
          </button>
        </div>
      </section>

      <section className="teaching-response-card handout-thread-card">
        <div className="teaching-response-head">
          <div>
            <strong>生成过程</strong>
            <span className="teaching-response-mode">按真实后端阶段展示，不暴露提示词和调试输出</span>
          </div>
        </div>
        <div className="handout-thread-feed">
          {task.nodes.map((node, index) => (
            <article className={`handout-thread-message ${nodeToneClass(node.status)}`} key={`${task.taskId}:${node.code}:${index}`}>
              <div className="handout-thread-message-index">{String(index + 1).padStart(2, "0")}</div>
              <div className="handout-thread-message-body">
                <div className="handout-thread-message-head">
                  <strong>{node.name}</strong>
                  <div className="handout-thread-message-meta">
                    <span>{statusLabel(node.status)}</span>
                    {stageElapsedForNode(node.code, stageTimings) ? <em>{stageElapsedForNode(node.code, stageTimings)}</em> : null}
                  </div>
                </div>
                <p><RichText text={stageSummaryText(node.summary)} /></p>
              </div>
            </article>
          ))}

          {evidencePreview.length ? (
            <article className="handout-thread-message subtle">
              <div className="handout-thread-message-index">源</div>
              <div className="handout-thread-message-body">
                <div className="handout-thread-message-head">
                  <strong>命中来源</strong>
                  <div className="handout-thread-message-meta">
                    <span>{task.evidence.length} 条可追溯证据</span>
                  </div>
                </div>
                <div className="handout-thread-source-list">
                  {evidencePreview.map((item) => (
                    <div className="handout-thread-source-item" key={`${task.taskId}:${item.chunkId}`}>
                      <strong>{compactText(cleanText(item.sourceTitle || scopeLabel(item.sourceScope)), 48)}</strong>
                      <span>{scopeLabel(item.sourceScope)}{item.pageNo > 0 ? ` · 第 ${item.pageNo} 页` : ""}</span>
                      <p>{evidenceDisplaySummary(item)}</p>
                    </div>
                  ))}
                </div>
              </div>
            </article>
          ) : null}

          {suggestions.length ? (
            <article className="handout-thread-message subtle">
              <div className="handout-thread-message-index">问</div>
              <div className="handout-thread-message-body">
                <div className="handout-thread-message-head">
                  <strong>下一轮可补充</strong>
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
    </>
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
    return task.lectureHandoutLatex ?? task.teacherHandoutLatex ?? task.handoutLatex ?? "";
  }
  if (version === "student") {
    return task.studentHandoutLatex ?? task.handoutLatex ?? "";
  }
  return task.teacherHandoutLatex ?? task.handoutLatex ?? "";
}

function handoutVersionLabel(version: TeachingHandoutVersion) {
  if (version === "lecture") return "16:10 讲解版";
  if (version === "student") return "学生版";
  return "教师版";
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

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    openai: "OpenAI",
    dashscope: "通义千问",
    deepseek: "DeepSeek",
    ark: "火山方舟",
    local: "本地模型",
  };
  return labels[provider] ?? provider;
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
  if (!text || isInternalDisplayText(text)) return "阶段已完成，结果已整理到讲义预览与审查入口。";
  return compactText(text, 120);
}

function evidenceDisplaySummary(item: TeachingTaskResponse["evidence"][number]) {
  const source = scopeLabel(item.sourceScope);
  const page = item.pageNo > 0 ? `第 ${item.pageNo} 页` : "页码未记录";
  const role = item.sourceScope === "QUESTION_BANK"
    ? "用于补充练习题型"
    : item.sourceScope === "PUBLIC_TEXTBOOK"
      ? "用于校准知识点表述"
      : "用于补充教师资料";
  return `${source} · ${page} · ${role}`;
}

function isInternalDisplayText(value: string) {
  return /MODEL_CALL|JSON_PARSE|promptTokens|completionTokens|tokens=|debug|系统提示|提示词|PDF\s*版式|页眉|页脚|documentclass|usepackage/i.test(value);
}

function buildVisibleHistory(history: TeachingTaskResponse[], currentTaskId: string) {
  const seen = new Set<string>();
  return history
    .filter((item) => {
      if (!isDisplayableHistoryTask(item) || seen.has(item.taskId)) return false;
      seen.add(item.taskId);
      return true;
    })
    .slice(0, 6)
    .sort((a, b) => (a.taskId === currentTaskId ? -1 : b.taskId === currentTaskId ? 1 : 0));
}

// 历史区不能直接回显旧坏数据，否则会把乱码、空讲义和离题内容重新带回当前工作区。
function isDisplayableHistoryTask(task: TeachingTaskResponse) {
  if (!task.taskId) return false;
  if ((task.status || "").toUpperCase() !== "COMPLETED") return false;
  const title = cleanText(task.learningGoal || task.questionText || "");
  if (!title || looksCorrupted(title)) return false;
  const body = cleanText(task.teacherHandoutLatex || task.studentHandoutLatex || "");
  if (body.length < 18 || looksCorrupted(body)) return false;
  return !containsProtocolLeak(`${title} ${body}`);
}

function containsProtocolLeak(value: string) {
  const lower = value.toLowerCase().replace(/[\s_-]+/g, "");
  return (
    lower.includes("capability")
    || lower.includes("requesthash")
    || lower.includes("idempotencykey")
    || lower.includes("modelcall")
    || lower.includes("jsonparse")
    || lower.includes("apiaccess")
    || lower.includes("subjecttype")
    || lower.includes("bearer")
    || lower.includes("mcp")
    || lower.includes("安全探针")
    || lower.includes("不做题目生成")
    || lower.includes("模型健康")
    || lower.includes("调试信息")
  );
}

function looksCorrupted(value: string) {
  const normalized = value.replace(/\s+/g, "");
  if (!normalized) return false;
  if (normalized.includes("???") || normalized.includes("？？？") || normalized.includes("�")) return true;
  const questionCount = [...normalized].filter((char) => char === "?").length;
  if (questionCount >= 3 && questionCount * 2 >= normalized.length) return true;
  return false;
}

function formatThreadTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(date);
}
