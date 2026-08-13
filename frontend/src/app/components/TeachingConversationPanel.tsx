import katex from "katex";
import { ChangeEvent, ClipboardEvent, FormEvent, useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, ChevronDown, ExternalLink, Loader2, Plus, Sparkles, X } from "lucide-react";
import {
  StudentExplanationConversationSummary,
  StudentExplanationImageUploadResponse,
  StudentExplanationResponse,
  StudentExplanationStage,
  StudentExplanationStreamProgress,
} from "../../shared/api/textbookApi";

export type TeachingConversationThreadItem =
  | {
      id: string;
      role: "user";
      questionText: string;
      imagePreviewUrl?: string;
      imageFileName?: string;
      imageStatus?: string;
      createdAt: string;
    }
  | {
      id: string;
      role: "assistant";
      createdAt: string;
      questionText?: string;
      imagePreviewUrl?: string;
      imageFileName?: string;
      imageStatus?: string;
      loading?: boolean;
      error?: string;
      response?: StudentExplanationResponse;
      /** Latest server-sent workflow snapshot, never advanced by a client timer. */
      progress?: StudentExplanationStreamProgress;
      /** Text delta received from the live model stream. */
      liveContent?: string;
      /** Compatibility-only provider reasoning field. Raw reasoning is intentionally never rendered to the learner. */
      liveThinking?: string;
    };

type ConversationImageDraft = StudentExplanationImageUploadResponse & {
  previewUrl: string;
};

