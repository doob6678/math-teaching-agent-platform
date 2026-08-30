import { FormEvent } from "react";
import katex from "katex";
import {
  AlertCircle,
  Check,
  Copy,
  Database,
  Download,
  Eye,
  FileText,
  Loader2,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import {
  MultiAgentWritingArtifact,
  MultiAgentWritingResponse,
  MultiAgentWritingTraceResponse,
} from "../../shared/api/textbookApi";
import { compactText, formatDateTime, statusClass, StatusBadge, StatusLine } from "./panelShared";
import { PdfCanvasPreview } from "./PdfCanvasPreview";

const CONTROLLED_STAGE_CODES = [
  "resource_curation",
  "template_selection",
  "outline_planning",
  "teacher_writer",
  "student_writer",
  "lecture_writer",
  "source_review",
  "student_safety_review",
  "layout_review",
  "merge_coordinator",
];
const LEGACY_STAGE_CODES = ["draft", "review", "format"];
type ArtifactFormat = "markdown" | "latex" | "pdf" | "pdf-teacher" | "pdf-student" | "pdf-lecture" | "zip";

export function MultiAgentWritingPanel({
  workflow,
  traces,
  artifact = null,
  writingGoal,
  questionText,
  providerName,
  modelCode,
  modelReady,
  starting,
  resuming = false,
  polling,
  loadingArtifact = false,
  artifactError = "",
  artifactMessage = "",
  exportingArtifactFormat = "",
  pdfPreviewUrl = "",
  pdfPreviewBytes = null,
  error,
  onWritingGoalChange,
  onQuestionTextChange,
  onSubmit,
  onResume,
  onRefresh,
  onLoadArtifact = () => undefined,
  onPreviewPdf = () => undefined,
  onExportArtifact = () => undefined,
}: {
  workflow: MultiAgentWritingResponse | null;
  traces: MultiAgentWritingTraceResponse | null;
  artifact?: MultiAgentWritingArtifact | null;
  writingGoal: string;
  questionText: string;
  providerName: string;
  modelCode: string;
  modelReady: boolean;
  starting: boolean;
  resuming?: boolean;
  polling: boolean;
  loadingArtifact?: boolean;
  artifactError?: string;
  artifactMessage?: string;
  exportingArtifactFormat?: string;
  pdfPreviewUrl?: string;
  pdfPreviewBytes?: Uint8Array | null;
  error: string;
  onWritingGoalChange: (value: string) => void;
  onQuestionTextChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onResume?: () => void;
  onRefresh: () => void;
  onLoadArtifact?: () => void;
  onPreviewPdf?: () => void;
  onExportArtifact?: (format: ArtifactFormat) => void;
}) {
  // Publication metadata is fixed at task creation and part of the audited artifact. Sending compatibility
  // header/footer values at export time makes Java correctly reject the mutation, which would look like a broken UI.
  const previewPdf = () => onPreviewPdf();
  const exportArtifact = (format: ArtifactFormat) => onExportArtifact(format);
  const stageCodes = workflow?.stages.some((stage) => CONTROLLED_STAGE_CODES.includes(stage.stageCode))
    ? CONTROLLED_STAGE_CODES
    : LEGACY_STAGE_CODES;
  const normalizedWorkflowStatus = workflow?.status?.toUpperCase() ?? "";
  const actualStage = workflow?.stages?.at(-1);
  const actualModel = actualStage
    ? `${providerLabel(actualStage.providerName)} / ${actualStage.modelCode}`
    : `${providerLabel(providerName)} / ${modelCode || "未选择"}`;
  const hasArtifact = Boolean(artifact?.mergedMarkdown?.trim());
  const canReviewArtifact = Boolean(workflow && ["COMPLETED", "FAILED", "SUCCESS"].includes(normalizedWorkflowStatus));
  const completed = normalizedWorkflowStatus === "COMPLETED" || normalizedWorkflowStatus === "SUCCESS";
  // Recovery is deliberately limited to terminal failures: a RUNNING workflow already owns durable Worker leases,
  // and a completed workflow has no missing stage to execute. The server repeats this ownership/status check.
  const canResume = normalizedWorkflowStatus === "FAILED";
  const artifactActionDisabled = !canReviewArtifact || Boolean(exportingArtifactFormat);
  const completedStageCount = workflow?.stages.filter((stage) => ["COMPLETED", "SUCCESS"].includes(stage.status.toUpperCase())).length ?? 0;
  const progressPercent = workflow ? Math.round((completedStageCount / stageCodes.length) * 100) : 0;
  const shortWorkflowId = workflow?.workflowId ? compactWorkflowId(workflow.workflowId) : "";
  const running = normalizedWorkflowStatus === "RUNNING";
  const currentStageCode = running
    ? stageCodes[Math.min(completedStageCount, stageCodes.length - 1)]
    : actualStage?.stageCode ?? stageCodes[Math.max(0, Math.min(stageCodes.length - 1, completedStageCount - 1))] ?? "";
  const currentStageTrace = traces?.stages.find((trace) => trace.planId?.endsWith(`:${currentStageCode}`));
  const workflowElapsed = workflow?.createdAt
    ? formatElapsed(workflow.createdAt, running ? undefined : workflow.updatedAt)
    : "";
  const deliveryHint = hasArtifact
    ? "正文、PDF、TeX 和打包文件都可以从这里审查或导出。"
    : "后端已保存生成结果，先预览 PDF 或打开正文审查。";

  const writingForm = (
      <form className="search-form agent-tool-form" onSubmit={onSubmit}>
        <label>
          <span>写作目标</span>
          <input
            className="form-input"
            value={writingGoal}
            onChange={(event) => onWritingGoalChange(event.target.value)}
            placeholder="例如：生成教师版双曲线讲义"
          />
        </label>
        <label>
          <span>补充要求（可选）</span>
          <input
            className="form-input"
            value={questionText}
            onChange={(event) => onQuestionTextChange(event.target.value)}
            placeholder="可填写题目、班级水平、风格或模板偏好；不填也能生成"
          />
        </label>
        <button className="btn btn-primary" type="submit" disabled={starting || !modelReady}>
          {starting ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
          <span>启动真实流程</span>
        </button>
        {onResume && canResume ? (
          <button className="btn btn-secondary" type="button" disabled={resuming} onClick={onResume}>
            {resuming ? <Loader2 className="spin" size={17} /> : <Database size={17} />}
            <span>从失败点恢复</span>
          </button>
        ) : null}
      </form>
  );

  const workflowStatusPanel = workflow ? (
      <div className="agent-usage-summary">
        <div className="result-header compact">
          <div>
            <p className="eyebrow">流程状态</p>
            <h3>{statusLabel(workflow.status)}</h3>
          </div>
          <StatusBadge status={statusLabel(workflow.status)} />
        </div>
        <div className="trace-badge-row workflow-id-row">
          <span>流程编号</span>
          <div>
            <strong>{workflow.workflowId}</strong>
            <button type="button" className="icon-action" title="复制流程编号" onClick={() => navigator.clipboard?.writeText(workflow.workflowId)}>
              <Copy size={14} />
            </button>
          </div>
        </div>
        {workflow.message ? (
          <div className="trace-badge-row">
            <span>说明</span>
            <div><strong>{workflowMessage(workflow.message)}</strong></div>
          </div>
        ) : null}
        <div className="tool-decision-list compact">
          {stageCodes.map((stageCode, index) => {
            const stage = workflow.stages.find((candidate) => candidate.stageCode === stageCode);
            const stageTrace = traces?.stages.find((trace) => trace.planId?.endsWith(`:${stageCode}`));
            const isCurrentStage = running && currentStageCode === stageCode;
            const stageClass = stage
              ? statusClass(stage.status)
              : isCurrentStage
                ? "running"
                : workflow.status === "RUNNING"
                  ? "unknown"
                  : "unknown";
            return (
              <div className={`tool-decision ${stageClass}`} key={stageCode}>
                <strong>{index + 1}. {stageLabel(stageCode)}</strong>
                <span>
                  {stage
                    ? stageMetrics(stage)
                    : isCurrentStage
                      ? "正在执行"
                      : workflow.status === "RUNNING"
                        ? "等待执行"
                        : "未执行"}
                </span>
                {stageTrace?.createdAt ? (
                  <p>{`阶段记录时间：${formatDateTime(stageTrace.createdAt)}`}</p>
                ) : isCurrentStage ? (
                  <p>后端正在执行这一阶段，结果返回后会显示模型与用量。</p>
                ) : null}
              </div>
            );
          })}
        </div>
      </div>
  ) : (
      <div className="empty-state compact">提交写作目标后，这里会显示可恢复流程、阶段状态和官方用量统计。</div>
  );

  return (
    <section className="agent-trace-panel writing-workbench">
      <div className="result-header">
        <div>
          <p className="eyebrow">讲义协作</p>
          <h2>生成、审校、排版一条链</h2>
        </div>
        <button type="button" className="inline-action btn btn-ghost btn-sm" onClick={onRefresh} disabled={!workflow || polling}>
          {polling ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
          <span>刷新状态</span>
        </button>
      </div>

      {completed && workflow ? (
        <section className="workflow-delivery-board">
          <div className="workflow-delivery-hero">
            <div className="workflow-delivery-title">
              <div className="workflow-completion-icon"><Check size={20} /></div>
              <div>
                <p className="eyebrow">成果文件</p>
                <h3>讲义已生成</h3>
                <p>{deliveryHint}</p>
              </div>
            </div>
            <div className="workflow-delivery-status">
              <span>{completedStageCount}/{stageCodes.length}</span>
              <strong>阶段完成</strong>
            </div>
          </div>
          <div className="workflow-delivery-actions">
            <ArtifactActionButtons
              hasArtifact={hasArtifact}
              loadingArtifact={loadingArtifact}
              exportingArtifactFormat={exportingArtifactFormat}
              disabled={artifactActionDisabled}
              onLoadArtifact={onLoadArtifact}
              onPreviewPdf={previewPdf}
              onExportArtifact={exportArtifact}
            />
          </div>
          <div className="workflow-delivery-meta">
            <div>
              <span>实际模型</span>
              <strong>{actualModel}</strong>
            </div>
            <div>
              <span>生成状态</span>
              <strong>{statusLabel(workflow.status)}</strong>
            </div>
            <div>
              <span>流程编号</span>
              <strong>{shortWorkflowId}</strong>
            </div>
          </div>
          <div className="workflow-progress-track" aria-label={`流程进度 ${progressPercent}%`}>
            <div style={{ width: `${progressPercent}%` }} />
          </div>
        </section>
      ) : canReviewArtifact ? (
        <section className="artifact-file-strip artifact-file-strip-primary artifact-file-strip-top">
          <div>
            <p className="eyebrow">成果文件</p>
            <h3>流程已结束，可以查看保留结果</h3>
            <span>
              {hasArtifact
                ? `已载入 ${artifact?.mergedMarkdown.length.toLocaleString("zh-CN")} 字符正文。可导出 PDF、正文、TeX 源码和打包文件。`
                : "后端已保存成果文件。先点“审查讲义”读取正文，也可以直接预览或下载 PDF。"}
            </span>
          </div>
          <ArtifactActionButtons
            hasArtifact={hasArtifact}
            loadingArtifact={loadingArtifact}
            exportingArtifactFormat={exportingArtifactFormat}
            disabled={artifactActionDisabled}
            onLoadArtifact={onLoadArtifact}
            onPreviewPdf={previewPdf}
            onExportArtifact={exportArtifact}
          />
        </section>
      ) : null}

      {completed ? (
        <details className="workflow-new-task-details">
          <summary>新建一份讲义</summary>
          {writingForm}
        </details>
      ) : writingForm}

      {!completed ? (
        <div className="writing-meta-grid">
          <div>
            <span>当前模型</span>
            <strong>{actualModel}</strong>
          </div>
          <div>
            <span>用量</span>
            <strong>{workflow?.totalUsage.totalTokens.toLocaleString("zh-CN") ?? "等待生成"}</strong>
          </div>
          <div>
            <span>任务状态</span>
            <strong>{workflow ? statusLabel(workflow.status) : modelReady ? "可启动" : "模型未就绪"}</strong>
          </div>
        </div>
      ) : null}

      {workflow && !completed ? (
        <section className="workflow-progress-board">
          <div className="workflow-progress-head">
            <div>
              <p className="eyebrow">当前在做什么</p>
              <h3>{stageLabel(currentStageCode || "draft")}</h3>
              <p>
                {workflow.message ? workflowMessage(workflow.message) : "流程已启动，正在按阶段推进。"}
                {running && completedStageCount > 0 ? " 已完成的阶段只代表局部产物可用，不代表最终讲义已经可下载。" : ""}
              </p>
            </div>
            <StatusBadge status={statusLabel(workflow.status)} />
          </div>
          <div className="workflow-progress-meta">
            <div>
              <span>流程编号</span>
              <strong>{shortWorkflowId || "待生成"}</strong>
            </div>
            <div>
              <span>已完成阶段</span>
              <strong>{completedStageCount} / {stageCodes.length}</strong>
            </div>
            <div>
              <span>流程耗时</span>
              <strong>{workflowElapsed || "进行中"}</strong>
            </div>
            <div>
              <span>最近阶段时间</span>
              <strong>{currentStageTrace?.createdAt ? formatDateTime(currentStageTrace.createdAt) : "等待回传"}</strong>
            </div>
          </div>
          <div className="workflow-progress-track" aria-label={`流程进度 ${progressPercent}%`}>
            <div style={{ width: `${progressPercent}%` }} />
          </div>
        </section>
      ) : null}

      {!modelReady ? (
        <StatusLine icon={<AlertCircle size={16} />} text="模型目录还没有加载完成，暂时不能启动写作流程。" tone="danger" />
      ) : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {canResume ? (
        <StatusLine
          icon={<RefreshCw size={16} />}
          text="失败任务已保留完成阶段、证据和用量。点击“从失败点恢复”只重新排队未完成阶段，不会重复已成功的模型调用。"
          tone="danger"
        />
      ) : null}
      {running ? (
        <StatusLine icon={<Loader2 className="spin" size={16} />} text="这里只显示真实阶段推进。只有出现 PDF 预览和下载入口，才表示讲义已经可交付。" />
      ) : null}

      {completed ? (
        <StatusLine icon={<Check size={16} />} text="讲义已生成，建议先预览 PDF，再按需要下载或进入正文审查。" />
      ) : null}

      {completed ? (
        <details className="workflow-process-details ai-run-disclosure">
          <summary>查看流程明细、模型切换和官方用量</summary>
          {workflowStatusPanel}
        </details>
      ) : workflowStatusPanel}

      <div className={completed ? "artifact-review-panel artifact-review-panel-ready" : "artifact-review-panel"}>
        <div className="result-header compact">
          <div>
            <p className="eyebrow">成果审查</p>
            <h3>讲义预览与导出</h3>
          </div>
          <ArtifactActionButtons
            hasArtifact={hasArtifact}
            loadingArtifact={loadingArtifact}
            exportingArtifactFormat={exportingArtifactFormat}
            disabled={artifactActionDisabled}
            onLoadArtifact={onLoadArtifact}
            onPreviewPdf={previewPdf}
            onExportArtifact={exportArtifact}
          />
        </div>
        {artifactError ? <StatusLine icon={<AlertCircle size={16} />} text={artifactError} tone="danger" /> : null}
        {artifactMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={artifactMessage} /> : null}
        {loadingArtifact ? (
          <div className="handout-preview-placeholder">
            <Loader2 className="spin" size={22} />
            <strong>正在加载可审查讲义</strong>
            <span>完成后会展示整理后的讲义正文，并开放 PDF、TeX 源码和打包导出。</span>
          </div>
        ) : hasArtifact || pdfPreviewUrl ? (
          <>
            {pdfPreviewUrl ? (
              <div className="artifact-pdf-preview">
                <PdfCanvasPreview
                  pdfBytes={pdfPreviewBytes}
                  pdfUrl={pdfPreviewUrl}
                  title="协作讲义 PDF 预览"
                  canvasLabel="协作讲义 PDF 页面预览"
                />
              </div>
            ) : null}
            {hasArtifact ? <ArtifactPreview markdown={artifact?.mergedMarkdown ?? ""} /> : null}
          </>
        ) : (
          <div className="handout-preview-placeholder">
            <FileText size={22} />
            <strong>{canReviewArtifact ? "讲义已生成，等待加载预览" : "流程完成后可审查讲义"}</strong>
            <span>{canReviewArtifact ? "点击“加载预览”读取后端保存的真实生成结果，再选择下载格式。" : "写作、审校、排版阶段完成后，这里会出现预览和下载入口。"}</span>
          </div>
        )}
      </div>

      {artifact?.stages?.length ? (
        <details className="review-details trace-review ai-run-disclosure">
          <summary>分阶段成果 {artifact.stages.length} 段</summary>
          <div className="agent-trace-list compact">
            {artifact.stages.map((stage) => (
              <article className="agent-trace-item compact" key={`${stage.stageCode}:${stage.traceId}`}>
                <div className="card-head">
                  <div>
                    <h3>{stageLabel(stage.stageCode)}</h3>
                    <p>{providerLabel(stage.providerName)} / {stage.modelCode}</p>
                  </div>
                  <StatusBadge status={statusLabel(stage.status)} />
                </div>
                <p className="stage-readable-content">{compactText(stage.generatedContent, 360)}</p>
              </article>
            ))}
          </div>
        </details>
      ) : null}

      {traces ? (
        <details className="review-details trace-review ai-run-disclosure">
          <summary>执行追踪 {traces.stages.length} 条</summary>
          <div className="agent-trace-list compact">
            {traces.stages.map((trace) => (
              <article className="agent-trace-item compact" key={trace.traceId}>
                <div className="card-head">
                  <div>
                    <h3>{stageLabel(trace.agentCode)}</h3>
                    <p>{compactText(trace.planId, 28)}</p>
                  </div>
                  <StatusBadge status={statusLabel(trace.status)} />
                </div>
                <div className="profile-strip">
                  <div>
                    <span>模型</span>
                    <strong>{providerLabel(trace.providerName)} / {trace.modelCode}</strong>
                  </div>
                  <div>
                    <span>用量</span>
                    <strong>{trace.actualUsage.totalTokens.toLocaleString("zh-CN")}</strong>
                  </div>
                  <div>
                    <span>诊断</span>
                    <strong>{trace.diagnosticEvents?.length ?? 0}</strong>
                  </div>
                </div>
              </article>
            ))}
          </div>
        </details>
      ) : null}
    </section>
  );
}

function ArtifactActionButtons({
  hasArtifact,
  loadingArtifact,
  exportingArtifactFormat,
  disabled,
  onLoadArtifact,
  onPreviewPdf,
  onExportArtifact,
}: {
  hasArtifact: boolean;
  loadingArtifact: boolean;
  exportingArtifactFormat: string;
  disabled: boolean;
  onLoadArtifact: () => void;
  onPreviewPdf: () => void;
  onExportArtifact: (format: ArtifactFormat) => void;
}) {
  return (
    <div className="handout-actions artifact-file-actions">
      <button className="btn btn-primary btn-sm" type="button" disabled={disabled || loadingArtifact} onClick={onLoadArtifact}>
        {loadingArtifact ? <Loader2 className="spin" size={15} /> : <Eye size={15} />}
        <span>{hasArtifact ? "审查讲义" : "打开成果"}</span>
      </button>
      <button className="btn btn-primary btn-sm" type="button" disabled={disabled} onClick={onPreviewPdf}>
        {exportingArtifactFormat === "preview-pdf" ? <Loader2 className="spin" size={15} /> : <Eye size={15} />}
        <span>预览 PDF</span>
      </button>
      <button className="btn btn-secondary btn-sm" type="button" disabled={disabled} onClick={() => onExportArtifact("pdf-teacher")}>
        {exportingArtifactFormat === "pdf-teacher" ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
        <span>教师版 PDF</span>
      </button>
      <button className="btn btn-secondary btn-sm" type="button" disabled={disabled} onClick={() => onExportArtifact("pdf-student")}>
        {exportingArtifactFormat === "pdf-student" ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
        <span>学生空白版</span>
      </button>
      <button className="btn btn-secondary btn-sm" type="button" disabled={disabled} onClick={() => onExportArtifact("pdf-lecture")}>
        {exportingArtifactFormat === "pdf-lecture" ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
        <span>16:10 单题版</span>
      </button>
      <button className="btn btn-secondary btn-sm" type="button" disabled={disabled} onClick={() => onExportArtifact("markdown")}>
        {exportingArtifactFormat === "markdown" ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
        <span>下载正文</span>
      </button>
      <button className="btn btn-secondary btn-sm" type="button" disabled={disabled} onClick={() => onExportArtifact("latex")}>
        {exportingArtifactFormat === "latex" ? <Loader2 className="spin" size={15} /> : <FileText size={15} />}
        <span>TeX 源码</span>
      </button>
      <button className="btn btn-secondary btn-sm" type="button" disabled={disabled} onClick={() => onExportArtifact("zip")}>
        {exportingArtifactFormat === "zip" ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
        <span>打包下载</span>
      </button>
    </div>
  );
}

function ArtifactPreview({ markdown }: { markdown: string }) {
  const blocks = markdownToBlocks(markdown);
  const sections = previewSections(blocks);
  const visibleSections = sections.slice(0, 6);
  const hiddenSections = sections.slice(6);
  return (
    <article className="artifact-preview">
      <div className="artifact-preview-head">
        <div>
          <p className="eyebrow">正文预览</p>
          <h4>讲义纸张预览</h4>
        </div>
        <span>{sections.length} 个部分</span>
      </div>
      <div className="artifact-preview-toc">
        {sections.slice(0, 8).map((section, sectionIndex) => (
          <span key={`${section.title}:toc:${sectionIndex}`}>{sectionIndex + 1}. {section.title}</span>
        ))}
        {sections.length > 8 ? <span>还有 {sections.length - 8} 节</span> : null}
      </div>
      {visibleSections.map((section, sectionIndex) => (
        <ArtifactPreviewSection section={section} sectionIndex={sectionIndex} key={`${section.title}:${sectionIndex}`} />
      ))}
      {hiddenSections.length ? (
        <details className="artifact-section-details artifact-hidden-sections">
          <summary>展开其余 {hiddenSections.length} 个部分</summary>
          <div className="artifact-hidden-section-list">
            {hiddenSections.map((section, sectionIndex) => (
              <ArtifactPreviewSection
                section={section}
                sectionIndex={sectionIndex + visibleSections.length}
                key={`${section.title}:hidden:${sectionIndex}`}
              />
            ))}
          </div>
        </details>
      ) : null}
    </article>
  );
}

function ArtifactPreviewSection({ section, sectionIndex }: { section: PreviewSection; sectionIndex: number }) {
  return (
    <section className="artifact-preview-section">
      <div className="artifact-section-title-row">
        <span>{sectionIndex + 1}</span>
        <h5>{section.title}</h5>
      </div>
      <div className="artifact-preview-section-body">
        {section.blocks.slice(0, 4).map((block, blockIndex) => (
          <PreviewBlockView block={block} key={blockIndex} compact={blockIndex > 1} />
        ))}
      </div>
      {section.blocks.length > 4 ? (
        <details className="artifact-section-details">
          <summary>展开本节完整内容</summary>
          <div className="artifact-preview-section-body">
            {section.blocks.slice(4).map((block, blockIndex) => (
              <PreviewBlockView block={block} key={blockIndex} />
            ))}
          </div>
        </details>
      ) : null}
    </section>
  );
}

function PreviewBlockView({ block, compact = false }: { block: PreviewBlock; compact?: boolean }) {
  if (block.type === "heading") {
    return <h6 className="artifact-subheading">{block.text}</h6>;
  }
  if (block.type === "list") {
    const visibleItems = compact ? block.items.slice(0, 4) : block.items;
    const hiddenCount = block.items.length - visibleItems.length;
    return (
      <div className="artifact-list-card">
        <ul>
          {visibleItems.map((item, itemIndex) => <li key={itemIndex}><InlineMathText text={item} /></li>)}
        </ul>
        {hiddenCount > 0 ? <small className="artifact-preview-more">还有 {hiddenCount} 条，展开后查看。</small> : null}
      </div>
    );
  }
  const text = compactText(block.text, compact ? 180 : 360);
  return (
    <p className="artifact-paragraph-card">
      <InlineMathText text={text} />
      {text !== block.text ? <small className="artifact-preview-more"> 已折叠长段落</small> : null}
    </p>
  );
}

type PreviewBlock =
  | { type: "heading"; text: string }
  | { type: "paragraph"; text: string }
  | { type: "list"; items: string[] };

interface PreviewSection {
  title: string;
  blocks: PreviewBlock[];
}

function previewSections(blocks: PreviewBlock[]): PreviewSection[] {
  const sections: PreviewSection[] = [];
  let current: PreviewSection = { title: "讲义正文", blocks: [] };
  const pushCurrent = () => {
    if (current.blocks.length) {
      sections.push(current);
    }
  };
  for (const block of blocks) {
    if (block.type === "heading") {
      pushCurrent();
      current = { title: block.text, blocks: [] };
    } else {
      current.blocks.push(block);
    }
  }
  pushCurrent();
  return sections.length ? sections : [{ title: "讲义正文", blocks: [{ type: "paragraph", text: "暂无可展示正文。" }] }];
}

function InlineMathText({ text }: { text: string }) {
  return (
    <>
      {splitMathText(text).map((segment) => {
        if (!segment.math) {
          return <span key={segment.key}>{segment.text}</span>;
        }
        const html = katex.renderToString(normalizeLatexExpression(segment.text), {
          displayMode: false,
          throwOnError: false,
          strict: false,
          trust: false,
        });
        return <span className="math-render inline" dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
      })}
    </>
  );
}

function splitMathText(text: string) {
  const segments: Array<{ key: string; text: string; math: boolean }> = [];
  let index = 0;
  let key = 0;
  while (index < text.length) {
    const start = text.indexOf("$", index);
    if (start < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(index), math: false });
      break;
    }
    if (start > index) {
      segments.push({ key: `text-${key++}`, text: text.slice(index, start), math: false });
    }
    const end = text.indexOf("$", start + 1);
    if (end < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(start), math: false });
      break;
    }
    const expression = text.slice(start + 1, end).trim();
    if (expression) {
      segments.push({ key: `math-${key++}`, text: expression, math: true });
    }
    index = end + 1;
  }
  return segments.length ? segments : [{ key: "text-0", text, math: false }];
}

function normalizeLatexExpression(value: string) {
  return value.replace(/\\\\(?=[A-Za-z])/g, "\\");
}

function workflowStageSummary(
  stage: MultiAgentWritingResponse["stages"][number] | undefined,
  workflowStatus: string,
) {
  if (!stage) {
    return workflowStatus === "RUNNING" ? "排队等待" : "未执行";
  }
  return `${statusLabel(stage.status)} · ${providerLabel(stage.providerName)} · ${stage.modelCode}`;
}

function compactWorkflowId(workflowId: string) {
  if (workflowId.length <= 18) {
    return workflowId;
  }
  return `${workflowId.slice(0, 8)}…${workflowId.slice(-6)}`;
}

function formatElapsed(startedAt?: string, endedAt?: string) {
  if (!startedAt) {
    return "";
  }
  const start = new Date(startedAt).getTime();
  const end = endedAt ? new Date(endedAt).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
    return "";
  }
  const totalSeconds = Math.max(1, Math.round((end - start) / 1000));
  if (totalSeconds < 60) {
    return `${totalSeconds} 秒`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds > 0 ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分`;
}

function markdownToBlocks(markdown: string): PreviewBlock[] {
  const blocks: PreviewBlock[] = [];
  let paragraph: string[] = [];
  let list: string[] = [];
  const flushParagraph = () => {
    if (paragraph.length) {
      blocks.push({ type: "paragraph", text: paragraph.join(" ").trim() });
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list.length) {
      blocks.push({ type: "list", items: list });
      list = [];
    }
  };
  let jsonLines: string[] | null = null;
  let jsonDepth = 0;
  for (const rawLine of markdown.replace(/\r\n/g, "\n").split("\n")) {
    const line = rawLine.trim();
    if (jsonLines) {
      jsonLines.push(rawLine);
      jsonDepth += jsonBraceDelta(rawLine);
      if (jsonDepth <= 0) {
        blocks.push(...jsonPreviewBlocks(jsonLines.join("\n")));
        jsonLines = null;
        jsonDepth = 0;
      }
      continue;
    }
    if (!line) {
      flushParagraph();
      flushList();
      continue;
    }
    if (/^[-*_]{3,}$/.test(line)) {
      flushParagraph();
      flushList();
      continue;
    }
    if (line.startsWith("#")) {
      flushParagraph();
      flushList();
      blocks.push({ type: "heading", text: cleanPreviewText(line.replace(/^#+\s*/, "")) });
    } else if (line.startsWith("{")) {
      flushParagraph();
      flushList();
      jsonLines = [rawLine];
      jsonDepth = jsonBraceDelta(rawLine);
      if (jsonDepth <= 0) {
        blocks.push(...jsonPreviewBlocks(jsonLines.join("\n")));
        jsonLines = null;
        jsonDepth = 0;
      }
    } else if (line.startsWith("- ") || line.startsWith("* ")) {
      flushParagraph();
      list.push(cleanPreviewText(line.slice(2).trim()));
    } else if (line.includes("|") && line.replace(/[|:\-\s]/g, "").length === 0) {
      flushParagraph();
      continue;
    } else if (line.startsWith("|") && line.endsWith("|")) {
      flushParagraph();
      const cells = line.split("|").map((cell) => cleanPreviewText(cell)).filter(Boolean);
      if (cells.length) {
        list.push(cells.join("："));
      }
    } else if (looksLikeJson(line)) {
      flushParagraph();
      flushList();
      blocks.push(...jsonPreviewBlocks(line));
    } else {
      flushList();
      paragraph.push(cleanPreviewText(line));
    }
  }
  if (jsonLines) {
    blocks.push(...jsonPreviewBlocks(jsonLines.join("\n")));
  }
  flushParagraph();
  flushList();
  return blocks.length ? blocks : [{ type: "paragraph", text: "暂无可展示正文。" }];
}

function jsonBraceDelta(line: string) {
  let delta = 0;
  let inString = false;
  let escaped = false;
  for (const char of line) {
    if (escaped) {
      escaped = false;
      continue;
    }
    if (char === "\\") {
      escaped = true;
      continue;
    }
    if (char === "\"") {
      inString = !inString;
      continue;
    }
    if (!inString && char === "{") {
      delta += 1;
    } else if (!inString && char === "}") {
      delta -= 1;
    }
  }
  return delta;
}

function looksLikeJson(value: string) {
  const text = value.trim();
  return text.startsWith("{") && text.endsWith("}");
}

function jsonPreviewBlocks(value: string): PreviewBlock[] {
  const candidates = [
    value,
    value.replace(/\\"/g, "\""),
    value.replace(/^"|"$/g, "").replace(/\\"/g, "\""),
    escapeMathBackslashes(value),
    escapeMathBackslashes(value.replace(/\\"/g, "\"")),
    escapeMathBackslashes(value.replace(/^"|"$/g, "").replace(/\\"/g, "\"")),
  ];
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate) as unknown;
      const blocks: PreviewBlock[] = [];
      appendStructuredPreview(parsed, blocks, "");
      return blocks.length ? blocks : [{ type: "paragraph", text: "该阶段已返回结构化内容，暂无可展示正文。" }];
    } catch {
      // Try the next normalized representation; older traces may store escaped JSON text.
    }
  }
  try {
    const parsed = JSON.parse(JSON.parse(value) as string) as unknown;
    const blocks: PreviewBlock[] = [];
    appendStructuredPreview(parsed, blocks, "");
    return blocks.length ? blocks : [{ type: "paragraph", text: "该阶段已返回结构化内容，暂无可展示正文。" }];
  } catch {
    return [{ type: "paragraph", text: compactText(value, 260) }];
  }
}

function escapeMathBackslashes(value: string) {
  return value.replace(/\\(?=[A-Za-z])/g, "\\\\");
}

function appendStructuredPreview(value: unknown, blocks: PreviewBlock[], fallbackTitle: string) {
  if (typeof value === "string") {
    const text = cleanPreviewText(value);
    if (text) {
      blocks.push({ type: "paragraph", text });
    }
    return;
  }
  if (Array.isArray(value)) {
    const simpleItems = value
      .filter((item): item is string | number | boolean => ["string", "number", "boolean"].includes(typeof item))
      .map((item) => cleanPreviewText(String(item)))
      .filter(Boolean);
    if (simpleItems.length === value.length && simpleItems.length > 0) {
      blocks.push({ type: "list", items: simpleItems });
      return;
    }
    for (const item of value) {
      appendStructuredPreview(item, blocks, fallbackTitle);
    }
    return;
  }
  if (!value || typeof value !== "object") {
    return;
  }

  const record = value as Record<string, unknown>;
  const title = textValue(record.title) || textValue(record.section) || fallbackTitle;
  if (title) {
    blocks.push({ type: "heading", text: title });
  }

  const directContent = textValue(record.content);
  if (directContent) {
    blocks.push({ type: "paragraph", text: directContent });
  }

  appendNamedValue("学习目标", record.objectives, blocks);
  appendNamedValue("核心概念", record.core_concepts, blocks);
  appendNamedValue("计算示例", record.calculation_example, blocks);
  appendNamedValue("常见错误", record.common_mistakes, blocks);
  appendNamedValue("课堂练习", record.class_exercise, blocks);
  appendNamedValue("总结与作业", record.summary_and_homework, blocks);
  appendNamedValue("教师提示", record.teacher_tips, blocks);

  if (Array.isArray(record.content)) {
    for (const item of record.content) {
      appendStructuredPreview(item, blocks, "");
    }
  }
}

function appendNamedValue(label: string, value: unknown, blocks: PreviewBlock[]) {
  if (value == null) {
    return;
  }
  if (typeof value === "string") {
    const text = cleanPreviewText(value);
    if (text) {
      blocks.push({ type: "heading", text: label });
      blocks.push({ type: "paragraph", text });
    }
    return;
  }
  if (Array.isArray(value)) {
    const items = value.map((item) => cleanPreviewText(String(item))).filter(Boolean);
    if (items.length) {
      blocks.push({ type: "heading", text: label });
      blocks.push({ type: "list", items });
    }
    return;
  }
  if (typeof value === "object") {
    const items = Object.entries(value as Record<string, unknown>)
      .map(([key, item]) => `${key}：${String(item).trim()}`)
      .filter((item) => item.length > 1);
    if (items.length) {
      blocks.push({ type: "heading", text: label });
      blocks.push({ type: "list", items });
    }
  }
}

function textValue(value: unknown) {
  return typeof value === "string" ? value.trim() : "";
}

function cleanPreviewText(value: string) {
  return value
    .replace(/[*_`]+/g, "")
    .replace(/^#+\s*/g, "")
    .replace(/\s+/g, " ")
    .replace(/^[\u{1F300}-\u{1FAFF}\u2600-\u27BF]\s*/u, "")
    .trim();
}

function stageLabel(stage: string) {
  const labels: Record<string, string> = {
    resource_curation: "资料汇总",
    template_selection: "模板选择",
    outline_planning: "共享大纲",
    teacher_writer: "教师版",
    student_writer: "学生版",
    lecture_writer: "16:10 讲解版",
    source_review: "来源审查",
    student_safety_review: "学生版安全审查",
    layout_review: "版式审查",
    merge_coordinator: "合并结果",
    draft: "讲义初稿",
    review: "质量审校",
    format: "排版整理",
    writing_draft: "讲义初稿",
    writing_review: "质量审校",
    writing_format: "排版整理",
    CoursewareAgent: "讲义初稿",
    CoursewareReviewer: "质量审校",
    QualityCheckAgent: "质量审校",
    HandoutFormatterAgent: "排版整理",
  };
  return labels[stage] ?? stage;
}

/** Formats the durable per-stage timing supplied by the backend without inventing a client-side duration. */
function stageMetrics(stage: MultiAgentWritingResponse["stages"][number]) {
  const duration = typeof stage.elapsedMs === "number" && stage.elapsedMs >= 0
    ? ` / 耗时 ${stage.elapsedMs.toLocaleString("zh-CN")} ms`
    : "";
  return `${statusLabel(stage.status)} / ${providerLabel(stage.providerName)} / ${stage.modelCode} / 用量 ${stage.actualUsage.totalTokens.toLocaleString("zh-CN")}${duration}`;
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

function workflowMessage(message: string) {
  const labels: Record<string, string> = {
    "Multi-agent writing workflow queued.": "任务已进入队列，正在等待执行。",
    "Multi-agent writing workflow started.": "任务已开始执行。",
    "Draft stage completed.": "初稿阶段已返回草案，系统正在继续审校。",
    "Review stage completed.": "审校阶段已完成，系统正在继续排版。",
    "Format stage completed.": "排版阶段已完成，正在整理最终交付文件。",
    "Multi-agent writing workflow completed.": "写作流程已完成，可以审查和导出。",
    "Workflow completed": "写作流程已完成，可以审查和导出。",
  };
  return labels[message] ?? compactText(message, 100);
}
