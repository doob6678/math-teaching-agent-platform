import { useEffect, useMemo, useState } from "react";
import { AlertCircle, ArrowLeft, ArrowRight, Database, Loader2, Search } from "lucide-react";
import { StudentDashboardResponse } from "../../shared/api/textbookApi";
import { boundedPercent, StatusLine } from "./panelShared";

const DEFAULT_DASHBOARD_PAGE_SIZE = 10;
const DASHBOARD_PAGE_SIZE_OPTIONS = [10, 20, 50];

export function StudentDashboardPanel({
  dashboard,
  loading,
  error,
  viewerRole,
  targetStudentId,
  onTargetStudentIdChange,
  onLoad,
  onRefresh,
}: {
  dashboard: StudentDashboardResponse | null;
  loading: boolean;
  error: string;
  viewerRole?: string;
  targetStudentId?: string;
  onTargetStudentIdChange?: (value: string) => void;
  onLoad?: () => void;
  onRefresh?: () => void;
}) {
  const [progressPage, setProgressPage] = useState(1);
  const [weakPointPage, setWeakPointPage] = useState(1);
  const [questionPage, setQuestionPage] = useState(1);
  const [scorePage, setScorePage] = useState(1);
  const [progressPageSize, setProgressPageSize] = useState(DEFAULT_DASHBOARD_PAGE_SIZE);
  const [weakPointPageSize, setWeakPointPageSize] = useState(DEFAULT_DASHBOARD_PAGE_SIZE);
  const [questionPageSize, setQuestionPageSize] = useState(DEFAULT_DASHBOARD_PAGE_SIZE);
  const [scorePageSize, setScorePageSize] = useState(DEFAULT_DASHBOARD_PAGE_SIZE);
  const latestScore = dashboard?.scoreTrend.at(-1);
  const needsTarget = viewerRole === "teacher" || viewerRole === "admin";
  const isGlobalView = needsTarget && dashboard?.studentId === "__all_students__";
  const progressItems = dashboard?.knowledgeProgress ?? [];
  const weakPointItems = dashboard?.weakPoints ?? [];
  const questionItems = dashboard?.recentQuestions ?? [];
  const scoreItems = dashboard?.scoreTrend ?? [];
  const visibleProgress = useMemo(
    () => pageSlice(progressItems, progressPage, progressPageSize),
    [progressItems, progressPage, progressPageSize],
  );
  const visibleScores = useMemo(
    () => pageSlice(scoreItems, scorePage, scorePageSize),
    [scoreItems, scorePage, scorePageSize],
  );

  useEffect(() => {
    setProgressPage(1);
    setWeakPointPage(1);
    setQuestionPage(1);
    setScorePage(1);
    setProgressPageSize(DEFAULT_DASHBOARD_PAGE_SIZE);
    setWeakPointPageSize(DEFAULT_DASHBOARD_PAGE_SIZE);
    setQuestionPageSize(DEFAULT_DASHBOARD_PAGE_SIZE);
    setScorePageSize(DEFAULT_DASHBOARD_PAGE_SIZE);
  }, [dashboard?.tenantId, dashboard?.studentId, dashboard?.viewerSubjectId]);

  return (
    <section className="student-dashboard">
      <div className="result-header">
        <div>
          <p className="eyebrow">学习画像</p>
          <h2>{needsTarget ? "学生画像概览" : "我的学习画像"}</h2>
        </div>
        <div className="result-actions">
          {dashboard ? <div className="strategy-pill">{roleLabel(dashboard.viewerRole)}查看</div> : null}
          {onRefresh ? (
            <button type="button" className="inline-action btn btn-ghost btn-sm" onClick={onRefresh} disabled={loading}>
              {loading ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>刷新快照</span>
            </button>
          ) : null}
        </div>
      </div>

      {needsTarget ? (
        <div className="dashboard-target-row">
          <label>
            <span>学生 ID</span>
            <input
              className="form-input"
              value={targetStudentId ?? ""}
              onChange={(event) => onTargetStudentIdChange?.(event.target.value)}
              placeholder="留空查看全局概览，或输入 student-001"
            />
          </label>
          <button type="button" className="btn btn-secondary" onClick={onLoad} disabled={loading}>
            {loading ? <Loader2 className="spin" size={15} /> : <Search size={15} />}
            <span>{targetStudentId?.trim() ? "查看学生" : "查看全局"}</span>
          </button>
        </div>
      ) : null}

      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在读取学习画像" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {!dashboard && !loading && !error ? (
        <div className="empty-state compact">
          {needsTarget ? "留空会查看当前租户的全局学习概览；输入学生 ID 可查看单个学生。" : "暂无学习画像。"}
        </div>
      ) : null}

      {dashboard ? (
        <div className="dashboard-grid">
          <div className="profile-strip">
            <div>
              <span>{subjectLabel(dashboard.subjectRole, isGlobalView)}</span>
              <strong>{isGlobalView ? "全局概览" : dashboard.studentId}</strong>
            </div>
            <div>
              <span>查看者</span>
              <strong>{dashboard.viewerSubjectId}</strong>
            </div>
            <div>
              <span>{isGlobalView ? "数据范围" : "最近成绩"}</span>
              <strong>{latestScore ? `${latestScore.score} / 年级 ${latestScore.rankInGrade}` : "未记录"}</strong>
            </div>
          </div>

          <div className="knowledge-panel">
            <h3>知识点进度</h3>
            {visibleProgress.length ? (
              <div className="progress-list">
                {visibleProgress.map((item) => (
                  <div className="progress-item" key={item.knowledgePointId ?? item.knowledgePointName}>
                    <div className="progress-head">
                      <strong>{item.knowledgePointName}</strong>
                      <span>{item.progressPercent}%</span>
                    </div>
                    <div className="progress-track" aria-label={`${item.knowledgePointName} ${item.progressPercent}%`}>
                      <div className="progress-fill" style={{ width: `${boundedPercent(item.progressPercent)}%` }} />
                    </div>
                    {item.textbookAnchor ? <p>{item.textbookAnchor}</p> : null}
                  </div>
                ))}
                <PaginationControls
                  label="知识点"
                  page={progressPage}
                  pageSize={progressPageSize}
                  total={progressItems.length}
                  onPageChange={setProgressPage}
                  onPageSizeChange={setProgressPageSize}
                />
              </div>
            ) : (
              <div className="empty-state compact">暂无知识点进度。</div>
            )}
          </div>

          <CompactList
            title="薄弱点"
            empty="暂无薄弱点。"
            page={weakPointPage}
            pageSize={weakPointPageSize}
            onPageChange={setWeakPointPage}
            onPageSizeChange={setWeakPointPageSize}
            items={weakPointItems.map((item) => ({
              key: item.knowledgePointId ?? item.knowledgePointName,
              title: item.knowledgePointName,
              meta: `等级 ${item.weaknessLevel}`,
            }))}
          />

          <CompactList
            title="历史问题"
            empty="暂无历史问题。"
            page={questionPage}
            pageSize={questionPageSize}
            onPageChange={setQuestionPage}
            onPageSizeChange={setQuestionPageSize}
            items={questionItems.map((item) => ({
              key: item.recordId,
              title: item.questionTitle,
              meta: `${questionSourceLabel(item.sourceType)} / ${questionStatusLabel(item.status)}`,
            }))}
          />

          <div className="dashboard-column score-column">
            <h3>成绩趋势</h3>
            {visibleScores.length ? (
              <>
                {visibleScores.map((item) => (
                  <div className="score-item" key={item.examName}>
                    <span>{item.examName}</span>
                    <div className="score-bar">
                      <div style={{ width: `${boundedPercent((item.score / 150) * 100)}%` }} />
                    </div>
                    <strong>{item.score}</strong>
                  </div>
                ))}
                <PaginationControls
                  label="成绩"
                  page={scorePage}
                  pageSize={scorePageSize}
                  total={scoreItems.length}
                  onPageChange={setScorePage}
                  onPageSizeChange={setScorePageSize}
                />
              </>
            ) : (
              <div className="empty-state compact">暂无成绩记录。</div>
            )}
          </div>
        </div>
      ) : null}
    </section>
  );
}