export function TeachingConversationPanel({
  conversationTitle,
  value,
  entries,
  recentConversations,
  loading,
  loadingHistory,
  error,
  imageDraft,
  uploadingImage,
  imageError,
  conversationMemoryEnabled: _conversationMemoryEnabled,
  openingConversationId,
  onValueChange,
  onSubmit,
  onImageSelect,
  onClearImage,
  onConversationMemoryChange: _onConversationMemoryChange,
  onStartNewConversation,
  onOpenConversation,
}: {
  conversationTitle: string;
  value: string;
  entries: TeachingConversationThreadItem[];
  recentConversations: StudentExplanationConversationSummary[];
  loading: boolean;
  loadingHistory: boolean;
  error: string;
  imageDraft: ConversationImageDraft | null;
  uploadingImage: boolean;
  imageError: string;
  /** Deprecated compatibility props; conversation context is always enabled within one conversation. */
  conversationMemoryEnabled?: boolean;
  openingConversationId: string;
  onValueChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onImageSelect: (file: File | null) => void;
  onClearImage: () => void;
  /** Deprecated compatibility callback; context is no longer opt-in. */
  onConversationMemoryChange?: (enabled: boolean) => void;
  onStartNewConversation: () => void;
  onOpenConversation: (conversation: StudentExplanationConversationSummary) => void;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const followsLatestRef = useRef(true);
  const [clipboardError, setClipboardError] = useState("");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const latestHistory = recentConversations.slice(0, 3);

  useEffect(() => {
    const container = scrollRef.current;
    // Do not steal the scroll position while the reader is inspecting an earlier streamed card.
    if (!container || !followsLatestRef.current) return;
    container.scrollTo({ top: container.scrollHeight, behavior: "auto" });
  }, [entries, loading]);

  function handleConversationScroll() {
    const container = scrollRef.current;
    if (!container) return;
    const remaining = container.scrollHeight - container.scrollTop - container.clientHeight;
    followsLatestRef.current = remaining <= 24;
  }

  function handlePickImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setClipboardError("");
    onImageSelect(file);
    event.target.value = "";
  }

  function handlePaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    const imageFile = firstClipboardImage(event.clipboardData?.items);
    if (!imageFile) return;
    event.preventDefault();
    setClipboardError("");
    onImageSelect(imageFile);
  }

  async function handlePasteImageFromClipboard() {
    if (!navigator.clipboard?.read) {
      setClipboardError("当前浏览器不支持主动读取剪贴板图片，可以直接按 Ctrl+V 粘贴。");
      return;
    }
    try {
      const items = await navigator.clipboard.read();
      for (const item of items) {
        const type = item.types.find((candidate) => candidate.startsWith("image/"));
        if (!type) continue;
        const blob = await item.getType(type);
        const file = new File([blob], `clipboard-image.${type.split("/")[1] || "png"}`, { type });
        setClipboardError("");
        onImageSelect(file);
        return;
      }
      setClipboardError("剪贴板里没有图片。");
    } catch {
      setClipboardError("读取剪贴板失败，可以直接按 Ctrl+V 粘贴。");
    }
  }

  const composerError = imageError || clipboardError;

  return (
    <section className="teaching-live-shell teaching-chat-shell" aria-label="AI 讲题">
      <header className="teaching-live-header teaching-chat-header-fixed">
        <div className="teaching-live-brand teaching-chat-header-main">
          <button
            type="button"
            className="teaching-live-brand-icon teaching-chat-drawer-trigger"
            onClick={() => setDrawerOpen((current) => !current)}
            aria-label={drawerOpen ? "收起历史抽屉" : "打开历史抽屉"}
            aria-expanded={drawerOpen}
          >
            <Sparkles size={16} />
          </button>
          <div className="teaching-live-brand-copy">
            <strong><InlineMathText text={safeUserFacingText(conversationTitle, "AI 讲题")} /></strong>
          </div>
        </div>
        <div className="teaching-live-toolbar">
          <button className="teaching-new-conversation-btn" type="button" disabled={loading} onClick={onStartNewConversation}>
            <Plus size={15} />
            <span>新建对话</span>
          </button>
          {latestHistory.length ? (
            <div className="teaching-history-strip" aria-label="最近讲题记录">
              {loadingHistory ? (
                <span className="teaching-history-chip muted"><Loader2 className="spin" size={12} />同步中</span>
              ) : latestHistory.map((item) => (
                <button
                  type="button"
                  className="teaching-history-chip"
                  key={item.conversationId}
                  title={titleTooltip(item.title)}
                  disabled={loading || openingConversationId === item.conversationId}
                  onClick={() => onOpenConversation(item)}
                >
                  <InlineMathText text={safeUserFacingText(item.title, "最近讲题")} />
                </button>
              ))}
            </div>
          ) : null}
        </div>
      </header>

      <div
        className={`teaching-chat-drawer-backdrop${drawerOpen ? " open" : ""}`}
        onClick={() => setDrawerOpen(false)}
        aria-hidden={!drawerOpen}
      />
      <aside className={`teaching-chat-drawer${drawerOpen ? " open" : ""}`} aria-label="AI讲题历史抽屉">
        <div className="teaching-chat-drawer-head">
          <strong>最近讲题</strong>
          <button type="button" className="teaching-chat-drawer-close" onClick={() => setDrawerOpen(false)} aria-label="关闭历史抽屉">
            <ArrowLeft size={16} />
          </button>
        </div>
        <div className="teaching-chat-drawer-list">
          {recentConversations.length ? recentConversations.slice(0, 12).map((item) => (
            <button
              type="button"
              className="teaching-chat-drawer-item"
              key={item.conversationId}
              title={titleTooltip(item.title)}
              disabled={loading || openingConversationId === item.conversationId}
              onClick={() => {
                setDrawerOpen(false);
                onOpenConversation(item);
              }}
            >
              <strong><InlineMathText text={safeUserFacingText(item.title, "最近讲题")} /></strong>
              <span>{openingConversationId === item.conversationId ? "正在加载" : `${item.totalMessages} 轮`}</span>
            </button>
          )) : (
            <div className="teaching-chat-drawer-empty">还没有历史讲题记录。</div>
          )}
        </div>
      </aside>

      <div className="teaching-live-scroll" ref={scrollRef} onScroll={handleConversationScroll}>
        {!entries.length ? (
          <div className="teaching-inline-guide">
            <span>发题目</span>
            <span>贴题图</span>
            <span>继续追问</span>
          </div>
        ) : null}

        {entries.map((entry) => entry.role === "user" ? (
          <article className="teaching-user-row" key={entry.id}>
            <div className="teaching-user-bubble">
              {entry.imagePreviewUrl ? (
                <div className="teaching-inline-image">
                  <img src={entry.imagePreviewUrl} alt={entry.imageFileName || "题图"} />
                </div>
              ) : null}
              <RichText text={entry.questionText} />
              {entry.imageFileName ? (
                <div className="teaching-inline-meta">
                  <span>{entry.imageFileName}</span>
                  <span>{imageStatusText(entry.imageStatus)}</span>
                </div>
              ) : null}
            </div>
          </article>
        ) : (
          <article className="teaching-assistant-row" key={entry.id}>
            <div className="teaching-assistant-avatar">AI</div>
            <div className="teaching-assistant-flow">
              {entry.response ? (
                <AssistantResponse response={entry.response} />
              ) : entry.loading ? (
                <LiveAssistantResponse entry={entry} />
              ) : entry.error ? (
                <section className="teaching-status-card error">
                  <div className="teaching-status-head">
                    <strong>这次讲解没有完成</strong>
                    <span>已停止</span>
                  </div>
                  <p>{safeOperationMessage(entry.error)}</p>
                </section>
              ) : null}
            </div>
          </article>
        ))}

        {error && !entries.some((entry) => entry.role === "assistant" && entry.error === error) ? (
          <div className="teaching-global-error">{error}</div>
        ) : null}
      </div>

      <form className="teaching-live-composer" onSubmit={onSubmit}>
        <input ref={fileInputRef} accept="image/*" className="sr-only-input" type="file" onChange={handlePickImage} />
        <div className="teaching-composer-box">
          {imageDraft || uploadingImage ? (
            <div className="teaching-upload-strip">
              {imageDraft ? (
                <div className="teaching-upload-chip">
                  <div className="teaching-upload-thumb">
                    <img src={imageDraft.previewUrl} alt={imageDraft.originalFileName || "题图"} />
                  </div>
                  <div className="teaching-upload-copy">
                    <strong>{imageDraft.originalFileName}</strong>
                    <span>题图已上传，发送后原图直接进入 AI 上下文。</span>
                  </div>
                  <button className="teaching-upload-remove" type="button" onClick={onClearImage} aria-label="移除图片">
                    <X size={15} />
                  </button>
                </div>
              ) : (
                <div className="teaching-upload-chip pending">
                  <div className="teaching-upload-thumb teaching-upload-thumb-placeholder"><Plus size={16} /></div>
                  <div className="teaching-upload-copy">
                    <strong>正在上传题图</strong>
                    <span>上传完成后会出现在输入框上方。</span>
                  </div>
                  <Loader2 className="spin" size={16} />
                </div>
              )}
            </div>
          ) : null}

          <div className="teaching-composer-field">
            <textarea
              value={value}
              onChange={(event) => onValueChange(event.target.value)}
              onPaste={handlePaste}
              placeholder="输入题目、追问或补充条件"
              rows={2}
            />
            <div className="teaching-composer-actions">
              <div className="teaching-composer-left">
                <button className="teaching-icon-btn" type="button" onClick={() => fileInputRef.current?.click()} aria-label="上传图片">
                  <Plus size={17} />
                </button>
                <button className="teaching-quick-chip" type="button" onClick={() => fileInputRef.current?.click()}>
                  <Plus size={14} />上传图片
                </button>
                <button className="teaching-quick-chip subtle" type="button" onClick={handlePasteImageFromClipboard}>
                  <Sparkles size={14} />粘贴图片
                </button>
              </div>
              <button className="teaching-send-btn" type="submit" disabled={loading || uploadingImage || (!value.trim() && !imageDraft)}>
                {loading ? <Loader2 className="spin" size={17} /> : <ArrowRight size={17} />}
              </button>
            </div>
          </div>
          {composerError ? <div className="teaching-inline-error">{composerError}</div> : null}
        </div>
      </form>
    </section>
  );
}

