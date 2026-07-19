import { useEffect, useMemo, useState } from "react";
import { AlertCircle, ArrowLeft, ArrowRight, Database, Loader2, Search } from "lucide-react";
import { StudentDashboardResponse } from "../../shared/api/textbookApi";
import { boundedPercent, StatusLine } from "./panelShared";

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZE_OPTIONS = [10, 20, 50];

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
  const [weakPage, setWeakPage] = useState(1);
  const [questionPage, setQuestionPage] = useState(1);
  const [scorePage, setScorePage] = useState(1);
  const [progressPageSize, setProgressPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [weakPageSize, setWeakPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [questionPageSize, setQuestionPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [scorePageSize, setScorePageSize] = useState(DEFAULT_PAGE_SIZE);

  const needsTarget = viewerRole === "teacher" || viewerRole === "admin";
  const hasTarget = Boolean(targetStudentId?.trim());
  const selectedStudentId = dashboard?.studentId?.trim() ?? "";
  const hasDashboardStudent = Boolean(selectedStudentId);
  const latestScore = dashboard?.scoreTrend.at(-1);

  const progressItems = dashboard?.knowledgeProgress ?? [];
  const weakPointItems = dashboard?.weakPoints ?? [];
  const questionItems = dashboard?.recentQuestions ?? [];
  const scoreItems = dashboard?.scoreTrend ?? [];

  const visibleProgress = useMemo(
    () => pageSlice(progressItems, progressPage, progressPageSize),
    [progressItems, progressPage, progressPageSize],
  );
  const visibleWeakPoints = useMemo(
    () => pageSlice(weakPointItems, weakPage, weakPageSize),
    [weakPointItems, weakPage, weakPageSize],
  );
  const visibleQuestions = useMemo(
    () => pageSlice(questionItems, questionPage, questionPageSize),
    [questionItems, questionPage, questionPageSize],
  );
  const visibleScores = useMemo(
    () => pageSlice(scoreItems, scorePage, scorePageSize),
    [scoreItems, scorePage, scorePageSize],
  );

  useEffect(() => {
    setProgressPage(1);
    setWeakPage(1);
    setQuestionPage(1);
    setScorePage(1);
    setProgressPageSize(DEFAULT_PAGE_SIZE);
    setWeakPageSize(DEFAULT_PAGE_SIZE);
    setQuestionPageSize(DEFAULT_PAGE_SIZE);
    setScorePageSize(DEFAULT_PAGE_SIZE);
  }, [dashboard?.tenantId, dashboard?.studentId, dashboard?.viewerSubjectId]);

  return (
    <section className="student-dashboard">
      <div className="result-header">
        <div>
          <p className="eyebrow">学习画像</p>
          <h2>{needsTarget ? "查看学生画像" : "我的学习画像"}</h2>
        </div>
        <div className="result-actions">
          {dashboard ? <div className="strategy-pill">{viewerRoleLabel(dashboard.viewerRole)}查看</div> : null}
          {onRefresh ? (
            <button
              type="button"
              className="inline-action btn btn-ghost btn-sm"
              onClick={onRefresh}
              disabled={loading || (needsTarget && !hasTarget)}
            >
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
              placeholder="例如 student-001"
            />
          </label>
          <button type="button" className="btn btn-secondary" onClick={onLoad} disabled={loading || !hasTarget}>
            {loading ? <Loader2 className="spin" size={15} /> : <Search size={15} />}
            <span>查看学生</span>
          </button>
        </div>
      ) : null}

      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在读取学习画像" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {!dashboard && !loading && !error ? (
        <div className="empty-state compact">
          {needsTarget ? "请输入要查看的学生 ID，例如 student-001。" : "暂无学习画像。"}
        </div>
      ) : null}

      {dashboard && !hasDashboardStudent && !loading && !error ? (
        <div className="empty-state compact">当前查询对象不是学生，无法展示学习画像。</div>
      ) : null}

      {dashboard && hasDashboardStudent ? (
        <div className="dashboard-grid">
          <div className="profile-strip">
            <div>
              <span>学生</span>
              <strong>{selectedStudentId}</strong>
            </div>
            <div>
              <span>查看者</span>
              <strong>{dashboard.viewerSubjectId}</strong>
            </div>
            <div>
              <span>最近成绩</span>
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
            items={visibleWeakPoints.map((item) => ({
              key: item.knowledgePointId ?? item.knowledgePointName,
              title: item.knowledgePointName,
              meta: `等级 ${item.weaknessLevel}`,
            }))}
            total={weakPointItems.length}
            page={weakPage}
            pageSize={weakPageSize}
            onPageChange={setWeakPage}
            onPageSizeChange={setWeakPageSize}
          />

          <CompactList
            title="历史问题"
            empty="暂无历史问题。"
            items={visibleQuestions.map((item) => ({
              key: item.recordId,
              title: item.questionTitle,
              meta: `${questionSourceLabel(item.sourceType)} / ${questionStatusLabel(item.status)}`,
            }))}
            total={questionItems.length}
            page={questionPage}
            pageSize={questionPageSize}
            onPageChange={setQuestionPage}
            onPageSizeChange={setQuestionPageSize}
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
  total,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
}: {
  title: string;
  empty: string;
  items: Array<{ key: string; title: string; meta: string }>;
  total: number;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
}) {
  return (
    <div className="dashboard-column">
      <h3>{title}</h3>
      {items.length ? (
        <>
          {items.map((item) => (
            <div className="question-item" key={item.key}>
              <strong>{item.title}</strong>
              <span>{item.meta}</span>
            </div>
          ))}
          <PaginationControls
            label={title}
            page={page}
            pageSize={pageSize}
            total={total}
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
              {PAGE_SIZE_OPTIONS.map((option) => (
                <option key={option} value={option}>{option}</option>
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
          aria-label={`${label}上一页`}
        >
          <ArrowLeft size={15} />
        </button>
        <span>第 {currentPage} / {pageCount} 页</span>
        <button
          type="button"
          className="btn-icon"
          onClick={() => onPageChange(Math.min(pageCount, currentPage + 1))}
          disabled={currentPage >= pageCount}
          aria-label={`${label}下一页`}
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

function viewerRoleLabel(role: string) {
  const labels: Record<string, string> = {
    student: "学生",
    teacher: "教师",
    admin: "管理员",
    global: "全局",
    unselected: "未选择学生",
    unknown: "未识别对象",
  };
  return labels[role] ?? role;
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
