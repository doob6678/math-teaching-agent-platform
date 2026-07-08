import { FormEvent, useMemo, useState } from "react";
import katex from "katex";
import { AlertCircle, BookOpen, Check, Clock, Database, Download, Eye, FileText, Loader2, ShieldCheck } from "lucide-react";
import { TeachingHandoutPdfResponse, TeachingHumanFeedbackResponse, TeachingTaskResponse } from "../../shared/api/textbookApi";
import { formatSimilarity, StatusLine } from "./panelShared";
import { PdfCanvasPreview, pdfRendererLabel } from "./PdfCanvasPreview";

export type HandoutVersion = "teacher" | "student" | "lecture";

type PreviewMode = "pdf" | "review" | "lecture";

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
  const [previewMode, setPreviewMode] = useState<PreviewMode>("lecture");
  const selectedDraft = task
    ? handoutDraftForVersion(task, version)
    : "";
  const busy = Boolean(action);
  const completed = task?.status === "COMPLETED";

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
      {task && !completed ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="讲义仍在生成中，完成后可打开 PDF 或结构审查。" /> : null}
      {completed ? <StatusLine icon={<Check size={16} />} text="讲义已生成。教师版可下载 PDF，学生版用于留白练习。" /> : null}

      <HistoryPanel history={history} currentTaskId={task?.taskId} loading={loadingHistory} loadingTaskId={loadingHistoryTaskId} onSelectHistory={onSelectHistory} />

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
                  <option value="lecture">横版讲解</option>
                </select>
              </label>
            </div>

            <div className="handout-toolbar primary">
              <button type="button" className="btn btn-primary" onClick={onExportPdf} disabled={busy || !selectedDraft}>
                {action === "pdf" ? <Loader2 className="spin" size={16} /> : <Download size={16} />}
                <span>下载 PDF</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={() => { setPreviewMode("pdf"); onPreviewPdf(); }} disabled={busy || !selectedDraft}>
                {action === "preview-pdf" ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
                <span>预览 PDF</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={() => { setPreviewMode("review"); onPreviewLatex(); }} disabled={busy || !selectedDraft}>
                {action === "preview" ? <Loader2 className="spin" size={16} /> : <BookOpen size={16} />}
                <span>结构审查</span>
              </button>
              <button type="button" className="btn btn-secondary" onClick={onExportLatex} disabled={busy || !selectedDraft}>
                {action === "latex" ? <Loader2 className="spin" size={16} /> : <FileText size={16} />}
                <span>下载 TeX</span>
              </button>
            </div>

            <div className="batch-export-row">
              <label>
                <span>ZIP 内文件夹</span>
                <input value={batchFolderPath} onChange={(event) => onBatchFolderPathChange(event.target.value)} placeholder={`handouts/${task.taskId}`} />
              </label>
              <button type="button" className="btn btn-secondary" onClick={onExportBatchZip} disabled={busy || !selectedDraft}>
                {action === "zip" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
                <span>打包导出</span>
              </button>
            </div>

            {action ? <OperationStatus action={action} version={version} /> : null}
            {exportMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={exportMessage} /> : null}

            <div className="handout-preview-mode-switch" role="tablist" aria-label="讲义预览模式">
              <button type="button" className={`handout-preview-mode${previewMode === "lecture" ? " active" : ""}`} onClick={() => setPreviewMode("lecture")}>
                <BookOpen size={15} /><span>协作卡片</span>
              </button>
              <button type="button" className={`handout-preview-mode${previewMode === "pdf" ? " active" : ""}`} onClick={() => setPreviewMode("pdf")} disabled={!previewPdfUrl}>
                <Eye size={15} /><span>打印预览</span>
              </button>
              <button type="button" className={`handout-preview-mode${previewMode === "review" ? " active" : ""}`} onClick={() => setPreviewMode("review")} disabled={!selectedDraft}>
                <FileText size={15} /><span>结构审查</span>
              </button>
            </div>

            {previewMode === "pdf" && previewPdfUrl ? (
              <PdfCanvasPreview pdfBytes={previewPdfBytes} pdfUrl={previewPdfUrl} meta={previewPdfMeta} />
            ) : previewMode === "review" && selectedDraft ? (
              <HandoutStructuredPreview latex={previewLatex || selectedDraft} version={version} />
            ) : selectedDraft ? (
              <LectureHandoutPreview task={task} version={version} />
            ) : (
              <div className="handout-preview-placeholder">
                <FileText size={22} />
                <strong>{completed ? "当前版本暂无讲义内容" : "正在等待讲义完成"}</strong>
                <span>{completed ? "请重新生成讲义，或切换到已有内容的版本。" : "后端完成后会自动拉取可审查内容。"}</span>
              </div>
            )}
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
            <div className="feedback-grid">
              <label>
                <span>评分</span>
                <input className="form-input" type="number" min={1} max={5} value={feedbackRating} onChange={(event) => onFeedbackRatingChange(Number(event.target.value))} />
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
              <textarea className="form-textarea" value={feedbackComment} onChange={(event) => onFeedbackCommentChange(event.target.value)} placeholder="记录需要保留、重写或补充的部分。" />
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

export function HandoutStructuredPreview({ latex, version }: { latex: string; version: HandoutVersion }) {
  const sections = useMemo(() => groupReviewBlocks(parseHandoutLatex(latex)), [latex]);
  const mathCount = splitMathText(normalizePreviewMath(decodeLatexText(latex))).filter((segment) => segment.math).length;
  const workspaceCount = sections.flatMap((section) => section.blocks).filter((block) => block.type === "space").length;

  return (
    <div className={`handout-review-paper ${version}`}>
      <div className="handout-review-paper-head">
        <div>
          <span>结构审查</span>
          <strong>{handoutVersionLabel(version)}</strong>
        </div>
        <em>{handoutVersionShortLabel(version)}</em>
      </div>
      <div className="handout-review-overview" aria-label="讲义导览">
        <div className="handout-review-overview-stats">
          <span>{sections.length} 个板块</span>
          <span>{mathCount} 处公式</span>
          <span>{workspaceCount > 0 ? `${workspaceCount} 处留白` : "未识别留白"}</span>
        </div>
        <div className="handout-review-outline">
          {sections.slice(0, 8).map((section, index) => <span key={`${section.title}-${index}`}>{index + 1}. {section.title}</span>)}
        </div>
      </div>
      <div className="handout-review-body">
        {sections.map((section, sectionIndex) => (
          <article className="handout-review-paper-section" key={`${section.title}-${sectionIndex}`}>
            <div className="handout-review-paper-title">
              <span>{String(sectionIndex + 1).padStart(2, "0")}</span>
              <h4 className="handout-review-section">{section.title}</h4>
            </div>
            <div className="handout-review-paper-content">
              {section.blocks.map((block, index) => renderReviewBlock(block, `${sectionIndex}-${index}`))}
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

export function LectureHandoutPreview({ task, version }: { task: TeachingTaskResponse; version: HandoutVersion }) {
  const aiDraft = task.aiDraft;
  const outline = version === "student"
    ? parseDraftOutlineItems(aiDraft?.studentHint || "")
    : version === "lecture"
      ? parseDraftOutlineItems(task.lectureHandoutLatex || "")
      : parseDraftOutlineItems(aiDraft?.teacherExplanation || "");
  const knowledgePoints = aiDraft?.knowledgePoints?.slice(0, 6) ?? [];
  const followUps = (aiDraft?.followUpQuestions?.length ? aiDraft.followUpQuestions : task.interactiveSuggestions).slice(0, 6);
  const evidencePreview = task.evidence.slice(0, 3);
  const modelLine = aiDraft?.enabled ? `${providerLabel(aiDraft.providerName)} / ${aiDraft.modelCode}` : "模板生成";
  const lectureCardItems = version === "student" ? [] : extractLectureCardItems(task.lectureHandoutLatex || task.teacherHandoutLatex || task.handoutLatex || "");

  return (
    <section className={`handout-live-preview ${version}`} aria-label={`${handoutVersionShortLabel(version)}协作卡片流`}>
      <div className="handout-live-preview-head">
        <div>
          <span>{handoutVersionShortLabel(version)}协作卡片流</span>
          <strong>{displayTaskTitle(task)}</strong>
          <p><MathRichText compact text={cleanShort(outline.map((item) => item.summary).join(" ") || task.learningGoal || task.questionText, 120)} /></p>
        </div>
        <div className="handout-live-preview-badges">
          <em>{handoutVersionShortLabel(version)}</em>
          <em>{statusLabel(task.status)}</em>
        </div>
      </div>

      <div className="handout-live-preview-grid">
        <PreviewCard index="01" title="本轮要求">
          <p><MathRichText text={task.learningGoal || task.questionText || "未提供学习目标"} /></p>
          <div className="handout-live-tag-row">
            <span>{task.selectedTemplate?.displayName || "标准讲义模板"}</span>
            <span>{modelLine}</span>
          </div>
        </PreviewCard>

        <PreviewCard index="02" title={version === "student" ? "学习提示" : version === "lecture" ? "投屏主线" : "讲评主线"}>
          {outline.length ? (
            <div className="handout-live-outline-list">
              {outline.slice(0, 4).map((item, index) => (
                <div className="handout-live-outline-item" key={`${item.title}-${index}`}>
                  <strong>{item.title}</strong>
                  <p><MathRichText compact text={item.summary} /></p>
                </div>
              ))}
            </div>
          ) : <p className="muted-line">等待讲义草稿生成。</p>}
        </PreviewCard>

        {lectureCardItems.length ? (
          <PreviewCard index="03" title="横版讲解卡">
            <div className="handout-live-outline-list">
              {lectureCardItems.slice(0, 3).map((item, index) => (
                <div className="handout-live-outline-item" key={`lecture-card-${index}`}>
                  <strong>{index === 0 ? "课堂投屏" : `板书要点 ${index + 1}`}</strong>
                  <p><MathRichText compact text={item} /></p>
                </div>
              ))}
            </div>
          </PreviewCard>
        ) : null}

        <PreviewCard index={lectureCardItems.length ? "04" : "03"} title="知识点与方法">
          {knowledgePoints.length ? (
            <div className="handout-live-tag-row">
              {knowledgePoints.map((item) => <span key={item}><MathRichText compact text={item} /></span>)}
            </div>
          ) : <p className="muted-line">当前还没有可展示的知识点标签。</p>}
        </PreviewCard>

        <PreviewCard index={lectureCardItems.length ? "05" : "04"} title="继续补充要求">
          {followUps.length ? (
            <ol className="handout-live-list">{followUps.map((item, index) => <li key={`${item}-${index}`}><MathRichText compact text={item} /></li>)}</ol>
          ) : <p className="muted-line">这一轮还没有新的追问建议。</p>}
        </PreviewCard>

        <PreviewCard index={lectureCardItems.length ? "06" : "05"} title="命中来源">
          {evidencePreview.length ? (
            <div className="handout-live-source-list">
              {evidencePreview.map((item) => (
                <div className="handout-live-source-item" key={item.chunkId}>
                  <strong>{cleanShort(item.sourceTitle || scopeLabel(item.sourceScope), 40)}</strong>
                  <span>{scopeLabel(item.sourceScope)}{item.pageNo > 0 ? ` · 第 ${item.pageNo} 页` : ""}</span>
                  <p>{evidenceDisplaySummary(item)}</p>
                </div>
              ))}
            </div>
          ) : <p className="muted-line">当前没有可展示的命中来源。</p>}
        </PreviewCard>

        <PreviewCard index={lectureCardItems.length ? "07" : "06"} title="当前进度">
          <div className="handout-live-step-list">
            {task.nodes.slice(0, 6).map((node) => (
              <div className={`handout-live-step ${nodeStatusTone(node.status)}`} key={node.code}>
                <div>
                  <strong>{node.name}</strong>
                  <p>{stageSummaryText(node.summary, 100)}</p>
                </div>
                <span>{nodeStatusLabel(node.status)}</span>
              </div>
            ))}
          </div>
        </PreviewCard>
      </div>
    </section>
  );
}

function extractLectureCardItems(latex: string) {
  const lectureSection = groupReviewBlocks(parseHandoutLatex(latex))
    .find((section) => section.title.includes("16:10") || section.title.includes("横版讲解"));
  if (!lectureSection) return [];
  const items: string[] = [];
  for (const block of lectureSection.blocks) {
    if (block.type === "text" && block.text) items.push(cleanShort(block.text, 120));
    if (block.type === "paragraph" && block.title) items.push(cleanShort(block.title, 80));
    if (block.type === "list") items.push(...block.items.map((item) => cleanShort(item, 100)));
  }
  return items.filter((item) => item && item !== "暂无内容" && !isNoiseText(item)).slice(0, 5);
}

function renderReviewBlock(block: ReviewBlock, key: string) {
  if (block.type === "subsection") return <h5 className="handout-review-subsection" key={key}>{block.title}</h5>;
  if (block.type === "paragraph") return <div className="handout-review-paragraph" key={key}>{block.title}</div>;
  if (block.type === "list") {
    const ListTag = block.ordered ? "ol" : "ul";
    return <ListTag className={block.ordered ? "handout-review-list ordered" : "handout-review-list"} key={key}>{block.items.map((item, index) => <li key={`${key}-${index}`}><MathRichText text={item} /></li>)}</ListTag>;
  }
  if (block.type === "space") return <div className="handout-review-space" key={key} />;
  if (block.type === "section") return null;
  return <p className="handout-review-text" key={key}><MathRichText text={block.text} /></p>;
}

function PreviewCard({ index, title, children }: { index: string; title: string; children: React.ReactNode }) {
  return (
    <article className="handout-live-card">
      <header><span>{index}</span><strong>{title}</strong></header>
      <div className="handout-live-card-body">{children}</div>
    </article>
  );
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
  const visibleHistory = useMemo(() => history.filter(isDisplayableHistoryTask).slice(0, 12), [history]);
  return (
    <div className="teaching-history-panel">
      <div className="teaching-history-head">
        <strong>历史讲义任务</strong>
        {loading ? <Loader2 className="spin" size={14} /> : <Clock size={14} />}
      </div>
      {!visibleHistory.length ? (
        <div className="empty-state compact">当前账号暂无历史讲义任务。</div>
      ) : (
        <div className="teaching-history-list">
          {visibleHistory.map((item) => {
            const opening = loadingTaskId === item.taskId;
            return (
              <button
                type="button"
                className={["teaching-history-item", currentTaskId === item.taskId ? "active" : "", opening ? "loading" : ""].filter(Boolean).join(" ")}
                key={item.taskId}
                disabled={opening}
                onClick={() => onSelectHistory(item)}
              >
                <strong>{displayTaskTitle(item)}</strong>
                <span>{statusLabel(item.status)} · {item.selectedTemplate?.displayName || "标准讲义"}</span>
                <span className="teaching-history-action">{opening ? "正在打开" : "打开讲义"}</span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

// 历史区不能直接回显旧坏数据，否则会把乱码、空讲义和离题内容重新带回当前工作区。
function isDisplayableHistoryTask(task: TeachingTaskResponse) {
  if (!task.taskId) return false;
  if ((task.status || "").toUpperCase() !== "COMPLETED") return false;
  const title = cleanShort(task.learningGoal || task.questionText || "", 80);
  if (!title || looksCorruptedHistoryText(title)) return false;
  const body = cleanShort(
    task.teacherHandoutLatex
    || task.studentHandoutLatex
    || "",
    160,
  );
  return body.length >= 18 && !looksCorruptedHistoryText(body) && !containsProtocolHistoryLeak(`${title} ${body}`);
}

function containsProtocolHistoryLeak(value: string) {
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

function looksCorruptedHistoryText(value: string) {
  const normalized = (value || "").replace(/\s+/g, "");
  if (!normalized) return false;
  if (normalized.includes("???") || normalized.includes("�")) return true;
  const questionCount = [...normalized].filter((char) => char === "?").length;
  if (questionCount >= 3 && questionCount * 2 >= normalized.length) return true;
  return /ã|â|ä¸|å|æ|ç/i.test(normalized);
}

function GenerationReviewPanel({ task }: { task: TeachingTaskResponse }) {
  const aiDraft = task.aiDraft;
  return (
    <section className="generation-review-panel">
      <div className="generation-review-head">
        <div>
          <p className="eyebrow">过程对话</p>
          <h3>{aiDraft?.structured ? "讲义内容已整理成可审查结果" : "讲义需要人工复核后再使用"}</h3>
          <small>只展示可审查结论，不展示系统提示词和原始模型内容。</small>
        </div>
        <span className={aiDraft?.structured ? "review-state good" : "review-state warning"}>{aiDraft?.structured ? "可审查" : "待复核"}</span>
      </div>
      <div className="review-chat-list">
        {task.nodes.map((node, index) => (
          <article className={`review-message review-dialogue-message ${nodeStatusTone(node.status)}`} key={node.code}>
            <span className="review-avatar">{index + 1}</span>
            <div>
              <strong>{node.name}</strong>
              <p>{stageSummaryText(node.summary, 140)}</p>
            </div>
          </article>
        ))}
      </div>
      <details className="review-source-drawer">
        <summary>运行与来源明细</summary>
        {aiDraft ? (
          <div className="diagnostic-meta">
            <span>模型：{aiDraft.enabled ? `${providerLabel(aiDraft.providerName)} / ${aiDraft.modelCode}` : "未启用"}</span>
            <span>用量：{aiDraft.totalTokens ?? 0}</span>
            <span>重试：{aiDraft.retryCount}/{aiDraft.maxRetries}</span>
          </div>
        ) : null}
        {task.evidence.length ? (
          <div className="hit-list source-hit-list">
            {task.evidence.slice(0, 6).map((item) => (
              <article className="evidence-card teaching-evidence-card" key={item.chunkId}>
                <div className="scope-badge">{scopeLabel(item.sourceScope)}</div>
                <div className="card-main">
                  <div className="card-head"><h3>{cleanShort(item.sourceTitle || scopeLabel(item.sourceScope), 48)}</h3></div>
                  <p className="snippet">{evidenceDisplaySummary(item)}</p>
                </div>
              </article>
            ))}
          </div>
        ) : <div className="empty-state compact">本次没有命中可展示来源。</div>}
      </details>
    </section>
  );
}

function FeedbackHistoryPanel({ feedback, loading }: { feedback: TeachingHumanFeedbackResponse[]; loading: boolean }) {
  return (
    <section className="feedback-history-panel" aria-label="人工审校历史">
      <div className="feedback-history-head">
        <div><strong>审校记录</strong><span>保留人工判断，便于后续重生成。</span></div>
        {loading ? <Loader2 className="spin" size={14} /> : <Clock size={14} />}
      </div>
      {!feedback.length ? (
        <div className="empty-state compact">暂无人工审校记录。</div>
      ) : (
        <div className="feedback-history-list">
          {feedback.slice(0, 5).map((item) => (
            <article className="feedback-history-item" key={item.feedbackId}>
              <div className="feedback-history-title"><strong>{decisionLabel(item.decision)}</strong><span>{item.rating} 星 · {formatDateTime(item.createdAt)}</span></div>
              <p>{item.comment ? cleanShort(item.comment, 150) : "未填写修改意见。"}</p>
              <FeedbackContextSummary context={item.reviewContext} />
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function FeedbackContextSummary({ context }: { context?: Record<string, unknown> }) {
  const renderer = stringValue(context?.pdfRenderer);
  const pageCount = numberValue(context?.pdfPageCount);
  const evidenceCount = numberValue(context?.evidenceCount);
  const items = [
    renderer ? `PDF：${pdfRendererLabel(renderer)}${pageCount ? ` · ${pageCount} 页` : ""}` : "",
    evidenceCount ? `来源：${evidenceCount} 条` : "",
  ].filter(Boolean);
  return items.length ? <div className="feedback-context-summary">{items.map((item) => <span key={item}>{item}</span>)}</div> : null;
}

function OperationStatus({ action, version }: { action: string; version: HandoutVersion }) {
  return (
    <div className="handout-operation-status" role="status" aria-live="polite">
      <Loader2 className="spin" size={16} />
      <div>
        <strong>{handoutActionTitle(action)}</strong>
        <span>{handoutVersionShortLabel(version)}正在处理，请稍候。</span>
      </div>
    </div>
  );
}

function InfoTile({ label, value }: { label: string; value: string }) {
  return <div className="info-tile"><span>{label}</span><strong>{value}</strong></div>;
}

function MathRichText({ text, compact = false }: { text: string; compact?: boolean }) {
  const normalizedText = normalizePreviewMath(decodeLatexText(text));
  return (
    <>
      {splitMathText(normalizedText).map((segment) => {
        if (!segment.math) return <span key={segment.key}>{segment.text}</span>;
        const display = compact ? false : segment.display;
        const expression = normalizeLatexExpression(segment.text);
        if (hasUnbalancedBraces(expression)) return null;
        const html = katex.renderToString(expression, { displayMode: display, throwOnError: false, strict: false, trust: false, output: "html" });
        return <span className={display ? "math-render display" : "math-render inline"} dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
      })}
    </>
  );
}

function parseHandoutLatex(latex: string): ReviewBlock[] {
  const blocks: ReviewBlock[] = [];
  const lines = latex.replace(/\r/g, "").split("\n");
  let listMode: { ordered: boolean; items: string[] } | null = null;
  const flushList = () => {
    if (listMode?.items.length) blocks.push({ type: "list", ordered: listMode.ordered, items: [...listMode.items] });
    listMode = null;
  };
  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line === "%") {
      flushList();
      continue;
    }
    if (line.startsWith("%")) continue;
    const section = line.match(/^\\section\{(.+)\}$/);
    if (section) {
      flushList();
      const title = cleanPreviewText(section[1]);
      if (title && !isNoiseText(title)) blocks.push({ type: "section", title });
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
      if (!listMode) listMode = { ordered: false, items: [] };
      const itemText = cleanPreviewText(line.replace(/^\\item\s*/, ""));
      if (itemText && !isNoiseText(itemText)) listMode.items.push(itemText);
      continue;
    }
    flushList();
    const subsection = line.match(/^\\(?:subsection|subsubsection)\*?\{(.+)\}$/);
    if (subsection) {
      const title = cleanPreviewText(subsection[1]);
      if (title && !isNoiseText(title)) blocks.push({ type: "subsection", title });
      continue;
    }
    const paragraph = line.match(/^\\paragraph\{(.+?)\}(.*)$/);
    if (paragraph) {
      const title = cleanPreviewText(paragraph[1]);
      if (title && !isNoiseText(title)) blocks.push({ type: "paragraph", title });
      const inlineText = cleanPreviewText(paragraph[2] ?? "");
      if (inlineText && !isNoiseText(inlineText)) blocks.push({ type: "text", text: inlineText });
      continue;
    }
    if (line.startsWith("\\vspace")) {
      blocks.push({ type: "space" });
      continue;
    }
    const cleanedLine = cleanPreviewText(line);
    if (cleanedLine && !isNoiseText(cleanedLine)) blocks.push({ type: "text", text: cleanedLine });
  }
  flushList();
  return blocks;
}

function groupReviewBlocks(blocks: ReviewBlock[]) {
  const groups: Array<{ title: string; blocks: ReviewBlock[] }> = [];
  let current: { title: string; blocks: ReviewBlock[] } | null = null;
  for (const block of blocks) {
    if (block.type === "section") {
      current = { title: block.title, blocks: [] };
      groups.push(current);
      continue;
    }
    if (!current) {
      current = { title: "讲义正文", blocks: [] };
      groups.push(current);
    }
    current.blocks.push(block);
  }
  return groups.length ? groups : [{ title: "讲义正文", blocks }];
}

function splitMathText(text: string) {
  const segments: Array<{ key: string; text: string; math: boolean; display: boolean }> = [];
  let key = 0;
  let cursor = 0;
  const pattern = /(\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]|\$[^$]+?\$|\\\([^)]+?\\\)|[A-Za-z0-9|()[\]{}_^+\-=<>.,\s]*\\(?:frac|sqrt|cdot|times|div|leq?|geq?|neq|pm|mp|sin|cos|tan|theta|alpha|beta|gamma|Delta|pi|angle|overline|vec|left|right|infty|circ)[A-Za-z0-9|()[\]{}_^+\-=<>.,\\\s]*|[A-Za-z0-9][A-Za-z0-9(){}_^+\-*/\\\s]*[\/^][A-Za-z0-9(){}_^+\-*/\\\s]*=[A-Za-z0-9(){}_^+\-*/\\\s]+)/g;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > cursor) segments.push({ key: `text-${key++}`, text: text.slice(cursor, start), math: false, display: false });
    const raw = match[0];
    const display = raw.startsWith("$$") || raw.startsWith("\\[");
    const expression = raw.replace(/^\$\$|\$\$$/g, "").replace(/^\\\[|\\\]$/g, "").replace(/^\$|\$$/g, "").replace(/^\\\(|\\\)$/g, "").trim();
    if (expression) segments.push({ key: `math-${key++}`, text: expression, math: true, display });
    cursor = start + raw.length;
  }
  if (cursor < text.length) segments.push({ key: `text-${key++}`, text: text.slice(cursor), math: false, display: false });
  return segments.length ? segments : [{ key: "text-0", text, math: false, display: false }];
}

function decodeLatexText(value: string) {
  return (value || "")
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
  return value
    .replace(/\\\(([\s\S]+?)\\\)/g, "$$$1$")
    .replace(/\\\[([\s\S]+?)\\\]/g, "$$$$ $1 $$$$");
}

function normalizeLatexExpression(value: string) {
  return normalizeAsciiFractions(value.replace(/\\\\(?=[A-Za-z])/g, "\\"));
}

function normalizeAsciiFractions(value: string) {
  return value.replace(
    /([A-Za-z](?:\^\{?\d+\}?)?|\d+(?:\.\d+)?)\s*\/\s*([A-Za-z](?:\^\{?\d+\}?)?|\d+(?:\.\d+)?)/g,
    "\\frac{$1}{$2}",
  );
}

function cleanPreviewText(value: string) {
  return decodeLatexText(value)
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\\(?:subsection|subsubsection|section|paragraph)\*?\{.+?\}/g, " ")
    .replace(/\\vspace\{.+?\}/g, " ")
    .replace(/\\par\b/g, " ")
    .replace(/\\underline\{\\hspace\{[0-9.]+em\}\}/g, "________")
    .replace(/[#$*_`>]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function parseDraftOutlineItems(text: string) {
  const cleaned = cleanPreviewText(text);
  const matches = [...cleaned.matchAll(/【([^】]{2,18})】([^【]*)/g)];
  if (!matches.length) return cleaned ? [{ title: "讲义摘要", summary: cleanShort(cleaned, 110) }] : [];
  return matches.map((match, index) => ({
    title: cleanShort(match[1] || `要点 ${index + 1}`, 24),
    summary: cleanShort(match[2] || "", 110),
  })).filter((item) => item.summary);
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    COMPLETED: "已完成",
    RUNNING: "生成中",
    FAILED: "失败",
    PENDING: "等待中",
    CREATED: "已创建",
    completed: "已完成",
    running: "生成中",
    failed: "失败",
    pending: "等待中",
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

function nodeStatusTone(status?: string) {
  const normalized = (status ?? "").toUpperCase();
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

function handoutActionTitle(action: string) {
  const labels: Record<string, string> = {
    "preview-pdf": "正在生成真实 PDF 预览",
    preview: "正在打开结构审查视图",
    pdf: "正在导出 PDF",
    latex: "正在导出 TeX",
    zip: "正在打包 ZIP",
  };
  return labels[action] ?? "正在处理讲义";
}

function decisionLabel(decision: string) {
  const labels: Record<string, string> = {
    helpful: "可用",
    confusing: "不清楚",
    needs_revision: "需要修改",
  };
  return labels[decision] ?? decision;
}

function handoutDraftForVersion(task: TeachingTaskResponse, version: HandoutVersion) {
  if (version === "lecture") {
    return task.lectureHandoutLatex ?? task.teacherHandoutLatex ?? task.handoutLatex ?? "";
  }
  if (version === "student") {
    return task.studentHandoutLatex ?? task.handoutLatex ?? "";
  }
  return task.teacherHandoutLatex ?? task.handoutLatex ?? "";
}

function handoutVersionLabel(version: HandoutVersion) {
  if (version === "lecture") return "横版讲解稿";
  return version === "teacher" ? "教师版讲义" : "学生版讲义";
}

function handoutVersionShortLabel(version: HandoutVersion) {
  if (version === "lecture") return "横版讲解";
  return version === "teacher" ? "教师版" : "学生版";
}

function displayTaskTitle(task: TeachingTaskResponse) {
  return cleanShort(task.learningGoal || task.questionText || task.aiDraft?.studentHint || task.aiDraft?.teacherExplanation || `讲义任务 ${task.taskId.slice(0, 8)}`, 42);
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

function humanMemoryReason(value: string | undefined) {
  const text = (value ?? "").trim();
  if (!text || text.toLowerCase().includes("no reusable memory")) return "本次没有命中可复用的历史学习记录。";
  return cleanShort(text, 120);
}

function cleanShort(value: string | undefined, maxLength: number) {
  const text = cleanPreviewText(value ?? "");
  if (!text) return "暂无内容";
  return text.length <= maxLength ? text : `${trimDanglingLatex(text.slice(0, Math.max(0, maxLength - 3)).trim())}...`;
}

function stageSummaryText(value: string | undefined, maxLength = 120) {
  const text = cleanPreviewText(value ?? "");
  if (!text || isNoiseText(text)) return "阶段已完成，结果已整理到讲义预览与审查入口。";
  return cleanShort(text, maxLength);
}

function evidenceDisplaySummary(item: TeachingTaskResponse["evidence"][number]) {
  const source = scopeLabel(item.sourceScope);
  const page = item.pageNo > 0 ? `第 ${item.pageNo} 页` : "页码未记录";
  const role = item.sourceScope === "QUESTION_BANK" ? "用于补充练习题型" : item.sourceScope === "PUBLIC_TEXTBOOK" ? "用于校准知识点表述" : "用于补充教师资料";
  return `${source} · ${page} · ${role}`;
}

function trimDanglingLatex(value: string) {
  const lastCommand = value.lastIndexOf("\\");
  if (lastCommand < 0) return value;
  const tail = value.slice(lastCommand);
  if (/^\\[A-Za-z]*$/.test(tail) || hasUnbalancedBraces(tail)) {
    return value.slice(0, lastCommand).trimEnd();
  }
  return value;
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

function isNoiseText(value: string) {
  return /页眉|页脚|PDF\s*规则|PDF\s*排版|版式要求|渲染规则|系统提示|prompt|MODEL_CALL|JSON_PARSE|tokens?|debug/i.test(value);
}

function formatDateTime(value: string | undefined) {
  if (!value) return "时间未记录";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date);
}

function stringValue(value: unknown) {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}
