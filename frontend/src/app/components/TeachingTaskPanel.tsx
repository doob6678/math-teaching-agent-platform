import { FormEvent, useEffect, useRef, useState } from "react";
import katex from "katex";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.mjs?url";
import {
  AlertCircle,
  BookOpen,
  Check,
  ArrowLeft,
  ArrowRight,
  Clock,
  Database,
  Download,
  Eye,
  FileText,
  Loader2,
  ShieldCheck,
} from "lucide-react";
import { TeachingHandoutPdfResponse, TeachingHumanFeedbackResponse, TeachingTaskResponse } from "../../shared/api/textbookApi";
import { formatSimilarity, stageLabel, StatusLine } from "./panelShared";

type HandoutVersion = "teacher" | "student";

type ReviewBlock =
  | { type: "section"; title: string }
  | { type: "subsection"; title: string }
  | { type: "paragraph"; title: string }
  | { type: "text"; text: string }
  | { type: "list"; ordered: boolean; items: string[] }
  | { type: "space" };

type ReviewCheck = {
  label: string;
  value: string;
  state: "good" | "warning";
};

type FeedbackQualityItem = {
  label: string;
  value: string;
  state: "good" | "warning";
};

type WorkflowConversationGroup = {
  title: string;
  summary: string;
  nodes: TeachingTaskResponse["nodes"];
};

type DraftOutlineItem = {
  title: string;
  summary: string;
  audience: "teacher" | "student";
};

