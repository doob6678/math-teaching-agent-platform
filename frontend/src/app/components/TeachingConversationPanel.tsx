import katex from "katex";
import { ChangeEvent, ClipboardEvent, FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, Loader2, Plus, Sparkles, X } from "lucide-react";
import {
  StudentExplanationHistoryItem,
  StudentExplanationImageUploadResponse,
  StudentExplanationResponse,
  StudentExplanationStage,
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
    };

type ConversationImageDraft = StudentExplanationImageUploadResponse & {
  previewUrl: string;
};

export function TeachingConversationPanel({
  value,
  entries,
  history,
  loading,
  loadingHistory,
  error,
  imageDraft,
  uploadingImage,
  imageError,
  onValueChange,
  onSubmit,
  onImageSelect,
  onClearImage,
}: {
  value: string;
  entries: TeachingConversationThreadItem[];
  history: StudentExplanationHistoryItem[];
  loading: boolean;
  loadingHistory: boolean;
  error: string;
  imageDraft: ConversationImageDraft | null;
  uploadingImage: boolean;
  imageError: string;
  onValueChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onImageSelect: (file: File | null) => void;
  onClearImage: () => void;
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [clipboardError, setClipboardError] = useState("");
  const latestHistory = history.slice(0, 3);

  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    container.scrollTo({ top: container.scrollHeight, behavior: "smooth" });
  }, [entries, loading]);

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
      <header className="teaching-live-header">
        <div className="teaching-live-brand">
          <div className="teaching-live-brand-icon"><Sparkles size={16} /></div>
          <div className="teaching-live-brand-copy">
            <strong>AI 讲题</strong>
            <span>输入题目或题图，按步骤实时讲解。</span>
          </div>
        </div>
        <div className="teaching-live-toolbar">
          {latestHistory.length ? (
            <div className="teaching-history-strip" aria-label="最近讲题记录">
              {loadingHistory ? (
                <span className="teaching-history-chip muted"><Loader2 className="spin" size={12} />同步中</span>
              ) : latestHistory.map((item) => (
                <span className="teaching-history-chip" key={item.explanationId}>
                  {formatShortTime(item.createdAt)}
                </span>
              ))}
            </div>
          ) : null}
        </div>
      </header>

      <div className="teaching-live-scroll" ref={scrollRef}>
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
              {entry.loading ? (
                <LoadingCard questionText={entry.questionText} hasImage={Boolean(entry.imagePreviewUrl || entry.imageFileName)} />
              ) : entry.error ? (
                <section className="teaching-status-card error">
                  <div className="teaching-status-head">
                    <strong>这次讲解没有完成</strong>
                    <span>已停止</span>
                  </div>
                  <p>{safeOperationMessage(entry.error)}</p>
                </section>
              ) : entry.response ? (
                <AssistantResponse response={entry.response} />
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
                    <span>题图已上传，发送后进入识别与讲解。</span>
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

function LoadingCard({ questionText, hasImage }: { questionText?: string; hasImage: boolean }) {
  const stages = useMemo(() => hasImage
    ? ["读取输入", "识别题图", "检索教材与题型", "整理讲解主线", "生成讲解卡片"]
    : ["读取问题", "检索教材与题型", "整理讲解主线", "补充易错点", "生成讲解卡片"], [hasImage]);
  const [stageIndex, setStageIndex] = useState(0);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    setStageIndex(0);
    const timer = globalThis.setInterval(() => {
      setStageIndex((current) => Math.min(current + 1, stages.length - 1));
    }, 1200);
    return () => globalThis.clearInterval(timer);
  }, [stages]);

  useEffect(() => {
    setElapsedSeconds(0);
    const timer = globalThis.setInterval(() => setElapsedSeconds((current) => current + 1), 1000);
    return () => globalThis.clearInterval(timer);
  }, []);

  return (
    <section className="teaching-status-card pending compact">
      <div className="teaching-status-head compact">
        <strong>正在讲解</strong>
        <span><Loader2 className="spin" size={12} />{elapsedSeconds}s</span>
      </div>
      {questionText ? <p className="teaching-status-question">{questionText}</p> : null}
      <div className="teaching-trace-live compact">
        {stages.map((stage, index) => {
          const state = index < stageIndex ? "done" : index === stageIndex ? "active" : "waiting";
          return (
            <div className={`teaching-trace-live-item ${state}`} key={stage}>
              <span className="teaching-trace-live-dot" />
              <strong>{stage}</strong>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function AssistantResponse({ response }: { response: StudentExplanationResponse }) {
  const cards = response.cards ?? [];
  const sources = response.sources ?? [];
  const stages = response.workflowStages ?? [];
  const maxReveal = Math.max(1, cards.length) + 1;
  const [revealedCount, setRevealedCount] = useState(1);
  const visibleCards = cards.length ? cards.slice(0, Math.min(revealedCount, cards.length)) : [];
  const showDetails = revealedCount > Math.max(1, cards.length);

  useEffect(() => {
    setRevealedCount(1);
    const timer = globalThis.setInterval(() => {
      setRevealedCount((current) => {
        if (current >= maxReveal) {
          globalThis.clearInterval(timer);
          return current;
        }
        return current + 1;
      });
    }, 340);
    return () => globalThis.clearInterval(timer);
  }, [response.explanationId, maxReveal]);

  return (
    <>
      {visibleCards.length ? visibleCards.map((card, index) => {
        const safeTitle = safeUserFacingText(card.title, "讲解卡片");
        const safeSummary = safeUserFacingText(card.summary, "");
        const safeItems = (card.items ?? [])
          .map((item) => safeUserFacingText(item, ""))
          .filter((item) => item.trim().length > 0);
        const tone = classifyCardTone(safeTitle, safeSummary, index);
        return (
          <section className={`teaching-response-card ${tone}${index === 0 ? " primary" : ""}`} key={`${response.explanationId}:${card.cardKey}:${index}`}>
            <div className="teaching-response-head">
              <div>
                <strong>{safeTitle}</strong>
                {card.renderMode ? <span className="teaching-response-mode">{renderModeText(card.renderMode)}</span> : null}
              </div>
              <span className={`teaching-response-badge ${tone}`}>{toneLabel(tone, index)}</span>
            </div>
            {safeSummary ? <div className="teaching-rich-block"><RichText text={safeSummary} /></div> : null}
            {safeItems.length ? (
              <ul className="teaching-response-list">
                {safeItems.map((item, itemIndex) => (
                  <li key={`${response.explanationId}:${card.cardKey}:${itemIndex}`}><RichText text={item} /></li>
                ))}
              </ul>
            ) : null}
          </section>
        );
      }) : (
        <section className="teaching-response-card primary core">
          <div className="teaching-response-head">
            <div><strong>讲解结果</strong></div>
            <span className="teaching-response-badge core">核心讲解</span>
          </div>
          <div className="teaching-rich-block"><RichText text={safeUserFacingText(response.questionText, "已收到本次问题。")} /></div>
        </section>
      )}

      {showDetails ? (
        <div className="teaching-assistant-extras subtle">
          {stages.length ? (
            <section className="teaching-subtle-panel">
              <div className="teaching-subtle-head">
                <span>AI 轨迹</span>
                <em>{stages.length} 步</em>
              </div>
              <div className="teaching-trace-inline-list">
                {stages.map((stage, index) => (
                  <article className={`teaching-trace-inline-item ${stageTone(stage.status)}`} key={`${response.explanationId}:${stage.stageKey}:${index}`}>
                    <span>{index + 1}</span>
                    <strong>{stageTitleText(stage.stageKey, stage.title)}</strong>
                    <p>{stageDetailText(stage)}</p>
                    <em>{formatElapsed(stage.elapsedMs)}</em>
                  </article>
                ))}
              </div>
            </section>
          ) : null}

          {sources.length ? (
            <section className="teaching-subtle-panel">
              <div className="teaching-subtle-head">
                <span>命中来源</span>
                <em>{sources.length} 条</em>
              </div>
              <div className="teaching-source-inline-list">
                {sources.slice(0, 4).map((source, index) => (
                  <article className="teaching-source-inline-item" key={`${response.explanationId}:${source.sourceUri}:${index}`}>
                    <strong>{safeUserFacingText(source.title, "命中资料")}</strong>
                    <span>{source.score.toFixed(2)}</span>
                    <p>{compactText(source.snippet, 72)}</p>
                  </article>
                ))}
              </div>
            </section>
          ) : null}
        </div>
      ) : null}
    </>
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
              const expression = normalizeLatex(segment.text);
              if (hasUnbalancedBraces(expression)) return null;
              const html = katex.renderToString(expression, {
                displayMode: segment.display,
                throwOnError: false,
                strict: false,
                trust: false,
              });
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

function renderModeText(renderMode: string) {
  if (renderMode === "formula") return "公式";
  if (renderMode === "source_list") return "来源";
  return "讲解";
}

function classifyCardTone(title: string, summary: string, index: number) {
  const content = `${title} ${summary}`;
  if (index === 0) return "core";
  if (/(易错|误区|常见错误|注意)/.test(content)) return "mistake";
  if (/(总结|回顾|归纳|结论)/.test(content)) return "summary";
  if (/(练习|追问|下一步|自测|应用)/.test(content)) return "final";
  return "default";
}

function toneLabel(tone: string, index: number) {
  if (index === 0 || tone === "core") return "核心讲解";
  if (tone === "mistake") return "常见错误";
  if (tone === "summary") return "总结回顾";
  if (tone === "final") return "继续练习";
  return "补充说明";
}

function stageTone(status: string) {
  if (status === "failed") return "failed";
  if (status === "completed") return "completed";
  return "running";
}

export function stageTitleText(stageKey: string, title: string) {
  const mapped: Record<string, string> = {
    analyze_image: "识别题图",
    load_conversation_context: "读取上下文",
    understand_problem: "理解题意",
    search_textbook: "检索教材",
    match_knowledge_graph: "匹配知识点",
    search_teacher_resources: "检索教师资料",
    ai_compose_cards: "生成讲解",
    assemble_cards: "整理卡片",
    persist_history: "保存记录",
  };
  const normalizedKey = (stageKey || "").toLowerCase();
  const fallbackTitle = safeUserFacingText(title, "处理步骤");
  return mapped[stageKey] || mapped[normalizedKey] || fallbackTitle || "处理步骤";
}

export function stageDetailText(stage: StudentExplanationStage) {
  const translated = translateStageDetail(stage.detail);
  const text = cleanText(translated);
  if (stage.status === "failed") return safeOperationMessage(text || "本步骤失败。");
  if (!text) return "已完成。";
  return safeUserFacingText(text, "已完成。");
}

function imageStatusText(status?: string) {
  if (status === "image_understood_by_vision") return "题图已识别";
  if (status === "image_uploaded_without_vision_analysis") return "已上传，等待识别";
  if (status === "image_vision_failed") return "题图识别失败";
  if (status === "none") return "未使用图片";
  return status || "图片已加入";
}

function formatShortTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "最近";
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
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
