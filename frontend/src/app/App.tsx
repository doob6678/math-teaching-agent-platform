import {
  AlertCircle, BookOpen, Bot, BrainCircuit, Check, ChevronDown, Database,
  FileText, FolderKanban, GitBranch, Globe, GraduationCap, Home,
  LayoutDashboard, Library, Loader2, LogOut, Network, RefreshCw,
  Bell, Search, Settings, ShieldCheck, Sparkles, User, X,
} from "lucide-react";
import katex from "katex";
import "katex/dist/katex.min.css";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
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
  MultiAgentWritingArtifact,
  MultiAgentWritingResponse,
  MultiAgentWritingTraceResponse,
  QuestionBankItemResponse,
  RetrievalAuditDetail,
  StudentDashboardResponse,
  TeachingHandoutTemplateResponse,
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
  compactText,
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

type PageId = "dashboard" | "search" | "teaching" | "agents" | "streaming" | "knowledge" | "settings" | "login";

interface NavItem {
  id: PageId;
  label: string;
  icon: React.ReactNode;
}

export function App() {
  const api = useMemo(() => createTextbookApiClient(DEFAULT_BACKEND_URL), []);
  const [activePage, setActivePage] = useState<PageId>("dashboard");
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

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
  const [teachingTemplates, setTeachingTemplates] = useState<TeachingHandoutTemplateResponse[]>([]);
  const [selectedTeachingTemplateCode, setSelectedTeachingTemplateCode] = useState("default_standard");
  const [studentDashboard, setStudentDashboard] = useState<StudentDashboardResponse | null>(null);
  const [dashboardStudentId, setDashboardStudentId] = useState("");
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
  const [handoutPreviewPdfUrl, setHandoutPreviewPdfUrl] = useState("");
  const [handoutPreviewPdfTaskId, setHandoutPreviewPdfTaskId] = useState("");
  const [teachingHistory, setTeachingHistory] = useState<TeachingTaskResponse[]>([]);
  const [loadingTeachingHistory, setLoadingTeachingHistory] = useState(false);
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
  const [multiAgentArtifact, setMultiAgentArtifact] = useState<MultiAgentWritingArtifact | null>(null);
  const [loadingMultiAgentArtifact, setLoadingMultiAgentArtifact] = useState(false);
  const [multiAgentArtifactError, setMultiAgentArtifactError] = useState("");
  const [multiAgentArtifactMessage, setMultiAgentArtifactMessage] = useState("");
  const [exportingMultiAgentArtifact, setExportingMultiAgentArtifact] = useState("");
  const [multiAgentArtifactPdfUrl, setMultiAgentArtifactPdfUrl] = useState("");
  const [multiAgentArtifactPdfWorkflowId, setMultiAgentArtifactPdfWorkflowId] = useState("");
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
  const [questionBankPage, setQuestionBankPage] = useState(1);
  const [questionBankPageSize, setQuestionBankPageSize] = useState(10);
  const [batchFolderPath, setBatchFolderPath] = useState("");
  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [authSession, setAuthSession] = useState<LoginResponse | null>(() => readStoredAuthSession());
  const [authSessionChecked, setAuthSessionChecked] = useState(() => readStoredAuthSession() === null);
  const hasVerifiedSession = authSessionChecked && authSession !== null;
  const canReadRetrievalAudit = authSession?.role === "teacher" || authSession?.role === "admin";
  const [loadingSummary, setLoadingSummary] = useState(false);
  const [searching, setSearching] = useState(false);
  const [loadingAudit, setLoadingAudit] = useState(false);
  const [submittingTeachingTask, setSubmittingTeachingTask] = useState(false);
  const [loadingTeachingTask, setLoadingTeachingTask] = useState(false);
  const [loadingTeachingTemplates, setLoadingTeachingTemplates] = useState(false);
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
  const [loadingQuestionBank, setLoadingQuestionBank] = useState(false);

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
        if (!active) return;
        setAuthSession(session);
        setAuthError("");
        setAuthSessionChecked(true);
      })
      .catch(() => {
        if (!active) return;
        globalThis.localStorage?.removeItem("math-agent:auth-session");
        setAuthSession(null);
        setAuthError("登录态已失效，请重新登录。");
        setAuthSessionChecked(true);
      });
    return () => { active = false; };
  }, [api]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    loadStudentDashboard();
  }, [api, hasVerifiedSession, authSession?.role]);

  function loadStudentDashboard(studentId = "") {
    setLoadingStudentDashboard(true);
    setStudentDashboardError("");
    setStudentDashboard(null);
    api
      .getStudentDashboard(studentId.trim() || undefined)
      .then(setStudentDashboard)
      .catch((error: Error) => setStudentDashboardError(error.message))
      .finally(() => setLoadingStudentDashboard(false));
  }

  function handleRefreshStudentDashboard() {
    const requestedStudentId = authSession?.role === "student" ? "" : dashboardStudentId.trim();
    setLoadingStudentDashboard(true);
    setStudentDashboardError("");
    setStudentDashboard(null);
    api
      .refreshStudentDashboard(requestedStudentId || undefined)
      .then(setStudentDashboard)
      .catch((error: Error) => setStudentDashboardError(error.message))
      .finally(() => setLoadingStudentDashboard(false));
  }

  function handleLoadStudentDashboard() {
    const requestedStudentId = authSession?.role === "student" ? "" : dashboardStudentId.trim();
    loadStudentDashboard(requestedStudentId);
  }

  useEffect(() => {
    if (!hasVerifiedSession) return;
    refreshTeacherResources();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    setLoadingTeachingTemplates(true);
    api
      .listTeachingHandoutTemplates()
      .then((templates) => {
        setTeachingTemplates(templates);
        if (templates.length && !templates.some((item) => item.templateCode === selectedTeachingTemplateCode)) {
          setSelectedTeachingTemplateCode(templates[0].templateCode);
        }
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setLoadingTeachingTemplates(false));
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    refreshKnowledgeQuestionBank();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    refreshAgentTraces();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    const workflowId = globalThis.localStorage?.getItem(MULTI_AGENT_WORKFLOW_STORAGE_KEY);
    if (workflowId) {
      refreshMultiAgentWritingWorkflow(workflowId);
    }
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!multiAgentWorkflow || multiAgentWorkflow.status !== "RUNNING") return;
    const timer = globalThis.setInterval(() => {
      refreshMultiAgentWritingWorkflow(multiAgentWorkflow.workflowId);
    }, 3000);
    return () => globalThis.clearInterval(timer);
  }, [api, multiAgentWorkflow?.workflowId, multiAgentWorkflow?.status]);

  useEffect(() => {
    if (!multiAgentWorkflow || (multiAgentWorkflow.status !== "COMPLETED" && multiAgentWorkflow.status !== "FAILED")) return;
    if (loadingMultiAgentArtifact || multiAgentArtifact?.workflowId === multiAgentWorkflow.workflowId) return;
    loadMultiAgentArtifact(multiAgentWorkflow.workflowId, true);
  }, [
    api,
    multiAgentWorkflow?.workflowId,
    multiAgentWorkflow?.status,
    multiAgentArtifact?.workflowId,
    loadingMultiAgentArtifact,
  ]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    api
      .getAgentModelCatalog()
      .then((catalog) => {
        setAgentModelCatalog(catalog);
        setAgentProvider(catalog.defaultProviderName);
        setAgentModel(catalog.defaultModelCode);
        setAgentModelCatalogError("");
      })
      .catch((error: Error) => setAgentModelCatalogError(toUserFacingError(error)));
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    refreshTeachingHistory();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    const taskId = window.localStorage.getItem(TEACHING_TASK_STORAGE_KEY);
    if (!taskId) return;
    setLoadingTeachingTask(true);
    api
      .getTeachingTask(taskId)
      .then((task) => {
        setTeachingTask(task);
        previewTeachingTaskPdf(task.taskId, handoutVersion);
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setLoadingTeachingTask(false));
  }, [api, hasVerifiedSession]);

  useEffect(() => () => {
    if (handoutPreviewPdfUrl) {
      URL.revokeObjectURL(handoutPreviewPdfUrl);
    }
  }, [handoutPreviewPdfUrl]);

  function refreshTeachingHistory() {
    setLoadingTeachingHistory(true);
    api
      .listTeachingTasks(20)
      .then(setTeachingHistory)
      .catch((error: Error) => {
        if (error.message.includes("405") || error.message.includes("404")) {
          setTeachingHistory([]);
          return;
        }
        setTeachingError(error.message);
      })
      .finally(() => setLoadingTeachingHistory(false));
  }

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
        if (checkpoint) checkpoints[jobId] = checkpoint;
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
    setLoadingQuestionBank(true);
    Promise.all([
      api.listKnowledgePoints(),
      api.listKnowledgeRelations(),
      api.searchQuestionBankItems(questionBankQuery.trim(), 50),
    ])
      .then(([points, relations, questions]) => {
        setKnowledgePoints(points);
        setKnowledgeRelations(relations);
        setQuestionBankItems(questions);
        setQuestionBankPage(1);
      })
      .catch((error: Error) => setKnowledgeBankError(toUserFacingError(error)))
      .finally(() => setLoadingQuestionBank(false));
  }

  function handleSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!query.trim()) { setSearchError("请输入检索词。"); return; }
    setSearching(true);
    setSearchError("");
    setAuditError("");
    setAuditDetail(null);
    api
      .search(query.trim(), limit)
      .then((result) => {
        setSearchResult(result);
        if (!canReadRetrievalAudit) {
          setAuditError("");
          setLoadingAudit(false);
          return undefined;
        }
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
    if (!learningGoal.trim()) { setTeachingError("请输入讲义主题或学习目标。"); return; }
    const clientRequestId = globalThis.crypto.randomUUID();
    setSubmittingTeachingTask(true);
    setTeachingError("");
    api
      .submitTeachingTask({
        clientRequestId,
        questionText: teachingQuestion.trim() || undefined,
        learningGoal: learningGoal.trim(),
        evidenceLimit: limit,
        handoutTemplateCode: selectedTeachingTemplateCode || undefined,
      })
      .then((task) => {
        window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
        setTeachingTask(task);
        setHandoutPreviewLatex("");
        setHandoutPreviewTaskId("");
        setHandoutPreviewPdfUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          return "";
        });
        setHandoutPreviewPdfTaskId("");
        setHandoutExportMessage("");
        setFeedbackMessage("");
        refreshTeachingHistory();
        previewTeachingTaskPdf(task.taskId, handoutVersion);
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setSubmittingTeachingTask(false));
  }

  function handleLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!loginUsername.trim() || !loginPassword.trim()) { setAuthError("请输入用户名和密码。"); return; }
    setLoggingIn(true);
    setAuthError("");
    api
      .login({ username: loginUsername.trim(), password: loginPassword })
      .then((session) => {
        setAuthSession(session);
        setTeachingError(""); setStudentDashboardError(""); setTeacherResourceError("");
        setKnowledgeBankError(""); setAgentPlanError(""); setAgentExecutionError("");
        setAgentTraceError(""); setAgentModelCatalogError(""); setAgentModelHealthError("");
        setMcpError("");
        loadStudentDashboard();
        refreshTeacherResources();
        refreshKnowledgeQuestionBank();
        refreshAgentTraces();
        api.getAgentModelCatalog().then((catalog) => {
          setAgentModelCatalog(catalog);
          setAgentProvider(catalog.defaultProviderName);
          setAgentModel(catalog.defaultModelCode);
        }).catch((error: Error) => setAgentModelCatalogError(toUserFacingError(error)));
        navigate("dashboard");
      })
      .catch((error: Error) => setAuthError(error.message))
      .finally(() => setLoggingIn(false));
  }

  function pollTeachingTask(taskId: string) {
    const poll = () => {
      api.getTeachingTask(taskId).then((task) => {
        setTeachingTask(task);
        if (task.status === "CREATED" || task.status === "RUNNING") {
          globalThis.setTimeout(poll, 2000);
        }
      }).catch(() => {
        globalThis.setTimeout(poll, 3000);
      });
    };
    globalThis.setTimeout(poll, 1000);
  }

  function handleRegisterResource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!resourceTitle.trim() || !resourceLocation.trim()) { setTeacherResourceError("请输入资料标题和本地路径或飞书 URL。"); return; }
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
        setResourceTitle(""); setResourceLocation(""); setFeishuDiscoveryResult(null);
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setRegisteringResource(false));
  }

  function handleArchiveResource(documentId: string) {
    setTeacherResourceError("");
    api
      .archiveTeacherResource(documentId)
      .then(() => setTeacherResources((current) => current.filter((r) => r.documentId !== documentId)))
      .catch((error: Error) => setTeacherResourceError(error.message));
  }

  function handleCreateKnowledgePoint(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!knowledgePointName.trim()) { setKnowledgeBankError("请输入知识点名称。"); return; }
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
    if (!questionTitle.trim() || !questionText.trim()) { setKnowledgeBankError("请输入题目标题和题干内容。"); return; }
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
      .then((question) => {
        setQuestionBankItems((current) => [question, ...current]);
        setQuestionBankPage(1);
      })
      .catch((error: Error) => setKnowledgeBankError(toUserFacingError(error)))
      .finally(() => setSavingKnowledgeBank(false));
  }

  function handleSearchQuestionBank(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setKnowledgeBankError("");
    setLoadingQuestionBank(true);
    api
      .searchQuestionBankItems(questionBankQuery.trim(), 50)
      .then((questions) => {
        setQuestionBankItems(questions);
        setQuestionBankPage(1);
      })
      .catch((error: Error) => setKnowledgeBankError(toUserFacingError(error)))
      .finally(() => setLoadingQuestionBank(false));
  }

  function handleCreateResourceSyncJob(documentId: string) {
    setSyncingResourceId(documentId);
    setTeacherResourceError("");
    api
      .createTeacherResourceSyncJob(documentId)
      .then((job) => {
        setTeacherSyncJobs((current) => ({ ...current, [documentId]: [job, ...(current[documentId] ?? [])] }));
        setTeacherSyncCheckpoints((current) => { const n = { ...current }; delete n[job.jobId]; return n; });
        return api.executeTeacherResourceSyncJob(documentId, job.jobId);
      })
      .then((executedJob) => {
        setTeacherSyncJobs((current) => ({
          ...current,
          [documentId]: [executedJob, ...(current[documentId] ?? []).filter((j) => j.jobId !== executedJob.jobId)],
        }));
        return api.getTeacherResourceSyncCheckpoint(documentId, executedJob.jobId)
          .then((cp) => { if (cp) setTeacherSyncCheckpoints((cur) => ({ ...cur, [executedJob.jobId]: cp })); })
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
          [documentId]: [resumedJob, ...(current[documentId] ?? []).filter((j) => j.jobId !== resumedJob.jobId)],
        }));
        return api.getTeacherResourceSyncCheckpoint(documentId, resumedJob.jobId)
          .then((cp) => { if (cp) setTeacherSyncCheckpoints((cur) => ({ ...cur, [resumedJob.jobId]: cp })); })
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
      .then((result) => { setTeacherResourceImportResult(result); setQuestionBankItems((c) => [...result.importedQuestions, ...c]); })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setImportingResourceId(""));
  }

  function handleRebuildTeacherResourceIndex(documentId: string) {
    setRebuildingResourceId(documentId);
    setTeacherResourceIndexRebuildResult(null);
    setTeacherResourceError("");
    api
      .rebuildTeacherResourceVectorIndex(documentId)
      .then((result) => { setTeacherResourceIndexRebuildResult(result); return refreshTeacherResources(); })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setRebuildingResourceId(""));
  }

  function handleTeacherBlockSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teacherResourceSearchQuery.trim()) { setTeacherResourceError("请输入资源检索关键词。"); return; }
    setSearchingTeacherBlocks(true);
    setTeacherResourceError("");
    setTeacherBlockSearchAudit(null);
    api
      .searchTeacherResourceBlocks(teacherResourceSearchQuery.trim(), 8)
      .then((result) => {
        setTeacherBlockSearchResult(result);
        return api.getTeacherResourceBlockSearchAudit(result.queryId).then(setTeacherBlockSearchAudit).catch((e) => setTeacherResourceError(e.message));
      })
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setSearchingTeacherBlocks(false));
  }

  function handleDiscoverFeishu(mode: "list" | "search") {
    if (mode === "search" && !feishuDiscoveryQuery.trim()) { setTeacherResourceError("请输入飞书搜索关键词。"); return; }
    const rootUrl = resourceLocation.trim();
    if (!rootUrl) { setTeacherResourceError("请先输入飞书文件夹或文档 URL。"); return; }
    setDiscoveringFeishu(true);
    setTeacherResourceError("");
    api
      .discoverFeishuResources({ mode, query: mode === "search" ? feishuDiscoveryQuery.trim() : "", rootUrl, listDepth: 1, maxDepth: 5 })
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
    if (!teachingTask) return;
    setHandoutAction("preview");
    setTeachingError("");
    setHandoutExportMessage("");
    setHandoutPreviewPdfUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setHandoutPreviewPdfTaskId("");
    api
      .previewTeachingTaskLatex(teachingTask.taskId, handoutVersion)
      .then((latex) => { setHandoutPreviewLatex(latex); setHandoutPreviewTaskId(`${teachingTask.taskId}:${handoutVersion}`); })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handlePreviewPdf() {
    if (!teachingTask) return;
    previewTeachingTaskPdf(teachingTask.taskId, handoutVersion);
  }

  function previewTeachingTaskPdf(taskId: string, version: "teacher" | "student") {
    setHandoutAction("preview-pdf");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskPdf(taskId, version)
      .then((pdf) => {
        setHandoutPreviewLatex("");
        setHandoutPreviewTaskId("");
        setHandoutPreviewPdfUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          const bytes = new Uint8Array(pdf.byteLength);
          bytes.set(pdf);
          return URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
        });
        setHandoutPreviewPdfTaskId(`${taskId}:${version}`);
      })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleExportLatex() {
    if (!teachingTask) return;
    setHandoutAction("latex");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskLatex(teachingTask.taskId, handoutVersion)
      .then((latex) => { downloadText(`${teachingTask.taskId}-${handoutVersion}.tex`, latex, "application/x-tex;charset=utf-8"); setHandoutExportMessage("TeX 源文件已下载。"); })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleExportPdf() {
    if (!teachingTask) return;
    setHandoutAction("pdf");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskPdf(teachingTask.taskId, handoutVersion)
      .then((pdf) => { downloadBytes(`${teachingTask.taskId}-${handoutVersion}.pdf`, pdf, "application/pdf"); setHandoutExportMessage("PDF 讲义已下载。"); })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleExportBatchZip() {
    if (!teachingTask) return;
    setHandoutAction("zip");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .createTeachingHandoutBatchZip({ taskIds: [teachingTask.taskId], folderIds: [`task-${teachingTask.taskId}`], folderPaths: [batchFolderPath.trim() || `handouts/${teachingTask.taskId}`] })
      .then((batch) => api.downloadTeachingHandoutBatchZip(batch.batchId).then((zip) => { downloadBytes(`${batch.batchId}.zip`, zip, "application/zip"); setHandoutExportMessage(`ZIP 已下载，临时文件有效期至 ${formatDateTime(batch.expiresAt)}。`); }))
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setHandoutAction(""));
  }

  function handleSelectTeachingHistory(task: TeachingTaskResponse) {
    window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
    setTeachingTask(task);
    setHandoutPreviewLatex("");
    setHandoutPreviewTaskId("");
    setHandoutPreviewPdfUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setHandoutPreviewPdfTaskId("");
    setHandoutExportMessage("");
    setTeachingError("");
    previewTeachingTaskPdf(task.taskId, handoutVersion);
  }

  function handleSubmitFeedback(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teachingTask) return;
    setSubmittingFeedback(true);
    setTeachingError("");
    setFeedbackMessage("");
    api
      .submitTeachingHumanFeedback(teachingTask.taskId, { rating: feedbackRating, decision: feedbackDecision, comment: feedbackComment.trim() })
      .then((feedback) => { setFeedbackMessage(`反馈已记录：${decisionLabel(feedback.decision)} / ${feedback.rating} 星`); setFeedbackComment(""); })
      .catch((error: Error) => setTeachingError(error.message))
      .finally(() => setSubmittingFeedback(false));
  }

  function handlePlanAgent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const selectedModels = agentModelsForProvider(agentModelCatalog, agentProvider);
    if (!selectedModels.some((model) => model.modelCode === agentModel)) { setAgentPlanError("模型目录还没有加载完成，或当前模型不可用。"); return; }
    const disabledToolScopes = [disablePrivateSearch ? "tool:search:private" : "", disableTextbookSearch ? "tool:search:textbook" : ""].filter(Boolean);
    setPlanningAgent(true);
    setAgentPlanError("");
    setAgentExecutionError("");
    setAgentExecution(null);
    api
      .planAgentRun({
        agentCode: "CoursewareAgent", taskType: "courseware_generation", userVipLevel: "teacher",
        estimatedInputTokens: 3200, estimatedOutputTokens: 1800, hasImage: false, hasFormula: true,
        difficulty: "medium", latencyRequirement: "normal", costBudget: 2.5, previousFailureCount: 0,
        requiredJsonSchema: true, requestedToolScopes: ["tool:courseware:generate", "tool:search:private", "tool:search:textbook"],
        disabledToolScopes, requestedDataScopes: ["TEACHER_PRIVATE", "CLASS_AUTHORIZED", "PUBLIC_TEXTBOOK"],
        highValueOperation: true, preferredProviderName: agentProvider, preferredModelCode: agentModel,
      })
      .then(setAgentPlan)
      .catch((error: Error) => setAgentPlanError(toUserFacingError(error)))
      .finally(() => setPlanningAgent(false));
  }

  function handleExecuteAgentRun() {
    if (!agentPlan) return;
    setExecutingAgent(true);
    setAgentExecutionError("");
    api
      .executeAgentRun({ plan: agentPlan, userInputSummary: [learningGoal.trim(), teachingQuestion.trim()].filter(Boolean).join(" / "), evidenceRefs: teachingTask ? [`teaching-task:${teachingTask.taskId}`] : [] })
      .then((execution) => { setAgentExecution(execution); refreshAgentTraces(); })
      .catch((error: Error) => setAgentExecutionError(toUserFacingError(error)))
      .finally(() => setExecutingAgent(false));
  }

  function handleStartMultiAgentWriting(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!multiAgentWritingGoal.trim()) { setMultiAgentWritingError("请输入写作目标。补充要求可以留空。"); return; }
    const selectedModels = agentModelsForProvider(agentModelCatalog, agentProvider);
    if (!selectedModels.some((model) => model.modelCode === agentModel)) { setMultiAgentWritingError("模型目录还没有加载完成，或当前模型不可用。"); return; }
    setStartingMultiAgentWriting(true);
    setMultiAgentWritingError("");
    setMultiAgentWorkflowTraces(null);
    setMultiAgentArtifact(null);
    setMultiAgentArtifactError("");
    setMultiAgentArtifactMessage("");
    setMultiAgentArtifactPdfUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setMultiAgentArtifactPdfWorkflowId("");
    api
      .startAsyncMultiAgentWriting({
        writingGoal: multiAgentWritingGoal.trim(),
        questionText: multiAgentWritingQuestion.trim() || multiAgentWritingGoal.trim(),
        evidenceRefs: multiAgentEvidenceRefs(searchResult),
        preferredProviderName: agentProvider,
        preferredModelCode: agentModel,
      })
      .then((workflow) => { setMultiAgentWorkflow(workflow); globalThis.localStorage?.setItem(MULTI_AGENT_WORKFLOW_STORAGE_KEY, workflow.workflowId); })
      .catch((error: Error) => setMultiAgentWritingError(toUserFacingError(error)))
      .finally(() => setStartingMultiAgentWriting(false));
  }

  function loadMultiAgentArtifact(workflowId: string, silent = false) {
    if (!silent) {
      setLoadingMultiAgentArtifact(true);
    }
    setMultiAgentArtifactError("");
    return api
      .getMultiAgentWritingArtifact(workflowId)
      .then((artifact) => {
        setMultiAgentArtifact(artifact);
        setMultiAgentArtifactMessage(artifact.mergedMarkdown?.trim() ? "讲义成果已加载，可预览和导出。" : "任务已完成，但当前阶段没有返回可展示正文。");
      })
      .catch((error: Error) => {
        setMultiAgentArtifactError(toUserFacingError(error));
      })
      .finally(() => {
        if (!silent) {
          setLoadingMultiAgentArtifact(false);
        }
      });
  }

  function handlePreviewMultiAgentArtifactPdf() {
    const workflowId = multiAgentWorkflow?.workflowId;
    if (!workflowId) return;
    setExportingMultiAgentArtifact("preview-pdf");
    setMultiAgentArtifactError("");
    setMultiAgentArtifactMessage("");
    api
      .exportMultiAgentWritingArtifact(workflowId, "pdf")
      .then((exported) => {
        const bytes = base64ToBytes(exported.base64Content);
        setMultiAgentArtifactPdfUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          return URL.createObjectURL(new Blob([bytes], { type: exported.mimeType || "application/pdf" }));
        });
        setMultiAgentArtifactPdfWorkflowId(workflowId);
        setMultiAgentArtifactMessage(`PDF 预览已生成，临时导出有效期至 ${formatDateTime(exported.expiresAt)}。`);
      })
      .catch((error: Error) => setMultiAgentArtifactError(toUserFacingError(error)))
      .finally(() => setExportingMultiAgentArtifact(""));
  }

  function handleExportMultiAgentArtifact(format: "markdown" | "latex" | "pdf" | "zip") {
    const workflowId = multiAgentWorkflow?.workflowId;
    if (!workflowId) return;
    setExportingMultiAgentArtifact(format);
    setMultiAgentArtifactError("");
    setMultiAgentArtifactMessage("");
    api
      .exportMultiAgentWritingArtifact(workflowId, format)
      .then((exported) => {
        downloadBytes(exported.fileName, base64ToBytes(exported.base64Content), exported.mimeType);
        setMultiAgentArtifactMessage(`${exportLabel(format)} 已下载，临时导出有效期至 ${formatDateTime(exported.expiresAt)}。`);
      })
      .catch((error: Error) => setMultiAgentArtifactError(toUserFacingError(error)))
      .finally(() => setExportingMultiAgentArtifact(""));
  }

  function refreshMultiAgentWritingWorkflow(workflowId?: string) {
    const targetWorkflowId = workflowId || multiAgentWorkflow?.workflowId;
    if (!targetWorkflowId) return;
    setPollingMultiAgentWriting(true);
    setMultiAgentWritingError("");
    api
      .getMultiAgentWritingWorkflow(targetWorkflowId)
      .then((workflow) => {
        setMultiAgentWorkflow(workflow);
        if (workflow.status === "COMPLETED" || workflow.status === "FAILED") {
          return Promise.all([
            api.getMultiAgentWritingTraces(workflow.workflowId).then(setMultiAgentWorkflowTraces).catch(() => undefined),
            loadMultiAgentArtifact(workflow.workflowId, true),
          ]).then(() => refreshAgentTraces());
        }
        return undefined;
      })
      .catch((error: Error) => {
        if (isWorkflowNotFound(error)) {
          globalThis.localStorage?.removeItem(MULTI_AGENT_WORKFLOW_STORAGE_KEY);
          setMultiAgentWorkflow(null);
          setMultiAgentWorkflowTraces(null);
          setMultiAgentArtifact(null);
          setMultiAgentWritingError("这个写作任务记录已经失效或不属于当前账号，请重新启动流程。");
          return;
        }
        setMultiAgentWritingError(toUserFacingError(error));
      })
      .finally(() => setPollingMultiAgentWriting(false));
  }

  function handleBuildMcpConfiguration(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMcpBuilding(true);
    setMcpError("");
    setMcpCopyMessage("");
    api
      .buildMcpConfiguration({ url: mcpUrl.trim(), secretKey: mcpSecretKey.trim(), secretEnvName: mcpSecretEnvName.trim(), enabledToolNames: mcpSelection.tools, enabledPromptNames: mcpSelection.prompts })
      .then(setMcpConfiguration)
      .catch((error: Error) => setMcpError(error.message))
      .finally(() => setMcpBuilding(false));
  }

  function handleMcpToolToggle(option: string, checked: boolean) {
    setMcpSelection((current) => ({ ...current, tools: toggleMcpExposureOption(current.tools, option, checked, MCP_TOOL_OPTIONS) }));
  }

  function handleAgentProviderChange(provider: string) {
    const models = agentModelsForProvider(agentModelCatalog, provider);
    setAgentProvider(provider);
    setAgentModel(models[0]?.modelCode ?? "");
  }

  function refreshAgentModelHealth() {
    setCheckingAgentModelHealth(true);
    setAgentModelHealthError("");
    api.getAgentModelHealth().then(setAgentModelHealth).catch((error: Error) => setAgentModelHealthError(toUserFacingError(error))).finally(() => setCheckingAgentModelHealth(false));
  }

  function handleMcpPromptToggle(option: string, checked: boolean) {
    setMcpSelection((current) => ({ ...current, prompts: toggleMcpExposureOption(current.prompts, option, checked, MCP_PROMPT_OPTIONS) }));
  }

  function handleCopyMcpConfiguration() {
    if (!mcpConfiguration?.configJson) return;
    if (!navigator.clipboard?.writeText) { setMcpCopyMessage("当前浏览器不支持剪贴板写入。"); return; }
    navigator.clipboard.writeText(mcpConfiguration.configJson).then(() => setMcpCopyMessage("MCP JSON 已复制。")).catch((error: Error) => setMcpCopyMessage(error.message));
  }

  function handleLogout() {
    globalThis.localStorage?.removeItem("math-agent:auth-session");
    setAuthSession(null);
    setDropdownOpen(false);
  }

  function navigate(page: PageId) {
    setActivePage(page);
    setDropdownOpen(false);
  }

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const navItems: NavItem[] = [
    { id: "dashboard", label: "工作台", icon: <LayoutDashboard size={16} /> },
    { id: "search", label: "教材检索", icon: <Search size={16} /> },
    { id: "teaching", label: "教学任务", icon: <BookOpen size={16} /> },
    { id: "agents", label: "AI 控制台", icon: <Bot size={16} /> },
    { id: "streaming", label: "讲义协作", icon: <GitBranch size={16} /> },
    { id: "knowledge", label: "知识库", icon: <BrainCircuit size={16} /> },
    { id: "settings", label: "系统设置", icon: <Settings size={16} /> },
  ];

  const avatarLetter = authSession?.userId?.charAt(0).toUpperCase() ?? "?";

  useEffect(() => {
    if (activePage === "login" && hasVerifiedSession) {
      setActivePage("dashboard");
    }
  }, [activePage, hasVerifiedSession]);

  return (
    <div className="app-root">
      <nav className="top-nav">
        <div className="nav-brand">
          <div className="nav-brand-icon">M</div>
          <span className="nav-brand-text">Math Agent</span>
        </div>
        <div className="nav-divider" />
        <div className="nav-links">
          {navItems.map((item) => (
            <button
              key={item.id}
              className={`nav-link${activePage === item.id ? " active" : ""}`}
              onClick={() => navigate(item.id)}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </div>
        <div className="nav-right">
          <div ref={dropdownRef} style={{ position: "relative" }}>
            <button className="nav-avatar" onClick={() => setDropdownOpen((d) => !d)} title={authSession?.userId ?? "未登录"}>
              {avatarLetter}
            </button>
            <div className={`nav-avatar-dropdown${dropdownOpen ? " open" : ""}`}>
              <div className="dropdown-header">
                <div className="dropdown-header-name">{authSession?.userId ?? "未登录用户"}</div>
                <div className="dropdown-header-role">{sessionRoleLabel(authSession?.role)}</div>
              </div>
              {authSession ? (
                <>
                  <button className="dropdown-item" onClick={() => navigate("settings")}>
                    <Settings size={14} />
                    系统设置
                  </button>
                  <button className="dropdown-item logout" onClick={handleLogout}>
                    <LogOut size={14} />
                    退出登录
                  </button>
                </>
              ) : (
                 <button className="dropdown-item" onClick={() => { navigate("login"); setDropdownOpen(false); }}>
                   <ShieldCheck size={14} />
                   登录账号
                 </button>
              )}
            </div>
          </div>
        </div>
      </nav>

      <main className="main-content">
        <div className="page-enter" key={activePage}>
          {activePage === "dashboard" && renderDashboard()}
          {activePage === "search" && renderSearch()}
          {activePage === "teaching" && renderTeaching()}
          {activePage === "agents" && renderAgents()}
          {activePage === "streaming" && renderStreaming()}
          {activePage === "knowledge" && renderKnowledge()}
          {activePage === "settings" && renderSettings()}
          {activePage === "login" && renderLogin()}
        </div>
      </main>
    </div>
  );

  function renderDashboard() {
    if (!hasVerifiedSession) {
      return <LoginPrompt onLogin={() => navigate("login")} />;
    }
    return (
      <>
        <div className="page-header">
          <h1 className="page-title">工作台</h1>
          <p className="page-subtitle">教学数据总览与快速入口</p>
        </div>
        <div className="card-grid">
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><LayoutDashboard size={16} /> 学生学习概览</h2>
              <button className="btn btn-ghost btn-sm" onClick={handleRefreshStudentDashboard} disabled={loadingStudentDashboard}>
                <RefreshCw size={14} className={loadingStudentDashboard ? "spin" : ""} />
              </button>
            </div>
            <div className="card-body">
              <StudentDashboardPanel
                dashboard={studentDashboard}
                loading={loadingStudentDashboard}
                error={studentDashboardError}
                viewerRole={authSession?.role}
                targetStudentId={dashboardStudentId}
                onTargetStudentIdChange={setDashboardStudentId}
                onLoad={handleLoadStudentDashboard}
                onRefresh={handleRefreshStudentDashboard}
              />
            </div>
          </div>
          <div className="card card-full teaching-create-card">
            <div className="card-header">
              <h2 className="card-title"><Database size={16} /> 教材资源</h2>
            </div>
            <div className="card-body">
              {loadingSummary ? (
                <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取教材目录中" />
              ) : summaryError ? (
                <StatusLine icon={<AlertCircle size={16} />} text={summaryError} tone="danger" />
              ) : summary ? (
                <div className="metric-grid">
                  <Metric label="教材" value={summary.bookCount} />
                  <Metric label="文本块" value={summary.totalChunkCount} />
                  <Metric label="PDF 页" value={summary.totalPageCount} />
                </div>
              ) : null}
            </div>
          </div>
          <div className="card">
            <div className="card-header">
              <h2 className="card-title"><GraduationCap size={16} /> 快捷操作</h2>
            </div>
            <div className="card-body">
              <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                <button className="btn btn-secondary" onClick={() => navigate("search")}><Search size={14} /> 教材检索</button>
                <button className="btn btn-secondary" onClick={() => navigate("teaching")}><BookOpen size={14} /> 教学任务</button>
                <button className="btn btn-secondary" onClick={() => navigate("agents")}><Bot size={14} /> AI 控制台</button>
                <button className="btn btn-secondary" onClick={() => navigate("streaming")}><GitBranch size={14} /> 讲义协作</button>
              </div>
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderRequiresAuth(children: React.ReactNode) {
    if (!hasVerifiedSession) {
      return <LoginPrompt onLogin={() => navigate("settings")} />;
    }
    return <>{children}</>;
  }

  function renderSearch() {
    return (
      <>
        <div className="page-header">
          <h1 className="page-title">教材检索</h1>
          <p className="page-subtitle">基于关键词与向量混合检索的教材证据搜索</p>
        </div>
        <div className="card-grid">
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><Search size={16} /> 检索参数</h2>
            </div>
            <div className="card-body">
              <form className="search-form" onSubmit={handleSearch}>
                <label>
                  <span>检索词</span>
                  <input
                    className="form-input"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="输入教材术语、题干片段或公式关键词"
                  />
                </label>
                <label>
                  <span>返回条数</span>
                  <input
                    className="form-input"
                    type="number" min={1} max={20}
                    value={limit}
                    onChange={(event) => setLimit(Number(event.target.value))}
                  />
                </label>
                <button className="btn btn-primary" type="submit" disabled={searching}>
                  {searching ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
                  <span>检索</span>
                </button>
              </form>
            </div>
          </div>
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><FileText size={16} /> 命中证据</h2>
              {searchResult ? (
                <span className="strategy-pill">
                  <ShieldCheck size={14} />
                  <span>{searchResult.retrievalStrategy}</span>
                </span>
              ) : null}
            </div>
            <div className="card-body">
              {searchError ? <StatusLine icon={<AlertCircle size={16} />} text={searchError} tone="danger" /> : null}
              {loadingAudit ? (
                <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取审计详情中" />
              ) : auditError ? (
                <StatusLine icon={<AlertCircle size={16} />} text={auditError} tone="danger" />
              ) : auditDetail ? (
                <AuditDetailPanel audit={auditDetail} />
              ) : null}
              {!searchResult && !searchError ? (
                <div className="empty-state">
                  <div className="empty-state-icon"><Search size={20} /></div>
                  <div className="empty-state-text">输入教材术语、定义、题干片段或公式关键词后开始检索。</div>
                </div>
              ) : null}
              {searchResult ? (
                <>
                  <div className="audit-row">
                    <span>审计追踪号</span>
                    <strong>{searchResult.queryId}</strong>
                  </div>
                  <div className="hit-list">
                    {searchResult.hits.map((hit, index) => (
                      <EvidenceCard key={hit.chunkId} hit={hit} rank={index + 1} />
                    ))}
                  </div>
                </>
              ) : null}
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderTeaching() {
    return renderRequiresAuth(
      <>
        <div className="page-header">
          <h1 className="page-title">教学任务</h1>
          <p className="page-subtitle">AI 教学任务编排与讲义导出</p>
        </div>
        <div className="card-grid">
          <div className="card">
            <div className="card-header">
              <h2 className="card-title"><BookOpen size={16} /> 创建教学任务</h2>
            </div>
            <div className="card-body">
              <form className="search-form" onSubmit={handleTeachingTask}>
                <label>
                  <span>讲义主题 / 学习目标</span>
                  <input className="form-input" value={learningGoal} onChange={(e) => setLearningGoal(e.target.value)} placeholder="例如：双曲线从定义到大题小题方法" />
                </label>
                <label>
                  <span>题目 / 补充要求（可选）</span>
                  <input className="form-input" value={teachingQuestion} onChange={(e) => setTeachingQuestion(e.target.value)} placeholder="没有具体题目可以留空，后端会按主题生成讲义" />
                </label>
                <TemplateShelf
                  templates={teachingTemplates}
                  selectedCode={selectedTeachingTemplateCode}
                  loading={loadingTeachingTemplates}
                  onSelect={setSelectedTeachingTemplateCode}
                />
                <button className="btn btn-primary" type="submit" disabled={submittingTeachingTask}>
                  {submittingTeachingTask ? <Loader2 className="spin" size={16} /> : <Sparkles size={16} />}
                  <span>生成讲义任务</span>
                </button>
              </form>
            </div>
          </div>
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><FileText size={16} /> 讲义与反馈</h2>
            </div>
            <div className="card-body">
              <TeachingTaskPanel
                task={teachingTask}
                loading={loadingTeachingTask}
                error={teachingError}
                version={handoutVersion}
                previewLatex={handoutPreviewTaskId === `${teachingTask?.taskId}:${handoutVersion}` ? handoutPreviewLatex : ""}
                previewPdfUrl={handoutPreviewPdfTaskId === `${teachingTask?.taskId}:${handoutVersion}` ? handoutPreviewPdfUrl : ""}
                history={teachingHistory}
                loadingHistory={loadingTeachingHistory}
                action={handoutAction}
                exportMessage={handoutExportMessage}
                feedbackRating={feedbackRating}
                feedbackDecision={feedbackDecision}
                feedbackComment={feedbackComment}
                submittingFeedback={submittingFeedback}
                feedbackMessage={feedbackMessage}
                batchFolderPath={batchFolderPath}
                onVersionChange={(v) => {
                  setHandoutVersion(v);
                  setHandoutPreviewLatex("");
                  setHandoutPreviewTaskId("");
                  setHandoutPreviewPdfUrl((current) => {
                    if (current) URL.revokeObjectURL(current);
                    return "";
                  });
                  setHandoutPreviewPdfTaskId("");
                  setHandoutExportMessage("");
                }}
                onBatchFolderPathChange={setBatchFolderPath}
                onPreviewLatex={handlePreviewLatex}
                onPreviewPdf={handlePreviewPdf}
                onExportLatex={handleExportLatex}
                onExportPdf={handleExportPdf}
                onExportBatchZip={handleExportBatchZip}
                onSelectHistory={handleSelectTeachingHistory}
                onFeedbackRatingChange={setFeedbackRating}
                onFeedbackDecisionChange={setFeedbackDecision}
                onFeedbackCommentChange={setFeedbackComment}
                onSubmitFeedback={handleSubmitFeedback}
              />
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderAgents() {
    return renderRequiresAuth(
      <>
        <div className="page-header">
          <h1 className="page-title">AI 能力控制台</h1>
          <p className="page-subtitle">管理模型选择、真实调用、讲义生成能力和执行记录</p>
        </div>
        <div className="card-grid">
          <div className="card">
            <div className="card-header">
              <h2 className="card-title"><ShieldCheck size={16} /> 模型与工具</h2>
            </div>
            <div className="card-body">
              {agentModelCatalogError ? (
                <StatusLine icon={<AlertCircle size={16} />} text={agentModelCatalogError} tone="danger" />
              ) : null}
              <AgentModelHealthPanel
                health={agentModelHealth}
                error={agentModelHealthError}
                loading={checkingAgentModelHealth}
                expanded={showAgentModelHealth}
                onToggle={() => setShowAgentModelHealth((c) => !c)}
                onRefresh={refreshAgentModelHealth}
              />
              <div className="divider" />
              <form className="search-form agent-tool-form" onSubmit={handlePlanAgent}>
                <label>
                  <span>服务商</span>
                  <select className="form-select" value={agentProvider} onChange={(e) => handleAgentProviderChange(e.target.value)}>
                    {agentProviders(agentModelCatalog).map((provider) => (
                      <option key={provider} value={provider}>{providerOptionLabel(provider)}</option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>模型</span>
                  <select className="form-select" value={agentModel} onChange={(e) => setAgentModel(e.target.value)}>
                    {agentModelsForProvider(agentModelCatalog, agentProvider).map((model) => (
                      <option key={model.modelCode} value={model.modelCode}>{model.modelCode}</option>
                    ))}
                  </select>
                </label>
                <label className="toggle-row">
                  <input type="checkbox" checked={disablePrivateSearch} onChange={(e) => setDisablePrivateSearch(e.target.checked)} />
                  <span>关闭私有资料检索</span>
                </label>
                <label className="toggle-row">
                  <input type="checkbox" checked={disableTextbookSearch} onChange={(e) => setDisableTextbookSearch(e.target.checked)} />
                  <span>关闭教材检索</span>
                </label>
                <button className="btn btn-primary" type="submit" disabled={planningAgent || !agentProvider || !agentModel}>
                  {planningAgent ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
                  <span>生成运行预案</span>
                </button>
              </form>
              {agentPlanError ? <StatusLine icon={<AlertCircle size={16} />} text={agentPlanError} tone="danger" /> : null}
            </div>
          </div>
          <div className="card">
            <div className="card-header">
              <h2 className="card-title"><Bot size={16} /> 真实调用</h2>
            </div>
            <div className="card-body">
              <AgentPlanPanel
                plan={agentPlan}
                execution={agentExecution}
                loading={planningAgent}
                executing={executingAgent}
                error={agentPlanError || agentExecutionError}
                onExecute={handleExecuteAgentRun}
              />
            </div>
          </div>
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><Network size={16} /> 调用记录</h2>
              <button className="btn btn-ghost btn-sm" onClick={refreshAgentTraces} disabled={loadingAgentTraces}>
                <RefreshCw size={14} className={loadingAgentTraces ? "spin" : ""} />
              </button>
            </div>
            <div className="card-body">
              <AgentTracePanel
                traces={agentTraces}
                usageSummary={agentUsageSummary}
                diagnosticSummary={agentDiagnosticSummary}
                loading={loadingAgentTraces}
                error={agentTraceError}
                onRefresh={refreshAgentTraces}
              />
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderStreaming() {
    return renderRequiresAuth(
      <>
        <div className="page-header">
          <h1 className="page-title">讲义协作流程</h1>
          <p className="page-subtitle">从生成、审校到导出的讲义协作流程</p>
        </div>
        <div className="card-grid">
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><GitBranch size={16} /> 创建写作任务</h2>
            </div>
            <div className="card-body">
              <MultiAgentWritingPanel
                workflow={multiAgentWorkflow}
                traces={multiAgentWorkflowTraces}
                artifact={multiAgentArtifact}
                writingGoal={multiAgentWritingGoal}
                questionText={multiAgentWritingQuestion}
                providerName={agentProvider}
                modelCode={agentModel}
                modelReady={agentModelsForProvider(agentModelCatalog, agentProvider).some((m) => m.modelCode === agentModel)}
                starting={startingMultiAgentWriting}
                polling={pollingMultiAgentWriting}
                loadingArtifact={loadingMultiAgentArtifact}
                artifactError={multiAgentArtifactError}
                artifactMessage={multiAgentArtifactMessage}
                exportingArtifactFormat={exportingMultiAgentArtifact}
                pdfPreviewUrl={
                  multiAgentArtifactPdfWorkflowId === multiAgentWorkflow?.workflowId ? multiAgentArtifactPdfUrl : ""
                }
                error={multiAgentWritingError}
                onWritingGoalChange={setMultiAgentWritingGoal}
                onQuestionTextChange={setMultiAgentWritingQuestion}
                onSubmit={handleStartMultiAgentWriting}
                onRefresh={() => refreshMultiAgentWritingWorkflow()}
                onLoadArtifact={() => multiAgentWorkflow && loadMultiAgentArtifact(multiAgentWorkflow.workflowId)}
                onPreviewPdf={handlePreviewMultiAgentArtifactPdf}
                onExportArtifact={handleExportMultiAgentArtifact}
              />
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderKnowledge() {
    return renderRequiresAuth(
      <>
        <div className="page-header">
          <h1 className="page-title">知识库</h1>
          <p className="page-subtitle">知识图谱、知识点与题库管理</p>
        </div>
        <div className="card-grid">
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><BrainCircuit size={16} /> 知识库工作台</h2>
            </div>
            <div className="card-body">
              {hasVerifiedSession ? (
                <KnowledgeWorkspace key={authSession.tokenValue} api={api} />
              ) : (
                <div className="knowledge-workspace" style={{ display: "flex", alignItems: "center", justifyContent: "center", minHeight: 120 }}>
                  <StatusLine
                    icon={authSessionChecked ? <ShieldCheck size={16} /> : <Loader2 className="spin" size={16} />}
                    text={authSessionChecked ? "登录后加载知识库工作台。" : "正在检查后端会话"}
                  />
                </div>
              )}
            </div>
          </div>
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><Library size={16} /> 题库管理</h2>
            </div>
            <div className="card-body">
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
                loadingQuestions={loadingQuestionBank}
                error={knowledgeBankError}
                questionPage={questionBankPage}
                questionPageSize={questionBankPageSize}
                onKnowledgePointNameChange={setKnowledgePointName}
                onChapterPathChange={setKnowledgeChapterPath}
                onQuestionTitleChange={setQuestionTitle}
                onQuestionTextChange={setQuestionText}
                onQueryChange={(value) => { setQuestionBankQuery(value); setQuestionBankPage(1); }}
                onQuestionPageChange={setQuestionBankPage}
                onQuestionPageSizeChange={(value) => { setQuestionBankPageSize(value); setQuestionBankPage(1); }}
                onCreateKnowledgePoint={handleCreateKnowledgePoint}
                onCreateQuestion={handleCreateQuestionBankItem}
                onSearchQuestions={handleSearchQuestionBank}
              />
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderSettings() {
    return (
      <>
        <div className="page-header">
          <h1 className="page-title">系统设置</h1>
          <p className="page-subtitle">MCP 配置、后端连接与资源管理</p>
        </div>
        <div className="settings-grid">
          {authSession ? (
            <div className="card">
              <div className="card-header">
                <h2 className="card-title"><ShieldCheck size={16} /> 当前会话</h2>
              </div>
              <div className="card-body">
                <div className="auth-session">
                  <Check size={14} />
                  <span>{sessionRoleLabel(authSession.role)}</span>
                  <strong>{authSession.userId}</strong>
                  <span style={{ color: "var(--slate)", fontSize: 12, marginLeft: "auto" }}>已登录</span>
                </div>
              </div>
            </div>
          ) : null}
          <div className="card">
            <div className="card-header">
              <h2 className="card-title"><Globe size={16} /> MCP 配置</h2>
            </div>
            <div className="card-body">
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
              <McpConfigurationPanel
                configuration={mcpConfiguration}
                copyMessage={mcpCopyMessage}
                onCopy={handleCopyMcpConfiguration}
              />
            </div>
          </div>
          <div className="card">
            <div className="card-header">
              <h2 className="card-title"><Database size={16} /> 后端连接</h2>
            </div>
            <div className="card-body">
              <div className="backend-pill" style={{ display: "inline-flex" }}>
                <Database size={14} />
                <span>{DEFAULT_BACKEND_URL}</span>
              </div>
            </div>
          </div>
          <div className="card card-full">
            <div className="card-header">
              <h2 className="card-title"><FolderKanban size={16} /> 教师资源管理</h2>
              <button className="btn btn-ghost btn-sm" onClick={refreshTeacherResources} disabled={loadingTeacherResources}>
                <RefreshCw size={14} className={loadingTeacherResources ? "spin" : ""} />
              </button>
            </div>
            <div className="card-body">
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
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderLogin() {
    return (
      <div className="page-enter" style={{ display: "flex", justifyContent: "center", alignItems: "center", minHeight: "60vh" }}>
        <div className="card login-card" style={{ width: "100%", maxWidth: 400 }}>
          <div className="card-body" style={{ padding: "40px 32px" }}>
            <div style={{ textAlign: "center", marginBottom: 28 }}>
              <div className="nav-brand-icon" style={{ margin: "0 auto 12px", width: 48, height: 48, fontSize: 20 }}>M</div>
              <h2 style={{ fontFamily: "var(--font-display)", fontSize: 20, fontWeight: 700, marginBottom: 4 }}>欢迎登录</h2>
              <p style={{ fontSize: 14, color: "var(--slate)" }}>使用后端账号登录 Math Agent</p>
            </div>
            <form className="form" onSubmit={handleLogin}>
              <div className="form-row">
                <label className="form-label">账号</label>
                <input className="form-input" type="text" value={loginUsername} onChange={(e) => setLoginUsername(e.target.value)} placeholder="输入后端账号" autoComplete="username" />
              </div>
              <div className="form-row">
                <label className="form-label">密码</label>
                <input className="form-input" type="password" value={loginPassword} onChange={(e) => setLoginPassword(e.target.value)} placeholder="输入真实密码" autoComplete="current-password" />
              </div>
              {authError ? <StatusLine icon={<AlertCircle size={16} />} text={authError} tone="danger" /> : null}
              <button className="btn btn-primary" type="submit" disabled={loggingIn} style={{ width: "100%", justifyContent: "center", padding: "10px 16px", marginTop: 4 }}>
                {loggingIn ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
                <span>登录</span>
              </button>
            </form>
            {authSession ? (
              <div className="auth-session" style={{ marginTop: 16 }}>
                <Check size={14} />
                <span>已登录为</span>
                <strong>{authSession.userId}</strong>
                <span style={{ color: "var(--slate)", fontSize: 12 }}>({sessionRoleLabel(authSession.role)})</span>
              </div>
            ) : null}
          </div>
        </div>
      </div>
    );
  }

}

function LoginPrompt({ onLogin }: { onLogin: () => void }) {
  return (
    <div style={{
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      minHeight: "50vh",
      textAlign: "center",
    }}>
      <div className="card" style={{ maxWidth: 400, width: "100%" }}>
        <div className="card-body" style={{ padding: "40px 32px" }}>
          <div className="empty-state-icon" style={{ margin: "0 auto 16px", width: 56, height: 56 }}>
            <ShieldCheck size={24} />
          </div>
          <h2 style={{ fontFamily: "var(--font-display)", fontSize: 18, fontWeight: 600, marginBottom: 8 }}>
            欢迎使用 Math Agent
          </h2>
          <p style={{ fontSize: 14, color: "var(--slate)", marginBottom: 24, lineHeight: 1.6 }}>
            请登录您的账号以访问教学管理、AI 编排、知识库等功能。
          </p>
          <button className="btn btn-primary" onClick={onLogin} style={{ width: "100%", justifyContent: "center", padding: "10px 16px" }}>
            <ShieldCheck size={16} />
            前往登录
          </button>
        </div>
      </div>
    </div>
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
  if (!searchResult) return [];
  return searchResult.hits.slice(0, 3).map((hit) => `PUBLIC_TEXTBOOK:${hit.docId}:${hit.chunkId}`);
}

function isWorkflowNotFound(error: Error) {
  return error.message.includes("Backend request failed: 404")
    && error.message.includes("/api/agents/writing/");
}

function toUserFacingError(error: Error) {
  const message = error.message || "";
  if (message.includes("Backend request failed: 403")) {
    return "当前账号没有执行这个操作的权限，请切换教师或管理员账号。";
  }
  if (message.includes("Backend request failed: 404")) {
    return "后端没有找到对应记录，请刷新页面后重试。";
  }
  if (message.includes("Backend request failed: 429")) {
    return "当前模型或任务队列繁忙，请稍后重试。";
  }
  if (message.includes("Failed to fetch") || message.includes("NetworkError")) {
    return "无法连接后端服务，请确认本机后端已经启动。";
  }
  return message.replace(/^Backend request failed:\s*/i, "").slice(0, 220);
}

function downloadText(fileName: string, content: string, mimeType: string) {
  downloadBlob(fileName, new Blob([content], { type: mimeType }));
}

function downloadBytes(fileName: string, bytes: Uint8Array, mimeType: string) {
  const arrayBuffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(arrayBuffer).set(bytes);
  downloadBlob(fileName, new Blob([arrayBuffer], { type: mimeType }));
}

function base64ToBytes(base64: string) {
  const binary = globalThis.atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function TemplateShelf({
  templates,
  selectedCode,
  loading,
  onSelect,
}: {
  templates: TeachingHandoutTemplateResponse[];
  selectedCode: string;
  loading: boolean;
  onSelect: (templateCode: string) => void;
}) {
  if (loading) {
    return (
      <div className="template-shelf">
        <div className="template-shelf-head">
          <span>讲义模板</span>
          <small>加载中</small>
        </div>
        <div className="template-card template-card-empty">
          <Loader2 className="spin" size={16} />
          <span>正在读取模板书架</span>
        </div>
      </div>
    );
  }
  if (!templates.length) {
    return (
      <div className="template-shelf">
        <div className="template-shelf-head">
          <span>讲义模板</span>
          <small>后端未返回模板</small>
        </div>
      </div>
    );
  }
  return (
    <div className="template-shelf">
      <div className="template-shelf-head">
        <span>讲义模板</span>
        <small>选择后会影响 AI 提示词、双版本和导出结构</small>
      </div>
      <div className="template-shelf-grid">
        {templates.map((template) => {
          const selected = template.templateCode === selectedCode;
          const localReference = template.sourceType === "local_reference" || Boolean(template.referenceTitle);
          return (
            <article
              key={template.templateCode}
              className={[
                "template-card",
                selected ? "selected" : "",
                localReference ? "template-card-reference" : "",
              ].filter(Boolean).join(" ")}
            >
              <button type="button" className="template-card-select" onClick={() => onSelect(template.templateCode)}>
                <div className="template-card-top">
                  <span>{template.category || audienceLabel(template.audience)}</span>
                  <strong>{template.visualStyle || sourceTypeLabel(template.sourceType)}</strong>
                </div>
                <h3>{template.displayName}</h3>
                <p>{template.description}</p>
                {localReference ? (
                  <div className="template-reference-strip">
                    <span>本机 PDF</span>
                    <strong>{template.referenceTitle || template.displayName}</strong>
                  </div>
                ) : null}
                <div className="template-chip-row">
                  <span>{audienceLabel(template.audience)}</span>
                  {(template.difficultyBands ?? []).slice(0, 3).map((item) => <span key={item}>{item}</span>)}
                </div>
                {template.tags?.length ? (
                  <div className="template-tag-row">
                    {template.tags.slice(0, 3).map((tag) => <small key={tag}>{tag}</small>)}
                  </div>
                ) : null}
              </button>
              {template.referencePreview?.trim() ? (
                <details className="template-reference-preview">
                  <summary>查看结构摘要</summary>
                  <p>{compactText(template.referencePreview, 160)}</p>
                </details>
              ) : null}
            </article>
          );
        })}
      </div>
    </div>
  );
}

function audienceLabel(audience?: string | null) {
  const normalized = (audience ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    teacher: "教师版",
    student: "学生版",
    mixed: "双版本",
  };
  return labels[normalized] ?? "通用";
}

function sourceTypeLabel(sourceType?: string | null) {
  const normalized = (sourceType ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    builtin: "内置",
    local_reference: "本机参考",
    pdf: "PDF 模板",
    latex: "LaTeX 模板",
  };
  return labels[normalized] ?? "模板";
}

function exportLabel(format: "markdown" | "latex" | "pdf" | "zip") {
  if (format === "markdown") return "正文文件";
  if (format === "latex") return "TeX 源文件";
  if (format === "pdf") return "PDF 文件";
  return "ZIP 打包文件";
}

function sessionRoleLabel(role?: string | null) {
  const normalized = (role ?? "").trim().toLowerCase();
  const labels: Record<string, string> = {
    admin: "管理员",
    teacher: "教师",
    student: "学生",
    guest: "访客",
  };
  return labels[normalized] ?? (role?.trim() || "未登录");
}

function decisionLabel(decision: string) {
  const labels: Record<string, string> = {
    helpful: "可用",
    confusing: "不清楚",
    needs_revision: "需要修改",
  };
  return labels[decision] ?? decision;
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
  return catalog?.providers.filter((provider) => provider.enabled).map((provider) => provider.name) ?? [];
}

function providerOptionLabel(provider: string) {
  const labels: Record<string, string> = {
    openai: "OpenAI",
    dashscope: "通义千问",
    deepseek: "DeepSeek",
    ark: "火山方舟",
  };
  return labels[provider] ?? provider;
}

function agentModelsForProvider(
  catalog: AgentModelCatalogResponse | null,
  providerName: string,
): { modelCode: string; modelLevel: string; priceTier: string }[] {
  const provider = catalog?.providers.find((candidate) => candidate.name === providerName && candidate.enabled);
  return provider && provider.models.length > 0 ? provider.models : [];
}
