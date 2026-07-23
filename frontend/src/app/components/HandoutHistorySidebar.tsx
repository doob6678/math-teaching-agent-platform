import { BookOpen, ChevronDown, Loader2, X } from "lucide-react";
import { useMemo } from "react";
import { TeachingTaskResponse } from "../../shared/api/textbookApi";

export function HandoutHistorySidebar({
  history,
  currentTaskId,
  loading,
  openingTaskId,
  isOpen,
  onToggle,
  onSelect,
  onRemove,
}: {
  history: TeachingTaskResponse[];
  currentTaskId: string;
  loading: boolean;
  openingTaskId: string;
  isOpen: boolean;
  onToggle: () => void;
  onSelect: (task: TeachingTaskResponse) => void;
  onRemove: (taskId: string) => void;
}) {
  const visibleHistory = useMemo(() => buildVisibleHistory(history), [history]);

  return (
    <aside className={`handout-resource-sidebar${isOpen ? " open" : " collapsed"}`} aria-label="讲义历史侧边栏">
      <button className="handout-resource-toggle" type="button" onClick={onToggle} aria-expanded={isOpen}>
        <ChevronDown size={16} className={isOpen ? "" : "collapsed"} />
        <span>{isOpen ? "收起历史" : "历史"}</span>
      </button>

      {isOpen ? (
        <div className="handout-resource-panel">
          <div className="handout-resource-head">
            <div>
              <strong>历史讲义</strong>
              <span>{loading ? "同步中" : `${visibleHistory.length} 条`}</span>
            </div>
          </div>

          {!visibleHistory.length ? (
            <div className="handout-resource-empty">
              <BookOpen size={16} />
              <span>当前还没有可恢复的讲义。</span>
            </div>
          ) : (
            <div className="handout-resource-list">
              {visibleHistory.map((item) => {
                const active = item.taskId === currentTaskId;
                const opening = item.taskId === openingTaskId;
                return (
                  <article className={`handout-resource-item${active || opening ? " active" : ""}${opening ? " loading" : ""}`} key={item.taskId}>
                    <button
                      type="button"
                      onClick={() => onSelect(item)}
                      aria-current={active || opening ? "page" : undefined}
                      aria-busy={opening}
                    >
                      <strong>{displayTaskTitle(item)}</strong>
                      <span>{active || opening ? "当前查看 · " : ""}{statusLabel(item.status)} · {handoutVersionSummary(item)}</span>
                    </button>
                    <button
                      className="handout-resource-remove"
                      type="button"
                      aria-label={`移除 ${displayTaskTitle(item)}`}
                      onClick={() => onRemove(item.taskId)}
                    >
                      {opening ? <Loader2 className="spin" size={14} /> : <X size={14} />}
                    </button>
                  </article>
                );
              })}
            </div>
          )}
        </div>
      ) : null}
    </aside>
  );
}

/** Replaces refreshed task data at its existing index so opening history never changes the user's ordering. */
export function replaceHistoryTaskInPlace(history: TeachingTaskResponse[], refreshedTask: TeachingTaskResponse) {
  const index = history.findIndex((item) => item.taskId === refreshedTask.taskId);
  if (index < 0) return history;
  return history.map((item, itemIndex) => itemIndex === index ? refreshedTask : item);
}

function buildVisibleHistory(history: TeachingTaskResponse[]) {
  const seen = new Set<string>();
  return history.filter((item) => {
    if (!isDisplayableHistoryTask(item) || seen.has(item.taskId)) return false;
    seen.add(item.taskId);
    return true;
  });
}

// 这里继续过滤历史脏数据，避免旧坏任务在新工作台里再次污染当前编辑区。
function isDisplayableHistoryTask(task: TeachingTaskResponse) {
  if (!task.taskId) return false;
  const status = (task.status || "").toUpperCase();
  // Created and running records contain the only durable checkpoint after a page refresh. Hiding them made an
  // interrupted generation disappear from history even though the backend still owned its evidence and stages.
  if (!["CREATED", "RUNNING", "FAILED", "COMPLETED"].includes(status)) return false;
  const title = cleanText(task.learningGoal || task.questionText || "");
  if (!title || looksCorrupted(title)) return false;
  const body = cleanText(task.teacherHandoutLatex || task.studentHandoutLatex || "");
  // A failed/running task may have no handout body yet, but its nodes/evidence are the recoverable progress record.
  if (status === "FAILED" && body.length < 18 && !(task.evidence?.length || task.nodes?.length)) return false;
  if (looksCorrupted(body)) return false;
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
    || lower.includes("内部提示词")
    || lower.includes("系统提示")
    || lower.includes("提示词")
    || lower.includes("{{")
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

function displayTaskTitle(task: TeachingTaskResponse) {
  return cleanText(task.learningGoal || task.questionText || task.aiDraft?.teacherExplanation || `讲义任务 ${task.taskId.slice(0, 8)}`).slice(0, 34);
}

function handoutVersionSummary(task: TeachingTaskResponse) {
  const versions = [
    task.teacherHandoutLatex ? "教师" : "",
    task.studentHandoutLatex ? "学生" : "",
    task.lectureHandoutLatex ? "讲解" : "",
  ].filter(Boolean);
  return versions.length ? versions.join("/") : "草稿";
}

function cleanText(value: string) {
  return value.replace(/!\[[^\]]*]\([^)]*\)/g, " ").replace(/[#*_`>$]/g, " ").replace(/\s+/g, " ").trim();
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    CREATED: "已创建",
    RUNNING: "生成中",
    COMPLETED: "已完成",
    FAILED: "失败",
  };
  return labels[status] ?? status;
}