/**
 * Shows only stages and text that have arrived from the backend SSE stream. The elapsed clock is measured locally
 * from the real request start because backend snapshots do not advance while the provider is producing deltas.
 */
function LiveAssistantResponse({ entry }: { entry: Extract<TeachingConversationThreadItem, { role: "assistant" }> }) {
  const progress = entry.progress;
  const stages = visibleWorkflowStages(progress?.workflowStages ?? []);
  const sources = progress?.sources ?? [];
  const image = progress?.imageUnderstanding;
  const [liveElapsedMs, setLiveElapsedMs] = useState(() => liveElapsedSince(entry.createdAt, progress?.totalElapsedMs));
  const liveAnswer = useCharacterRenderedText(liveTextForDisplay(entry.liveContent ?? "", sources));

  useEffect(() => {
    if (entry.response || entry.error) return;
    const refresh = () => setLiveElapsedMs(liveElapsedSince(entry.createdAt, progress?.totalElapsedMs));
    refresh();
    const timer = globalThis.setInterval(refresh, LIVE_ELAPSED_REFRESH_MS);
    return () => globalThis.clearInterval(timer);
  }, [entry.createdAt, entry.error, entry.response, progress?.totalElapsedMs]);

  return (
    <>
      <section className="teaching-status-card pending compact">
        <div className="teaching-status-head compact">
          <strong>正在讲解</strong>
          <span><Loader2 className="spin" size={12} />{formatElapsed(liveElapsedMs)}</span>
        </div>
        {progress?.questionText || entry.questionText ? (
          <p className="teaching-status-question">{progress?.questionText || entry.questionText}</p>
        ) : null}
        {stages.length ? (
          <div className="teaching-trace-live compact" aria-label="真实处理过程">
            {stages.map((stage) => (
              <div className={`teaching-trace-live-item ${stageTone(stage.status)}`} key={stage.stageKey}>
                <span className="teaching-trace-live-dot" />
                <div>
                  <strong>{stageTitleText(stage.stageKey, stage.title)}</strong>
                  <small>{stageDetailText(stage)}</small>
                </div>
              </div>
            ))}
          </div>
        ) : null}
        {image?.problemText ? (
          <details className="teaching-live-details">
            <summary>查看题图识别结果</summary>
            <RichText text={image.problemText} />
            <span>识别置信度 {Math.round(image.confidence * 100)}%</span>
          </details>
        ) : null}
      </section>
      {sources.length ? (
        <section className="teaching-live-sources" aria-label="已找到的资料">
          <div className="teaching-live-sources-head">
            <strong>已找到的资料</strong>
            <span>{sources.length} 条</span>
          </div>
          <EvidenceSourceList sources={sources} />
        </section>
      ) : null}
      {liveAnswer ? (
        <section className="teaching-response-card agent teaching-live-answer" aria-label="讲解内容">
          <div className="teaching-rich-block">
            <RichText text={liveAnswer} />
          </div>
        </section>
      ) : null}
    </>
  );
}

