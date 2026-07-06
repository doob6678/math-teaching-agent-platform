import { FormEvent } from "react";
import { AlertCircle, ArrowLeft, ArrowRight, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import {
  KnowledgePointResponse,
  KnowledgeRelationResponse,
  QuestionBankItemResponse,
} from "../../shared/api/textbookApi";
import { compactText, StatusLine } from "./panelShared";

export function KnowledgeQuestionBankPanel({
  knowledgePoints,
  knowledgeRelations,
  questions,
  knowledgePointName,
  chapterPath,
  questionTitle,
  questionText,
  query,
  saving,
  loadingQuestions,
  error,
  questionPage,
  questionPageSize,
  onKnowledgePointNameChange,
  onChapterPathChange,
  onQuestionTitleChange,
  onQuestionTextChange,
  onQueryChange,
  onQuestionPageChange,
  onQuestionPageSizeChange,
  onCreateKnowledgePoint,
  onCreateQuestion,
  onSearchQuestions,
}: {
  knowledgePoints: KnowledgePointResponse[];
  knowledgeRelations: KnowledgeRelationResponse[];
  questions: QuestionBankItemResponse[];
  knowledgePointName: string;
  chapterPath: string;
  questionTitle: string;
  questionText: string;
  query: string;
  saving: boolean;
  loadingQuestions: boolean;
  error: string;
  questionPage: number;
  questionPageSize: number;
  onKnowledgePointNameChange: (value: string) => void;
  onChapterPathChange: (value: string) => void;
  onQuestionTitleChange: (value: string) => void;
  onQuestionTextChange: (value: string) => void;
  onQueryChange: (value: string) => void;
  onQuestionPageChange: (value: number) => void;
  onQuestionPageSizeChange: (value: number) => void;
  onCreateKnowledgePoint: (event: FormEvent<HTMLFormElement>) => void;
  onCreateQuestion: (event: FormEvent<HTMLFormElement>) => void;
  onSearchQuestions: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const displaySpinePoints = knowledgePoints.filter((point) => point.sourceSummary?.includes("主干"));
  const displayedKnowledgePoints = (displaySpinePoints.length > 0 ? displaySpinePoints : knowledgePoints).slice(0, 8);
  const displaySpinePointIds = new Set(displayedKnowledgePoints.map((point) => point.knowledgePointId));
  const displayedKnowledgeRelations = knowledgeRelations
    .filter((relation) => displaySpinePointIds.has(relation.sourceKnowledgePointId) && displaySpinePointIds.has(relation.targetKnowledgePointId))
    .slice(0, 6);
  const normalizedPageSize = Math.max(5, Math.min(30, questionPageSize || 10));
  const questionPageCount = Math.max(1, Math.ceil(questions.length / normalizedPageSize));
  const safeQuestionPage = Math.min(Math.max(1, questionPage), questionPageCount);
  const visibleQuestions = questions.slice((safeQuestionPage - 1) * normalizedPageSize, safeQuestionPage * normalizedPageSize);

  return (
    <section className="agent-plan-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">知识库与题库</p>
          <h2>主干知识点</h2>
        </div>
        <div className="strategy-pill">
          {displaySpinePoints.length > 0 ? `${displaySpinePoints.length}/${knowledgePoints.length} 主干节点` : `${knowledgePoints.length} 个知识点`}
        </div>
      </div>

      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}

      <div className="agent-plan-grid">
        <form className="search-form" onSubmit={onCreateKnowledgePoint}>
          <label>
            <span>知识点名称</span>
            <input
              className="form-input"
              value={knowledgePointName}
              onChange={(event) => onKnowledgePointNameChange(event.target.value)}
              placeholder="例如：函数零点"
            />
          </label>
          <label>
            <span>章节路径</span>
            <input
              className="form-input"
              value={chapterPath}
              onChange={(event) => onChapterPathChange(event.target.value)}
              placeholder="例如：高中数学/函数/函数零点"
            />
          </label>
          <button className="btn btn-primary" type="submit" disabled={saving}>
            {saving ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>保存知识点</span>
          </button>
        </form>

        <form className="search-form" onSubmit={onCreateQuestion}>
          <label>
            <span>题目标题</span>
            <input
              className="form-input"
              value={questionTitle}
              onChange={(event) => onQuestionTitleChange(event.target.value)}
              placeholder="例如：双曲线定义基础题"
            />
          </label>
          <label>
            <span>题干内容</span>
            <textarea
              className="form-textarea"
              value={questionText}
              onChange={(event) => onQuestionTextChange(event.target.value)}
              placeholder="输入题干正文"
              rows={3}
            />
          </label>
          <button className="btn btn-secondary" type="submit" disabled={saving}>
            {saving ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
            <span>保存题目</span>
          </button>
        </form>

        <form className="resource-block-search" onSubmit={onSearchQuestions}>
          <label>
            <span>题库检索</span>
            <input className="form-input" value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder="可留空浏览最近题目，也可输入题型或关键词" />
          </label>
          <button className="btn btn-primary" type="submit" disabled={loadingQuestions}>
            {loadingQuestions ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            <span>{query.trim() ? "检索" : "浏览题库"}</span>
          </button>
        </form>

        <div className="tool-decision-list compact">
          {displayedKnowledgePoints.map((point) => (
            <div className="tool-decision allowed" key={point.knowledgePointId}>
              <strong>{point.knowledgePointName}</strong>
              <span>{scopeLabel(point.permissionScope)}</span>
              <p>{compactText(point.chapterPath, 50)}</p>
            </div>
          ))}
        </div>

        <div className="knowledge-relation-list">
          {displayedKnowledgeRelations.length > 0 ? (
            displayedKnowledgeRelations.map((relation) => (
              <div className="knowledge-relation-row" key={relation.relationId}>
                <strong>{relationTypeLabel(relation.relationType)}</strong>
                <span>{compactRelation(relation, knowledgePoints)}</span>
                <p>{compactText(relation.evidenceSummary, 84)}</p>
              </div>
            ))
          ) : (
            <div className="empty-state compact">当前还没有可展示的主干关系。</div>
          )}
        </div>

        <div className="resource-search-results">
          <div className="resource-search-summary">
            <span>{query.trim() ? `关键词：${query.trim()}` : "最近题目"}</span>
            <span>共 {questions.length} 条</span>
            <label className="dashboard-page-size">
              每页
              <input
                className="form-input"
                type="number"
                min={5}
                max={30}
                value={normalizedPageSize}
                onChange={(event) => onQuestionPageSizeChange(Number(event.target.value))}
              />
            </label>
          </div>
          {loadingQuestions ? (
            <div className="handout-preview-placeholder">
              <Loader2 className="spin" size={20} />
              <strong>正在读取题库</strong>
              <span>后端会按当前账号权限返回真实题库数据。</span>
            </div>
          ) : !questions.length ? (
            <div className="handout-preview-placeholder">
              <Search size={20} />
              <strong>{query.trim() ? "没有匹配题目" : "题库还没有入库题目"}</strong>
              <span>{query.trim() ? "可以换一个关键词，或先从教师资源导入题目。" : "当前库为空；请在教师资源里选择已解析资料执行“导入题库”，不会展示假数据。"}</span>
            </div>
          ) : null}
          {visibleQuestions.map((question) => (
            <article className="resource-search-hit" key={question.questionId}>
              <strong>{questionDisplayTitle(question)}</strong>
              <span>{scopeLabel(question.permissionScope)} / {difficultyLabel(question.difficulty)}</span>
              <p>{questionSummary(question.questionText)}</p>
              <details className="review-details">
                <summary>查看题干</summary>
                <p>{cleanQuestionText(question.questionText)}</p>
              </details>
            </article>
          ))}
          {questions.length ? (
            <div className="dashboard-pagination">
              <div className="dashboard-pagination-meta">
                <span>第 {safeQuestionPage} / {questionPageCount} 页</span>
                <span>当前显示 {visibleQuestions.length} 条</span>
              </div>
              <div className="dashboard-pagination-nav">
                <button className="btn-icon" type="button" disabled={safeQuestionPage <= 1} onClick={() => onQuestionPageChange(safeQuestionPage - 1)} title="上一页">
                  <ArrowLeft size={16} />
                </button>
                <button className="btn-icon" type="button" disabled={safeQuestionPage >= questionPageCount} onClick={() => onQuestionPageChange(safeQuestionPage + 1)} title="下一页">
                  <ArrowRight size={16} />
                </button>
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
}

function questionDisplayTitle(question: QuestionBankItemResponse) {
  const cleanedTitle = cleanQuestionText(question.questionTitle);
  const cleanedBody = cleanQuestionText(question.questionText);
  const title = meaningfulQuestionTitle(cleanedTitle) || meaningfulQuestionTitle(cleanedBody);
  return title || "未命名题目";
}

function meaningfulQuestionTitle(value: string) {
  const text = value
    .replace(/赵礼显数学/g, "")
    .replace(/\*+/g, "")
    .replace(/\s+/g, " ")
    .trim();
  const numbered = text.match(/(?:^|[。；;])\s*(\d+[.．、]\s*[^。；;]{12,90})/);
  if (numbered?.[1]) {
    return compactText(numbered[1], 64);
  }
  if (text.length < 8 || /^[\W_]+$/.test(text)) {
    return "";
  }
  return compactText(text, 64);
}

function questionSummary(value: string) {
  const text = cleanQuestionText(value);
  const firstQuestion = text.match(/(?:^|[。；;])\s*\d+[.．、]\s*([^。；;]{20,150})/);
  return compactText(firstQuestion?.[1] || text, 110);
}

function cleanQuestionText(value?: string | null) {
  return (value ?? "")
    .replace(/赵礼显数学/g, " ")
    .replace(/(?:\*\s*){2,}/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function compactRelation(relation: KnowledgeRelationResponse, points: KnowledgePointResponse[]) {
  const pointMap = new Map(points.map((point) => [point.knowledgePointId, point.knowledgePointName]));
  const source = pointMap.get(relation.sourceKnowledgePointId) ?? compactText(relation.sourceKnowledgePointId, 18);
  const target = pointMap.get(relation.targetKnowledgePointId) ?? compactText(relation.targetKnowledgePointId, 18);
  return `${source} → ${target}`;
}

function scopeLabel(value?: string | null) {
  const labels: Record<string, string> = {
    TEACHER_PRIVATE: "教师私有",
    MATH_VIP: "教研共享",
    PUBLIC_TEXTBOOK: "公开教材",
  };
  return labels[value ?? ""] ?? (value || "未设置范围");
}

function difficultyLabel(value?: string | null) {
  const labels: Record<string, string> = {
    easy: "简单",
    medium: "中等",
    hard: "较难",
  };
  return labels[(value ?? "medium").toLowerCase()] ?? (value || "中等");
}

function relationTypeLabel(value?: string | null) {
  const normalized = (value ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    prerequisite: "前置关系",
    prerequisite_for: "前置关系",
    requires: "依赖关系",
    contains: "包含",
    part_of: "归属",
    related: "相关",
    related_to: "相关",
    similar: "相似",
    method_of: "方法归属",
    supports: "支撑",
  };
  return labels[normalized] ?? "关系";
}
