import { AlertCircle, BookOpen, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import katex from "katex";
import "katex/dist/katex.min.css";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  AgentRunExecuteResponse,
  AgentModelHealthResponse,
  AgentModelCatalogResponse,
  AgentRunPlanResponse,
  AgentTraceDiagnosticSummaryResponse,
  AgentTraceResponse,
  AgentTraceUsageSummaryResponse,
  KnowledgePointResponse,
  KnowledgeRelationResponse,
  McpConfigurationResponse,
  MultiAgentWritingResponse,
  MultiAgentWritingTraceResponse,
  QuestionBankItemResponse,
  RetrievalAuditDetail,
  StudentDashboardResponse,
  TeachingTaskResponse,
  TeacherFeishuDiscoveryCandidate,
  TeacherFeishuDiscoveryResponse,
  TeacherBlockQuestionImportResponse,
  TeacherResourceBlockSearchAuditEvent,
  TeacherResourceBlockSearchResponse,
  TeacherResourceDocumentResponse,
  TeacherSourceSyncCheckpointResponse,
  TeacherSourceSyncJobResponse,
  TextbookSearchResponse,
  TextbookSummary,
  VectorIndexRebuildResponse,
  LoginResponse,
  createTextbookApiClient,
} from "../shared/api/textbookApi";
import {
  MCP_PROMPT_OPTIONS,
  MCP_TOOL_OPTIONS,
  defaultMcpExposureSelection,
  toggleMcpExposureOption,
} from "./mcpExposureSelection";
import { AgentModelHealthPanel, AgentPlanPanel, AgentTracePanel } from "./components/AgentPanels";
import { AuditDetailPanel, EvidenceCard } from "./components/EvidencePanels";
import { KnowledgeQuestionBankPanel } from "./components/KnowledgeQuestionBankPanel";
import { McpConfigurationForm, McpConfigurationPanel } from "./components/McpPanels";
import { MultiAgentWritingPanel } from "./components/MultiAgentWritingPanel";
import {
  formatDateTime,
  Metric,
  PanelTitle,
  StatusLine,
  statusClass,
  statusTone,
} from "./components/panelShared";
import { StudentDashboardPanel } from "./components/StudentDashboardPanel";
import { SyncCheckpointView, TeacherResourcePanel } from "./components/TeacherResourcePanel";
import { TeachingTaskPanel } from "./components/TeachingTaskPanel";
import { KnowledgeWorkspace } from "./knowledge/KnowledgeWorkspace";

export { MultiAgentWritingPanel } from "./components/MultiAgentWritingPanel";
export { StudentDashboardPanel } from "./components/StudentDashboardPanel";
export { SyncCheckpointView } from "./components/TeacherResourcePanel";
export { AgentTracePanel } from "./components/AgentPanels";
export { statusClass, statusTone } from "./components/panelShared";

const DEFAULT_BACKEND_URL = import.meta.env.VITE_BACKEND_URL ?? "http://127.0.0.1:8080";
const TEACHING_TASK_STORAGE_KEY = "math-agent:last-teaching-task-id";
const MULTI_AGENT_WORKFLOW_STORAGE_KEY = "math-agent:last-multi-agent-workflow-id";

type MathSegment = {
  key: string;
  text: string;
  math: boolean;
  display: boolean;
};

export function splitFeishuMath(text: string): MathSegment[] {
  const segments: MathSegment[] = [];
  let index = 0;
  let key = 0;
  while (index < text.length) {
    const displayStart = text.indexOf("$$", index);
    const inlineStart = text.indexOf("$", index);
    const nextStart = displayStart >= 0 && (inlineStart < 0 || displayStart <= inlineStart) ? displayStart : inlineStart;
    if (nextStart < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(index), math: false, display: false });
      break;
    }
    if (nextStart > index) {
      segments.push({ key: `text-${key++}`, text: text.slice(index, nextStart), math: false, display: false });
    }
    const display = text.startsWith("$$", nextStart);
    const delimiter = display ? "$$" : "$";
    const contentStart = nextStart + delimiter.length;
    const end = text.indexOf(delimiter, contentStart);
    if (end < 0) {
      segments.push({ key: `text-${key++}`, text: text.slice(nextStart), math: false, display: false });
      break;
    }
    const expression = text.slice(contentStart, end).trim();
    if (expression) {
      segments.push({ key: `math-${key++}`, text: expression, math: true, display });
    }
    index = end + delimiter.length;
  }
  return segments.length ? segments : [{ key: "text-0", text, math: false, display: false }];
}

export function MathText({ text, block = false }: { text: string; block?: boolean }) {
  return (
    <>
      {splitFeishuMath(text).map((segment) => {
        if (!segment.math) {
          return <span key={segment.key}>{segment.text}</span>;
        }
        const html = katex.renderToString(segment.text, {
          displayMode: segment.display || block,
          throwOnError: false,
          strict: false,
          trust: false,
        });
        const className = segment.display || block ? "math-render display" : "math-render inline";
        return <span className={className} dangerouslySetInnerHTML={{ __html: html }} key={segment.key} />;
      })}
    </>
  );
}

/**
 * 教材检索控制台。当前阶段面向教师端/后台资料搜索，用来验证 BM25-first 检索证据是否可审计。
 */