/** Adds each received character to the same answer card so network-sized SSE frames never appear all at once. */
function useCharacterRenderedText(source: string) {
  // The initial source is rendered for server/static output only. During an actual conversation the pending card
  // starts empty, then every later SSE update enters through the character queue below.
  const [rendered, setRendered] = useState(source);

  useEffect(() => {
    setRendered((current) => source.startsWith(current) ? current : "");
  }, [source]);

  useEffect(() => {
    if (!source || rendered.length >= source.length || !source.startsWith(rendered)) return;
    const timer = globalThis.setTimeout(
      () => setRendered(source.slice(0, rendered.length + 1)),
      CHARACTER_RENDER_INTERVAL_MS,
    );
    return () => globalThis.clearTimeout(timer);
  }, [rendered, source]);

  return rendered;
}

function AssistantResponse({ response }: { response: StudentExplanationResponse }) {
  const cards = visibleExplanationCards(response.cards ?? []);
  const sources = response.sources ?? [];
  const stages = visibleWorkflowStages(response.workflowStages ?? []);

  return (
    <div className="teaching-answer-layout">
      <div className="teaching-answer-content">
        {cards.length ? cards.map((card, index) => (
          <ExplanationCard key={`${response.explanationId}:${card.cardKey}:${index}`} card={card} sources={sources} />
        )) : (
          <section className="teaching-response-card primary core">
            <div className="teaching-response-head">
              <div><strong>讲解结果</strong></div>
            </div>
            <div className="teaching-rich-block"><RichText text={safeUserFacingText(response.questionText, "已收到本次问题。")} /></div>
          </section>
        )}
      </div>
      <EvidenceInspector response={response} stages={stages} sources={sources} />
    </div>
  );
}

/**
 * Exposes only server-produced evidence and stage snapshots. It is collapsed by default so the lesson remains the
 * primary reading surface; opening it never fabricates a progress timeline or silently truncates source evidence.
 */
function EvidenceInspector({
  response,
  stages,
  sources,
}: {
  response: StudentExplanationResponse;
  stages: StudentExplanationStage[];
  sources: StudentExplanationResponse["sources"];
}) {
  if (!stages.length && !sources.length) return null;
  const sourceSummary = sourceSummaryText(sources);
  const assignedSourceTypes = new Set(
    stages.map((stage) => retrievalSourceType(stage.stageKey)).filter((type): type is string => type !== null),
  );
  const unassignedSources = sources.filter((source) => !assignedSourceTypes.has(source.sourceType));
  return (
    <aside className="teaching-evidence-inspector" aria-label="本轮检索与执行记录">
      <details className="ai-run-disclosure teaching-evidence-disclosure">
        <summary>
          <span>执行与证据</span>
          <span className="teaching-evidence-summary">{stages.length ? `${stages.length} 个实际步骤` : sourceSummary}</span>
          <ChevronDown size={15} aria-hidden="true" />
        </summary>
        <div className="teaching-evidence-body">
          {stages.length ? (
            <section className="teaching-subtle-panel">
              <div className="teaching-subtle-head">
                <span>AI 轨迹</span>
                <em>{stages.length} 个实际步骤</em>
              </div>
              <div className="teaching-trace-inline-list">
                {stages.map((stage, index) => (
                  <WorkflowStageEvidence
                    key={`${response.explanationId}:${stage.stageKey}:${index}`}
                    stage={stage}
                    index={index}
                    sources={sourcesForRetrievalStage(stage.stageKey, sources)}
                  />
                ))}
              </div>
            </section>
          ) : null}
          {unassignedSources.length ? (
            <section className="teaching-subtle-panel">
              <div className="teaching-subtle-head">
                <span>其他来源</span>
                <em>{unassignedSources.length} 条</em>
              </div>
              <EvidenceSourceList sources={unassignedSources} />
            </section>
          ) : null}
        </div>
      </details>
    </aside>
  );
}

/**
 * Keeps a retrieval stage and the evidence used by that exact stage together. Non-retrieval stages intentionally
 * remain plain timeline rows because they do not have a source collection to inspect.
 */