function CompactList({
  title,
  empty,
  items,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
}: {
  title: string;
  empty: string;
  items: Array<{ key: string; title: string; meta: string }>;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}) {
  const visibleItems = pageSlice(items, page, pageSize);
  return (
    <div className="dashboard-column">
      <h3>{title}</h3>
      {visibleItems.length ? (
        <>
          {visibleItems.map((item) => (
            <div className="question-item" key={item.key}>
              <strong>{item.title}</strong>
              <span>{item.meta}</span>
            </div>
          ))}
          <PaginationControls
            label={title}
            page={page}
            pageSize={pageSize}
            total={items.length}
            onPageChange={onPageChange}
            onPageSizeChange={onPageSizeChange}
          />
        </>
      ) : (
        <div className="empty-state compact">{empty}</div>
      )}
    </div>
  );
}

function PaginationControls({
  label,
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
}: {
  label: string;
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
}) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize));
  const currentPage = Math.min(Math.max(1, page), pageCount);
  return (
    <div className="dashboard-pagination" aria-label={`${label}分页`}>
      <div className="dashboard-pagination-meta">
        <span>共 {total} 条{label}</span>
        {onPageSizeChange ? (
          <label className="dashboard-page-size">
            <span>每页</span>
            <select
              className="form-input"
              value={pageSize}
              onChange={(event) => {
                onPageSizeChange(Number(event.target.value));
                onPageChange(1);
              }}
            >
              {DASHBOARD_PAGE_SIZE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        ) : null}
      </div>
      <div className="dashboard-pagination-nav">
      <button
        type="button"
        className="btn-icon"
        onClick={() => onPageChange(Math.max(1, currentPage - 1))}
        disabled={currentPage <= 1}
        aria-label={`上一页${label}`}
      >
        <ArrowLeft size={15} />
      </button>
      <span>
        第 {currentPage} / {pageCount} 页 · 共 {total} 条
      </span>
      <button
        type="button"
        className="btn-icon"
        onClick={() => onPageChange(Math.min(pageCount, currentPage + 1))}
        disabled={currentPage >= pageCount}
        aria-label={`下一页${label}`}
      >
        <ArrowRight size={15} />
      </button>
      </div>
    </div>
  );
}

