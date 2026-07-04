import { FormEvent } from "react";
import { AlertCircle, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import {
  KnowledgePointResponse,
  KnowledgeRelationResponse,
  QuestionBankItemResponse,
} from "../../shared/api/textbookApi";
import { StatusLine } from "./panelShared";

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
  error,
  onKnowledgePointNameChange,
  onChapterPathChange,
  onQuestionTitleChange,
  onQuestionTextChange,
  onQueryChange,
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
  error: string;
  onKnowledgePointNameChange: (value: string) => void;
  onChapterPathChange: (value: string) => void;
  onQuestionTitleChange: (value: string) => void;
  onQuestionTextChange: (value: string) => void;
  onQueryChange: (value: string) => void;
  onCreateKnowledgePoint: (event: FormEvent<HTMLFormElement>) => void;
  onCreateQuestion: (event: FormEvent<HTMLFormElement>) => void;
  onSearchQuestions: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const displaySpinePoints = knowledgePoints.filter((point) => point.sourceSummary?.includes("人工主干v0.1"));
  const displayedKnowledgePoints = (displaySpinePoints.length > 0 ? displaySpinePoints : knowledgePoints).slice(0, 6);
  const displaySpinePointIds = new Set(displaySpinePoints.map((point) => point.knowledgePointId));
  const displaySpineRelations =
    displaySpinePoints.length > 0
      ? knowledgeRelations.filter(
          (relation) =>
            displaySpinePointIds.has(relation.sourceKnowledgePointId)
            && displaySpinePointIds.has(relation.targetKnowledgePointId),
        )
      : knowledgeRelations;
  const displayedKnowledgeRelations = displaySpineRelations.slice(0, 6);

  return (
    <section className="agent-plan-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Knowledge Bank</p>
          <h2>知识点与题库</h2>
        </div>
        <div className="strategy-pill">
          {displaySpinePoints.length > 0 ? `${displaySpinePoints.length}/${knowledgePoints.length} 主干` : `${knowledgePoints.length} 个知识点`}
        </div>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      <div className="agent-plan-grid">
        <form className="search-form" onSubmit={onCreateKnowledgePoint}>
          <label>
            <span>知识点</span>
            <input
              value={knowledgePointName}
              onChange={(event) => onKnowledgePointNameChange(event.target.value)}
              placeholder="例如：函数零点"
            />
          </label>
          <label>
            <span>章节路径</span>
            <input
              value={chapterPath}
              onChange={(event) => onChapterPathChange(event.target.value)}
              placeholder="例如：高中数学/函数/函数零点"
            />
          </label>
          <button type="submit" disabled={saving}>
            {saving ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>保存知识点</span>
          </button>
        </form>
        <form className="search-form" onSubmit={onCreateQuestion}>
          <label>
            <span>题目标题</span>
            <input
              value={questionTitle}
              onChange={(event) => onQuestionTitleChange(event.target.value)}
              placeholder="例如：函数零点个数判断"
            />
          </label>
          <label>
            <span>题干</span>
            <input
              value={questionText}
              onChange={(event) => onQuestionTextChange(event.target.value)}
              placeholder="输入题干正文"
            />
          </label>
          <button type="submit" disabled={saving}>
            {saving ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
            <span>保存题目</span>
          </button>
        </form>
        <form className="resource-block-search" onSubmit={onSearchQuestions}>
          <label>
            <span>题库检索</span>
            <input value={query} onChange={(event) => onQueryChange(event.target.value)} placeholder="输入题型或关键词" />
          </label>
          <button type="submit">
            <Search size={16} />
            <span>检索</span>
          </button>
        </form>
        <div className="tool-decision-list compact">
          {displayedKnowledgePoints.map((point) => (
            <div className="tool-decision allowed" key={point.knowledgePointId}>
              <strong>{point.knowledgePointName}</strong>
              <span>{point.permissionScope}</span>
              <p>{point.chapterPath}</p>
            </div>
          ))}
        </div>
        <div className="knowledge-relation-list">
          {displayedKnowledgeRelations.length > 0 ? (
            displayedKnowledgeRelations.map((relation) => (
              <div className="knowledge-relation-row" key={relation.relationId}>
                <strong>
                  {relation.sourceKnowledgePointId} -&gt; {relation.targetKnowledgePointId}
                </strong>
                <span>{relation.relationType}</span>
                <p>{relation.evidenceSummary || "暂无证据摘要。"}</p>
              </div>
            ))
          ) : (
            <div className="empty-state compact">当前还没有可展示的知识点关系。</div>
          )}
        </div>
        <div className="resource-search-results">
          {questions.map((question) => (
            <article className="resource-search-hit" key={question.questionId}>
              <strong>{question.questionTitle}</strong>
              <span>{question.permissionScope ?? "未设置范围"} / {question.difficulty ?? "medium"}</span>
              <p>{question.questionText}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
