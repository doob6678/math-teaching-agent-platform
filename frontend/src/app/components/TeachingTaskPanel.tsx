import { FormEvent, useEffect, useRef, useState } from "react";
import katex from "katex";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.mjs?url";
import {
  AlertCircle,
  BookOpen,
  Check,
  Clock,
  Database,
  Download,
  Eye,
  FileText,
  Loader2,
  ShieldCheck,
} from "lucide-react";
import { TeachingTaskResponse } from "../../shared/api/textbookApi";
import { formatSimilarity, stageLabel, StatusLine } from "./panelShared";

type HandoutVersion = "teacher" | "student";

type ReviewBlock =
  | { type: "section"; title: string }
  | { type: "subsection"; title: string }
  | { type: "paragraph"; title: string }
  | { type: "text"; text: string }
  | { type: "list"; ordered: boolean; items: string[] }
  | { type: "space" };

export function TeachingTaskPanel({
  task,
  loading,
  error,
  history,
  loadingHistory,
  version,
  previewLatex,
  previewPdfUrl,
  previewPdfBytes,
  action,
  exportMessage,
  feedbackRating,
  feedbackDecision,
  feedbackComment,
  submittingFeedback,
  feedbackMessage,
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
  version: HandoutVersion;
  previewLatex: string;
  previewPdfUrl: string;
  previewPdfBytes: Uint8Array | null;
  action: string;
  exportMessage: string;
  feedbackRating: number;
  feedbackDecision: string;
  feedbackComment: string;
  submittingFeedback: boolean;
  feedbackMessage: string;
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
                <h3>{task.learningGoal || task.questionText || "未命名讲义"}</h3>
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

            {exportMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={exportMessage} /> : null}

            {previewPdfUrl ? (
              <PdfCanvasPreview pdfBytes={previewPdfBytes} pdfUrl={previewPdfUrl} />
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

          {task.aiDraft ? (
            <details className="ai-draft-panel">
              <summary>
                <div>
                  <p className="eyebrow">生成诊断</p>
                  <h3>{task.aiDraft.structured ? "讲义内容已结构化" : "模型返回仍需人工复核"}</h3>
                </div>
                <StatusBadgeText text={`重试 ${task.aiDraft.retryCount}/${task.aiDraft.maxRetries}`} />
              </summary>
              <div className="diagnostic-meta">
                <span>模型：{task.aiDraft.enabled ? `${providerLabel(task.aiDraft.providerName)} / ${task.aiDraft.modelCode}` : "未启用"}</span>
                <span>Token：{task.aiDraft.totalTokens ?? 0}</span>
              </div>
              {task.aiDraft.structured ? (
                <div className="ai-draft-content">
                  <div className="summary-card">
                    <span>教师讲解主线</span>
                    <strong><MathRichText text={shortText(task.aiDraft.teacherExplanation, 140)} /></strong>
                  </div>
                  <div className="summary-card">
                    <span>学生提示</span>
                    <strong><MathRichText text={shortText(task.aiDraft.studentHint, 120)} /></strong>
                  </div>
                  {task.aiDraft.knowledgePoints.length ? (
                    <div className="tag-list">{task.aiDraft.knowledgePoints.map((item) => <span key={item}><MathRichText text={item} /></span>)}</div>
                  ) : null}
                </div>
              ) : (
                <div className="summary-card">
                  <span>错误摘要</span>
                  <strong><MathRichText text={shortText(task.aiDraft.parseError || task.aiDraft.content || task.aiDraft.message, 160)} /></strong>
                </div>
              )}
            </details>
          ) : null}

          <details className="review-details">
            <summary>流程与证据</summary>
            <div className="node-list">
              {task.nodes.map((node, index) => (
                <div className="node-item node-item-rich" key={node.code}>
                  <div className="node-item-top">
                    <span className="node-index">{index + 1}</span>
                    <strong>{node.name}</strong>
                    <em>{nodeStatusLabel(node.status)}</em>
                  </div>
                  <span>{shortText(node.summary, 120)}</span>
                </div>
              ))}
            </div>
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
              <div className="hit-list">
                {task.evidence.slice(0, 5).map((item) => (
                  <article className="evidence-card teaching-evidence-card" key={item.chunkId}>
                    <div className="scope-badge">{scopeLabel(item.sourceScope)}</div>
                    <div className="card-main">
                      <div className="card-head">
                        <h3>{item.sourceTitle}</h3>
                      </div>
                      <div className="meta-row">
                        <span>{shortText(item.chunkId, 28)}</span>
                        <span>{item.sourceScope === "QUESTION_BANK" || item.pageNo <= 0 ? "题库题目" : `PDF ${item.pageNo}`}</span>
                      </div>
                      <p className="snippet">{shortText(cleanSnippet(item.snippet), 120)}</p>
                    </div>
                  </article>
                ))}
              </div>
            ) : null}
          </details>

          <form className="human-feedback-panel" onSubmit={onSubmitFeedback}>
            <div className="feedback-head">
              <strong>人工反馈</strong>
              {feedbackMessage ? <span>{feedbackMessage}</span> : null}
            </div>
            <div className="feedback-quality-list" aria-label="讲义质量审查要点">
              {[
                "页眉页脚完整",
                "教师/学生版区分清楚",
                "公式渲染正确",
                "版式无重叠",
                "来源可追溯",
              ].map((item) => (
                <span key={item}><Check size={13} />{item}</span>
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

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

function PdfCanvasPreview({ pdfBytes, pdfUrl }: { pdfBytes: Uint8Array | null; pdfUrl: string }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "failed">("loading");
  const [pageInfo, setPageInfo] = useState("");

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
        const page = await pdf.getPage(1);
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
        const totalPages = pdf.numPages;
        await pdf.cleanup();
        if (!cancelled) {
          setPageInfo(`第 1 页 / 共 ${totalPages} 页`);
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
  }, [pdfBytes]);

  return (
    <div className="pdf-canvas-preview">
      <div className="pdf-canvas-toolbar">
        <div>
          <strong>PDF 真实渲染预览</strong>
          <span>{state === "ready" ? pageInfo : state === "loading" ? "正在渲染首页" : "Canvas 预览不可用"}</span>
        </div>
        <a href={pdfUrl} target="_blank" rel="noreferrer">打开原始 PDF</a>
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
          <strong>未打开 PDF 时，先按结构预览内容与公式</strong>
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

function HistoryPanel({
  history,
  currentTaskId,
  loading,
  onSelectHistory,
}: {
  history: TeachingTaskResponse[];
  currentTaskId?: string;
  loading: boolean;
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
            return (
              <button
                type="button"
                className={currentTaskId === item.taskId ? "teaching-history-item active" : "teaching-history-item"}
                key={item.taskId}
                onClick={() => onSelectHistory(item)}
              >
                <strong>{item.learningGoal || item.questionText || "未命名讲义"}</strong>
                <span>{statusLabel(item.status)} · {shortText(item.taskId, 22)}</span>
                <span className={hasHandout ? "teaching-history-action" : "teaching-history-action muted"}>
                  {hasHandout ? "打开并预览内容，可下载或复核" : "任务尚未产出可预览讲义"}
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

function StatusBadgeText({ text }: { text: string }) {
  return <div className="strategy-pill">{text}</div>;
}

function MathRichText({ text }: { text: string }) {
  return (
    <>
      {splitMathText(decodeLatexText(text)).map((segment) => {
        if (!segment.math) {
          return <span key={segment.key}>{segment.text}</span>;
        }
        const html = katex.renderToString(normalizeLatexExpression(segment.text), {
          displayMode: segment.display,
          throwOnError: false,
          strict: false,
          trust: false,
        });
        return <span className={segment.display ? "math-render display" : "math-render inline"} dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
      })}
    </>
  );
}

function parseHandoutLatex(latex: string): ReviewBlock[] {
  const blocks: ReviewBlock[] = [];
  const lines = latex.replace(/\r/g, "").split("\n");
  let listMode: { ordered: boolean; items: string[] } | null = null;

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
      listMode.items.push(cleanPreviewText(line.replace(/^\\item\s*/, "")));
      continue;
    }
    flushList();

    const section = line.match(/^\\section\{(.+)\}$/);
    if (section) {
      blocks.push({ type: "section", title: cleanPreviewText(section[1]) });
      continue;
    }
    const subsection = line.match(/^\\subsection\*?\{(.+)\}$/);
    if (subsection) {
      blocks.push({ type: "subsection", title: cleanPreviewText(subsection[1]) });
      continue;
    }
    const paragraph = line.match(/^\\paragraph\{(.+?)\}(.*)$/);
    if (paragraph) {
      blocks.push({ type: "paragraph", title: cleanPreviewText(paragraph[1]) });
      const inlineText = cleanPreviewText(paragraph[2] ?? "");
      if (inlineText) {
        pushRichTextBlocks(blocks, inlineText);
      }
      continue;
    }
    if (line.startsWith("\\vspace")) {
      blocks.push({ type: "space" });
      continue;
    }
    pushRichTextBlocks(blocks, cleanPreviewText(line));
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
  let index = 0;
  let key = 0;
  while (index < text.length) {
    const displayStart = text.indexOf("$$", index);
    const inlineStart = text.indexOf("$", index);
    const nextStart = displayStart >= 0 && (inlineStart < 0 || displayStart <= inlineStart) ? displayStart : inlineStart;
    if (nextStart < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(index), math: false, display: false });
      break;
    }
    if (nextStart > index) {
      segments.push({ key: `text-${key++}`, text: text.slice(index, nextStart), math: false, display: false });
    }
    const display = text.startsWith("$$", nextStart);
    const delimiter = display ? "$$" : "$";
    const start = nextStart + delimiter.length;
    const end = text.indexOf(delimiter, start);
    if (end < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(nextStart), math: false, display: false });
      break;
    }
    const expression = text.slice(start, end).trim();
    if (expression) {
      segments.push({ key: `math-${key++}`, text: expression, math: true, display });
    }
    index = end + delimiter.length;
  }
  return segments.length ? segments : [{ key: "text-0", text, math: false, display: false }];
}

function decodeLatexText(value: string) {
  return value
    .replace(/\\textbackslash\{\}/g, "\\")
    .replace(/\\_/g, "_")
    .replace(/\\%/g, "%")
    .replace(/\\#/g, "#")
    .replace(/\\&/g, "&")
    .replace(/\\\{/g, "{")
    .replace(/\\\}/g, "}")
    .replace(/\\\\/g, "\\");
}

function cleanPreviewText(value: string) {
  return decodeLatexText(value)
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

function shortText(value: string | undefined, maxLength: number) {
  const text = (value ?? "").replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) {
    return text || "暂无内容";
  }
  return `${text.slice(0, Math.max(0, maxLength - 1))}…`;
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
    .replace(/[#*_`>$]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}
