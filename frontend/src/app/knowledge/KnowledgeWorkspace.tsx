import { AlertCircle, Database, Loader2, Search } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  KnowledgeGraphSpineResponse,
  KnowledgePointResponse,
  KnowledgeRelationResponse,
  QuestionBankItemResponse,
  TeacherResourceBlockSearchAuditEvent,
  TeacherResourceBlockSearchResponse,
  VectorIndexStatusResponse,
  createTextbookApiClient,
} from "../../shared/api/textbookApi";

type TextbookApiClient = ReturnType<typeof createTextbookApiClient>;

type KnowledgeWorkspaceProps = {
  api: TextbookApiClient;
};

export function KnowledgeWorkspace({ api }: KnowledgeWorkspaceProps) {
  const [graph, setGraph] = useState<KnowledgeGraphSpineResponse | null>(null);
  const [knowledgePoints, setKnowledgePoints] = useState<KnowledgePointResponse[]>([]);
  const [knowledgeRelations, setKnowledgeRelations] = useState<KnowledgeRelationResponse[]>([]);
  const [questions, setQuestions] = useState<QuestionBankItemResponse[]>([]);
  const [vectorStatus, setVectorStatus] = useState<VectorIndexStatusResponse | null>(null);
  const [query, setQuery] = useState("");
  const [questionQuery, setQuestionQuery] = useState("");
  const [pointName, setPointName] = useState("");
  const [chapterPath, setChapterPath] = useState("");
  const [questionTitle, setQuestionTitle] = useState("");
  const [questionText, setQuestionText] = useState("");
  const [searchResult, setSearchResult] = useState<TeacherResourceBlockSearchResponse | null>(null);
  const [searchAudit, setSearchAudit] = useState<TeacherResourceBlockSearchAuditEvent | null>(null);
  const [loading, setLoading] = useState(false);
  const [searching, setSearching] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const nodes = graph?.nodes ?? [];
  const edges = graph?.edges ?? [];
  const moduleNodes = useMemo(() => nodes.filter((node) => node.nodeType === "MODULE"), [nodes]);
  const topicNodes = useMemo(() => nodes.filter((node) => node.nodeType === "TOPIC"), [nodes]);
  const methodNodes = useMemo(() => nodes.filter((node) => node.nodeType === "METHOD"), [nodes]);
  const nodeById = useMemo(() => new Map(nodes.map((node) => [node.id, node])), [nodes]);

  function refresh() {
    setLoading(true);
    setError("");
    Promise.all([
      api.getKnowledgeGraphSpine(),
      api.listKnowledgePoints(),
      api.listKnowledgeRelations(),
      api.getVectorIndexStatus(),
      questionQuery.trim() ? api.searchQuestionBankItems(questionQuery.trim(), 8) : Promise.resolve([]),
    ])
      .then(([nextGraph, points, relations, nextVectorStatus, nextQuestions]) => {
        setGraph(nextGraph);
        setKnowledgePoints(points);
        setKnowledgeRelations(relations);
        setVectorStatus(nextVectorStatus);
        setQuestions(nextQuestions);
      })
      .catch((caught: Error) => setError(caught.message))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    refresh();
  }, [api]);

  function handleSearchResources(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim()) {
      setError("请输入资源检索词。");
      return;
    }
    setSearching(true);
    setError("");
    setSearchAudit(null);
    api
      .searchTeacherResourceBlocks(query.trim(), 8)
      .then((result) => {
        setSearchResult(result);
        return api
          .getTeacherResourceBlockSearchAudit(result.queryId)
          .then(setSearchAudit)
          .catch((caught: Error) => setError(caught.message));
      })
      .catch((caught: Error) => setError(caught.message))
      .finally(() => setSearching(false));
  }

  function handleSearchQuestions(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError("");
    if (!questionQuery.trim()) {
      setQuestions([]);
      setLoading(false);
      return;
    }
    api
      .searchQuestionBankItems(questionQuery.trim(), 8)
      .then(setQuestions)
      .catch((caught: Error) => setError(caught.message))
      .finally(() => setLoading(false));
  }

  function handleCreatePoint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!pointName.trim() || !chapterPath.trim()) {
      setError("知识点名称和章节路径不能为空。");
      return;
    }
    setSaving(true);
    setError("");
    api
      .createKnowledgePoint({
        knowledgePointName: pointName.trim(),
        chapterPath: chapterPath.trim(),
        permissionScope: "MATH_VIP",
        sourceSummary: "前端知识库管理入口创建",
      })
      .then((created) => {
        setKnowledgePoints((current) => [created, ...current]);
        setPointName("");
        setChapterPath("");
      })
      .catch((caught: Error) => setError(caught.message))
      .finally(() => setSaving(false));
  }

  function handleCreateQuestion(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!questionTitle.trim() || !questionText.trim()) {
      setError("题目标题和题干不能为空。");
      return;
    }
    setSaving(true);
    setError("");
    api
      .createQuestionBankItem({
        questionTitle: questionTitle.trim(),
        questionText: questionText.trim(),
        answerJson: JSON.stringify({ answer: "", steps: [] }),
        difficulty: "medium",
        permissionScope: "MATH_VIP",
        knowledgePointIds: knowledgePoints.slice(0, 1).map((point) => point.knowledgePointId),
      })
      .then((created) => {
        setQuestions((current) => [created, ...current]);
        setQuestionTitle("");
        setQuestionText("");
      })
      .catch((caught: Error) => setError(caught.message))
      .finally(() => setSaving(false));
  }

  return (
    <section className="knowledge-workspace">
      <div className="result-header">
        <div>
          <p className="eyebrow">Knowledge Workspace</p>
          <h2>知识库 / 图谱 / RAG</h2>
        </div>
        <div className="result-actions">
          {vectorStatus ? (
            <div className="strategy-pill">
              {vectorStatus.collectionName} / {vectorStatus.dimension}d
            </div>
          ) : null}
          <button type="button" className="inline-action" onClick={refresh} disabled={loading}>
            {loading ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>刷新</span>
          </button>
        </div>
      </div>

      {error ? <StatusLine text={error} tone="danger" /> : null}

      <div className="graph-spine-metrics">
        <Metric label="图谱节点" value={graph?.nodeCount ?? nodes.length} />
        <Metric label="关系边" value={graph?.edgeCount ?? edges.length} />
        <Metric label="知识点" value={knowledgePoints.length} />
        <Metric label="题库项" value={questions.length} />
        <Metric label="向量状态" value={vectorStatus?.status ?? "unknown"} />
      </div>
      {vectorStatus ? (
        <div className="resource-audit-summary">
          <span>{vectorStatus.collectionState || "collection_unknown"}</span>
          <span>{vectorStatus.indexState || "index_unknown"}</span>
          <span>{vectorStatus.loadState || "load_unknown"}</span>
          <span>{vectorStatus.rowCount} rows</span>
          <span>{vectorStatus.embeddingModel}</span>
        </div>
      ) : null}

      <div className="graph-spine-layout">
        <div className="graph-spine-column modules">
          <h3>主干模块</h3>
          <div className="graph-spine-node-list">
            {moduleNodes.map((node) => (
              <article className="graph-spine-node" key={node.id}>
                <strong>{node.label}</strong>
                <span>{node.chapterPath}</span>
              </article>
            ))}
          </div>
        </div>

        <div className="graph-spine-column">
          <h3>章节知识点</h3>
          <div className="graph-spine-node-list compact">
            {topicNodes.slice(0, 16).map((node) => (
              <article className="graph-spine-node topic" key={node.id}>
                <strong>{node.label}</strong>
                <span>{node.chapterPath}</span>
              </article>
            ))}
          </div>
        </div>

        <div className="graph-spine-column">
          <h3>高频方法</h3>
          <div className="graph-spine-node-list compact">
            {methodNodes.slice(0, 16).map((node) => (
              <article className="graph-spine-node method" key={node.id}>
                <strong>{node.label}</strong>
                <span>{node.chapterPath}</span>
              </article>
            ))}
          </div>
        </div>
      </div>

      <div className="knowledge-workspace-grid">
        <section className="graph-spine-relations">
          <div className="knowledge-graph-head">
            <h3>可展示关系</h3>
            <span>{edges.length} 条</span>
          </div>
          <div className="graph-spine-edge-list">
            {edges.slice(0, 24).map((edge) => {
              const source = nodeById.get(edge.source)?.label ?? edge.source;
              const target = nodeById.get(edge.target)?.label ?? edge.target;
              return (
                <article className="graph-spine-edge" key={edge.id}>
                  <strong>{edge.relationType}</strong>
                  <span>
                    {source} -&gt; {target}
                  </span>
                  <p>{edge.evidenceSummary}</p>
                </article>
              );
            })}
          </div>
        </section>

        <section className="graph-spine-relations">
          <div className="knowledge-graph-head">
            <h3>真实向量检索</h3>
            <span>{searchResult?.retrievalMode ?? "未检索"}</span>
          </div>
          <form className="resource-block-search" onSubmit={handleSearchResources}>
            <label>
              <span>RAG 查询</span>
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="输入知识点、题型或公式关键词"
              />
            </label>
            <button type="submit" disabled={searching}>
              {searching ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              <span>检索</span>
            </button>
          </form>
          {searchResult ? (
            <div className="resource-search-results">
              <div className="resource-search-summary">
                <span>{searchResult.hitCount} hits</span>
                <span>{searchResult.queryId}</span>
              </div>
              {searchAudit ? (
                <div className="resource-audit-summary">
                  <span>{searchAudit.elapsedMs}ms</span>
                  <span>
                    {searchAudit.subjectType}:{searchAudit.subjectId}
                  </span>
                </div>
              ) : null}
              {searchResult.hits.map((hit) => (
                <article className="resource-search-hit" key={`${hit.documentId}:${hit.blockId}`}>
                  <strong>{hit.documentTitle}</strong>
                  <span>
                    {hit.blockType} #{hit.blockOrder} / score {hit.score.toFixed(4)}
                  </span>
                  <p>{hit.snippet}</p>
                </article>
              ))}
            </div>
          ) : null}
        </section>
      </div>

      <div className="knowledge-workspace-grid">
        <section className="graph-spine-relations">
          <div className="knowledge-graph-head">
            <h3>知识点维护</h3>
            <span>{knowledgeRelations.length} relations</span>
          </div>
          <form className="search-form" onSubmit={handleCreatePoint}>
            <label>
              <span>知识点</span>
              <input
                value={pointName}
                onChange={(event) => setPointName(event.target.value)}
                placeholder="例如：函数零点"
              />
            </label>
            <label>
              <span>章节路径</span>
              <input
                value={chapterPath}
                onChange={(event) => setChapterPath(event.target.value)}
                placeholder="例如：高中数学/函数/函数零点"
              />
            </label>
            <button type="submit" disabled={saving}>
              {saving ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>保存知识点</span>
            </button>
          </form>
          <div className="graph-spine-node-list compact">
            {knowledgePoints.slice(0, 10).map((point) => (
              <article className="graph-spine-node topic" key={point.knowledgePointId}>
                <strong>{point.knowledgePointName}</strong>
                <span>{point.chapterPath}</span>
              </article>
            ))}
          </div>
        </section>

        <section className="graph-spine-relations">
          <div className="knowledge-graph-head">
            <h3>题库维护</h3>
            <span>{questions.length} shown</span>
          </div>
          <form className="resource-block-search" onSubmit={handleSearchQuestions}>
            <label>
              <span>题库查询</span>
              <input
                value={questionQuery}
                onChange={(event) => setQuestionQuery(event.target.value)}
                placeholder="输入题型或关键词"
              />
            </label>
            <button type="submit" disabled={loading}>
              {loading ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              <span>查找</span>
            </button>
          </form>
          <form className="search-form" onSubmit={handleCreateQuestion}>
            <label>
              <span>标题</span>
              <input
                value={questionTitle}
                onChange={(event) => setQuestionTitle(event.target.value)}
                placeholder="例如：函数零点个数判断"
              />
            </label>
            <label>
              <span>题干</span>
              <textarea
                value={questionText}
                onChange={(event) => setQuestionText(event.target.value)}
                placeholder="输入题干正文"
              />
            </label>
            <button type="submit" disabled={saving}>
              {saving ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>保存题目</span>
            </button>
          </form>
          <div className="resource-search-results">
            {questions.map((question) => (
              <article className="resource-search-hit" key={question.questionId}>
                <strong>{question.questionTitle}</strong>
                <p>{question.questionText}</p>
              </article>
            ))}
          </div>
        </section>
      </div>
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function StatusLine({ text, tone }: { text: string; tone?: "danger" }) {
  return (
    <div className={`status-line ${tone ?? ""}`}>
      <AlertCircle size={16} />
      <span>{text}</span>
    </div>
  );
}