function WorkflowStageEvidence({
  stage,
  index,
  sources,
}: {
  stage: StudentExplanationStage;
  index: number;
  sources: StudentExplanationResponse["sources"] | null;
}) {
  const stageContent = <WorkflowStageSummary stage={stage} index={index} />;
  if (sources === null) return stageContent;
  return (
    <details className={`ai-run-disclosure teaching-retrieval-stage ${stageTone(stage.status)}`} data-retrieval-stage={stage.stageKey}>
      <summary>{stageContent}</summary>
      <div className="teaching-retrieval-evidence">
        {sources.length ? <EvidenceSourceList sources={sources} /> : (
          <p className="teaching-retrieval-empty">本步骤没有纳入可展示的资料。</p>
        )}
      </div>
    </details>
  );
}

/** Renders a stage consistently whether it is a plain operation or an expandable retrieval operation. */
function WorkflowStageSummary({ stage, index }: { stage: StudentExplanationStage; index: number }) {
  return (
    <article className={`teaching-trace-inline-item ${stageTone(stage.status)}`}>
      <span>{index + 1}</span>
      <strong>{stageTitleText(stage.stageKey, stage.title)}</strong>
      <p>{stageDetailText(stage)}</p>
      <em>{formatElapsed(stage.elapsedMs)}</em>
      {retrievalSourceType(stage.stageKey) ? <ChevronDown className="teaching-retrieval-chevron" size={14} aria-hidden="true" /> : null}
    </article>
  );
}

/** Displays only backend-returned evidence; the same component is reused by every retrieval stage. */
function EvidenceSourceList({ sources }: { sources: StudentExplanationResponse["sources"] }) {
  return (
    <div className="teaching-source-inline-list">
      {sources.map((source, index) => (
        <article className="teaching-source-inline-item" key={`${source.sourceUri}:${index}`}>
          <strong>{safeUserFacingText(source.title, "命中资料")}</strong>
          <span>{source.score.toFixed(2)}</span>
          <p>{compactText(source.snippet, 72)}</p>
          {source.sourcePath?.trim() ? (
            <div className="teaching-source-path" title={source.sourcePath}>
              <span>资料路径</span>
              <code>{safeUserFacingText(source.sourcePath, "")}</code>
            </div>
          ) : null}
          {isSafeSourceUrl(source.openUrl) ? (
            <a className="teaching-source-open-link" href={source.openUrl} target="_blank" rel="noreferrer">
              <span>查看原文</span>
              <ExternalLink size={12} aria-hidden="true" />
            </a>
          ) : null}
        </article>
      ))}
    </div>
  );
}

/** Maps stable backend stage keys to their exact source category; titles are user-facing text and never routing data. */
function retrievalSourceType(stageKey: string): string | null {
  const sourceTypes: Record<string, string> = {
    search_textbook: "textbook",
    match_knowledge_graph: "knowledge_graph",
    search_teacher_resources: "teacher_resource",
  };
  return sourceTypes[stageKey] ?? null;
}

/** Returns null for normal workflow steps and a possibly empty source list for a retrieval step. */
function sourcesForRetrievalStage(
  stageKey: string,
  sources: StudentExplanationResponse["sources"],
): StudentExplanationResponse["sources"] | null {
  const sourceType = retrievalSourceType(stageKey);
  return sourceType === null ? null : sources.filter((source) => source.sourceType === sourceType);
}

/** Counts the same source list delivered to the model and learner; no display-only limit may change these numbers. */
function sourceSummaryText(sources: StudentExplanationResponse["sources"]) {
  const counts = new Map<string, number>();
  for (const source of sources) {
    counts.set(source.sourceType, (counts.get(source.sourceType) ?? 0) + 1);
  }
  const labels: Record<string, string> = {
    textbook: "教材",
    knowledge_graph: "知识图谱",
    teacher_resource: "教师资料",
  };
  return [...counts.entries()]
    .map(([type, count]) => `${labels[type] ?? type} ${count} 条`)
    .join(" · ");
}

/**
 * Permits only browser-safe remote source URLs supplied by the backend.
 *
 * Source entries can originate from textbook pages, local files, and Feishu documents. The UI must not turn an
 * opaque retrieval URI or a filesystem path into a clickable navigation target, but an audited HTTP(S) openUrl is
 * intentionally safe to expose so learners can inspect the exact evidence used by the RAG result.
 */
function isSafeSourceUrl(value?: string): value is string {
  return /^https?:\/\/\S+$/i.test(value?.trim() ?? "");
}

export function visibleExplanationCards(cards: StudentExplanationResponse["cards"]) {
  // A card is only a typed transport unit. The agent decides whether it needs one section or many, and in what order.
  return cards;
}

const LEGACY_SYNTHETIC_STAGE_KEYS = new Set(["plan_explanation", "understand_problem", "default_retrieval"]);

/** Keeps the trace factual: skipped/pending work and legacy pre-announced stages are never presented as execution. */
export function visibleWorkflowStages(stages: StudentExplanationStage[]) {
  return stages.filter((stage) =>
    stage.status !== "skipped"
    && stage.status !== "pending"
    && !LEGACY_SYNTHETIC_STAGE_KEYS.has(stage.stageKey));
}

