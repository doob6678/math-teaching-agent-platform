import katex from "katex";
import { ChangeEvent, ClipboardEvent, FormEvent, useEffect, useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowUp,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Copy,
  ExternalLink,
  History,
  Lightbulb,
  Loader2,
  Plus,
  Sparkles,
  X,
  XCircle,
} from "lucide-react";
import {
  AgentModelCatalogResponse,
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
      /** 提交到首个内容增量到达的毫秒数（TTFT）。用于在界面上如实展示首 token 响应速度。 */
      firstTokenMs?: number;
      /** 提交到流式回合完整结束的毫秒数，与 firstTokenMs 一起构成速度展示。 */
      totalMs?: number;
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
  hasMoreConversations = false,
  loadingMoreConversations = false,
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
  onLoadMoreConversations = () => {},
  modelCatalog = null,
  selectedModel = "",
  onModelChange = () => {},
}: {
  conversationTitle: string;
  value: string;
  entries: TeachingConversationThreadItem[];
  recentConversations: StudentExplanationConversationSummary[];
  loading: boolean;
  loadingHistory: boolean;
  /** 服务端还有更多历史会话时显示“加载更多”，列表分页由后端 page 参数支撑。 */
  hasMoreConversations?: boolean;
  loadingMoreConversations?: boolean;
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
  onLoadMoreConversations?: () => void;
  /** 后端模型目录（复用控制台已加载数据）；为空时选择器只保留“自动”。 */
  modelCatalog?: AgentModelCatalogResponse | null;
  /** 当前选中的 "provider::model"；空串表示自动路由。 */
  selectedModel?: string;
  onModelChange?: (value: string) => void;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const followsLatestRef = useRef(true);
  // 追平 Qwen 的回到底部浮动按钮：ref 供滚动联动逻辑同步读取，state 只驱动按钮显隐。
  const [followsLatest, setFollowsLatest] = useState(true);
  const [clipboardError, setClipboardError] = useState("");
  // 宽屏默认展开左侧历史栏（常驻侧栏），窄屏默认收起、由顶栏按钮唤出抽屉。
  const [drawerOpen, setDrawerOpen] = useState(() => typeof window !== "undefined" && window.innerWidth >= 1100);

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
    setFollowsLatest(remaining <= 24);
  }

  function scrollToLatestMessage() {
    const container = scrollRef.current;
    if (!container) return;
    followsLatestRef.current = true;
    setFollowsLatest(true);
    container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
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
    <section
      className={`teaching-live-shell teaching-chat-shell${entries.length ? "" : " is-empty"}`}
      aria-label="AI 讲题"
    >
      <div
        className={`teaching-chat-drawer-backdrop${drawerOpen ? " open" : ""}`}
        onClick={() => setDrawerOpen(false)}
        aria-hidden={!drawerOpen}
      />
      <aside className={`teaching-chat-drawer${drawerOpen ? " open" : ""}`} aria-label="AI讲题历史记录">
        <div className="teaching-chat-drawer-head">
          <strong>最近讲题</strong>
          {loadingHistory ? <span className="teaching-chat-drawer-sync"><Loader2 className="spin" size={12} />同步中</span> : null}
          <button type="button" className="teaching-chat-drawer-close" onClick={() => setDrawerOpen(false)} aria-label="关闭历史记录">
            <ArrowLeft size={16} />
          </button>
        </div>
        <div className="teaching-chat-drawer-list">
          {recentConversations.length ? recentConversations.slice(0, 30).map((item) => (
            <button
              type="button"
              className="teaching-chat-drawer-item"
              key={item.conversationId}
              title={titleTooltip(item.title)}
              disabled={loading || openingConversationId === item.conversationId}
              onClick={() => {
                // 宽屏（≥1101px）历史栏是常驻侧栏，点开对话不收起——此前无条件收起且收起后没有明显
                // 的恢复入口，观感上就是"点进对话左侧栏消失了"的 bug。窄屏抽屉是覆盖层，选中后
                // 仍要自动收起露出正文。
                if (typeof window !== "undefined" && window.innerWidth < 1101) setDrawerOpen(false);
                onOpenConversation(item);
              }}
            >
              <strong><InlineMathText text={safeUserFacingText(item.title, "最近讲题")} /></strong>
              <span>{openingConversationId === item.conversationId ? "正在加载" : `${item.totalMessages} 轮`}</span>
            </button>
          )) : (
            <div className="teaching-chat-drawer-empty">还没有历史讲题记录。</div>
          )}
          {hasMoreConversations ? (
            <button
              type="button"
              className="teaching-chat-drawer-more"
              disabled={loadingMoreConversations}
              onClick={onLoadMoreConversations}
            >
              {loadingMoreConversations ? "正在加载…" : "加载更早的讲题"}
            </button>
          ) : null}
        </div>
      </aside>

      {/* 抽屉拉手：挂在 shell 上而不是 aside 里，侧栏 display:none 收起时拉手仍然可见，
          贴在侧栏右缘（收起时贴屏幕左缘），点一下即可展开/伸缩。仅宽屏渲染，窄屏沿用顶栏按钮。 */}
      <button
        type="button"
        className={`teaching-chat-drawer-handle${drawerOpen ? " open" : ""}`}
        onClick={() => setDrawerOpen((current) => !current)}
        aria-label={drawerOpen ? "收起历史记录" : "展开历史记录"}
        aria-expanded={drawerOpen}
        title={drawerOpen ? "收起历史记录" : "展开历史记录"}
      >
        <ChevronLeft size={14} />
      </button>

      <div className="teaching-chat-main">
      <header className="teaching-live-header teaching-chat-header-fixed">
        <div className="teaching-live-brand teaching-chat-header-main">
          <button
            type="button"
            className="teaching-live-brand-icon teaching-chat-drawer-trigger"
            onClick={() => setDrawerOpen((current) => !current)}
            aria-label={drawerOpen ? "收起历史记录" : "打开历史记录"}
            aria-expanded={drawerOpen}
            title={drawerOpen ? "收起历史记录" : "历史记录"}
          >
            <History size={16} />
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
        </div>
      </header>

      <div className="teaching-live-scroll" ref={scrollRef} onScroll={handleConversationScroll}>
        {!entries.length ? (
          <div className="teaching-chat-hero">
            <h1>有什么数学题可以帮你？</h1>
            <div className="teaching-chat-hero-tips">
              <span>发题目</span>
              <span>贴题图</span>
              <span>继续追问</span>
            </div>
          </div>
        ) : null}

        {entries.map((entry) => entry.role === "user" ? (
          <article className="teaching-user-row" key={entry.id}>
            <div className="teaching-user-bubble-wrap">
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
              <CopyButton text={entry.questionText} label="复制提问" />
            </div>
          </article>
        ) : (
          <article className="teaching-assistant-row" key={entry.id}>
            <div className="teaching-assistant-flow">
              {entry.response ? (
                <AssistantResponse
                  response={entry.response}
                  firstTokenMs={entry.firstTokenMs}
                  totalMs={entry.totalMs}
                  reasoningTrace={entry.liveThinking}
                />
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

      <button
        type="button"
        className={`teaching-scroll-bottom${followsLatest ? "" : " show"}`}
        onClick={scrollToLatestMessage}
        aria-label="回到最新消息"
      >
        <ChevronDown size={16} />
      </button>

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
                {/* 模型切换（老板 2026-09-01）：目录来自后端白名单，选择仅作路由偏好，权限与校验仍在后端。
                    老板反馈原生 select 在嵌入式浏览器里点击弹不出选项，改为 button+menu 的自定义下拉。 */}
                <ModelPicker catalog={modelCatalog} value={selectedModel} onChange={onModelChange} />
              </div>
              <button className="teaching-send-btn" type="submit" disabled={loading || uploadingImage || (!value.trim() && !imageDraft)}>
                {loading ? <Loader2 className="spin" size={17} /> : <ArrowUp size={18} />}
              </button>
            </div>
          </div>
          {composerError ? <div className="teaching-inline-error">{composerError}</div> : null}
        </div>
        <div className="teaching-composer-disclaimer">内容由 AI 生成，请自行核对重要信息。</div>
      </form>
      </div>
    </section>
  );
}

/**
 * Live view of a running explanation, mirroring the Qwen chat pattern: one collapsed thinking row that streams the
 * active workflow step in place, plus a fixed right panel ("思考与搜索") that holds the full stage timeline and the
 * evidence found so far. The panel stays mounted (translated off-screen when closed) so opened state survives
 * re-renders without remounting the stream-driven list.
 */
function LiveAssistantResponse({ entry }: { entry: Extract<TeachingConversationThreadItem, { role: "assistant" }> }) {
  const progress = entry.progress;
  const stages = visibleWorkflowStages(progress?.workflowStages ?? []);
  const sources = progress?.sources ?? [];
  const [liveElapsedMs, setLiveElapsedMs] = useState(() => liveElapsedSince(entry.createdAt, progress?.totalElapsedMs));
  const [thinkingOpen, setThinkingOpen] = useState(false);
  const liveAnswer = useCharacterRenderedText(liveTextForDisplay(entry.liveContent ?? "", sources));
  // 主区行只滚动展示最近一条推进：优先正在运行的步骤，否则取最后一条已完成的步骤。
  const activeStage = [...stages].reverse().find((stage) => stage.status !== "completed") ?? stages[stages.length - 1];
  // Provider 隐藏思考的完整流式文本；有内容时主区行与右侧面板都展示它（老板要求的"展示思考所有内容"）。
  const reasoning = entry.liveThinking ?? "";
  const traceTextRef = useRef<HTMLDivElement | null>(null);
  const reasoningFollowsRef = useRef(true);

  useEffect(() => {
    if (entry.response || entry.error) return;
    const refresh = () => setLiveElapsedMs(liveElapsedSince(entry.createdAt, progress?.totalElapsedMs));
    refresh();
    const timer = globalThis.setInterval(refresh, LIVE_ELAPSED_REFRESH_MS);
    return () => globalThis.clearInterval(timer);
  }, [entry.createdAt, entry.error, entry.response, progress?.totalElapsedMs]);

  // 思考文本增长时把轨迹滚动区钉在底部；读者向上回看时停止跟随。
  useEffect(() => {
    const el = traceTextRef.current;
    if (!el || !reasoningFollowsRef.current) return;
    el.scrollTop = el.scrollHeight;
  }, [reasoning]);

  return (
    <>
      <button
        type="button"
        className={`teaching-thinking-row live${thinkingOpen ? " open" : ""}`}
        onClick={() => setThinkingOpen((current) => !current)}
        aria-expanded={thinkingOpen}
      >
        <Lightbulb size={17} className="teaching-thinking-bulb" aria-hidden="true" />
        <strong>正在讲解</strong>
        <span className="teaching-thinking-live-text">
          {reasoning ? reasoningTailText(reasoning) : activeStage ? stageDetailText(activeStage) : "正在整理思路…"}
        </span>
        {typeof entry.firstTokenMs === "number" ? (
          <span className="teaching-speed-chip good" title="从思考开始到首个讲解内容到达的耗时（决策与检索等系统开销不计入）">首字 {formatSpeedMs(entry.firstTokenMs)}</span>
        ) : null}
        <span className="teaching-thinking-elapsed"><Loader2 className="spin" size={12} />{formatElapsed(liveElapsedMs)}</span>
        <ChevronRight size={15} className="teaching-thinking-chevron" aria-hidden="true" />
      </button>

      <aside
        className={`teaching-thinking-panel${thinkingOpen ? " open" : ""}`}
        aria-label="思考与搜索"
        aria-hidden={!thinkingOpen}
      >
        <div className="teaching-thinking-panel-head">
          <Lightbulb size={16} aria-hidden="true" />
          <strong>思考与搜索</strong>
          <button type="button" className="teaching-thinking-panel-close" onClick={() => setThinkingOpen(false)} aria-label="关闭思考面板">
            <X size={15} />
          </button>
        </div>
        <div className="teaching-thinking-panel-body">
          <section className="teaching-thinking-trace" aria-label="思考过程">
            <div className="teaching-thinking-sources-head">
              <strong>思考过程</strong>
              <span>{countCharacters(reasoning)} 字</span>
            </div>
            <div
              className="teaching-thinking-trace-text"
              ref={traceTextRef}
              onScroll={(event) => {
                const el = event.currentTarget;
                reasoningFollowsRef.current = el.scrollHeight - el.scrollTop - el.clientHeight <= 24;
              }}
            >
              {reasoning || "模型正在思考…"}
            </div>
          </section>
          <div className="teaching-thinking-timeline" aria-label="真实处理过程">
            {stages.map((stage) => (
              <div className={`teaching-thinking-step ${stageTone(stage.status)}`} key={stage.stageKey}>
                <span className="teaching-thinking-step-icon" aria-hidden="true">
                  {stage.status === "completed" ? <CheckCircle2 size={15} /> : stage.status === "failed" ? <XCircle size={15} /> : <Loader2 className="spin" size={15} />}
                </span>
                <div className="teaching-thinking-step-copy">
                  <strong>{stageTitleText(stage.stageKey, stage.title)}</strong>
                  <p>{stageDetailText(stage)}</p>
                </div>
              </div>
            ))}
          </div>
          {sources.length ? (
            <section className="teaching-thinking-sources" aria-label="已找到的资料">
              <div className="teaching-thinking-sources-head">
                <strong>已找到的资料</strong>
                <span>{sources.length} 条</span>
              </div>
              <EvidenceSourceList sources={sources} />
            </section>
          ) : null}
        </div>
      </aside>

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

/** 主区思考行只保留推理的最新片段，等价于 Qwen 在状态行上流式刷新的最新思考句。 */
function reasoningTailText(reasoning: string) {
  const tail = reasoning.slice(-60);
  return tail.length < reasoning.length ? `…${tail}` : tail;
}

function countCharacters(value: string) {
  return Array.from(value).length;
}

/** Adaptive character queue: small deltas keep a typewriter feel while large backlogs drain within a bounded time. */
function useCharacterRenderedText(source: string) {
  // The initial source is rendered for server/static output only. During an actual conversation the pending card
  // starts empty, then every later SSE update enters through the character queue below.
  const [rendered, setRendered] = useState(source);

  useEffect(() => {
    setRendered((current) => source.startsWith(current) ? current : "");
  }, [source]);

  useEffect(() => {
    if (!source || rendered.length >= source.length || !source.startsWith(rendered)) return;
    const remaining = source.length - rendered.length;
    // 积压越大步长越大，保证任何长度的回答都能在约 MAX_CATCH_UP 内追平；小增量时步长为 1，仍保留逐字效果。
    const maxTicks = Math.max(1, CHARACTER_RENDER_MAX_CATCH_UP_MS / CHARACTER_RENDER_TICK_MS);
    const step = Math.max(1, Math.ceil(remaining / maxTicks));
    const timer = globalThis.setTimeout(
      () => setRendered(source.slice(0, rendered.length + step)),
      CHARACTER_RENDER_TICK_MS,
    );
    return () => globalThis.clearTimeout(timer);
  }, [rendered, source]);

  return rendered;
}

function AssistantResponse({
  response,
  firstTokenMs,
  totalMs,
  reasoningTrace,
}: {
  response: StudentExplanationResponse;
  /** 本轮实测的首字耗时与总耗时；只有当回合由当前浏览器流式发起时才有值。 */
  firstTokenMs?: number;
  totalMs?: number;
  /** 本轮的模型思考轨迹：流式回合来自浏览器累计，历史回合来自 aiDraft.reasoningTrace 持久化。 */
  reasoningTrace?: string;
}) {
  const cards = visibleExplanationCards(response.cards ?? []);
  const sources = response.sources ?? [];
  const stages = visibleWorkflowStages(response.workflowStages ?? []);
  const reasoning = (reasoningTrace || response.aiDraft?.reasoningTrace || "").trim();

  return (
    <div className="teaching-answer-layout">
      {/* 老板 2026-09-01：完成态"已完成思考"必须像 Qwen 一样位于回答最上方，而不是压在正文下面。 */}
      <EvidenceInspector response={response} stages={stages} sources={sources} reasoningTrace={reasoning} />
      <div className="teaching-answer-content">
        {typeof firstTokenMs === "number" ? (
          <div className="teaching-speed-line" title="首字=思考开始到首个讲解内容（不含决策与检索） / 全程=本轮讲解总耗时">
            <span className="teaching-speed-chip good">首字 {formatSpeedMs(firstTokenMs)}</span>
            {typeof totalMs === "number" ? <span className="teaching-speed-chip">全程 {formatSpeedMs(totalMs)}</span> : null}
          </div>
        ) : null}
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
        <CopyButton
          text={answerPlainText(cards)}
          label="复制讲解"
          disabled={!cards.length}
        />
      </div>
    </div>
  );
}

/** 把讲解卡片串成可复制的纯文本；只使用已经过安全清洗的标题与正文，公式保留原始分隔符供粘贴到其他工具。 */
function answerPlainText(cards: StudentExplanationResponse["cards"]) {
  return cards
    .map((card) => {
      const items = (card.items ?? []).filter((item) => item.trim().length > 0);
      return [card.title, card.summary, ...items]
        .map((part) => (part ?? "").trim())
        .filter(Boolean)
        .join("\n");
    })
    .filter(Boolean)
    .join("\n\n");
}

/** Qwen 式悬浮小复制按钮：悬停在消息行上时出现，点击写入剪贴板并短暂提示已复制。 */
function CopyButton({ text, label, disabled = false }: { text: string; label: string; disabled?: boolean }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    if (!text.trim()) return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      globalThis.setTimeout(() => setCopied(false), 1600);
    } catch {
      // 剪贴板不可用（非安全上下文等）时静默失败，不打断阅读。
    }
  }

  return (
    <button
      type="button"
      className={`teaching-copy-btn${copied ? " done" : ""}`}
      onClick={handleCopy}
      disabled={disabled || !text.trim()}
      aria-label={label}
      title={copied ? "已复制" : label}
    >
      {copied ? <CheckCircle2 size={13} /> : <Copy size={13} />}
      <span>{copied ? "已复制" : "复制"}</span>
    </button>
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
  reasoningTrace,
}: {
  response: StudentExplanationResponse;
  stages: StudentExplanationStage[];
  sources: StudentExplanationResponse["sources"];
  /** 模型思考轨迹；为空时折叠区不渲染该节，保持旧数据形态不变。 */
  reasoningTrace?: string;
}) {
  if (!stages.length && !sources.length && !reasoningTrace) return null;
  const sourceSummary = sourceSummaryText(sources);
  const assignedSourceTypes = new Set(
    stages.map((stage) => retrievalSourceType(stage.stageKey)).filter((type): type is string => type !== null),
  );
  const unassignedSources = sources.filter((source) => !assignedSourceTypes.has(source.sourceType));
  return (
    <aside className="teaching-evidence-inspector" aria-label="本轮检索与执行记录">
      <details className="ai-run-disclosure teaching-evidence-disclosure">
        <summary>
          <Lightbulb size={16} className="teaching-thinking-bulb" aria-hidden="true" />
          <strong className="teaching-thinking-title">已完成思考</strong>
          <span className="teaching-evidence-summary">{stages.length ? `${stages.length} 个实际步骤` : sourceSummary}</span>
          <ChevronDown size={15} aria-hidden="true" />
        </summary>
        <div className="teaching-evidence-body">
          {reasoningTrace ? (
            <section className="teaching-thinking-trace" aria-label="思考过程">
              <div className="teaching-thinking-sources-head">
                <strong>思考过程</strong>
                <span>{countCharacters(reasoningTrace)} 字</span>
              </div>
              <div className="teaching-thinking-trace-text static">{reasoningTrace}</div>
            </section>
          ) : null}
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

// 内部持久化/会话缓存步骤不是教学动作，学生时间线不展示（老板 2026-09-01 反馈："Mysql 加载那个不应该显示"）。
const INTERNAL_STAGE_KEYS = new Set(["persist_history", "load_conversation_context"]);

/** Keeps the trace factual: skipped/pending work and legacy pre-announced stages are never presented as execution. */
export function visibleWorkflowStages(stages: StudentExplanationStage[]) {
  return stages.filter((stage) =>
    stage.status !== "skipped"
    && stage.status !== "pending"
    && !LEGACY_SYNTHETIC_STAGE_KEYS.has(stage.stageKey)
    && !INTERNAL_STAGE_KEYS.has(stage.stageKey));
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

/**
 * 部分历史模型输出用 ◆ 充当强调定界符。成对出现时转成内部标记并渲染为加粗，孤立的 ◆ 直接移除，
 * 避免坏字符裸露在回答正文里；标记只在本函数内部使用，不会进入存储或后端。
 */
function stripDecorationGlyphs(text: string) {
  return text.replace(/◆\s*([^◆]{0,160}?)\s*◆/g, "\u0001$1\u0002").replace(/◆+/g, "");
}

/** 把内部强调标记渲染为加粗片段；没有标记时按纯文本返回，保持普通内容的渲染路径不变。 */
function renderEmphasisText(text: string, keyPrefix: string) {
  const parts = text.split(/\u0001([\s\S]*?)\u0002/);
  if (parts.length === 1) return text;
  return parts.map((part, index) => index % 2 === 1
    ? <strong key={`${keyPrefix}-em-${index}`} className="teaching-emphasis">{part}</strong>
    : <span key={`${keyPrefix}-plain-${index}`}>{part}</span>);
}

function RichText({ text }: { text: string }) {
  const prepared = stripDecorationGlyphs(text || "");
  return (
    <>
      {prepared
        .split(/\n+/)
        .filter((line) => line.trim().length > 0)
        .map((line, lineIndex) => (
          <span className="teaching-rich-line" key={`line-${lineIndex}`}>
            {splitMathText(line).map((segment) => {
              if (!segment.math) return <span key={segment.key}>{renderEmphasisText(segment.text, segment.key)}</span>;
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
      {splitMathText(stripDecorationGlyphs(text)).map((segment) => {
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
// 打字机按 24ms 一跳渲染；积压越多每跳补的字符越多，追平时间被压在约 1.5 秒内。
// 旧实现固定每 12ms 渲染 1 个字符，3000 字回答在流结束后还要空转约 36 秒才能读完逐字效果。
const CHARACTER_RENDER_TICK_MS = 24;
const CHARACTER_RENDER_MAX_CATCH_UP_MS = 1500;

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

/** Speed chips keep one decimal under 10s and round above, so long waits stay readable without false precision. */
function formatSpeedMs(value?: number) {
  const ms = Math.max(0, value ?? 0);
  if (ms >= 10_000) return `${Math.round(ms / 1000)} 秒`;
  return `${(ms / 1000).toFixed(1)} 秒`;
}

/**
 * 修复历史数据里的双重编码乱码（例如 “ä¸­ç­‰æ•°å­¦” → “中等数学”）。
 * 双重编码有两个常见变体：按 Latin-1 解码（字节原样落在 U+00A0-U+00FF）和按 Windows-1252 解码
 * （0x80-0x9F 字节变成 € ‰ • 等可见字符）。这里先把两种变体都还原成字节序列，再按 UTF-8 重解码；
 * 只有解码成功、结果含中文且没有替换符时才替换，正常中英文不会进入该分支。
 */
const CP1252_REVERSE_BYTES: ReadonlyMap<string, number> = new Map([
  ["\u20ac", 0x80], ["\u201a", 0x82], ["\u0192", 0x83], ["\u201e", 0x84],
  ["\u2026", 0x85], ["\u2020", 0x86], ["\u2021", 0x87], ["\u02c6", 0x88], ["\u2030", 0x89],
  ["\u0160", 0x8a], ["\u2039", 0x8b], ["\u0152", 0x8c], ["\u017d", 0x8e], ["\u2018", 0x91],
  ["\u2019", 0x92], ["\u201c", 0x93], ["\u201d", 0x94], ["\u2022", 0x95], ["\u2013", 0x96],
  ["\u2014", 0x97], ["\u02dc", 0x98], ["\u2122", 0x99], ["\u0161", 0x9a], ["\u203a", 0x9b],
  ["\u0153", 0x9c], ["\u017e", 0x9e], ["\u0178", 0x9f],
]);

export function repairMojibakeText(value: string): string {
  // 快速路径：不含任何 Latin-1 高位字符或 cp1252 特有符号时必然不是双重编码。
  if (!/[\u00a0-\u00ff\u20ac\u201a-\u2026\u2020-\u2030\u2039-\u203a\u0152-\u0153\u0160-\u0161\u0178-\u017e\u02c6\u02dc\u2018-\u201d\u2122\u0192]/.test(value)) {
    return value;
  }
  const bytes: number[] = [];
  for (const char of value) {
    const code = char.charCodeAt(0);
    if (code <= 0xff) {
      bytes.push(code);
      continue;
    }
    const mapped = CP1252_REVERSE_BYTES.get(char);
    if (mapped === undefined) return value;
    bytes.push(mapped);
  }
  try {
    const decoded = new TextDecoder("utf-8", { fatal: true }).decode(new Uint8Array(bytes));
    if (decoded !== value && /[\u4e00-\u9fff]/.test(decoded) && !decoded.includes("\ufffd")) {
      return decoded;
    }
  } catch {
    // 无法按 UTF-8 重解码说明不是双重编码乱码，保持原文。
  }
  return value;
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
  const raw = repairMojibakeText(value ?? "").replace(/\r\n/g, "\n").replace(/\r/g, "\n");
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

/**
 * 讲解模型选择器（老板 2026-09-01 二轮反馈）：原生 <select> 在 ZCode 内嵌浏览器里点击弹不出选项，
 * 改为 button + 弹出菜单的自定义下拉。菜单向上弹出（composer 固定在页面底部）；点击外部或选中后关闭。
 * 目录仍来自后端白名单（AgentModelCatalogResponse），这里只负责展示与选择，不做任何路由校验。
 */
function ModelPicker({
  catalog,
  value,
  onChange,
}: {
  catalog: AgentModelCatalogResponse | null;
  value: string;
  onChange: (next: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDocMouseDown);
    return () => document.removeEventListener("mousedown", onDocMouseDown);
  }, [open]);
  const autoLabel = `自动 · ${catalog ? `${catalog.defaultProviderName}/${catalog.defaultModelCode}` : "默认模型"}`;
  const selectedLabel = value ? value.split("::").slice(1).join("::") : autoLabel;
  const providers = (catalog?.providers ?? []).filter((provider) => provider.enabled);
  return (
    <div className="teaching-model-picker" ref={rootRef}>
      <button
        type="button"
        className="teaching-model-trigger"
        onClick={() => setOpen((current) => !current)}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label="选择讲解模型"
        title="选择本轮讲解使用的模型；自动=后端默认路由"
      >
        <span>{selectedLabel}</span>
        <ChevronDown size={13} />
      </button>
      {open ? (
        <div className="teaching-model-menu" role="listbox">
          <button
            type="button"
            role="option"
            aria-selected={!value}
            className={value ? undefined : "active"}
            onClick={() => {
              onChange("");
              setOpen(false);
            }}
          >
            {autoLabel}
          </button>
          {providers.map((provider) => (
            <div key={provider.name} className="teaching-model-group">
              <em>{provider.name}</em>
              {(provider.models ?? []).map((model) => {
                const optionValue = `${provider.name}::${model.modelCode}`;
                return (
                  <button
                    key={optionValue}
                    type="button"
                    role="option"
                    aria-selected={value === optionValue}
                    className={value === optionValue ? "active" : undefined}
                    onClick={() => {
                      onChange(optionValue);
                      setOpen(false);
                    }}
                  >
                    <span>{model.modelCode}</span>
                    {model.modelLevel ? <small>{model.modelLevel}</small> : null}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