export function TeachingTaskPanel({
  task,
  loading,
  error,
  history,
  loadingHistory,
  loadingHistoryTaskId,
  version,
  previewLatex,
  previewPdfUrl,
  previewPdfBytes,
  previewPdfMeta,
  action,
  exportMessage,
  feedbackRating,
  feedbackDecision,
  feedbackComment,
  submittingFeedback,
  feedbackMessage,
  feedbackHistory,
  loadingFeedbackHistory,
  batchFolderPath,
  onVersionChange,
  onBatchFolderPathChange,
  onPreviewLatex,
  onPreviewPdf,
  onExportLatex,
  onExportPdf,
  onExportBatchZip,
  onSelectHistory,
  onFeedbackRatingChange,
  onFeedbackDecisionChange,
  onFeedbackCommentChange,
  onSubmitFeedback,
}: {
  task: TeachingTaskResponse | null;
  loading: boolean;
  error: string;
  history: TeachingTaskResponse[];
  loadingHistory: boolean;
  loadingHistoryTaskId: string;
  version: HandoutVersion;
  previewLatex: string;
  previewPdfUrl: string;
  previewPdfBytes: Uint8Array | null;
  previewPdfMeta: TeachingHandoutPdfResponse | null;
  action: string;
  exportMessage: string;
  feedbackRating: number;
  feedbackDecision: string;
  feedbackComment: string;
  submittingFeedback: boolean;
  feedbackMessage: string;
  feedbackHistory: TeachingHumanFeedbackResponse[];
  loadingFeedbackHistory: boolean;
  batchFolderPath: string;
  onVersionChange: (value: HandoutVersion) => void;
  onBatchFolderPathChange: (value: string) => void;
  onPreviewLatex: () => void;
  onPreviewPdf: () => void;
  onExportLatex: () => void;
  onExportPdf: () => void;
  onExportBatchZip: () => void;
  onSelectHistory: (task: TeachingTaskResponse) => void;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const busy = Boolean(action);
  const normalizedStatus = task?.status?.toUpperCase() ?? "";
  const selectedDraft = version === "student"
    ? task?.studentHandoutLatex ?? task?.handoutLatex ?? ""
    : task?.teacherHandoutLatex ?? task?.handoutLatex ?? "";
  const completed = normalizedStatus === "COMPLETED";
  const pending = normalizedStatus === "CREATED" || normalizedStatus === "RUNNING";
  const feedbackQualityItems = task
    ? buildFeedbackQualityItems({
      latex: selectedDraft,
      version,
      pdfMeta: previewPdfMeta,
      pdfBytes: previewPdfBytes,
      evidenceCount: task.evidence.length,
    })
    : [];

  return (
    <section className="teaching-task">
      <div className="result-header">
        <div>
          <p className="eyebrow">讲义中心</p>
          <h2>讲义生成、审查与导出</h2>
        </div>
        {task ? <div className="strategy-pill">{statusLabel(task.status)}</div> : null}
      </div>

      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在恢复上次教学任务。" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {pending ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="讲义仍在生成中，页面会自动刷新；生成完成后会自动打开 PDF 预览。" /> : null}
      {completed ? <StatusLine icon={<Check size={16} />} text="讲义已生成。优先查看 PDF，TeX 仅用于人工复核。" /> : null}

      <HistoryPanel
        history={history}
        currentTaskId={task?.taskId}
        loading={loadingHistory}
        loadingTaskId={loadingHistoryTaskId}
        onSelectHistory={onSelectHistory}
      />

      {!task && !loading && !error ? (
        <div className="empty-state compact">提交讲义主题后，这里会显示预览、下载、历史记录和人工复核入口。</div>
      ) : null}

      {task ? (
        <div className="teaching-grid">
          <section className="handout-primary-panel">
            <div className="handout-primary-head">
              <div>
                <p className="eyebrow">当前讲义</p>
                <h3>{displayTaskTitle(task)}</h3>
                <span>{task.taskId}</span>
              </div>
              <label>
                <span>版本</span>
                <select className="form-select" value={version} onChange={(event) => onVersionChange(event.target.value as HandoutVersion)}>
                  <option value="teacher">教师版</option>
                  <option value="student">学生版</option>
                </select>
              </label>
            </div>

            <div className="handout-toolbar primary">
              <button type="button" className="btn btn-primary" onClick={onExportPdf} disabled={busy || !selectedDraft}>
                {action === "pdf" ? <Loader2 className="spin" size={16} /> : <Download size={16} />}
                <span>下载 PDF</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onPreviewPdf} disabled={busy || !selectedDraft}>
                {action === "preview-pdf" ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
                <span>预览 PDF</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onPreviewLatex} disabled={busy || !selectedDraft}>
                {action === "preview" ? <Loader2 className="spin" size={16} /> : <BookOpen size={16} />}
                <span>审查 TeX</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onExportLatex} disabled={busy || !selectedDraft}>
                {action === "latex" ? <Loader2 className="spin" size={16} /> : <FileText size={16} />}
                <span>下载 TeX</span>
              </button>
            </div>

            <div className="batch-export-row">
              <label>
                <span>ZIP 内文件夹</span>
                <input
                  value={batchFolderPath}
                  onChange={(event) => onBatchFolderPathChange(event.target.value)}
                  placeholder={`handouts/${task.taskId}`}
                />
              </label>
              <button type="button" className="btn btn-secondary" onClick={onExportBatchZip} disabled={busy || !selectedDraft}>
                {action === "zip" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
                <span>打包导出</span>
              </button>
            </div>

            {action ? (
              <div className="handout-operation-status" role="status" aria-live="polite">
                <Loader2 className="spin" size={16} />
                <div>
                  <strong>{handoutActionTitle(action)}</strong>
                  <span>{handoutActionDescription(action, version)}</span>
                </div>
              </div>
            ) : null}

            {exportMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={exportMessage} /> : null}

            {selectedDraft ? <HandoutReviewChecks latex={selectedDraft} version={version} /> : null}

            {previewPdfUrl ? (
              <PdfCanvasPreview pdfBytes={previewPdfBytes} pdfUrl={previewPdfUrl} meta={previewPdfMeta} />
            ) : selectedDraft ? (
              <HandoutStructuredPreview latex={selectedDraft} version={version} />
            ) : (
              <div className="handout-preview-placeholder">
                <FileText size={22} />
                <strong>{pending ? "正在等待讲义完成" : "当前版本暂无讲义内容"}</strong>
                <span>
                  {pending
                    ? "后端生成结束后会自动拉取可审查内容，并优先显示 PDF。"
                    : "请重新生成讲义，或切换到已有内容的版本。"}
                </span>
              </div>
            )}

            {previewLatex ? (
              <details className="latex-review-panel">
                <summary>查看 TeX 源码</summary>
                <pre className="formula-block handout preview">{previewLatex}</pre>
              </details>
            ) : null}
          </section>

          <section className="teaching-summary-grid">
            <InfoTile label="模板" value={task.selectedTemplate?.displayName ?? "标准讲义"} />
            <InfoTile label="内容来源" value={task.aiDraft?.enabled ? "检索 + 模型生成" : "检索 + 模板生成"} />
            <InfoTile label="当前状态" value={statusLabel(task.status)} />
            <InfoTile label="记忆复用" value={task.memoryReuse?.reused ? "已复用" : "未复用"} />
          </section>

          <GenerationReviewPanel task={task} />

          <form className="human-feedback-panel" onSubmit={onSubmitFeedback}>
            <div className="feedback-head">
              <strong>人工反馈</strong>
              {feedbackMessage ? <span>{feedbackMessage}</span> : null}
            </div>
            <div className="feedback-quality-list" aria-label="讲义质量审查要点">
              {feedbackQualityItems.map((item) => (
                <span className={`feedback-quality-item ${item.state}`} key={item.label}>
                  {item.state === "good" ? <Check size={13} /> : <AlertCircle size={13} />}
                  <strong>{item.label}</strong>
                  <em>{item.value}</em>
                </span>
              ))}
            </div>
            <div className="feedback-grid">
              <label>
                <span>评分</span>
                <input
                  className="form-input"
                  type="number"
                  min={1}
                  max={5}
                  value={feedbackRating}
                  onChange={(event) => onFeedbackRatingChange(Number(event.target.value))}
                />
              </label>
              <label>
                <span>处理结论</span>
                <select className="form-select" value={feedbackDecision} onChange={(event) => onFeedbackDecisionChange(event.target.value)}>
                  <option value="helpful">可用</option>
                  <option value="confusing">不清楚</option>
                  <option value="needs_revision">需要修改</option>
                </select>
              </label>
            </div>
            <label>
              <span>修改意见</span>
              <textarea
                className="form-textarea"
                value={feedbackComment}
                onChange={(event) => onFeedbackCommentChange(event.target.value)}
                placeholder="记录需要保留、重写或补充的部分。"
              />
            </label>
            <button type="submit" className="btn btn-secondary" disabled={submittingFeedback}>
              {submittingFeedback ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>提交反馈</span>
            </button>
          </form>

          <FeedbackHistoryPanel feedback={feedbackHistory} loading={loadingFeedbackHistory} />

          {task.memoryReuse ? (
            <details className="review-details">
              <summary>记忆复用说明</summary>
              <p>{humanMemoryReason(task.memoryReuse.reason)} 相似度：{formatSimilarity(task.memoryReuse.similarity)}</p>
            </details>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function FeedbackHistoryPanel({
  feedback,
  loading,
}: {
  feedback: TeachingHumanFeedbackResponse[];
  loading: boolean;
}) {
  return (
    <section className="feedback-history-panel" aria-label="人工审校历史">
      <div className="feedback-history-head">
        <div>
          <strong>审校记录</strong>
          <span>保留人工判断、PDF 渲染和结构检查，后续可用于重新生成。</span>
        </div>
        {loading ? <Loader2 className="spin" size={14} /> : <Clock size={14} />}
      </div>
      {!feedback.length ? (
        <div className="empty-state compact">暂无人工审校记录。</div>
      ) : (
        <div className="feedback-history-list">
          {feedback.slice(0, 5).map((item) => (
            <article className="feedback-history-item" key={item.feedbackId}>
              <div className="feedback-history-title">
                <strong>{decisionLabel(item.decision)}</strong>
                <span>{item.rating} 星 · {formatDateTime(item.createdAt)}</span>
              </div>
              {item.comment ? <p>{shortText(item.comment, 150)}</p> : <p className="muted-line">未填写修改意见。</p>}
              <FeedbackContextSummary context={item.reviewContext} />
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function FeedbackContextSummary({ context }: { context?: Record<string, unknown> }) {
  const checks = isRecord(context?.checks) ? context.checks : {};
  const reviewEvidence = isRecord(context?.reviewEvidence) ? context.reviewEvidence : {};
  const safety = isRecord(reviewEvidence.safety) ? reviewEvidence.safety : {};
  const aiReviewBrief = stringArrayValue(context?.aiReviewBrief).slice(0, 5);
  const handoutVersion = stringValue(context?.handoutVersion);
  const renderer = stringValue(context?.pdfRenderer);
  const pageCount = numberValue(context?.pdfPageCount);
  const pdfPreviewReady = booleanValue(context?.pdfPreviewReady) || booleanValue(checks.pdfPreviewReady);
  const evidenceCount = numberValue(context?.evidenceCount);
  const sourceTraceable = booleanValue(context?.sourceTraceable) || booleanValue(checks.sourceTraceable);
  const matchedCoreColumns = numberValue(checks.matchedCoreColumns);
  const coreColumnTotal = numberValue(checks.coreColumnTotal);
  const hasMath = booleanValue(checks.hasMath);
  const hasWorkspace = booleanValue(checks.hasWorkspace);
  const answerLeak = booleanValue(checks.answerLeak);
  const internalDebugLeak = booleanValue(checks.internalDebugLeak) || booleanValue(safety.internalDebugLeak);
  const layoutRuleLeak = booleanValue(checks.layoutRuleLeak) || booleanValue(safety.layoutRuleLeak);
  const studentAnswerIsolated = context?.handoutVersion === "student"
    ? booleanValue(checks.studentAnswerIsolated) || booleanValue(safety.studentAnswerIsolated)
    : true;
  const teacherAnswerPresent = context?.handoutVersion === "teacher"
    ? booleanValue(checks.teacherAnswerPresent) || booleanValue(safety.teacherAnswerPresent)
    : true;
  const items = [
    handoutVersion ? `版本：${handoutVersion === "student" ? "学生版" : "教师版"}` : "",
    renderer ? `PDF：${pdfRendererLabel(renderer)}${pageCount ? ` · ${pageCount} 页` : ""}` : "",
    pdfPreviewReady ? "PDF已预览" : "",
    evidenceCount ? `来源：${evidenceCount} 条` : (sourceTraceable ? "来源可追溯" : ""),
    coreColumnTotal ? `结构：${matchedCoreColumns}/${coreColumnTotal} 栏` : "",
    hasMath ? "含公式" : "",
    hasWorkspace ? "有作答区" : "",
    internalDebugLeak ? "疑似内部调试词" : "无调试词泄漏",
    layoutRuleLeak ? "疑似版式规则泄漏" : "无版式规则泄漏",
    !studentAnswerIsolated ? "学生版疑似露出答案" : "",
    !teacherAnswerPresent ? "教师版缺少答案" : "",
    answerLeak && handoutVersion === "student" ? "学生版疑似露出答案" : "",
  ].filter(Boolean);
  if (!items.length && !aiReviewBrief.length) {
    return null;
  }
  return (
    <div className="feedback-context-summary">
      {aiReviewBrief.length ? (
        <div className="feedback-review-brief" aria-label="AI 审稿摘要">
          {aiReviewBrief.map((item) => <span key={item}>{item}</span>)}
        </div>
      ) : null}
      {items.length ? (
        <div className="feedback-context-tags">
          {items.map((item) => (
            <span
              className={feedbackContextTagTone(item) === "warning" ? "warning" : ""}
              key={item}
            >
              {item}
            </span>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function feedbackContextTagTone(item: string) {
  return /疑似|缺少|未预览|露出答案/.test(item) ? "warning" : "good";
}

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

function PdfCanvasPreview({
  pdfBytes,
  pdfUrl,
  meta,
}: {
  pdfBytes: Uint8Array | null;
  pdfUrl: string;
  meta: TeachingHandoutPdfResponse | null;
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "failed">("loading");
  const [pageInfo, setPageInfo] = useState("");
  const [pageCount, setPageCount] = useState(0);
  const [currentPage, setCurrentPage] = useState(1);

  useEffect(() => {
    setCurrentPage(1);
    setPageCount(0);
  }, [pdfBytes]);

  useEffect(() => {
    let cancelled = false;
    async function renderPdf() {
      const canvas = canvasRef.current;
      if (!canvas || !pdfBytes?.byteLength) {
        setState(pdfBytes?.byteLength ? "loading" : "failed");
        return;
      }
      setState("loading");
      try {
        const loadingTask = pdfjsLib.getDocument({ data: pdfBytes.slice() });
        const pdf = await loadingTask.promise;
        const totalPages = pdf.numPages;
        const safePage = Math.min(Math.max(1, currentPage), totalPages);
        if (safePage !== currentPage) {
          setCurrentPage(safePage);
          await pdf.cleanup();
          return;
        }
        const page = await pdf.getPage(safePage);
        const baseViewport = page.getViewport({ scale: 1 });
        const containerWidth = Math.min(880, canvas.parentElement?.clientWidth ?? 880);
        const scale = Math.max(1, Math.min(1.6, containerWidth / baseViewport.width));
        const viewport = page.getViewport({ scale });
        const pixelRatio = window.devicePixelRatio || 1;
        canvas.width = Math.floor(viewport.width * pixelRatio);
        canvas.height = Math.floor(viewport.height * pixelRatio);
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        const context = canvas.getContext("2d");
        if (!context) {
          throw new Error("canvas context unavailable");
        }
        context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
        await page.render({ canvas, canvasContext: context, viewport }).promise;
        await pdf.cleanup();
        if (!cancelled) {
          setPageCount(totalPages);
          setPageInfo(`第 ${safePage} 页 / 共 ${totalPages} 页`);
          setState("ready");
        }
      } catch {
        if (!cancelled) {
          setState("failed");
        }
      }
    }
    renderPdf();
    return () => {
      cancelled = true;
    };
  }, [pdfBytes, currentPage]);

  return (
    <div className="pdf-canvas-preview">
      <div className="pdf-canvas-toolbar">
        <div>
          <strong>PDF 真实渲染预览</strong>
          <span>{state === "ready" ? pageInfo : state === "loading" ? "正在渲染首页" : "Canvas 预览不可用"}</span>
          {meta ? (
            <span>
              {pdfRendererLabel(meta.renderer)}
              {meta.pageCount > 0 ? ` · ${meta.pageCount} 页` : ""}
            </span>
          ) : null}
        </div>
        <div className="pdf-canvas-actions">
          <button
            type="button"
            className="icon-button compact"
            onClick={() => setCurrentPage((page) => Math.max(1, page - 1))}
            disabled={state !== "ready" || currentPage <= 1}
            aria-label="上一页"
          >
            <ArrowLeft size={16} />
          </button>
          <button
            type="button"
            className="icon-button compact"
            onClick={() => setCurrentPage((page) => Math.min(pageCount || page, page + 1))}
            disabled={state !== "ready" || pageCount <= 1 || currentPage >= pageCount}
            aria-label="下一页"
          >
            <ArrowRight size={16} />
          </button>
          <a href={pdfUrl} target="_blank" rel="noreferrer">打开原始 PDF</a>
        </div>
      </div>
      <div className={state === "ready" ? "pdf-canvas-page" : "pdf-canvas-page loading"}>
        {state === "loading" ? <Loader2 className="spin" size={18} /> : null}
        {state === "failed" ? (
          <div className="handout-preview-placeholder compact">
            <FileText size={20} />
            <strong>PDF 已生成</strong>
            <span>当前浏览器无法渲染 Canvas 预览，可以打开原始 PDF 或直接下载。</span>
          </div>
        ) : null}
        <canvas ref={canvasRef} aria-label="讲义 PDF 首页预览" />
      </div>
    </div>
  );
}

function HandoutStructuredPreview({ latex, version }: { latex: string; version: HandoutVersion }) {
  const blocks = parseHandoutLatex(latex);
  const title = version === "teacher" ? "教师版讲义审查视图" : "学生版讲义审查视图";

  return (
    <div className="handout-review-card">
      <div className="handout-review-head">
        <div>
          <span>{title}</span>
          <strong>纸面审查视图：未打开 PDF 时先检查结构、公式和留白</strong>
        </div>
        <em>{version === "teacher" ? "教师版" : "学生版"}</em>
      </div>
      <div className="handout-review-body">
        {blocks.map((block, index) => {
          if (block.type === "section") {
            return <h4 className="handout-review-section" key={`section-${index}`}>{block.title}</h4>;
          }
          if (block.type === "subsection") {
            return <h5 className="handout-review-subsection" key={`subsection-${index}`}>{block.title}</h5>;
          }
          if (block.type === "paragraph") {
            return <div className="handout-review-paragraph" key={`paragraph-${index}`}>{block.title}</div>;
          }
          if (block.type === "list") {
            const ListTag = block.ordered ? "ol" : "ul";
            return (
              <ListTag className={block.ordered ? "handout-review-list ordered" : "handout-review-list"} key={`list-${index}`}>
                {block.items.map((item, itemIndex) => (
                  <li key={`item-${index}-${itemIndex}`}><MathRichText text={item} /></li>
                ))}
              </ListTag>
            );
          }
          if (block.type === "space") {
            return <div className="handout-review-space" key={`space-${index}`} />;
          }
          return <p className="handout-review-text" key={`text-${index}`}><MathRichText text={block.text} /></p>;
        })}
      </div>
    </div>
  );
}

function HandoutReviewChecks({ latex, version }: { latex: string; version: HandoutVersion }) {
  const checks = buildHandoutReviewChecks(parseHandoutLatex(latex), latex, version);

  return (
    <div className="handout-review-checks" aria-label="讲义结构审查">
      {checks.map((check) => (
        <div className={`handout-review-check ${check.state}`} key={check.label}>
          <span>{check.label}</span>
          <strong>{check.value}</strong>
        </div>
      ))}
    </div>
  );
}

function buildFeedbackQualityItems({
  latex,
  version,
  pdfMeta,
  pdfBytes,
  evidenceCount,
}: {
  latex: string;
  version: HandoutVersion;
  pdfMeta: TeachingHandoutPdfResponse | null;
  pdfBytes: Uint8Array | null;
  evidenceCount: number;
}): FeedbackQualityItem[] {
  if (!latex.trim()) {
    return [
      { label: "讲义内容", value: "暂无可审查内容", state: "warning" },
      { label: "PDF 预览", value: "等待生成后再检查", state: "warning" },
    ];
  }

  const checks = buildHandoutReviewChecks(parseHandoutLatex(latex), latex, version);
  const structureCheck = checks.find((check) => check.label === "结构");
  const formulaCheck = checks.find((check) => check.label === "公式");
  const versionCheck = checks.find((check) => check.label === "教师解析" || check.label === "学生作答");
  const answerCheck = checks.find((check) => check.label === "答案区分" || check.label === "答案隔离");
  const hasPdfBytes = Boolean(pdfBytes?.byteLength);
  const renderer = pdfMeta?.renderer ?? "";
  const pdfPreviewReady = hasPdfBytes && Boolean(pdfMeta);
  const pdfIsXeLaTeX = renderer === "xelatex";

  return [
    {
      label: "PDF 预览",
      value: pdfPreviewReady
        ? `${pdfRendererLabel(renderer)}${pdfMeta?.pageCount ? ` · ${pdfMeta.pageCount} 页` : ""}`
        : "待打开真实 PDF",
      state: pdfPreviewReady && pdfIsXeLaTeX ? "good" : "warning",
    },
    {
      label: "结构栏目",
      value: structureCheck?.value ?? "未识别结构",
      state: structureCheck?.state ?? "warning",
    },
    {
      label: "公式渲染",
      value: formulaCheck?.value ?? "未检测到公式",
      state: formulaCheck?.state ?? "warning",
    },
    {
      label: version === "teacher" ? "教师版内容" : "学生版留白",
      value: versionCheck?.value ?? "未识别版本要求",
      state: versionCheck?.state ?? "warning",
    },
    {
      label: version === "teacher" ? "答案审查" : "答案隔离",
      value: answerCheck?.value ?? "未完成答案检查",
      state: answerCheck?.state ?? "warning",
    },
    {
      label: "来源追溯",
      value: evidenceCount > 0 ? `${evidenceCount} 条来源` : "缺少教材/题库来源",
      state: evidenceCount > 0 ? "good" : "warning",
    },
  ];
}

function buildHandoutReviewChecks(blocks: ReviewBlock[], latex: string, version: HandoutVersion): ReviewCheck[] {
  const sectionCount = blocks.filter((block) => block.type === "section").length;
  const hasMath = splitMathText(normalizePreviewMath(decodeLatexText(latex))).some((segment) => segment.math);
  const hasWorkspace = blocks.some((block) => block.type === "space") || /作答区|订正|留白|___/.test(latex);
  const plainText = cleanPreviewText(latex);
  const answerLeak = /【答案与评分点】|参考答案|评分标准|答案[:：]|答案为|故答案|因此答案|得分/.test(plainText);
  const teacherHasAnswer = /【答案与评分点】|答案|解析|讲评|评分/.test(plainText);
  const labels = blocks
    .filter((block): block is Extract<ReviewBlock, { type: "section" | "subsection" | "paragraph" }> =>
      block.type === "section" || block.type === "subsection" || block.type === "paragraph")
    .map((block) => block.title);
  const reviewGroups = version === "teacher" ? teacherReviewGroups : studentReviewGroups;
  const matchedLabels = reviewGroups.filter((group) => matchesReviewGroup(group, labels, plainText)).length;

  return [
    {
      label: "结构",
      value: `${sectionCount} 个章节，${matchedLabels}/${reviewGroups.length} 个核心栏目`,
      state: sectionCount >= 3 && matchedLabels >= Math.min(4, reviewGroups.length) ? "good" : "warning",
    },
    {
      label: "公式",
      value: hasMath ? "已按 KaTeX 渲染" : "暂无公式",
      state: hasMath ? "good" : "warning",
    },
    {
      label: version === "teacher" ? "教师解析" : "学生作答",
      value: version === "teacher"
        ? (teacherHasAnswer ? "包含解析与讲评信息" : "缺少答案或讲评")
        : (hasWorkspace ? "保留空白作答区" : "缺少作答留白"),
      state: version === "teacher" ? (teacherHasAnswer ? "good" : "warning") : (hasWorkspace ? "good" : "warning"),
    },
    {
      label: version === "teacher" ? "答案区分" : "答案隔离",
      value: version === "teacher"
        ? "答案仅用于教师审查"
        : (answerLeak ? "发现疑似答案词" : "未发现答案泄漏"),
      state: version === "teacher" ? "good" : (answerLeak ? "warning" : "good"),
    },
  ];
}

const teacherReviewGroups = [
  ["讲义信息", "学习目标", "本讲任务", "课前定位"],
  ["来源索引", "知识点归属", "知识定位", "教材", "题库", "证据"],
  ["板书流程", "板书", "方法步骤", "讲解路径", "方法卡片", "讲解"],
  ["例题与答案", "例题详解", "答案与评分点", "评分点", "解析"],
  ["课堂追问", "追问与变式训练", "变式", "问题预设", "互动练习"],
  ["课后订正", "反馈记录", "易错提醒", "订正记录", "反馈"],
];

const studentReviewGroups = [
  ["第 1 讲", "学习主题", "学习目标", "专题标题"],
  ["知识点", "知识速记", "核心定义", "核心方法"],
  ["题型", "例题任务", "题目", "连续编号"],
  ["思路提示", "作答提醒", "注意", "方法提示"],
  ["课堂练习", "练习任务", "课后巩固"],
  ["我的解答", "课堂作答区", "订正记录", "错因"],
];

function matchesReviewGroup(group: string[], labels: string[], plainText: string) {
  return group.some((keyword) =>
    labels.some((label) => label.includes(keyword)) || plainText.includes(keyword));
}

function HistoryPanel({
  history,
  currentTaskId,
  loading,
  loadingTaskId,
  onSelectHistory,
}: {
  history: TeachingTaskResponse[];
  currentTaskId?: string;
  loading: boolean;
  loadingTaskId: string;
  onSelectHistory: (task: TeachingTaskResponse) => void;
}) {
  return (
    <div className="teaching-history-panel">
      <div className="teaching-history-head">
        <strong>历史讲义任务</strong>
        {loading ? <Loader2 className="spin" size={14} /> : <Clock size={14} />}
      </div>
      {!history.length ? (
        <div className="empty-state compact">当前账号暂无历史讲义任务。</div>
      ) : (
        <div className="teaching-history-list">
          {history.map((item) => {
            const hasHandout = Boolean(item.teacherHandoutLatex || item.studentHandoutLatex || item.handoutLatex);
            const opening = loadingTaskId === item.taskId;
            return (
              <button
                type="button"
                className={[
                  "teaching-history-item",
                  currentTaskId === item.taskId ? "active" : "",
                  opening ? "loading" : "",
                ].filter(Boolean).join(" ")}
                key={item.taskId}
                disabled={opening}
                onClick={() => onSelectHistory(item)}
              >
                <strong>{displayTaskTitle(item)}</strong>
                <span>{statusLabel(item.status)} · {shortText(item.taskId, 22)}</span>
                <span className={hasHandout ? "teaching-history-action" : "teaching-history-action muted"}>
                  {opening ? "正在打开真实讲义内容" : (hasHandout ? "打开并预览内容，可下载或复核" : "任务尚未产出可预览讲义")}
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function InfoTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="info-tile">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function GenerationReviewPanel({ task }: { task: TeachingTaskResponse }) {
  const aiDraft = task.aiDraft;
  const structured = Boolean(aiDraft?.structured);
  const templateName = task.selectedTemplate?.displayName ?? "标准讲义";
  const evidenceCount = task.evidence.length;
  const knowledgePoints = aiDraft?.knowledgePoints ?? [];
  const workflowGroups = buildWorkflowConversationGroups(task.nodes);
  const draftOutline = aiDraft ? buildDraftOutline(aiDraft.teacherExplanation, aiDraft.studentHint) : [];

  return (
    <section className="generation-review-panel">
      <div className="generation-review-head">
        <div>
          <p className="eyebrow">过程对话</p>
          <h3>{structured ? "讲义内容已整理成可审查结构" : "讲义需要人工复核后再使用"}</h3>
          <small>把检索、生成、排版和人工审校折叠成可追踪步骤。</small>
        </div>
        <span className={structured ? "review-state good" : "review-state warning"}>{structured ? "可进入审校" : "待修订"}</span>
      </div>

      <div className="review-chat-list">
        <article className="review-message system">
          <span className="review-avatar">1</span>
          <div>
            <strong>确定讲义框架</strong>
            <p>使用「{templateName}」组织教师版和学生版；本次引用 {evidenceCount} 条教材、题库或教师资料作为来源。</p>
          </div>
        </article>

        {workflowGroups.map((group, groupIndex) => (
          <details className="review-process-group" open={groupIndex < 2} key={group.title}>
            <summary>
              <span className="review-avatar">{groupIndex + 2}</span>
              <div>
                <strong>{group.title}</strong>
                <p>{group.summary}</p>
              </div>
              <em>{group.nodes.length} 个步骤</em>
            </summary>
            <div className="review-process-steps">
              {group.nodes.map((node) => (
                <article className="review-message tool compact" key={node.code}>
                  <span className="review-step-dot" />
                  <div>
                    <strong>{node.name}<em>{nodeStatusLabel(node.status)}</em></strong>
                    <p>{cleanReviewSummary(node.summary)}</p>
                  </div>
                </article>
              ))}
            </div>
          </details>
        ))}

        {aiDraft ? (
          <article className={structured ? "review-message assistant" : "review-message warning"}>
            <span className="review-avatar">{workflowGroups.length + 2}</span>
            <div>
              <strong>{structured ? "生成讲义草稿" : "需要复核的问题"}</strong>
              {structured ? (
                <>
                  <p className="muted-line">
                    已拆成教师版、学生版两套讲义结构。正文请以 PDF 预览和结构化讲义为准，这里只保留审校摘要。
                  </p>
                  {draftOutline.length ? (
                    <div className="draft-outline-list" aria-label="讲义结构摘要">
                      {draftOutline.slice(0, 8).map((item) => (
                        <div className={`draft-outline-item ${item.audience}`} key={`${item.audience}-${item.title}`}>
                          <span>{item.audience === "teacher" ? "教师版" : "学生版"}</span>
                          <strong>{item.title}</strong>
                          <p><MathRichText compact text={item.summary} /></p>
                        </div>
                      ))}
                    </div>
                  ) : null}
                  {knowledgePoints.length ? (
                    <div className="tag-list compact">{knowledgePoints.slice(0, 8).map((item) => <span key={item}><MathRichText compact text={item} /></span>)}</div>
                  ) : null}
                </>
              ) : (
                <p>{shortText(aiDraft.parseError || aiDraft.content || aiDraft.message, 180)}</p>
              )}
            </div>
          </article>
        ) : null}
      </div>

      <details className="review-source-drawer">
        <summary>查看来源与运行明细</summary>
        {aiDraft ? (
          <div className="diagnostic-meta">
            <span>模型：{aiDraft.enabled ? `${providerLabel(aiDraft.providerName)} / ${aiDraft.modelCode}` : "未启用"}</span>
            <span>用量：{aiDraft.totalTokens ?? 0}</span>
            <span>重试：{aiDraft.retryCount}/{aiDraft.maxRetries}</span>
          </div>
        ) : null}
        {task.stageTimings?.length ? (
          <div className="timing-list">
            {task.stageTimings.map((timing, index) => (
              <div className="timing-item" key={timing.stage}>
                <span>{index + 1}. {stageLabel(timing.stage)}</span>
                <strong>{timing.elapsedMs} ms</strong>
              </div>
            ))}
          </div>
        ) : null}
        {task.evidence.length ? (
          <div className="hit-list source-hit-list">
            {task.evidence.slice(0, 6).map((item) => (
              <article className="evidence-card teaching-evidence-card" key={item.chunkId}>
                <div className="scope-badge">{scopeLabel(item.sourceScope)}</div>
                <div className="card-main">
                  <div className="card-head">
                    <h3>{item.sourceTitle}</h3>
                  </div>
                  <div className="meta-row">
                    <span>{item.sourceScope === "QUESTION_BANK" || item.pageNo <= 0 ? "题库题目" : `PDF ${item.pageNo}`}</span>
                  </div>
                  <p className="snippet">{shortText(cleanSnippet(item.snippet), 120)}</p>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <div className="empty-state compact">本次没有命中可展示来源。</div>
        )}
      </details>
    </section>
  );
}

function buildDraftOutline(teacherExplanation: string, studentHint: string): DraftOutlineItem[] {
  return [
    ...parseDraftOutlineItems(teacherExplanation, "teacher"),
    ...parseDraftOutlineItems(studentHint, "student"),
  ];
}

function parseDraftOutlineItems(text: string, audience: "teacher" | "student"): DraftOutlineItem[] {
  const cleaned = cleanPreviewText(text);
  if (!cleaned) {
    return [];
  }
  const labels = [...cleaned.matchAll(/【([^】]{2,18})】/g)];
  if (!labels.length) {
    return [{ title: audience === "teacher" ? "讲解摘要" : "练习摘要", summary: shortText(stripDraftNoise(cleaned), 90), audience }];
  }
  const items: DraftOutlineItem[] = [];
  for (let index = 0; index < labels.length; index += 1) {
    const match = labels[index];
    const labelStart = match.index ?? 0;
    const labelEnd = labelStart + match[0].length;
    const nextStart = labels[index + 1]?.index ?? cleaned.length;
    const title = match[1].trim();
    const summary = shortText(stripDraftNoise(cleaned.slice(labelEnd, nextStart)), 88);
    if (title && summary) {
      items.push({ title, summary, audience });
    }
  }
  return items;
}

function stripDraftNoise(value: string) {
  return value
    .replace(/【[^】]{2,18}】/g, " ")
    .replace(/\b(AI|MODEL|JSON|token|tokens|retry)\b/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function buildWorkflowConversationGroups(nodes: TeachingTaskResponse["nodes"]): WorkflowConversationGroup[] {
  const groups: WorkflowConversationGroup[] = [
    { title: "理解任务", summary: "识别学习目标、复用历史记忆，并确定本次讲义边界。", nodes: [] },
    { title: "工具调用与检索", summary: "调用教材、题库或教师资源检索，收集可追溯证据。", nodes: [] },
    { title: "内容生成与排版", summary: "整理讲解路径，生成教师版和学生版，并完成 LaTeX 排版。", nodes: [] },
    { title: "审查与交付", summary: "等待人工反馈，保留后续追问和导出入口。", nodes: [] },
  ];

  for (const node of nodes) {
    const code = node.code.toUpperCase();
    if (code.includes("RETRIEVAL") || code.includes("RESOURCE")) {
      groups[1].nodes.push(node);
    } else if (code.includes("DRAFT") || code.includes("HANDOUT") || code.includes("SOLVE") || code.includes("TEMPLATE")) {
      groups[2].nodes.push(node);
    } else if (code.includes("FEEDBACK") || code.includes("FOLLOW_UP") || code.includes("EXPORT")) {
      groups[3].nodes.push(node);
    } else {
      groups[0].nodes.push(node);
    }
  }

  return groups.filter((group) => group.nodes.length);
}

function MathRichText({ text, compact = false }: { text: string; compact?: boolean }) {
  const normalizedText = normalizePreviewMath(decodeLatexText(text));
  return (
    <>
      {splitMathText(normalizedText).map((segment) => {
        if (!segment.math) {
          return <span key={segment.key}>{segment.text}</span>;
        }
        const display = compact ? false : segment.display;
        const html = katex.renderToString(normalizeLatexExpression(segment.text), {
          displayMode: display,
          throwOnError: false,
          strict: false,
          trust: false,
        });
        return <span className={display ? "math-render display" : "math-render inline"} dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
      })}
    </>
  );
}

function parseHandoutLatex(latex: string): ReviewBlock[] {
  const blocks: ReviewBlock[] = [];
  const lines = latex.replace(/\r/g, "").split("\n");
  let listMode: { ordered: boolean; items: string[] } | null = null;
  let skippedSection = false;

  const flushList = () => {
    if (listMode && listMode.items.length) {
      blocks.push({ type: "list", ordered: listMode.ordered, items: [...listMode.items] });
    }
    listMode = null;
  };

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line === "%") {
      flushList();
      continue;
    }
    if (line.startsWith("%")) {
      continue;
    }
    const section = line.match(/^\\section\{(.+)\}$/);
    if (section) {
      flushList();
      const title = cleanPreviewText(section[1]);
      skippedSection = isReviewNoiseSection(title);
      if (!skippedSection) {
        blocks.push({ type: "section", title });
      }
      continue;
    }
    if (skippedSection) {
      continue;
    }
    if (line.startsWith("\\begin{itemize}")) {
      flushList();
      listMode = { ordered: false, items: [] };
      continue;
    }
    if (line.startsWith("\\begin{enumerate}")) {
      flushList();
      listMode = { ordered: true, items: [] };
      continue;
    }
    if (line.startsWith("\\end{itemize}") || line.startsWith("\\end{enumerate}")) {
      flushList();
      continue;
    }
    if (line.startsWith("\\item")) {
      if (!listMode) {
        listMode = { ordered: false, items: [] };
      }
      const itemText = cleanPreviewText(line.replace(/^\\item\s*/, ""));
      if (itemText && !isReviewNoiseText(itemText)) {
        listMode.items.push(compactReviewText(itemText));
      }
      continue;
    }
    flushList();

    const subsection = line.match(/^\\subsection\*?\{(.+)\}$/);
    if (subsection) {
      const title = cleanPreviewText(subsection[1]);
      if (title && !isReviewNoiseText(title)) {
        blocks.push({ type: "subsection", title });
      }
      continue;
    }
    const paragraph = line.match(/^\\paragraph\{(.+?)\}(.*)$/);
    if (paragraph) {
      const title = cleanPreviewText(paragraph[1]);
      if (!title || isReviewNoiseText(title)) {
        continue;
      }
      blocks.push({ type: "paragraph", title });
      const inlineText = cleanPreviewText(paragraph[2] ?? "");
      if (inlineText) {
        pushRichTextBlocks(blocks, compactReviewText(inlineText));
      }
      continue;
    }
    if (line.startsWith("\\vspace")) {
      blocks.push({ type: "space" });
      continue;
    }
    const cleanedLine = cleanPreviewText(line);
    if (!cleanedLine || isReviewNoiseText(cleanedLine)) {
      continue;
    }
    pushRichTextBlocks(blocks, compactReviewText(cleanedLine));
  }

  flushList();
  return blocks;
}

function pushRichTextBlocks(blocks: ReviewBlock[], text: string) {
  const cleaned = cleanPreviewText(text);
  if (!cleaned) {
    return;
  }
  const labelPattern = /【([^】]{2,16})】/g;
  const matches = [...cleaned.matchAll(labelPattern)];
  if (!matches.length) {
    blocks.push({ type: "text", text: cleaned });
    return;
  }
  let cursor = 0;
  for (let index = 0; index < matches.length; index += 1) {
    const match = matches[index];
    const labelStart = match.index ?? 0;
    if (labelStart > cursor) {
      const before = cleaned.slice(cursor, labelStart).trim();
      if (before) {
        blocks.push({ type: "text", text: before });
      }
    }
    const nextStart = matches[index + 1]?.index ?? cleaned.length;
    const labelEnd = labelStart + match[0].length;
    blocks.push({ type: "paragraph", title: match[1] });
    const body = cleaned.slice(labelEnd, nextStart).trim();
    if (body) {
      blocks.push({ type: "text", text: body });
    }
    cursor = nextStart;
  }
  const tail = cleaned.slice(cursor).trim();
  if (tail) {
    blocks.push({ type: "text", text: tail });
  }
}

function splitMathText(text: string) {
  const segments: Array<{ key: string; text: string; math: boolean; display: boolean }> = [];
  let key = 0;
  const pattern = /(\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]|\$[^$]+?\$|\\\([^)]+?\\\))/g;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > cursor) {
      segments.push({ key: `text-${key++}`, text: text.slice(cursor, start), math: false, display: false });
    }
    const raw = match[0];
    const display = raw.startsWith("$$") || raw.startsWith("\\[");
    const expression = raw
      .replace(/^\$\$|\$\$$/g, "")
      .replace(/^\\\[|\\\]$/g, "")
      .replace(/^\$|\$$/g, "")
      .replace(/^\\\(|\\\)$/g, "")
      .trim();
    if (expression) {
      segments.push({ key: `math-${key++}`, text: expression, math: true, display });
    }
    cursor = start + raw.length;
  }
  if (cursor < text.length) {
    segments.push({ key: `text-${key++}`, text: text.slice(cursor), math: false, display: false });
  }
  return segments.length ? segments : [{ key: "text-0", text, math: false, display: false }];
}

function decodeLatexText(value: string) {
  return value
    .replace(/\\textbackslash\{\}/g, "\\")
    .replace(/\\textasciicircum\{\}/g, "^")
    .replace(/\\textasciitilde\{\}/g, "~")
    .replace(/\\_/g, "_")
    .replace(/\\%/g, "%")
    .replace(/\\#/g, "#")
    .replace(/\\&/g, "&")
    .replace(/\\\{/g, "{")
    .replace(/\\\}/g, "}")
    .replace(/\\\\/g, "\\");
}

function normalizePreviewMath(value: string) {
  return wrapBarePreviewMath(value
    .replace(/\\\(([\s\S]+?)\\\)/g, "$$$1$")
    .replace(/\\\[([\s\S]+?)\\\]/g, "$$$$ $1 $$$$")
    .replace(/⁰/g, "^0")
    .replace(/¹/g, "^1")
    .replace(/²/g, "^2")
    .replace(/³/g, "^3")
    .replace(/⁴/g, "^4")
    .replace(/⁵/g, "^5")
    .replace(/⁶/g, "^6")
    .replace(/⁷/g, "^7")
    .replace(/⁸/g, "^8")
    .replace(/⁹/g, "^9")
    .replace(/₀/g, "_0")
    .replace(/₁/g, "_1")
    .replace(/₂/g, "_2")
    .replace(/₃/g, "_3")
    .replace(/₄/g, "_4")
    .replace(/₅/g, "_5")
    .replace(/₆/g, "_6")
    .replace(/₇/g, "_7")
    .replace(/₈/g, "_8")
    .replace(/₉/g, "_9")
    .replace(/±/g, "\\pm ")
    .replace(/×/g, "\\times ")
    .replace(/÷/g, "/"));
}

function wrapBarePreviewMath(value: string) {
  const mathAtom = String.raw`(?:[+\-]?\s*(?:\\frac\{[^{}]+\}\{[^{}]+\}|\\sqrt\{[^{}]+\}|(?:\\pm\s*)?[A-Za-z0-9]+(?:[_^]\{?[-+]?\d+\}?)?|\d+(?:\.\d+)?))`;
  return wrapRegexOutsideMath(
    value,
    new RegExp(String.raw`(?<![$\\A-Za-z0-9_])(${mathAtom}(?:\s*[+\-*/=]\s*${mathAtom})+)(?![$A-Za-z0-9_])`, "g"),
  );
}

function wrapRegexOutsideMath(value: string, pattern: RegExp) {
  const segments = value.split(/(\$\$[\s\S]*?\$\$|\$[^$]+\$)/g);
  return segments.map((segment) => {
    if (!segment || segment.startsWith("$")) {
      return segment;
    }
    return segment.replace(pattern, "$$$1$$");
  }).join("");
}

function cleanPreviewText(value: string) {
  return decodeLatexText(value)
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/#\s*p\d+\s*-\s*/gi, " ")
    .replace(/##+\s*正文/g, " ")
    .replace(/\.\.\/\.\.\/pages\/[^\s，。；;)]*/g, " ")
    .replace(/\s*-\s*(书名|章节|PDF页码|印刷页码|页图)[:：][^-#，。；;]*/g, " ")
    .replace(/PDF页码[:：]?\s*\d*/g, " ")
    .replace(/印刷页码[:：]?\s*[^\s，。；;]*/g, " ")
    .replace(/页图[:：]?\s*/g, " ")
    .replace(/\\subsection\*?\{.+?\}/g, " ")
    .replace(/\\section\{.+?\}/g, " ")
    .replace(/\\paragraph\{.+?\}/g, " ")
    .replace(/\\vspace\{.+?\}/g, " ")
    .replace(/\\\\/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeLatexExpression(value: string) {
  return value.replace(/\\\\(?=[A-Za-z])/g, "\\");
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    COMPLETED: "已完成",
    RUNNING: "生成中",
    FAILED: "失败",
    PENDING: "等待中",
    CREATED: "已创建",
  };
  return labels[status] ?? status;
}

function nodeStatusLabel(status?: string) {
  const normalized = (status ?? "").toUpperCase();
  const labels: Record<string, string> = {
    COMPLETED: "完成",
    RUNNING: "进行中",
    PENDING: "等待",
    SKIPPED: "跳过",
    FAILED: "失败",
  };
  return labels[normalized] ?? (status || "完成");
}

function providerLabel(provider: string) {
  const labels: Record<string, string> = {
    openai: "OpenAI",
    dashscope: "通义千问",
    deepseek: "DeepSeek",
    ark: "火山方舟",
  };
  return labels[provider] ?? provider;
}

function pdfRendererLabel(renderer: string) {
  const labels: Record<string, string> = {
    xelatex: "XeLaTeX 编译",
    pdfbox_fallback: "后备排版",
  };
  return labels[renderer] ?? (renderer || "渲染方式未知");
}

function handoutActionTitle(action: string) {
  const labels: Record<string, string> = {
    "preview-pdf": "正在生成真实 PDF 预览",
    preview: "正在打开 TeX 审查视图",
    pdf: "正在导出 PDF",
    latex: "正在导出 TeX",
    zip: "正在打包 ZIP",
  };
  return labels[action] ?? "正在处理讲义";
}

function handoutActionDescription(action: string, version: HandoutVersion) {
  const versionLabel = version === "teacher" ? "教师版" : "学生版";
  const labels: Record<string, string> = {
    "preview-pdf": `${versionLabel}会经过后端权限校验和 XeLaTeX/PDF 渲染，完成后直接显示页面预览。`,
    preview: `${versionLabel}源码仅用于人工复核，页面会先渲染结构和公式。`,
    pdf: `${versionLabel}PDF 生成后会自动下载。`,
    latex: `${versionLabel}TeX 源文件生成后会自动下载。`,
    zip: "会按填写的文件夹路径组织压缩包，并显示临时文件有效期。",
  };
  return labels[action] ?? "请等待当前操作完成。";
}

function decisionLabel(decision: string) {
  const labels: Record<string, string> = {
    helpful: "可用",
    confusing: "不清楚",
    needs_revision: "需要修改",
  };
  return labels[decision] ?? decision;
}

function formatDateTime(value: string | undefined) {
  if (!value) {
    return "时间未记录";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function stringValue(value: unknown) {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}

function booleanValue(value: unknown) {
  return value === true;
}

function stringArrayValue(value: unknown) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is string => typeof item === "string" && item.trim().length > 0)
    .map((item) => item.trim());
}

function shortText(value: string | undefined, maxLength: number) {
  const text = (value ?? "").replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) {
    return text || "暂无内容";
  }
  const rawCut = text.slice(0, Math.max(0, maxLength - 1));
  const safeCut = trimDanglingMathPreview(rawCut).replace(/\s+/g, " ").trim();
  return `${safeCut || rawCut.replace(/[$\\{}_^]+/g, "").trim() || "暂无内容"}…`;
}

function displayTaskTitle(task: TeachingTaskResponse) {
  const raw = (task.learningGoal || task.questionText || "").replace(/\s+/g, " ").trim();
  if (!raw || isLikelyBrokenTitle(raw)) {
    return `历史讲义 ${shortText(task.taskId, 8)}`;
  }
  return shortText(raw, 42);
}

function isLikelyBrokenTitle(value: string) {
  const questionMarks = (value.match(/\?/g) ?? []).length;
  return questionMarks >= 6 && questionMarks / Math.max(1, value.length) > 0.35;
}

function trimDanglingMathPreview(value: string) {
  let text = value.trim();
  const dollarCount = (text.match(/\$/g) ?? []).length;
  if (dollarCount % 2 === 1) {
    text = text.slice(0, text.lastIndexOf("$")).trim();
  }
  const openInline = text.lastIndexOf("\\(");
  const closeInline = text.lastIndexOf("\\)");
  if (openInline > closeInline) {
    text = text.slice(0, openInline).trim();
  }
  const openDisplay = text.lastIndexOf("\\[");
  const closeDisplay = text.lastIndexOf("\\]");
  if (openDisplay > closeDisplay) {
    text = text.slice(0, openDisplay).trim();
  }
  return text
    .replace(/\s+\\(?:frac|sqrt|left|right)?[A-Za-z]*\{?[^，。；、,.!?！？]*$/g, "")
    .replace(/\s+\$?\\?[A-Za-z0-9+\-=*/_^{}()[\],. ]{1,24}$/g, "")
    .trim();
}

function isReviewNoiseSection(title: string) {
  return /讲义模板与版式|版式要求|页面样式|渲染规则|系统规则/.test(title);
}

function isReviewNoiseText(value: string) {
  return /页眉|页脚|讲评色|练习色|PDF\s*版式要求|版式要求|系统渲染|渲染引擎|模板规则|不要写|颜色/.test(value)
    || /^p\d+\b/i.test(value)
    || /pages\/p\d+\.png/i.test(value);
}

function compactReviewText(value: string) {
  const cleaned = value
    .replace(/\s*书名[:：]\s*/g, "")
    .replace(/\s*章节[:：]\s*/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (/教材|教科书|PDF|题库|来源|证据/.test(cleaned) && cleaned.length > 260) {
    return shortText(cleaned, 260);
  }
  return cleaned;
}

function humanMemoryReason(value: string | undefined) {
  const text = (value ?? "").trim();
  if (!text) {
    return "本次没有记录可复用的历史记忆。";
  }
  if (text.toLowerCase().includes("no reusable memory")) {
    return "本次没有命中可复用的历史学习记录。";
  }
  return shortText(text, 120);
}

function cleanReviewSummary(value: string | undefined) {
  const cleaned = (value ?? "")
    .replace(/No reusable memory matched\.?/gi, "未找到适合复用的历史学习记录")
    .replace(/MODEL_CALL_SUCCEEDED[^。]*。?/gi, "")
    .replace(/JSON_PARSE_SUCCEEDED[^。]*。?/gi, "")
    .replace(/当前模型\s*[^，。]*[，。]?/g, "")
    .replace(/模型\s*[A-Za-z0-9_./:-]+[，。]?/g, "")
    .replace(/重试\s*\d+\s*\/\s*\d+[，。]?/g, "")
    .replace(/诊断事件\s*\d+\s*条[，。]?/g, "")
    .replace(/question_bank_retrieval/gi, "题库检索")
    .replace(/\s+/g, " ")
    .replace(/，。/g, "。")
    .replace(/[，,、\s]+$/g, "")
    .trim();
  return shortText(cleaned, 140);
}

function scopeLabel(scope: string) {
  const labels: Record<string, string> = {
    PUBLIC_TEXTBOOK: "公开教材",
    QUESTION_BANK: "题库",
    TEACHER_PRIVATE: "教师资料",
    MATH_VIP: "教研共享",
  };
  return labels[scope] ?? scope;
}

function cleanSnippet(value: string | undefined) {
  return (value ?? "")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/p\d+\s*-\s*书名：[\s\S]*?正文\s*/g, "")
    .replace(/^p\d+\s*-\s*/i, "")
    .replace(/书名：[^#]*?正文/g, "")
    .replace(/PDF页码：\d+/g, "")
    .replace(/印刷页码：[^-#]*/g, "")
    .replace(/页图：/g, "")
    .replace(/[#*_`>$]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