/** Renders one backend-produced section without inferring a template role from its title or position. */
function ExplanationCard({
  card,
  sources,
}: {
  card: StudentExplanationResponse["cards"][number];
  sources: StudentExplanationResponse["sources"];
}) {
  // An absent title is intentional for a continuous agent answer; the UI must not inject a template heading.
  const safeTitle = safeUserFacingText(replaceVisibleSourceReferences(card.title, sources), "");
  const safeSummary = safeUserFacingText(replaceVisibleSourceReferences(card.summary, sources), "");
  const safeItems = (card.items ?? [])
    .map((item) => safeUserFacingText(replaceVisibleSourceReferences(item, sources), ""))
    .filter((item) => item.trim().length > 0);
  return (
    <section className="teaching-response-card agent">
      {/* Card titles come from the same AI response as the body. Render their TeX too so raw delimiters never leak into the answer heading. */}
      {safeTitle ? <div className="teaching-response-head"><strong><InlineMathText text={safeTitle} /></strong></div> : null}
      {safeSummary ? <div className="teaching-rich-block"><RichText text={safeSummary} /></div> : null}
      {safeItems.length ? (
        <ul className="teaching-response-list">
          {safeItems.map((item, itemIndex) => <li key={`${card.cardKey}:${itemIndex}`}><RichText text={item} /></li>)}
        </ul>
      ) : null}
    </section>
  );
}

function RichText({ text }: { text: string }) {
  return (
    <>
      {(text || "")
        .split(/\n+/)
        .filter((line) => line.trim().length > 0)
        .map((line, lineIndex) => (
          <span className="teaching-rich-line" key={`line-${lineIndex}`}>
            {splitMathText(line).map((segment) => {
              if (!segment.math) return <span key={segment.key}>{segment.text}</span>;
              const expression = segment.text;
              if (hasUnbalancedBraces(expression)) return <span key={segment.key}>{segment.raw}</span>;
              try {
                const html = katex.renderToString(expression, {
                  displayMode: segment.display,
                  throwOnError: true,
                  strict: false,
                  trust: false,
                });
                return <span className={`math-render ${segment.display ? "display" : "inline"}`} dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
              } catch {
                return <span key={segment.key}>{segment.raw}</span>;
              }
            })}
          </span>
        ))}
    </>
  );
}

/**
 * Renders formula delimiters in compact conversation chrome without turning a title into a multi-line text block.
 * The same parser as answer cards is used so a stored title such as “解方程 $x^2-5x+6=0$” never exposes raw LaTeX.
 */
function InlineMathText({ text }: { text: string }) {
  return (
    <span className="teaching-title-math">
      {splitMathText(text).map((segment) => {
        if (!segment.math) return <span key={segment.key}>{segment.text}</span>;
        if (hasUnbalancedBraces(segment.text)) return <span key={segment.key}>{segment.raw}</span>;
        try {
          const html = katex.renderToString(segment.text, {
            // A header must remain one compact row even if a provider accidentally uses a display delimiter.
            displayMode: false,
            throwOnError: true,
            strict: false,
            trust: false,
          });
          return <span className="math-render inline" dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
        } catch {
          return <span key={segment.key}>{segment.raw}</span>;
        }
      })}
    </span>
  );
}

/** Keeps native hover text readable without exposing the TeX delimiter syntax that the visual title already renders. */
function titleTooltip(value: string) {
  return safeUserFacingText(value, "最近讲题")
    .replace(/\$\$([\s\S]+?)\$\$|\$([^$\n]+?)\$/g, (_whole, display, inline) => display ?? inline ?? "")
    .replace(/\\\[([\s\S]+?)\\\]|\\\(([^\n]+?)\\\)/g, (_whole, display, inline) => display ?? inline ?? "")
    .replace(/\s+/g, " ")
    .trim();
}

function splitMathText(text: string) {
  const segments: Array<{ key: string; text: string; raw: string; math: boolean; display: boolean }> = [];
  let index = 0;
  let key = 0;
  const pattern = /(\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\]|\$[^$\n]+?\$|\\\([\s\S]+?\\\))/g;
  for (const match of text.matchAll(pattern)) {
    const start = match.index ?? 0;
    if (start > index) {
      const plainText = text.slice(index, start);
      segments.push({ key: `text-${key++}`, text: plainText, raw: plainText, math: false, display: false });
    }
    const raw = match[0];
    const display = raw.startsWith("$$") || raw.startsWith("\\[");
    const expression = raw.replace(/^\$\$|\$\$$/g, "").replace(/^\\\[|\\\]$/g, "").replace(/^\$|\$$/g, "").replace(/^\\\(|\\\)$/g, "").trim();
    if (expression) segments.push({ key: `math-${key++}`, text: expression, raw, math: true, display });
    index = start + raw.length;
  }
  if (index < text.length) {
    const plainText = text.slice(index);
    segments.push({ key: `text-${key++}`, text: plainText, raw: plainText, math: false, display: false });
  }
  return segments.length ? segments : [{ key: "text-0", text, raw: text, math: false, display: false }];
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