export function App() {
  const api = useMemo(() => createTextbookApiClient(DEFAULT_BACKEND_URL), []);
  const [summary, setSummary] = useState<TextbookSummary | null>(null);
  const [summaryError, setSummaryError] = useState("");
  const [query, setQuery] = useState("");
  const [limit, setLimit] = useState(5);
  const [searchResult, setSearchResult] = useState<TextbookSearchResponse | null>(null);
  const [auditDetail, setAuditDetail] = useState<RetrievalAuditDetail | null>(null);
  const [searchError, setSearchError] = useState("");
  const [auditError, setAuditError] = useState("");
  const [teachingQuestion, setTeachingQuestion] = useState("");
  const [learningGoal, setLearningGoal] = useState("");
  const [teachingTask, setTeachingTask] = useState<TeachingTaskResponse | null>(null);
  const [studentDashboard, setStudentDashboard] = useState<StudentDashboardResponse | null>(null);
  const [teacherResources, setTeacherResources] = useState<TeacherResourceDocumentResponse[]>([]);
  const [teacherSyncJobs, setTeacherSyncJobs] = useState<Record<string, TeacherSourceSyncJobResponse[]>>({});
  const [teacherSyncCheckpoints, setTeacherSyncCheckpoints] =
    useState<Record<string, TeacherSourceSyncCheckpointResponse>>({});
  const [knowledgePoints, setKnowledgePoints] = useState<KnowledgePointResponse[]>([]);
  const [knowledgeRelations, setKnowledgeRelations] = useState<KnowledgeRelationResponse[]>([]);
  const [questionBankItems, setQuestionBankItems] = useState<QuestionBankItemResponse[]>([]);
  const [teacherResourceSearchQuery, setTeacherResourceSearchQuery] = useState("");
  const [teacherBlockSearchResult, setTeacherBlockSearchResult] = useState<TeacherResourceBlockSearchResponse | null>(null);
  const [teacherBlockSearchAudit, setTeacherBlockSearchAudit] = useState<TeacherResourceBlockSearchAuditEvent | null>(null);
  const [feishuDiscoveryQuery, setFeishuDiscoveryQuery] = useState("");
  const [feishuDiscoveryResult, setFeishuDiscoveryResult] = useState<TeacherFeishuDiscoveryResponse | null>(null);
  const [handoutPreviewLatex, setHandoutPreviewLatex] = useState("");
  const [handoutPreviewTaskId, setHandoutPreviewTaskId] = useState("");
  const [handoutVersion, setHandoutVersion] = useState<"teacher" | "student">("teacher");
  const [handoutAction, setHandoutAction] = useState("");
  const [handoutExportMessage, setHandoutExportMessage] = useState("");
  const [feedbackRating, setFeedbackRating] = useState(4);
  const [feedbackDecision, setFeedbackDecision] = useState("needs_revision");
  const [feedbackComment, setFeedbackComment] = useState("");
  const [submittingFeedback, setSubmittingFeedback] = useState(false);
  const [feedbackMessage, setFeedbackMessage] = useState("");
  const [agentPlan, setAgentPlan] = useState<AgentRunPlanResponse | null>(null);
  const [agentExecution, setAgentExecution] = useState<AgentRunExecuteResponse | null>(null);
  const [agentTraces, setAgentTraces] = useState<AgentTraceResponse[]>([]);
  const [agentUsageSummary, setAgentUsageSummary] = useState<AgentTraceUsageSummaryResponse | null>(null);
  const [agentDiagnosticSummary, setAgentDiagnosticSummary] = useState<AgentTraceDiagnosticSummaryResponse | null>(null);
  const [multiAgentWorkflow, setMultiAgentWorkflow] = useState<MultiAgentWritingResponse | null>(null);
  const [multiAgentWorkflowTraces, setMultiAgentWorkflowTraces] = useState<MultiAgentWritingTraceResponse | null>(null);
  const [planningAgent, setPlanningAgent] = useState(false);
  const [executingAgent, setExecutingAgent] = useState(false);
  const [startingMultiAgentWriting, setStartingMultiAgentWriting] = useState(false);
  const [pollingMultiAgentWriting, setPollingMultiAgentWriting] = useState(false);
  const [loadingAgentTraces, setLoadingAgentTraces] = useState(false);
  const [agentPlanError, setAgentPlanError] = useState("");
  const [agentExecutionError, setAgentExecutionError] = useState("");
  const [agentTraceError, setAgentTraceError] = useState("");
  const [agentModelCatalog, setAgentModelCatalog] = useState<AgentModelCatalogResponse | null>(null);
  const [agentModelCatalogError, setAgentModelCatalogError] = useState("");
  const [agentModelHealth, setAgentModelHealth] = useState<AgentModelHealthResponse | null>(null);
  const [agentModelHealthError, setAgentModelHealthError] = useState("");
  const [checkingAgentModelHealth, setCheckingAgentModelHealth] = useState(false);
  const [showAgentModelHealth, setShowAgentModelHealth] = useState(false);
  const [disablePrivateSearch, setDisablePrivateSearch] = useState(true);
  const [disableTextbookSearch, setDisableTextbookSearch] = useState(false);
  const [agentProvider, setAgentProvider] = useState("");
  const [agentModel, setAgentModel] = useState("");
  const [multiAgentWritingGoal, setMultiAgentWritingGoal] = useState("");
  const [multiAgentWritingQuestion, setMultiAgentWritingQuestion] = useState("");
  const [multiAgentWritingError, setMultiAgentWritingError] = useState("");
  const [mcpUrl, setMcpUrl] = useState(`${DEFAULT_BACKEND_URL}/api/mcp`);
  const [mcpSecretKey, setMcpSecretKey] = useState("");
  const [mcpSecretEnvName, setMcpSecretEnvName] = useState("MATH_AGENT_MCP_SECRET");
  const [mcpSelection, setMcpSelection] = useState(() => defaultMcpExposureSelection());
  const [mcpConfiguration, setMcpConfiguration] = useState<McpConfigurationResponse | null>(null);
  const [mcpBuilding, setMcpBuilding] = useState(false);
  const [mcpCopyMessage, setMcpCopyMessage] = useState("");
  const [mcpError, setMcpError] = useState("");
  const [teachingError, setTeachingError] = useState("");
  const [studentDashboardError, setStudentDashboardError] = useState("");
  const [teacherResourceError, setTeacherResourceError] = useState("");
  const [knowledgeBankError, setKnowledgeBankError] = useState("");
  const [authError, setAuthError] = useState("");
  const [resourceTitle, setResourceTitle] = useState("");
  const [resourceLocation, setResourceLocation] = useState("");
  const [resourceSourceType, setResourceSourceType] = useState("feishu");
  const [resourceScope, setResourceScope] = useState("TEACHER_PRIVATE");
  const [feishuExportFormat, setFeishuExportFormat] = useState<"md" | "docx" | "pdf">("md");
  const [knowledgePointName, setKnowledgePointName] = useState("");
  const [knowledgeChapterPath, setKnowledgeChapterPath] = useState("");
  const [questionTitle, setQuestionTitle] = useState("");
  const [questionText, setQuestionText] = useState("");
  const [questionBankQuery, setQuestionBankQuery] = useState("");
  const [batchFolderPath, setBatchFolderPath] = useState("");
  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [authSession, setAuthSession] = useState<LoginResponse | null>(() => readStoredAuthSession());
  const [authSessionChecked, setAuthSessionChecked] = useState(() => readStoredAuthSession() === null);
  const hasVerifiedSession = authSessionChecked && authSession !== null;
  const [loadingSummary, setLoadingSummary] = useState(false);
  const [searching, setSearching] = useState(false);
  const [loadingAudit, setLoadingAudit] = useState(false);
  const [submittingTeachingTask, setSubmittingTeachingTask] = useState(false);
  const [loadingTeachingTask, setLoadingTeachingTask] = useState(false);
  const [loadingStudentDashboard, setLoadingStudentDashboard] = useState(false);
  const [loadingTeacherResources, setLoadingTeacherResources] = useState(false);
  const [registeringResource, setRegisteringResource] = useState(false);
  const [searchingTeacherBlocks, setSearchingTeacherBlocks] = useState(false);
  const [discoveringFeishu, setDiscoveringFeishu] = useState(false);
  const [syncingResourceId, setSyncingResourceId] = useState("");
  const [importingResourceId, setImportingResourceId] = useState("");
  const [rebuildingResourceId, setRebuildingResourceId] = useState("");
  const [teacherResourceImportResult, setTeacherResourceImportResult] =
    useState<TeacherBlockQuestionImportResponse | null>(null);
  const [teacherResourceIndexRebuildResult, setTeacherResourceIndexRebuildResult] =
    useState<VectorIndexRebuildResponse | null>(null);
  const [loggingIn, setLoggingIn] = useState(false);
  const [savingKnowledgeBank, setSavingKnowledgeBank] = useState(false);

  useEffect(() => {
    setLoadingSummary(true);
    api
      .getSummary()
      .then(setSummary)
      .catch((error: Error) => setSummaryError(error.message))
      .finally(() => setLoadingSummary(false));
  }, [api]);

  useEffect(() => {
    if (!authSession) {
      setAuthSessionChecked(true);
      return;
    }
    let active = true;
    setAuthSessionChecked(false);
    api
      .currentSession()
      .then((session) => {
        if (!active) {
          return;
        }
        setAuthSession(session);
        setAuthError("");
        setAuthSessionChecked(true);
      })
      .catch(() => {
        if (!active) {
          return;
        }
        globalThis.localStorage?.removeItem("math-agent:auth-session");
        setAuthSession(null);
        setAuthError("登录态已失效，请重新登录。");
        setAuthSessionChecked(true);
      });
    return () => {
      active = false;
    };
  }, [api]);

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    loadStudentDashboard();
  }, [api, hasVerifiedSession]);

  function loadStudentDashboard() {
    setLoadingStudentDashboard(true);
    setStudentDashboardError("");
    api
      .getStudentDashboard()
      .then(setStudentDashboard)
      .catch((error: Error) => setStudentDashboardError(error.message))
      .finally(() => setLoadingStudentDashboard(false));
  }

  function handleRefreshStudentDashboard() {
    setLoadingStudentDashboard(true);
    setStudentDashboardError("");
    api
      .refreshStudentDashboard()
      .then(setStudentDashboard)
      .catch((error: Error) => setStudentDashboardError(error.message))
      .finally(() => setLoadingStudentDashboard(false));
  }

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    refreshTeacherResources();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    refreshKnowledgeQuestionBank();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    refreshAgentTraces();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    const workflowId = globalThis.localStorage?.getItem(MULTI_AGENT_WORKFLOW_STORAGE_KEY);
    if (workflowId) {
      refreshMultiAgentWritingWorkflow(workflowId);
    }
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!multiAgentWorkflow || multiAgentWorkflow.status !== "RUNNING") {
      return;
    }
    const timer = globalThis.setInterval(() => {
      refreshMultiAgentWritingWorkflow(multiAgentWorkflow.workflowId);
    }, 3000);
    return () => globalThis.clearInterval(timer);
  }, [api, multiAgentWorkflow?.workflowId, multiAgentWorkflow?.status]);

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    api
      .getAgentModelCatalog()
      .then((catalog) => {
        setAgentModelCatalog(catalog);
        setAgentProvider(catalog.defaultProviderName);
        setAgentModel(catalog.defaultModelCode);
        setAgentModelCatalogError("");
      })
      .catch((error: Error) => setAgentModelCatalogError(error.message));
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) {
      return;
    }
    refreshAgentModelHealth();
  }, [api, hasVerifiedSession]);

  function refreshTeacherResources() {
    setLoadingTeacherResources(true);
    api
      .listTeacherResources()
      .then((resources) => {
        setTeacherResources(resources);
        return loadTeacherSyncJobs(resources);
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setLoadingTeacherResources(false));
  }

  function loadTeacherSyncJobs(resources: TeacherResourceDocumentResponse[]) {
    return Promise.all(
      resources.map((resource) =>
        api
          .listTeacherResourceSyncJobs(resource.documentId)
          .then((jobs) => [resource.documentId, jobs] as const),
      ),
    ).then((entries) => {
      const jobsByDocument = Object.fromEntries(entries);
      setTeacherSyncJobs(jobsByDocument);
      return loadTeacherSyncCheckpoints(jobsByDocument);
    });
  }

  function loadTeacherSyncCheckpoints(jobsByDocument: Record<string, TeacherSourceSyncJobResponse[]>) {
    const requests = Object.entries(jobsByDocument).flatMap(([documentId, jobs]) =>
      jobs.map((job) =>
        api
          .getTeacherResourceSyncCheckpoint(documentId, job.jobId)
          .then((checkpoint) => [job.jobId, checkpoint] as const)
          .catch(() => [job.jobId, null] as const),
      ),
    );
    return Promise.all(requests).then((entries) => {
      const checkpoints: Record<string, TeacherSourceSyncCheckpointResponse> = {};
      for (const [jobId, checkpoint] of entries) {
        if (checkpoint) {
          checkpoints[jobId] = checkpoint;
        }
      }
      setTeacherSyncCheckpoints(checkpoints);
    });
  }

  function refreshAgentTraces() {
    setLoadingAgentTraces(true);
    setAgentTraceError("");
    api
      .listAgentTraces({ limit: 10 })
      .then((traces) => {
        setAgentTraces(traces);
        return Promise.all([
          api.getAgentTraceUsageSummary({ limit: 100 }),
          api.getAgentTraceDiagnosticSummary({ limit: 100 }),
        ]).then(([usageSummary, diagnosticSummary]) => {
          setAgentUsageSummary(usageSummary);
          setAgentDiagnosticSummary(diagnosticSummary);
        });
      })
      .catch((error: Error) => setAgentTraceError(error.message))
      .finally(() => setLoadingAgentTraces(false));
  }

  function refreshKnowledgeQuestionBank() {
    setKnowledgeBankError("");
    Promise.all([
      api.listKnowledgePoints(),
      api.listKnowledgeRelations(),
      questionBankQuery.trim() ? api.searchQuestionBankItems(questionBankQuery.trim(), 8) : Promise.resolve([]),
    ])
      .then(([points, relations, questions]) => {
        setKnowledgePoints(points);
        setKnowledgeRelations(relations);
        setQuestionBankItems(questions);
      })
      .catch((error: Error) => setKnowledgeBankError(error.message));
  }

  useEffect(() => {
    const taskId = window.localStorage.getItem(TEACHING_TASK_STORAGE_KEY);
    if (!taskId) {
      return;
    }
    setLoadingTeachingTask(true);
    api
      .getTeachingTask(taskId)
      .then(setTeachingTask)
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setLoadingTeachingTask(false));
  }, [api]);

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim()) {
      setSearchError("请输入检索词。");
      return;
    }
    setSearching(true);
    setSearchError("");
    setAuditError("");
    setAuditDetail(null);
    api
      .search(query.trim(), limit)
      .then((result) => {
        setSearchResult(result);
        setLoadingAudit(true);
        return api
          .getAudit(result.queryId)
          .then(setAuditDetail)
          .catch((error: Error) => setAuditError(error.message))
          .finally(() => setLoadingAudit(false));
      })
      .catch((error: Error) => setSearchError(error.message))
      .finally(() => setSearching(false));
  }

  function handleTeachingTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teachingQuestion.trim() || !learningGoal.trim()) {
      setTeachingError("请输入题目和学习目标。");
      return;
    }
    const clientRequestId = `local-${Date.now()}`;
    setSubmittingTeachingTask(true);
    setTeachingError("");
    api
      .submitTeachingTask({
        clientRequestId,
        questionText: teachingQuestion.trim(),
        learningGoal: learningGoal.trim(),
        evidenceLimit: limit,
      })
      .then((task) => {
        window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
        setTeachingTask(task);
        setHandoutPreviewLatex("");
        setHandoutPreviewTaskId("");
        setHandoutExportMessage("");
        setFeedbackMessage("");
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setSubmittingTeachingTask(false));
  }

  function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!loginUsername.trim() || !loginPassword.trim()) {
      setAuthError("请输入用户名和密码。");
      return;
    }
    setLoggingIn(true);
    setAuthError("");
    api
      .login({ username: loginUsername.trim(), password: loginPassword })
      .then((session) => {
        setAuthSession(session);
        setTeachingError("");
        setStudentDashboardError("");
        setTeacherResourceError("");
        setKnowledgeBankError("");
        setAgentPlanError("");
        setAgentExecutionError("");
        setAgentTraceError("");
        setAgentModelCatalogError("");
        setAgentModelHealthError("");
        setMcpError("");
        loadStudentDashboard();
        refreshTeacherResources();
        refreshKnowledgeQuestionBank();
        refreshAgentTraces();
        api.getAgentModelCatalog().then((catalog) => {
          setAgentModelCatalog(catalog);
          setAgentProvider(catalog.defaultProviderName);
          setAgentModel(catalog.defaultModelCode);
        }).catch((error: Error) => setAgentModelCatalogError(error.message));
        refreshAgentModelHealth();
      })
      .catch((error: Error) => setAuthError(error.message))
      .finally(() => setLoggingIn(false));
  }

  function handleRegisterResource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!resourceTitle.trim() || !resourceLocation.trim()) {
      setTeacherResourceError("请输入资料标题和本地路径或飞书 URL。");
      return;
    }
    setRegisteringResource(true);
    setTeacherResourceError("");
    api
      .registerTeacherResource({
        sourceType: resourceSourceType,
        title: resourceTitle.trim(),
        originalUrl: resourceSourceType === "feishu" ? resourceLocation.trim() : undefined,
        localPath: resourceSourceType === "local_path" ? resourceLocation.trim() : undefined,
        permissionScope: resourceScope,
        feishuExportFormat: resourceSourceType === "feishu" ? feishuExportFormat : undefined,
      })
      .then((resource) => {
        setTeacherResources((current) => [resource, ...current]);
        setTeacherSyncJobs((current) => ({ ...current, [resource.documentId]: [] }));
        setResourceTitle("");
        setResourceLocation("");
        setFeishuDiscoveryResult(null);
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setRegisteringResource(false));
  }

  function handleArchiveResource(documentId: string) {
    setTeacherResourceError("");
    api
      .archiveTeacherResource(documentId)
      .then(() => setTeacherResources((current) => current.filter((resource) => resource.documentId !== documentId)))
      .catch((error: Error) => setTeacherResourceError(error.message));
  }

  function handleCreateKnowledgePoint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!knowledgePointName.trim()) {
      setKnowledgeBankError("Knowledge point name is required.");
      return;
    }
    setSavingKnowledgeBank(true);
    setKnowledgeBankError("");
    api
      .createKnowledgePoint({
        knowledgePointName: knowledgePointName.trim(),
        chapterPath: knowledgeChapterPath.trim(),
        permissionScope: resourceScope,
        sourceSummary: "前端管理台创建",
      })
      .then((point) => setKnowledgePoints((current) => [point, ...current]))
      .catch((error: Error) => setKnowledgeBankError(error.message))
      .finally(() => setSavingKnowledgeBank(false));
  }

  function handleCreateQuestionBankItem(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!questionTitle.trim() || !questionText.trim()) {
      setKnowledgeBankError("Question title and text are required.");
      return;
    }
    setSavingKnowledgeBank(true);
    setKnowledgeBankError("");
    const firstPointId = knowledgePoints[0]?.knowledgePointId;
    api
      .createQuestionBankItem({
        questionTitle: questionTitle.trim(),
        questionText: questionText.trim(),
        answerJson: "{}",
        difficulty: "medium",
        permissionScope: resourceScope,
        knowledgePointIds: firstPointId ? [firstPointId] : [],
      })
      .then((question) => setQuestionBankItems((current) => [question, ...current]))
      .catch((error: Error) => setKnowledgeBankError(error.message))
      .finally(() => setSavingKnowledgeBank(false));
  }

  function handleSearchQuestionBank(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setKnowledgeBankError("");
    if (!questionBankQuery.trim()) {
      setQuestionBankItems([]);
      return;
    }
    api
      .searchQuestionBankItems(questionBankQuery.trim(), 8)
      .then(setQuestionBankItems)
      .catch((error: Error) => setKnowledgeBankError(error.message));
  }

  function handleCreateResourceSyncJob(documentId: string) {
    setSyncingResourceId(documentId);
    setTeacherResourceError("");
    api
      .createTeacherResourceSyncJob(documentId)
      .then((job) => {
        setTeacherSyncJobs((current) => ({
          ...current,
          [documentId]: [job, ...(current[documentId] ?? [])],
        }));
        setTeacherSyncCheckpoints((current) => {
          const next = { ...current };
          delete next[job.jobId];
          return next;
        });
        return api.executeTeacherResourceSyncJob(documentId, job.jobId);
      })
      .then((executedJob) => {
        setTeacherSyncJobs((current) => ({
          ...current,
          [documentId]: [
            executedJob,
            ...(current[documentId] ?? []).filter((job) => job.jobId !== executedJob.jobId),
          ],
        }));
        return api
          .getTeacherResourceSyncCheckpoint(documentId, executedJob.jobId)
          .then((checkpoint) => {
            if (checkpoint) {
              setTeacherSyncCheckpoints((current) => ({ ...current, [executedJob.jobId]: checkpoint }));
            }
          })
          .catch(() => undefined);
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setSyncingResourceId(""));
  }

  function handleResumeResourceSyncJob(documentId: string, jobId: string) {
    setSyncingResourceId(documentId);
    setTeacherResourceError("");
    api
      .resumeTeacherResourceSyncJob(documentId, jobId)
      .then((resumedJob) => {
        setTeacherSyncJobs((current) => ({
          ...current,
          [documentId]: [
            resumedJob,
            ...(current[documentId] ?? []).filter((job) => job.jobId !== resumedJob.jobId),
          ],
        }));
        return api
          .getTeacherResourceSyncCheckpoint(documentId, resumedJob.jobId)
          .then((checkpoint) => {
            if (checkpoint) {
              setTeacherSyncCheckpoints((current) => ({ ...current, [resumedJob.jobId]: checkpoint }));
            }
          })
          .catch(() => undefined);
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setSyncingResourceId(""));
  }

  function handleImportTeacherResourceQuestions(documentId: string) {
    setImportingResourceId(documentId);
    setTeacherResourceImportResult(null);
    setTeacherResourceError("");
    api
      .importTeacherResourceQuestions(documentId)
      .then((result) => {
        setTeacherResourceImportResult(result);
        setQuestionBankItems((current) => [...result.importedQuestions, ...current]);
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setImportingResourceId(""));
  }

  function handleRebuildTeacherResourceIndex(documentId: string) {
    setRebuildingResourceId(documentId);
    setTeacherResourceIndexRebuildResult(null);
    setTeacherResourceError("");
    api
      .rebuildTeacherResourceVectorIndex(documentId)
      .then((result) => {
        setTeacherResourceIndexRebuildResult(result);
        return refreshTeacherResources();
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setRebuildingResourceId(""));
  }

  function handleTeacherBlockSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teacherResourceSearchQuery.trim()) {
      setTeacherResourceError("Enter a resource keyword to search.");
      return;
    }
    setSearchingTeacherBlocks(true);
    setTeacherResourceError("");
    setTeacherBlockSearchAudit(null);
    api
      .searchTeacherResourceBlocks(teacherResourceSearchQuery.trim(), 8)
      .then((result) => {
        setTeacherBlockSearchResult(result);
        return api
          .getTeacherResourceBlockSearchAudit(result.queryId)
          .then(setTeacherBlockSearchAudit)
          .catch((error: Error) => setTeacherResourceError(error.message));
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setSearchingTeacherBlocks(false));
  }

  function handleDiscoverFeishu(mode: "list" | "search") {
    if (mode === "search" && !feishuDiscoveryQuery.trim()) {
      setTeacherResourceError("Enter a Feishu search keyword.");
      return;
    }
    const rootUrl = resourceLocation.trim();
    if (!rootUrl) {
      setTeacherResourceError("Enter a Feishu folder or document URL first.");
      return;
    }
    setDiscoveringFeishu(true);
    setTeacherResourceError("");
    api
      .discoverFeishuResources({
        mode,
        query: mode === "search" ? feishuDiscoveryQuery.trim() : "",
        rootUrl,
        listDepth: 1,
        maxDepth: 5,
      })
      .then(setFeishuDiscoveryResult)
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setDiscoveringFeishu(false));
  }

  function handleUseFeishuCandidate(candidate: TeacherFeishuDiscoveryCandidate) {
    setResourceSourceType("feishu");
    setResourceLocation(candidate.url);
    setResourceTitle(candidate.name || candidate.path || "飞书资源");
  }

  function handlePreviewLatex() {
    if (!teachingTask) {
      return;
    }
    setHandoutAction("preview");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .previewTeachingTaskLatex(teachingTask.taskId, handoutVersion)
      .then((latex) => {
        setHandoutPreviewLatex(latex);
        setHandoutPreviewTaskId(`${teachingTask.taskId}:${handoutVersion}`);
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleExportLatex() {
    if (!teachingTask) {
      return;
    }
    setHandoutAction("latex");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskLatex(teachingTask.taskId, handoutVersion)
      .then((latex) => {
        downloadText(`${teachingTask.taskId}-${handoutVersion}.tex`, latex, "application/x-tex;charset=utf-8");
        setHandoutExportMessage("LaTeX source exported.");
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleExportPdf() {
    if (!teachingTask) {
      return;
    }
    setHandoutAction("pdf");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskPdf(teachingTask.taskId, handoutVersion)
      .then((pdf) => {
        downloadBytes(`${teachingTask.taskId}-${handoutVersion}.pdf`, pdf, "application/pdf");
        setHandoutExportMessage("PDF handout exported.");
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleExportBatchZip() {
    if (!teachingTask) {
      return;
    }
    setHandoutAction("zip");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .createTeachingHandoutBatchZip({
        taskIds: [teachingTask.taskId],
        folderIds: [`task-${teachingTask.taskId}`],
        folderPaths: [batchFolderPath.trim() || `handouts/${teachingTask.taskId}`],
      })
      .then((batch) =>
        api.downloadTeachingHandoutBatchZip(batch.batchId).then((zip) => {
          downloadBytes(`${batch.batchId}.zip`, zip, "application/zip");
          setHandoutExportMessage(`ZIP exported. Temporary package expires at ${formatDateTime(batch.expiresAt)}.`);
        }),
      )
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleSubmitFeedback(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teachingTask) {
      return;
    }
    setSubmittingFeedback(true);
    setTeachingError("");
    setFeedbackMessage("");
    api
      .submitTeachingHumanFeedback(teachingTask.taskId, {
        rating: feedbackRating,
        decision: feedbackDecision,
        comment: feedbackComment.trim(),
      })
      .then((feedback) => {
        setFeedbackMessage(`Feedback recorded: ${feedback.decision} / ${feedback.rating}`);
        setFeedbackComment("");
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setSubmittingFeedback(false));
  }

  function handlePlanAgent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const selectedModels = agentModelsForProvider(agentModelCatalog, agentProvider);
    if (!selectedModels.some((model) => model.modelCode === agentModel)) {
      setAgentPlanError("Backend model catalog is not loaded or the selected model is unavailable.");
      return;
    }
    const disabledToolScopes = [
      disablePrivateSearch ? "tool:search:private" : "",
      disableTextbookSearch ? "tool:search:textbook" : "",
    ].filter(Boolean);
    setPlanningAgent(true);
    setAgentPlanError("");
    setAgentExecutionError("");
    setAgentExecution(null);
    api
      .planAgentRun({
        agentCode: "CoursewareAgent",
        taskType: "courseware_generation",
        userVipLevel: "teacher",
        estimatedInputTokens: 3200,
        estimatedOutputTokens: 1800,
        hasImage: false,
        hasFormula: true,
        difficulty: "medium",
        latencyRequirement: "normal",
        costBudget: 2.5,
        previousFailureCount: 0,
        requiredJsonSchema: true,
        requestedToolScopes: ["tool:courseware:generate", "tool:search:private", "tool:search:textbook"],
        disabledToolScopes,
        requestedDataScopes: ["TEACHER_PRIVATE", "CLASS_AUTHORIZED", "PUBLIC_TEXTBOOK"],
        highValueOperation: true,
        preferredProviderName: agentProvider,
        preferredModelCode: agentModel,
      })
      .then(setAgentPlan)
      .catch((error: Error) => setAgentPlanError(error.message))
      .finally(() => setPlanningAgent(false));
  }

  function handleExecuteAgentRun() {
    if (!agentPlan) {
      return;
    }
    setExecutingAgent(true);
    setAgentExecutionError("");
    api
      .executeAgentRun({
        plan: agentPlan,
        userInputSummary: [learningGoal.trim(), teachingQuestion.trim()].filter(Boolean).join(" / "),
        evidenceRefs: teachingTask ? [`teaching-task:${teachingTask.taskId}`] : [],
      })
      .then((execution) => {
        setAgentExecution(execution);
        refreshAgentTraces();
      })
      .catch((error: Error) => setAgentExecutionError(error.message))
      .finally(() => setExecutingAgent(false));
  }

  function handleStartMultiAgentWriting(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!multiAgentWritingGoal.trim() || !multiAgentWritingQuestion.trim()) {
      setMultiAgentWritingError("请输入写作目标和题目。");
      return;
    }
    const selectedModels = agentModelsForProvider(agentModelCatalog, agentProvider);
    if (!selectedModels.some((model) => model.modelCode === agentModel)) {
      setMultiAgentWritingError("Backend model catalog is not loaded or the selected model is unavailable.");
      return;
    }
    setStartingMultiAgentWriting(true);
    setMultiAgentWritingError("");
    setMultiAgentWorkflowTraces(null);
    api
      .startAsyncMultiAgentWriting({
        writingGoal: multiAgentWritingGoal,
        questionText: multiAgentWritingQuestion,
        evidenceRefs: multiAgentEvidenceRefs(searchResult),
        preferredProviderName: agentProvider,
        preferredModelCode: agentModel,
      })
      .then((workflow) => {
        setMultiAgentWorkflow(workflow);
        globalThis.localStorage?.setItem(MULTI_AGENT_WORKFLOW_STORAGE_KEY, workflow.workflowId);
      })
      .catch((error: Error) => setMultiAgentWritingError(error.message))
      .finally(() => setStartingMultiAgentWriting(false));
  }

  function refreshMultiAgentWritingWorkflow(workflowId?: string) {
    const targetWorkflowId = workflowId || multiAgentWorkflow?.workflowId;
    if (!targetWorkflowId) {
      return;
    }
    setPollingMultiAgentWriting(true);
    setMultiAgentWritingError("");
    api
      .getMultiAgentWritingWorkflow(targetWorkflowId)
      .then((workflow) => {
        setMultiAgentWorkflow(workflow);
        if (workflow.status === "COMPLETED" || workflow.status === "FAILED") {
          return api
            .getMultiAgentWritingTraces(workflow.workflowId)
            .then(setMultiAgentWorkflowTraces)
            .catch(() => undefined)
            .then(() => refreshAgentTraces());
        }
        return undefined;
      })
      .catch((error: Error) => setMultiAgentWritingError(error.message))
      .finally(() => setPollingMultiAgentWriting(false));
  }

  function handleBuildMcpConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMcpBuilding(true);
    setMcpError("");
    setMcpCopyMessage("");
    api
      .buildMcpConfiguration({
        url: mcpUrl.trim(),
        secretKey: mcpSecretKey.trim(),
        secretEnvName: mcpSecretEnvName.trim(),
        enabledToolNames: mcpSelection.tools,
        enabledPromptNames: mcpSelection.prompts,
      })
      .then(setMcpConfiguration)
      .catch((error: Error) => setMcpError(error.message))
      .finally(() => setMcpBuilding(false));
  }

  function handleMcpToolToggle(option: string, checked: boolean) {
    setMcpSelection((current) => ({
      ...current,
      tools: toggleMcpExposureOption(current.tools, option, checked, MCP_TOOL_OPTIONS),
    }));
  }

  function handleAgentProviderChange(provider: string) {
    const models = agentModelsForProvider(agentModelCatalog, provider);
    setAgentProvider(provider);
    setAgentModel(models[0]?.modelCode ?? "");
  }

  function refreshAgentModelHealth() {
    setCheckingAgentModelHealth(true);
    setAgentModelHealthError("");
    api
      .getAgentModelHealth()
      .then(setAgentModelHealth)
      .catch((error: Error) => setAgentModelHealthError(error.message))
      .finally(() => setCheckingAgentModelHealth(false));
  }

  function handleMcpPromptToggle(option: string, checked: boolean) {
    setMcpSelection((current) => ({
      ...current,
      prompts: toggleMcpExposureOption(current.prompts, option, checked, MCP_PROMPT_OPTIONS),
    }));
  }

  function handleCopyMcpConfiguration() {
    if (!mcpConfiguration?.configJson) {
      return;
    }
    if (!navigator.clipboard?.writeText) {
      setMcpCopyMessage("当前浏览器不支持剪贴板写入。");
      return;
    }
    navigator.clipboard
      .writeText(mcpConfiguration.configJson)
      .then(() => setMcpCopyMessage("MCP JSON 已复制。"))
      .catch((error: Error) => setMcpCopyMessage(error.message));
  }

  return (
    <main className="app-shell">
      <section className="topbar">
        <div>
          <p className="eyebrow">Math Agent RAG</p>
          <h1>教材证据检索控制台</h1>
        </div>
        <div className="backend-pill">
          <Database size={16} />
          <span>{DEFAULT_BACKEND_URL}</span>
        </div>
      </section>

      <section className="workspace">
        <aside className="side-panel">
          <PanelTitle icon={<ShieldCheck size={18} />} title="会话身份" />
          <form className="search-form auth-form" onSubmit={handleLogin}>
            <label>
              <span>账号</span>
              <select value={loginUsername} onChange={(event) => setLoginUsername(event.target.value)}>
                <option value="">选择账号</option>
                <option value="student">student</option>
                <option value="teacher">teacher</option>
                <option value="admin">admin</option>
              </select>
            </label>
            <label>
              <span>密码</span>
              <input
                type="password"
                value={loginPassword}
                onChange={(event) => setLoginPassword(event.target.value)}
                placeholder="输入真实密码"
              />
            </label>
            <button type="submit" disabled={loggingIn}>
              {loggingIn ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
              <span>登录</span>
            </button>
          </form>
          {authError ? <StatusLine icon={<AlertCircle size={16} />} text={authError} tone="danger" /> : null}
          {authSession ? (
            <div className="auth-session">
              <span>{authSession.role}</span>
              <strong>{authSession.userId}</strong>
            </div>
          ) : null}
          <div className="divider" />

          <PanelTitle icon={<BookOpen size={18} />} title="教材资源" />
          {loadingSummary ? (
            <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取教材目录中" />
          ) : summaryError ? (
            <StatusLine icon={<AlertCircle size={16} />} text={summaryError} tone="danger" />
          ) : summary ? (
            <div className="metric-grid">
              <Metric label="教材" value={summary.bookCount} />
              <Metric label="Chunks" value={summary.totalChunkCount} />
              <Metric label="PDF 页" value={summary.totalPageCount} />
            </div>
          ) : null}

          <div className="divider" />

          <PanelTitle icon={<Search size={18} />} title="检索参数" />
          <form className="search-form" onSubmit={handleSearch}>
            <label>
              <span>Query</span>
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="输入教材术语、题干片段或公式关键词"
              />
            </label>
            <label>
              <span>Top K</span>
              <input
                type="number"
                min={1}
                max={20}
                value={limit}
                onChange={(event) => setLimit(Number(event.target.value))}
              />
            </label>
            <button type="submit" disabled={searching}>
              {searching ? <Loader2 className="spin" size={17} /> : <Search size={17} />}
              <span>检索</span>
            </button>
          </form>

          <div className="divider" />

          <PanelTitle icon={<BookOpen size={18} />} title="教学任务编排" />
          <form className="search-form" onSubmit={handleTeachingTask}>
            <label>
              <span>想学什么</span>
              <input
                value={learningGoal}
                onChange={(event) => setLearningGoal(event.target.value)}
                placeholder="例如：掌握函数零点个数判断"
              />
            </label>
            <label>
              <span>题目/问题</span>
              <input
                value={teachingQuestion}
                onChange={(event) => setTeachingQuestion(event.target.value)}
                placeholder="输入真实题目或课堂问题"
              />
            </label>
            <button type="submit" disabled={submittingTeachingTask}>
              {submittingTeachingTask ? <Loader2 className="spin" size={17} /> : <BookOpen size={17} />}
              <span>生成讲义任务</span>
            </button>
          </form>

          <div className="divider" />

          <PanelTitle icon={<ShieldCheck size={18} />} title="Agent tool policy" />
          {agentModelCatalogError ? (
            <StatusLine icon={<AlertCircle size={16} />} text={agentModelCatalogError} tone="danger" />
          ) : null}
          <AgentModelHealthPanel
            health={agentModelHealth}
            error={agentModelHealthError}
            loading={checkingAgentModelHealth}
            expanded={showAgentModelHealth}
            onToggle={() => setShowAgentModelHealth((current) => !current)}
            onRefresh={refreshAgentModelHealth}
          />
          <form className="search-form agent-tool-form" onSubmit={handlePlanAgent}>
            <label>
              <span>Provider</span>
              <select value={agentProvider} onChange={(event) => handleAgentProviderChange(event.target.value)}>
                {agentProviders(agentModelCatalog).map((provider) => (
                  <option key={provider} value={provider}>
                    {provider}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>Model</span>
              <select value={agentModel} onChange={(event) => setAgentModel(event.target.value)}>
                {agentModelsForProvider(agentModelCatalog, agentProvider).map((model) => (
                  <option key={model.modelCode} value={model.modelCode}>
                    {model.modelCode}
                  </option>
                ))}
              </select>
            </label>
            <label className="toggle-row">
              <input
                type="checkbox"
                checked={disablePrivateSearch}
                onChange={(event) => setDisablePrivateSearch(event.target.checked)}
              />
              <span>Disable private RAG search</span>
            </label>
            <label className="toggle-row">
              <input
                type="checkbox"
                checked={disableTextbookSearch}
                onChange={(event) => setDisableTextbookSearch(event.target.checked)}
              />
              <span>Disable textbook search</span>
            </label>
            <button type="submit" disabled={planningAgent || !agentProvider || !agentModel}>
              {planningAgent ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
              <span>Plan tools</span>
            </button>
          </form>
          {agentPlanError ? <StatusLine icon={<AlertCircle size={16} />} text={agentPlanError} tone="danger" /> : null}

          <div className="divider" />

          <McpConfigurationForm
            url={mcpUrl}
            secretKey={mcpSecretKey}
            secretEnvName={mcpSecretEnvName}
            selectedTools={mcpSelection.tools}
            selectedPrompts={mcpSelection.prompts}
            building={mcpBuilding}
            error={mcpError}
            onUrlChange={setMcpUrl}
            onSecretKeyChange={setMcpSecretKey}
            onSecretEnvNameChange={setMcpSecretEnvName}
            onToolToggle={handleMcpToolToggle}
            onPromptToggle={handleMcpPromptToggle}
            onSubmit={handleBuildMcpConfiguration}
          />

          <div className="divider" />

          <TeacherResourcePanel
            resources={teacherResources}
            title={resourceTitle}
            location={resourceLocation}
            sourceType={resourceSourceType}
            scope={resourceScope}
            feishuExportFormat={feishuExportFormat}
            loading={loadingTeacherResources}
            registering={registeringResource}
            searchingBlocks={searchingTeacherBlocks}
            syncingResourceId={syncingResourceId}
            importingResourceId={importingResourceId}
            rebuildingResourceId={rebuildingResourceId}
            importResult={teacherResourceImportResult}
            indexRebuildResult={teacherResourceIndexRebuildResult}
            syncJobsByDocument={teacherSyncJobs}
            syncCheckpointsByJob={teacherSyncCheckpoints}
            blockSearchQuery={teacherResourceSearchQuery}
            blockSearchResult={teacherBlockSearchResult}
            blockSearchAudit={teacherBlockSearchAudit}
            feishuDiscoveryQuery={feishuDiscoveryQuery}
            feishuDiscoveryResult={feishuDiscoveryResult}
            discoveringFeishu={discoveringFeishu}
            error={teacherResourceError}
            onTitleChange={setResourceTitle}
            onLocationChange={setResourceLocation}
            onSourceTypeChange={setResourceSourceType}
            onScopeChange={setResourceScope}
            onFeishuExportFormatChange={setFeishuExportFormat}
            onBlockSearchQueryChange={setTeacherResourceSearchQuery}
            onBlockSearch={handleTeacherBlockSearch}
            onFeishuDiscoveryQueryChange={setFeishuDiscoveryQuery}
            onDiscoverFeishu={handleDiscoverFeishu}
            onUseFeishuCandidate={handleUseFeishuCandidate}
            onRegister={handleRegisterResource}
            onArchive={handleArchiveResource}
            onSync={handleCreateResourceSyncJob}
            onResume={handleResumeResourceSyncJob}
            onImportQuestions={handleImportTeacherResourceQuestions}
            onRebuildIndex={handleRebuildTeacherResourceIndex}
          />
        </aside>

        <section className="result-panel">
          <StudentDashboardPanel
            dashboard={studentDashboard}
            loading={loadingStudentDashboard}
            error={studentDashboardError}
            onRefresh={handleRefreshStudentDashboard}
          />

          {hasVerifiedSession ? (
            <KnowledgeWorkspace key={authSession.tokenValue} api={api} />
          ) : (
            <section className="knowledge-workspace">
              <StatusLine
                icon={authSessionChecked ? <ShieldCheck size={16} /> : <Loader2 className="spin" size={16} />}
                text={authSessionChecked ? "登录后加载知识库工作台。" : "正在检查后端会话"}
              />
            </section>
          )}

          <KnowledgeQuestionBankPanel
            knowledgePoints={knowledgePoints}
            knowledgeRelations={knowledgeRelations}
            questions={questionBankItems}
            knowledgePointName={knowledgePointName}
            chapterPath={knowledgeChapterPath}
            questionTitle={questionTitle}
            questionText={questionText}
            query={questionBankQuery}
            saving={savingKnowledgeBank}
            error={knowledgeBankError}
            onKnowledgePointNameChange={setKnowledgePointName}
            onChapterPathChange={setKnowledgeChapterPath}
            onQuestionTitleChange={setQuestionTitle}
            onQuestionTextChange={setQuestionText}
            onQueryChange={setQuestionBankQuery}
            onCreateKnowledgePoint={handleCreateKnowledgePoint}
            onCreateQuestion={handleCreateQuestionBankItem}
            onSearchQuestions={handleSearchQuestionBank}
          />

          <AgentPlanPanel
            plan={agentPlan}
            execution={agentExecution}
            loading={planningAgent}
            executing={executingAgent}
            error={agentPlanError || agentExecutionError}
            onExecute={handleExecuteAgentRun}
          />

          <MultiAgentWritingPanel
            workflow={multiAgentWorkflow}
            traces={multiAgentWorkflowTraces}
            writingGoal={multiAgentWritingGoal}
            questionText={multiAgentWritingQuestion}
            providerName={agentProvider}
            modelCode={agentModel}
            modelReady={agentModelsForProvider(agentModelCatalog, agentProvider)
              .some((model) => model.modelCode === agentModel)}
            starting={startingMultiAgentWriting}
            polling={pollingMultiAgentWriting}
            error={multiAgentWritingError}
            onWritingGoalChange={setMultiAgentWritingGoal}
            onQuestionTextChange={setMultiAgentWritingQuestion}
            onSubmit={handleStartMultiAgentWriting}
            onRefresh={() => refreshMultiAgentWritingWorkflow()}
          />

            <AgentTracePanel
              traces={agentTraces}
              usageSummary={agentUsageSummary}
              diagnosticSummary={agentDiagnosticSummary}
              loading={loadingAgentTraces}
              error={agentTraceError}
              onRefresh={refreshAgentTraces}
          />

          <McpConfigurationPanel
            configuration={mcpConfiguration}
            copyMessage={mcpCopyMessage}
            onCopy={handleCopyMcpConfiguration}
          />

          <div className="result-header">
            <div>
              <p className="eyebrow">Evidence</p>
              <h2>命中证据</h2>
            </div>
            {searchResult ? (
              <div className="strategy-pill">
                <ShieldCheck size={16} />
                <span>{searchResult.retrievalStrategy}</span>
              </div>
            ) : null}
          </div>

          {searchError ? <StatusLine icon={<AlertCircle size={16} />} text={searchError} tone="danger" /> : null}

          {!searchResult && !searchError ? (
            <div className="empty-state">输入教材术语、定义、题干片段或公式关键词后开始检索。</div>
          ) : null}

          {searchResult ? (
            <>
              <div className="audit-row">
                <span>审计追踪号</span>
                <strong>{searchResult.queryId}</strong>
              </div>
              {loadingAudit ? (
                <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取审计详情中" />
              ) : auditError ? (
                <StatusLine icon={<AlertCircle size={16} />} text={auditError} tone="danger" />
              ) : auditDetail ? (
                <AuditDetailPanel audit={auditDetail} />
              ) : null}
              <div className="hit-list">
                {searchResult.hits.map((hit, index) => (
                  <EvidenceCard key={hit.chunkId} hit={hit} rank={index + 1} />
                ))}
              </div>
            </>
          ) : null}

          <TeachingTaskPanel
            task={teachingTask}
            loading={loadingTeachingTask}
            error={teachingError}
            version={handoutVersion}
            previewLatex={handoutPreviewTaskId === `${teachingTask?.taskId}:${handoutVersion}` ? handoutPreviewLatex : ""}
            action={handoutAction}
            exportMessage={handoutExportMessage}
            feedbackRating={feedbackRating}
            feedbackDecision={feedbackDecision}
            feedbackComment={feedbackComment}
            submittingFeedback={submittingFeedback}
            feedbackMessage={feedbackMessage}
            batchFolderPath={batchFolderPath}
            onVersionChange={(version) => {
              setHandoutVersion(version);
              setHandoutPreviewLatex("");
              setHandoutPreviewTaskId("");
              setHandoutExportMessage("");
            }}
            onBatchFolderPathChange={setBatchFolderPath}
            onPreviewLatex={handlePreviewLatex}
            onExportLatex={handleExportLatex}
            onExportPdf={handleExportPdf}
            onExportBatchZip={handleExportBatchZip}
            onFeedbackRatingChange={setFeedbackRating}
            onFeedbackDecisionChange={setFeedbackDecision}
            onFeedbackCommentChange={setFeedbackComment}
            onSubmitFeedback={handleSubmitFeedback}
          />
        </section>
      </section>
    </main>
  );
}

function readStoredAuthSession() {
  try {
    const value = globalThis.localStorage?.getItem("math-agent:auth-session");
    return value ? (JSON.parse(value) as LoginResponse) : null;
  } catch {
    return null;
  }
}

function multiAgentEvidenceRefs(searchResult: TextbookSearchResponse | null) {
  if (!searchResult) {
    return [];
  }
  return searchResult.hits.slice(0, 3).map((hit) => `PUBLIC_TEXTBOOK:${hit.docId}:${hit.chunkId}`);
}

function downloadText(fileName: string, content: string, mimeType: string) {
  downloadBlob(fileName, new Blob([content], { type: mimeType }));
}

function downloadBytes(fileName: string, bytes: Uint8Array, mimeType: string) {
  const arrayBuffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(arrayBuffer).set(bytes);
  downloadBlob(fileName, new Blob([arrayBuffer], { type: mimeType }));
}

function downloadBlob(fileName: string, blob: Blob) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

function agentProviders(catalog: AgentModelCatalogResponse | null): string[] {
  const providers = catalog?.providers.filter((provider) => provider.enabled).map((provider) => provider.name) ?? [];
  return providers;
}

function agentModelsForProvider(
  catalog: AgentModelCatalogResponse | null,
  providerName: string,
): { modelCode: string; modelLevel: string; priceTier: string }[] {
  const provider = catalog?.providers.find((candidate) => candidate.name === providerName && candidate.enabled);
  if (provider && provider.models.length > 0) {
    return provider.models;
  }
  return [];
}