function pageSlice<T>(items: T[], page: number, pageSize: number) {
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));
  const currentPage = Math.min(Math.max(1, page), pageCount);
  const start = (currentPage - 1) * pageSize;
  return items.slice(start, start + pageSize);
}

function roleLabel(role: string) {
  return ({
    student: "学生",
    teacher: "教师",
    admin: "管理员",
    global: "全局",
    unknown: "未识别对象",
  } as Record<string, string>)[role] ?? role;
}

function questionSourceLabel(sourceType: string) {
  const normalized = sourceType.trim().toLowerCase();
  const labels: Record<string, string> = {
    student_memory: "学习记录",
    exam_paper: "试卷题目",
    teaching_task: "教学任务",
    uploaded_image: "图片上传",
    textbook: "教材",
    teacher_resource: "教师资料",
    knowledge_graph: "知识图谱",
  };
  return labels[normalized] ?? sourceType;
}

function questionStatusLabel(status: string) {
  const normalized = status.trim().toLowerCase();
  const labels: Record<string, string> = {
    active: "进行中",
    completed: "已完成",
    pending: "等待中",
    failed: "失败",
    archived: "已归档",
  };
  return labels[normalized] ?? status;
}

function subjectLabel(role: string, isGlobalView: boolean) {
  if (isGlobalView || role === "global") {
    return "范围";
  }
  return roleLabel(role);
}