function stageTone(status: string) {
  if (status === "failed") return "failed";
  if (status === "completed") return "completed";
  return "running";
}

export function stageTitleText(_stageKey: string, title: string) {
  // Stage titles are produced by the backend orchestration that actually ran. Never replace them with a UI template.
  return safeUserFacingText(title, "实际处理步骤");
}

/**
 * The provider uses a strict JSON transport contract, but that wire format is not a learner-facing answer.
 * During streaming, expose only explanation fields already present in the partial object and never print braces,
 * property names, or escaped JSON to the page. Once a complete card arrives, the normal card renderer takes over.
 */
function liveTextForDisplay(raw: string, sources: StudentExplanationResponse["sources"] = []) {
  const value = (raw || "").trim();
  if (!value) return "";
  // Some compatible providers stream ordinary Markdown instead of the structured card envelope.
  // Preserve that real text verbatim so the learner sees useful progress immediately.
  if (!/^[\[{]/.test(value)) return replaceVisibleSourceReferences(value, sources);
  const fields: string[] = [];
  const fieldPattern = /"(?:conversationTitle|title|summary|item|items)"\s*:\s*(?:"((?:\\.|[^"\\])*)"|\[([^\]]*)\])/g;
  for (const match of value.matchAll(fieldPattern)) {
    if (match[1]) {
      try {
        const decoded = JSON.parse(`"${match[1]}"`);
        if (typeof decoded === "string" && decoded.trim()) fields.push(decoded.trim());
      } catch {
        // Partial provider chunks can end inside a quoted string; skip until the next complete field arrives.
      }
    } else if (match[2]) {
      for (const item of match[2].matchAll(/"((?:\\.|[^"\\])*)"/g)) {
        try {
          const decoded = JSON.parse(`"${item[1]}"`);
          if (typeof decoded === "string" && decoded.trim()) fields.push(decoded.trim());
        } catch {
          // Ignore incomplete array members for the same reason as incomplete string fields.
        }
      }
    }
  }
  if (fields.length) return replaceVisibleSourceReferences([...new Set(fields)].join("\n"), sources);
  // A JSON chunk commonly ends in the middle of a summary string. Showing the completed prefix is safe and useful;
  // the parser still owns the final card and no transport punctuation is exposed.
  const partialFieldPattern = /"(?:conversationTitle|title|summary|item|items)"\s*:\s*"((?:\\.|[^"\\])*)/g;
  const partialFields: string[] = [];
  for (const match of value.matchAll(partialFieldPattern)) {
    const candidate = match[1].replace(/\\n/g, "\n").replace(/\\"/g, '"').trim();
    if (candidate) partialFields.push(candidate);
  }
  return partialFields.length
    ? replaceVisibleSourceReferences([...new Set(partialFields)].join("\n"), sources)
    : "";
}

/** Replaces opaque backend citation URIs with the real source title before rich-text parsing. */
function replaceVisibleSourceReferences(
  value: string | undefined,
  sources: StudentExplanationResponse["sources"],
) {
  let result = value ?? "";
  const orderedSources = [...sources]
    .filter((source) => source.sourceUri && source.title)
    .sort((left, right) => right.sourceUri.length - left.sourceUri.length);
  for (const source of orderedSources) {
    result = result.split(source.sourceUri).join(`《${safeUserFacingText(source.title, "相关资料")}》`);
  }
  return result;
}

export function stageDetailText(stage: StudentExplanationStage) {
  const translated = translateStageDetail(stage.detail);
  const text = cleanText(translated);
  if (stage.status === "failed") return safeOperationMessage(text || "本步骤失败。");
  if (!text) return "已完成。";
  return safeUserFacingText(text, "已完成。");
}

function imageStatusText(status?: string) {
  if (status === "image_direct_context") return "题图已传入 AI 上下文";
  if (status === "image_uploaded_for_direct_context") return "已上传，提交后直接传入 AI";
  // Old persisted statuses are presentation-only compatibility; the current pipeline never performs standalone OCR.
  if (status === "image_uploaded_without_vision_analysis") return "已上传，提交后直接传入 AI";
  if (status === "image_understood_by_vision") return "题图已传入 AI 上下文";
  if (status === "image_vision_failed") return "题图已传入 AI 上下文";
  if (status === "none") return "未使用图片";
  return status || "图片已加入";
}

function formatShortTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "最近";
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

const LIVE_ELAPSED_REFRESH_MS = 250;
const CHARACTER_RENDER_INTERVAL_MS = 12;

function liveElapsedSince(createdAt: string, backendElapsedMs?: number) {
  const createdAtMs = Date.parse(createdAt);
  const wallClockMs = Number.isNaN(createdAtMs) ? 0 : Math.max(0, Date.now() - createdAtMs);
  return Math.max(0, backendElapsedMs ?? 0, wallClockMs);
}

