import { useEffect, useRef, useState } from "react";
import { AlertCircle, Clapperboard, LoaderCircle, X } from "lucide-react";
import type { createTextbookApiClient, AnimatedLessonMeta } from "../../shared/api/textbookApi";
import { RichText } from "./TeachingConversationPanel";

/**
 * 「动画讲题」浮动入口 + 模态播放器（2026-09-09 主链路接入）。
 *
 * 为什么是浮层而不是讲题页内联块：讲题对话壳 .teaching-chat-shell 是
 * calc(100dvh - nav) 视口高度容器，内联兄弟块会把对话挤出滚动；浮层零布局侵入。
 * 任务本身是分钟级异步（agent_worker_task 队列），关闭浮层不取消任务——
 * 状态留在组件里，重新打开可继续看进度，轮询间隔 5s 与 Java meta 合同一致。
 * 教学正文（章节名/题干/解析卡）全部透传 worker 响应，前端不补写任何教学语义。
 */

type ApiClient = ReturnType<typeof createTextbookApiClient>;

type Phase = "form" | "running" | "completed" | "failed";

const ACCENT = "#4D6BFE";
const INK = "#1F2937";

function formatElapsed(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

export function AnimatedLessonPanel({ api }: { api: ApiClient }) {
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>("form");
  const [problemText, setProblemText] = useState("");
  const [taskId, setTaskId] = useState<string | null>(null);
  const [meta, setMeta] = useState<AnimatedLessonMeta | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const [activeChapter, setActiveChapter] = useState<string | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  // running 期间 5s 轮询 meta；终态（COMPLETED/FAILED）即停轮，避免无意义请求。
  useEffect(() => {
    if (phase !== "running" || !taskId) return;
    let cancelled = false;
    const tick = async () => {
      try {
        const next = await api.animatedLessonMeta(taskId);
        if (cancelled) return;
        setMeta(next);
        if (next.status === "COMPLETED") setPhase("completed");
        else if (next.status === "FAILED") {
          setError(next.errorSummary || "生成失败，请重试或换一种题干表述");
          setPhase("failed");
        }
      } catch (pollError) {
        if (!cancelled) setError(pollError instanceof Error ? pollError.message : String(pollError));
      }
    };
    void tick();
    const timer = window.setInterval(tick, 5000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [phase, taskId, api]);

  useEffect(() => {
    if (phase !== "running") return;
    const timer = window.setInterval(() => setElapsed((v) => v + 1), 1000);
    return () => window.clearInterval(timer);
  }, [phase]);

  async function submit() {
    setError(null);
    setPhase("running");
    setElapsed(0);
    try {
      const created = await api.createAnimatedLessonTask(problemText);
      setTaskId(created.taskId);
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : String(submitError));
      setPhase("failed");
    }
  }

  function seekChapter(start: number, id: string) {
    if (videoRef.current) {
      videoRef.current.currentTime = start;
      setActiveChapter(id);
      void videoRef.current.play();
    }
  }

  const result = meta?.result ?? null;
  const chapters = result?.chapters ?? [];

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        style={{
          position: "fixed", right: 28, bottom: 28, zIndex: 40,
          display: "flex", alignItems: "center", gap: 8,
          padding: "10px 18px", borderRadius: 999, border: "1px solid #E4E7F5",
          background: "#FFFFFF", color: INK, fontSize: 14, cursor: "pointer",
          boxShadow: "0 6px 24px rgba(31, 41, 55, 0.10)",
        }}
      >
        <Clapperboard size={16} color={ACCENT} />
        动画讲题
        {phase === "running" && <LoaderCircle size={14} className="spin" color={ACCENT} />}
      </button>

      {open && (
        <div
          onClick={(event) => { if (event.target === event.currentTarget) setOpen(false); }}
          style={{
            position: "fixed", inset: 0, zIndex: 60,
            background: "rgba(15, 23, 42, 0.42)", display: "flex",
            alignItems: "center", justifyContent: "center", padding: 24,
          }}
        >
          <div style={{
            width: "min(980px, 94vw)", maxHeight: "88vh", overflowY: "auto",
            background: "#FFFFFF", borderRadius: 16, padding: "20px 24px",
            boxShadow: "0 24px 64px rgba(15, 23, 42, 0.22)",
          }}>
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14 }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 16, fontWeight: 600, color: INK }}>
                <Clapperboard size={18} color={ACCENT} /> 一题一课 · 动画讲解
              </div>
              <button type="button" onClick={() => setOpen(false)} aria-label="关闭"
                style={{ border: "none", background: "transparent", cursor: "pointer", color: "#6B7280", display: "flex" }}>
                <X size={18} />
              </button>
            </div>

            {phase === "form" && (
              <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
                <textarea
                  value={problemText}
                  onChange={(event) => setProblemText(event.target.value)}
                  placeholder="粘贴题目文本（含答案与推导过程效果更好），AI 将编写分镜并渲染逐步动画讲解视频。"
                  style={{
                    minHeight: 140, padding: 12, borderRadius: 10, border: "1px solid #E5E7EB",
                    fontSize: 14, lineHeight: 1.7, resize: "vertical", color: INK, fontFamily: "inherit",
                  }}
                />
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ fontSize: 12, color: "#9CA3AF" }}>生成约需 5~25 分钟，可关闭窗口，任务在服务端队列继续。</span>
                  <button
                    type="button" disabled={problemText.trim().length < 10}
                    onClick={() => void submit()}
                    style={{
                      padding: "9px 22px", borderRadius: 10, border: "none",
                      background: problemText.trim().length < 10 ? "#C7D2FE" : ACCENT,
                      color: "#FFFFFF", fontSize: 14, cursor: problemText.trim().length < 10 ? "default" : "pointer",
                    }}
                  >
                    生成动画讲解
                  </button>
                </div>
              </div>
            )}

            {phase === "running" && (
              <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "26px 4px", color: "#4B5563", fontSize: 14 }}>
                <LoaderCircle size={18} className="spin" color={ACCENT} />
                AI 正在编写分镜并渲染动画，已等待 {formatElapsed(elapsed)}
                {error && <span style={{ color: "#B45309" }}>（查询暂时失败，继续重试中：{error}）</span>}
              </div>
            )}

            {phase === "failed" && (
              <div style={{ display: "flex", alignItems: "flex-start", gap: 10, padding: "18px 4px", fontSize: 14, color: "#B91C1C" }}>
                <AlertCircle size={18} style={{ flexShrink: 0, marginTop: 2 }} />
                <div>
                  <div>{error}</div>
                  <button type="button" onClick={() => { setPhase("form"); setError(null); }}
                    style={{ marginTop: 10, padding: "6px 14px", borderRadius: 8, border: "1px solid #E5E7EB", background: "#FFF", cursor: "pointer", color: INK }}>
                    返回修改题干
                  </button>
                </div>
              </div>
            )}

            {phase === "completed" && result && (
              <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
                <div style={{ display: "flex", gap: 16, alignItems: "stretch" }}>
                  <video
                    ref={videoRef}
                    controls
                    preload="metadata"
                    src={taskId ? api.animatedLessonVideoUrl(taskId) : undefined}
                    onTimeUpdate={(event) => {
                      const t = event.currentTarget.currentTime;
                      let current: string | null = null;
                      for (const chapter of chapters) {
                        if (t >= chapter.start - 0.05) current = chapter.id;
                      }
                      setActiveChapter(current);
                    }}
                    style={{ flex: 1, minWidth: 0, borderRadius: 12, background: "#0B1220", alignSelf: "flex-start", width: "60%" }}
                  />
                  <div style={{ width: 230, flexShrink: 0, display: "flex", flexDirection: "column", gap: 6 }}>
                    {chapters.map((chapter, index) => (
                      <button
                        key={chapter.id}
                        type="button"
                        onClick={() => seekChapter(chapter.start, chapter.id)}
                        style={{
                          textAlign: "left", padding: "8px 10px", borderRadius: 9, fontSize: 13, cursor: "pointer",
                          border: activeChapter === chapter.id ? `1px solid ${ACCENT}` : "1px solid #EEF0F6",
                          background: activeChapter === chapter.id ? "#F4F6FF" : "#FFFFFF",
                          color: activeChapter === chapter.id ? ACCENT : "#4B5563",
                        }}
                      >
                        <div style={{ fontWeight: 600 }}>{index + 1} {chapter.title}</div>
                        <div style={{ fontSize: 12, opacity: 0.75, marginTop: 2 }}>
                          {formatElapsed(Math.floor(chapter.start))} · {chapter.subtitle || `${Math.round(chapter.duration)}s`}
                        </div>
                      </button>
                    ))}
                  </div>
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                  <div style={{ padding: "12px 14px", borderRadius: 10, background: "#FAFAF7", border: "1px solid #EFEEE8", fontSize: 14, lineHeight: 1.8, color: INK }}>
                    <RichText text={result.problem.stem} />
                  </div>
                  <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
                    <div style={{ padding: "10px 14px", borderRadius: 10, border: "1px solid #EFEEE8", fontSize: 13, lineHeight: 1.8 }}>
                      <div style={{ fontWeight: 600, color: "#6B7280", marginBottom: 4 }}>已知条件</div>
                      {result.problem.known.map((item, i) => <div key={i}><RichText text={item} /></div>)}
                    </div>
                    <div style={{ padding: "10px 14px", borderRadius: 10, border: "1px solid #EFEEE8", fontSize: 13, lineHeight: 1.8 }}>
                      <div style={{ fontWeight: 600, color: "#6B7280", marginBottom: 4 }}>解题目标</div>
                      <RichText text={result.problem.goal} />
                      <div style={{ fontWeight: 600, color: "#6B7280", margin: "8px 0 4px" }}>核心观察</div>
                      <RichText text={result.problem.core_observation} />
                    </div>
                  </div>
                  <div style={{ padding: "10px 14px", borderRadius: 10, border: "1px solid #D7E3D8", background: "#F6FAF6", fontSize: 14 }}>
                    <span style={{ fontWeight: 600, color: "#3F7A46", marginRight: 8 }}>答案</span>
                    <RichText text={result.problem.answer} />
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}