function formatElapsed(value?: number) {
  const ms = Math.max(0, value ?? 0);
  if (ms >= 1000) return `${(ms / 1000).toFixed(ms >= 10_000 ? 0 : 1)} 秒`;
  return `${ms} 毫秒`;
}

function cleanText(value?: string) {
  return safeUserFacingText(value, "")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/[#*_`>$]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function compactText(value: string, maxLength: number) {
  const text = safeUserFacingText(value, "").replace(/\s+/g, " ").trim();
  return text.length <= maxLength ? text : `${text.slice(0, Math.max(0, maxLength - 1)).trim()}…`;
}

function translateStageDetail(value?: string) {
  return (value ?? "")
    .replace("No image upload.", "未上传题图。")
    .replace("Textbook retrieval is disabled for this request.", "本轮未启用教材检索。")
    .replace("Knowledge graph matching is disabled for this request.", "本轮未启用知识点匹配。")
    .replace("Teacher resource retrieval is disabled for this request.", "本轮未启用教师资料检索。")
    .replace("Current student identity cannot read private teacher resources.", "当前身份不能读取教师私有资料。")
    .replace("Used real model output and parsed it as JSON explanation cards.", "已用模型结果整理成讲解卡片。")
    .replace("MySQL history persistence is enabled for conversation recovery.", "已保存本轮记录。")
    .replace(/Loaded (\d+) recent messages for this backend subject\./, "已读取 $1 条最近会话。")
    .replace(/Matched (\d+) textbook evidence items\./, "命中 $1 条教材证据。")
    .replace(/Matched (\d+) curated spine knowledge nodes\./, "命中 $1 个主干知识点。")
    .replace(/Matched (\d+) teacher resource evidence items\./, "命中 $1 条教师资料。")
    .replace(/\b(?:promptTokens|completionTokens|totalTokens|tokens?)\s*[=:：]\s*\d+\b/gi, "");
}

export function safeUserFacingText(value?: string, fallback = "内容已整理。") {
  const raw = (value ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  const lines = raw
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !isInternalLine(line));
  const text = lines
    .join("\n")
    .replace(/```(?:json|text|markdown)?/gi, "")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\b(?:promptTokens|completionTokens|totalTokens|tokens?)\s*[=:：]\s*\d+\b/gi, "")
    .replace(/\b(?:provider|model|retry|debug)\s*[=:：]\s*[\w./:-]+\b/gi, "")
    .replace(/\s{2,}/g, " ")
    .trim();
  if (!text) return fallback;
  if (looksLikeJsonPayload(text) || isMostlyInternalEnglish(text)) return fallback;
  return text;
}

function safeOperationMessage(value?: string) {
  const text = safeUserFacingText(value, "");
  if (!text) return "当前讲解没有完成，可以稍后重试或减少并发后再试。";
  if (/403|API_ACCESS_DENIED|access denied|forbidden/i.test(value ?? "")) {
    return "当前账号没有权限执行这个操作，请切换到有权限的账号。";
  }
  if (/429|rate limit|too many requests|quota/i.test(value ?? "")) {
    return "模型请求过于频繁或额度受限，请稍后重试。";
  }
  if (/timeout|timed out/i.test(value ?? "")) {
    return "请求超时，请稍后重试。";
  }
  return text;
}

function isInternalLine(value: string) {
  return /(MODEL_CALL|JSON_PARSE|SYSTEM_PROMPT|RAW_RESPONSE|STACKTRACE|Exception|Debug|traceId|requestId|promptTokens|completionTokens|totalTokens|tokens\s*[=:：]\s*\d+)/i.test(value)
    && !/[\u4e00-\u9fff]/.test(value)
    || /\b(?:system|developer|assistant|user)\s+prompt\b/i.test(value)
    || /^\s*[{[]\s*"(?:cards|nodes|messages|prompt|schema|model|provider)/i.test(value);
}

function looksLikeJsonPayload(value: string) {
  return /^\s*[{[][\s\S]*["'}\]]\s*$/.test(value) && /"(?:cards|messages|prompt|schema|model|provider|tokens?)"/i.test(value);
}

function isMostlyInternalEnglish(value: string) {
  if (/[\u4e00-\u9fff]/.test(value)) return false;
  if (/[\\$^_=<>]/.test(value)) return false;
  return /\b(?:used real model output|parsed|json|mysql|backend|provider|model|retry|conversation recovery|disabled for this request)\b/i.test(value);
}

function firstClipboardImage(items?: DataTransferItemList | null) {
  if (!items) return null;
  for (const item of Array.from(items)) {
    if (!item.type.startsWith("image/")) continue;
    const file = item.getAsFile();
    if (file) return file;
  }
  return null;
}
