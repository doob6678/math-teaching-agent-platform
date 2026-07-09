import {
  AlertCircle, BookOpen, Bot, BrainCircuit, Check, ChevronDown, Database,
  Eye, FileText, FolderKanban, GitBranch, Globe, GraduationCap, Home,
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
  McpClientKeyCreatedResponse,
  McpClientKeyResponse,
  McpConnectionTestResult,
  McpConfigurationResponse,
  MultiAgentWritingArtifact,
  MultiAgentWritingResponse,
  MultiAgentWritingTraceResponse,
  QuestionBankItemResponse,
  RetrievalAuditDetail,
  StudentDashboardResponse,
  StudentExplanationHistoryItem,
  StudentExplanationImageUploadResponse,
  StudentExplanationResponse,
  StudentExplanationStreamEvent,
  TeachingHandoutVersion,
  TeachingHandoutPdfResponse,
  TeachingHandoutTemplateResponse,
  TeachingHumanFeedbackResponse,
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
  UNTITLED_TEACHER_RESOURCE_TITLE,
  VectorIndexRebuildResponse,
  LoginResponse,
  createTextbookApiClient,
  deriveTeacherResourceTitle,
} from "../shared/api/textbookApi";
import { AgentModelHealthPanel, AgentPlanPanel, AgentTracePanel } from "./components/AgentPanels";
import { AuditDetailPanel, EvidenceCard } from "./components/EvidencePanels";
import { KnowledgeQuestionBankPanel } from "./components/KnowledgeQuestionBankPanel";
import {
  McpConnectionTestPanel,
  McpConfigurationForm,
  McpConfigurationPanel,
  McpIdentityBoundaryCard,
  McpKeyVaultPanel,
} from "./components/McpPanels";
import { MultiAgentWritingPanel } from "./components/MultiAgentWritingPanel";
import { HandoutCollaborationPanel, HandoutCollaborationThreadItem } from "./components/HandoutCollaborationPanel";
import { HandoutWorkspacePreviewPanel } from "./components/HandoutWorkspacePreviewPanel";
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
import { TeachingConversationPanel, TeachingConversationThreadItem } from "./components/TeachingConversationPanel";
import { SyncCheckpointView, TeacherResourcePanel } from "./components/TeacherResourcePanel";
import { PdfCanvasPreview } from "./components/PdfCanvasPreview";
import { KnowledgeWorkspace } from "./knowledge/KnowledgeWorkspace";

export { MultiAgentWritingPanel } from "./components/MultiAgentWritingPanel";
export { StudentDashboardPanel } from "./components/StudentDashboardPanel";
export { SyncCheckpointView } from "./components/TeacherResourcePanel";
export { AgentTracePanel } from "./components/AgentPanels";
export { statusClass, statusTone } from "./components/panelShared";

const DEFAULT_BACKEND_URL = import.meta.env.VITE_BACKEND_URL ?? "http://127.0.0.1:8080";
const TEACHING_TASK_STORAGE_KEY = "math-agent:last-teaching-task-id";
const MULTI_AGENT_WORKFLOW_STORAGE_KEY = "math-agent:last-multi-agent-workflow-id";
const TEACHING_CONVERSATION_STORAGE_KEY = "math-agent:teaching-conversation-thread";
const HANDOUT_COLLABORATION_STORAGE_KEY = "math-agent:handout-collaboration-thread";

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

type PageId = "dashboard" | "search" | "teaching" | "agents" | "streaming" | "knowledge" | "mcp" | "settings" | "login";

type TeachingPdfPreviewVisualEvidence = {
  artifactType: "browser_pdf_canvas";
  captured: boolean;
  selector: string;
  version: TeachingHandoutVersion;
  imageRef: string;
  previewImageDataUrl?: string;
  previewState: string;
  page: number;
  pixelWidth: number;
  pixelHeight: number;
  cssWidth: number;
  cssHeight: number;
  attachToAiReview: boolean;
  aiAttachmentPlan: string;
  reason?: string;
};

interface NavItem {
  id: PageId;
  label: string;
  icon: React.ReactNode;
}

type TeachingConversationImageDraft = StudentExplanationImageUploadResponse & {
  previewUrl: string;
};

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
  const [teachingConversationInput, setTeachingConversationInput] = useState("");
  const [teachingConversationId, setTeachingConversationId] = useState(() => readStoredTeachingConversation().conversationId);
  const [teachingConversationHistory, setTeachingConversationHistory] = useState<StudentExplanationHistoryItem[]>([]);
  const [teachingConversationEntries, setTeachingConversationEntries] =
    useState<TeachingConversationThreadItem[]>(() => readStoredTeachingConversation().entries);
  const [teachingConversationImageDraft, setTeachingConversationImageDraft] = useState<TeachingConversationImageDraft | null>(null);
  const [uploadingTeachingConversationImage, setUploadingTeachingConversationImage] = useState(false);
  const [teachingConversationImageError, setTeachingConversationImageError] = useState("");
  const [handoutCollaborationEntries, setHandoutCollaborationEntries] =
    useState<HandoutCollaborationThreadItem[]>(() => readStoredHandoutCollaboration());
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
  const [handoutPreviewPdfBytes, setHandoutPreviewPdfBytes] = useState<Uint8Array | null>(null);
  const [handoutPreviewPdfTaskId, setHandoutPreviewPdfTaskId] = useState("");
  const [handoutPreviewPdfMeta, setHandoutPreviewPdfMeta] = useState<TeachingHandoutPdfResponse | null>(null);
  const [teachingHistory, setTeachingHistory] = useState<TeachingTaskResponse[]>([]);
  const [loadingTeachingHistory, setLoadingTeachingHistory] = useState(false);
  const [openingTeachingHistoryTaskId, setOpeningTeachingHistoryTaskId] = useState("");
  const [handoutVersion, setHandoutVersion] = useState<TeachingHandoutVersion>("teacher");
  const [handoutAction, setHandoutAction] = useState("");
  const [handoutExportMessage, setHandoutExportMessage] = useState("");
  const [feedbackRating, setFeedbackRating] = useState(4);
  const [feedbackDecision, setFeedbackDecision] = useState("needs_revision");
  const [feedbackComment, setFeedbackComment] = useState("");
  const [submittingFeedback, setSubmittingFeedback] = useState(false);
  const [feedbackMessage, setFeedbackMessage] = useState("");
  const [feedbackHistory, setFeedbackHistory] = useState<TeachingHumanFeedbackResponse[]>([]);
  const [loadingFeedbackHistory, setLoadingFeedbackHistory] = useState(false);
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
  const [mcpKeys, setMcpKeys] = useState<McpClientKeyResponse[]>([]);
  const [mcpLatestCreatedKey, setMcpLatestCreatedKey] = useState<McpClientKeyCreatedResponse | null>(null);
  const [mcpConfiguration, setMcpConfiguration] = useState<McpConfigurationResponse | null>(null);
  const [mcpCreating, setMcpCreating] = useState(false);
  const [mcpLoadingKeys, setMcpLoadingKeys] = useState(false);
  const [mcpRevokingKeyId, setMcpRevokingKeyId] = useState("");
  const [mcpCopyMessage, setMcpCopyMessage] = useState("");
  const [mcpError, setMcpError] = useState("");
  const [mcpTesting, setMcpTesting] = useState(false);
  const [mcpTestError, setMcpTestError] = useState("");
  const [mcpTestResult, setMcpTestResult] = useState<McpConnectionTestResult | null>(null);
  const [teachingError, setTeachingError] = useState("");
  const [studentDashboardError, setStudentDashboardError] = useState("");
  const [teacherResourceError, setTeacherResourceError] = useState("");
  const [knowledgeBankError, setKnowledgeBankError] = useState("");
  const [authError, setAuthError] = useState("");
  const [resourceTitle, setResourceTitle] = useState("");
  const [resourceLocation, setResourceLocation] = useState("");
  const [resourceSourceType, setResourceSourceType] = useState("teacher_resource");
  const [resourceScope, setResourceScope] = useState("TEACHER_PRIVATE");
  const [feishuExportFormat, setFeishuExportFormat] = useState<"md" | "docx" | "pdf">("md");
  const [resourceParseMode, setResourceParseMode] = useState<"TEXT" | "AI">("TEXT");
  const [resourceFiles, setResourceFiles] = useState<File[]>([]);
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
  const [submittingTeachingConversation, setSubmittingTeachingConversation] = useState(false);
  const [loadingTeachingConversationHistory, setLoadingTeachingConversationHistory] = useState(false);
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
    if (authSession?.role === "student") {
      loadStudentDashboard();
      return;
    }
    setStudentDashboard(null);
    setStudentDashboardError("");
  }, [api, hasVerifiedSession, authSession?.role]);

  useEffect(() => {
    if (!authSession) {
      setMcpKeys([]);
      setMcpConfiguration(null);
      setMcpLatestCreatedKey(null);
      return;
    }
    if (activePage !== "mcp") {
      return;
    }
    refreshMcpState();
  }, [api, activePage, authSession?.tokenValue]);

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
    if (authSession?.role !== "student" && !requestedStudentId) {
      setStudentDashboardError("请输入学生 ID 后再刷新画像。");
      return;
    }
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
    if (authSession?.role !== "student" && !requestedStudentId) {
      setStudentDashboardError("请输入要查看的学生 ID，例如 student-001。");
      return;
    }
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
    refreshTeachingConversationHistory();
  }, [api, hasVerifiedSession]);

  useEffect(() => {
    globalThis.localStorage?.setItem(
      TEACHING_CONVERSATION_STORAGE_KEY,
      JSON.stringify({
        conversationId: teachingConversationId,
        entries: sanitizeTeachingConversationEntries(teachingConversationEntries),
      }),
    );
  }, [teachingConversationEntries, teachingConversationId]);

  useEffect(() => {
    globalThis.localStorage?.setItem(
      HANDOUT_COLLABORATION_STORAGE_KEY,
      JSON.stringify(handoutCollaborationEntries),
    );
  }, [handoutCollaborationEntries]);

  useEffect(() => {
    if (!hasVerifiedSession) return;
    const taskId = window.localStorage.getItem(TEACHING_TASK_STORAGE_KEY);
    if (!taskId) return;
    setLoadingTeachingTask(true);
    api
      .getTeachingTask(taskId)
      .then((task) => {
        syncSelectedTeachingTemplate(task);
        setTeachingTask(task);
        loadTeachingFeedbackHistory(task.taskId);
        if (task.status === "CREATED" || task.status === "RUNNING") {
          pollTeachingTask(task.taskId);
        }
      })
      .catch((error: Error) => {
        if (isBackendNotFound(error)) {
          window.localStorage.removeItem(TEACHING_TASK_STORAGE_KEY);
          setTeachingTask(null);
          setTeachingError("");
          return;
        }
        setTeachingError(toUserFacingError(error));
      })
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
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
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

  function loadTeachingFeedbackHistory(taskId: string) {
    if (!taskId) {
      setFeedbackHistory([]);
      return;
    }
    setLoadingFeedbackHistory(true);
    api
      .listTeachingHumanFeedback(taskId)
      .then(setFeedbackHistory)
      .catch(() => setFeedbackHistory([]))
      .finally(() => setLoadingFeedbackHistory(false));
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

  function upsertHandoutCollaborationTask(task: TeachingTaskResponse, requestId?: string) {
    setHandoutCollaborationEntries((current) => {
      const updated = current.map((entry) => {
        if (entry.role !== "assistant") {
          return entry;
        }
        if (entry.taskId === task.taskId || (requestId && entry.id === `assistant-pending:${requestId}`)) {
          return {
            id: `assistant:${task.taskId}`,
            role: "assistant" as const,
            taskId: task.taskId,
            task,
            createdAt: entry.createdAt,
          };
        }
        return entry;
      });
      const exists = updated.some((entry) => entry.role === "assistant" && entry.taskId === task.taskId);
      if (exists) {
        return updated;
      }
      return [
        ...updated,
        {
          id: `assistant:${task.taskId}`,
          role: "assistant" as const,
          taskId: task.taskId,
          task,
          createdAt: new Date().toISOString(),
        },
      ];
    });
  }

  function handleTeachingTask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!learningGoal.trim()) { setTeachingError("请输入讲义主题或学习目标。"); return; }
    const clientRequestId = globalThis.crypto.randomUUID();
    const submittedGoal = learningGoal.trim();
    const submittedQuestion = teachingQuestion.trim();
    const selectedTemplate = teachingTemplates.find((item) => item.templateCode === selectedTeachingTemplateCode);
    setSubmittingTeachingTask(true);
    setTeachingError("");
    setHandoutCollaborationEntries((current) => [
      ...current,
      {
        id: `user:${clientRequestId}`,
        role: "user" as const,
        learningGoal: submittedGoal,
        questionText: submittedQuestion || undefined,
        templateName: selectedTemplate?.displayName ?? "标准讲义模板",
        evidenceLimit: limit,
        createdAt: new Date().toISOString(),
      },
      {
        id: `assistant-pending:${clientRequestId}`,
        role: "assistant" as const,
        createdAt: new Date().toISOString(),
        loading: true,
      },
    ]);
    api
      .submitTeachingTask({
        clientRequestId,
        questionText: submittedQuestion || undefined,
        learningGoal: submittedGoal,
        evidenceLimit: limit,
        handoutTemplateCode: selectedTeachingTemplateCode || undefined,
      })
      .then((task) => {
        syncSelectedTeachingTemplate(task);
        window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
        setTeachingTask(task);
        setHandoutPreviewLatex("");
        setHandoutPreviewTaskId("");
        setHandoutPreviewPdfUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          return "";
        });
        setHandoutPreviewPdfBytes(null);
        setHandoutPreviewPdfTaskId("");
        setHandoutPreviewPdfMeta(null);
        setHandoutExportMessage("");
        setFeedbackMessage("");
        setFeedbackHistory([]);
        upsertHandoutCollaborationTask(task, clientRequestId);
        refreshTeachingHistory();
        loadTeachingFeedbackHistory(task.taskId);
        if (task.status !== "COMPLETED") {
          pollTeachingTask(task.taskId);
        }
      })
      .catch((error: Error) => {
        const message = toUserFacingError(error);
        setTeachingError(message);
        setHandoutCollaborationEntries((current) =>
          current.map((entry) =>
            entry.id === `assistant-pending:${clientRequestId}`
              ? {
                  id: `assistant-error:${clientRequestId}`,
                  role: "assistant" as const,
                  createdAt: entry.createdAt,
                  error: message,
                }
              : entry,
          ),
        );
      })
      .finally(() => setSubmittingTeachingTask(false));
  }

  function refreshTeachingConversationHistory(conversationId?: string) {
    setLoadingTeachingConversationHistory(true);
    return api
      .getStudentExplanationHistory(conversationId || teachingConversationId || undefined, 12)
      .then((history) => setTeachingConversationHistory(history.items))
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
      .finally(() => setLoadingTeachingConversationHistory(false));
  }

  function handleTeachingConversation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submittedQuestion = teachingConversationInput.trim();
    const submittedImage = teachingConversationImageDraft;
    if (!submittedQuestion && !submittedImage) {
      setTeachingError("请输入题目，或先上传题图。");
      return;
    }
    const requestId = globalThis.crypto.randomUUID();
    const pendingAssistantId = `assistant-pending:${requestId}`;
    setSubmittingTeachingConversation(true);
    setTeachingError("");
    setTeachingConversationImageError("");
    // 发送后立刻清空输入区的题图草稿，避免底部上传条在请求期间继续占位。
    setTeachingConversationImageDraft(null);
    setTeachingConversationEntries((current) => [
      ...current,
      {
        id: `user:${requestId}`,
        role: "user",
        questionText: submittedQuestion || "图片讲题",
        imagePreviewUrl: submittedImage?.previewUrl,
        imageFileName: submittedImage?.originalFileName,
        imageStatus: submittedImage?.imageStatus,
        createdAt: new Date().toISOString(),
      },
      {
        id: pendingAssistantId,
        role: "assistant",
        questionText: submittedQuestion,
        imagePreviewUrl: submittedImage?.previewUrl,
        imageFileName: submittedImage?.originalFileName,
        imageStatus: submittedImage?.imageStatus,
        loading: true,
        progress: undefined,
        createdAt: new Date().toISOString(),
      },
    ]);
    setTeachingConversationInput("");
    api
      .streamStudentQuestion({
        conversationId: teachingConversationId || undefined,
        questionText: submittedQuestion || undefined,
        imageUploadId: submittedImage?.uploadId,
        imageFileName: submittedImage?.originalFileName,
        imageContentType: submittedImage?.contentType,
        imageSizeBytes: submittedImage?.sizeBytes,
        searchTextbook: true,
        searchKnowledgeGraph: true,
        searchTeacherResources: authSession?.role === "teacher" || authSession?.role === "admin",
        maxTextbookHits: 5,
        maxTeacherResourceHits: 3,
      }, (_eventName: string, payload: StudentExplanationStreamEvent) => {
        if (!payload.progress) {
          return;
        }
        if (payload.progress.conversationId) {
          setTeachingConversationId(payload.progress.conversationId);
        }
        setTeachingConversationEntries((current) =>
          current.map((entry) =>
            entry.id === pendingAssistantId
              ? {
                  ...entry,
                  progress: payload.progress ?? undefined,
                  imageStatus: payload.progress?.imageStatus || entry.imageStatus,
                }
              : entry,
          ),
        );
      })
      .then((response: StudentExplanationResponse) => {
        setTeachingConversationId(response.conversationId);
        setTeachingConversationEntries((current) =>
          current.map((entry) =>
            entry.id === pendingAssistantId
              ? {
                  id: `assistant:${response.explanationId}`,
                  role: "assistant",
                  questionText: submittedQuestion || "图片讲题",
                  imagePreviewUrl: submittedImage?.previewUrl,
                  imageFileName: submittedImage?.originalFileName,
                  imageStatus: response.imageStatus || submittedImage?.imageStatus,
                  response,
                  createdAt: new Date().toISOString(),
                }
              : entry,
          ),
        );
        return refreshTeachingConversationHistory(response.conversationId);
      })
      .catch((error: Error) => {
        const message = toUserFacingError(error);
        setTeachingError(message);
        setTeachingConversationEntries((current) =>
          current.map((entry) =>
            entry.id === pendingAssistantId
              ? {
                  id: `assistant-error:${requestId}`,
                  role: "assistant",
                  questionText: submittedQuestion || "图片讲题",
                  imagePreviewUrl: submittedImage?.previewUrl,
                  imageFileName: submittedImage?.originalFileName,
                  imageStatus: submittedImage?.imageStatus,
                  error: message,
                  createdAt: new Date().toISOString(),
                }
              : entry,
          ),
        );
      })
      .finally(() => setSubmittingTeachingConversation(false));
  }

  function clearTeachingConversationImage() {
    setTeachingConversationImageDraft(null);
    setTeachingConversationImageError("");
  }

  function handleTeachingConversationImageUpload(file: File | null) {
    if (!file) {
      return;
    }
    setUploadingTeachingConversationImage(true);
    setTeachingConversationImageError("");
    api
      .uploadStudentExplanationImage(file)
      .then(async (uploaded) => {
        setTeachingConversationImageDraft({
          ...uploaded,
          previewUrl: await fileToDataUrl(file),
        });
      })
      .catch((error: Error) => setTeachingConversationImageError(toImageUploadError(error)))
      .finally(() => setUploadingTeachingConversationImage(false));
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
        if (session.role === "student") {
          loadStudentDashboard();
        } else {
          setStudentDashboard(null);
        }
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
    const startedAt = Date.now();
    const poll = (attempt = 0) => {
      api.getTeachingTask(taskId).then((task) => {
        syncSelectedTeachingTemplate(task);
        setTeachingTask(task);
        upsertHandoutCollaborationTask(task);
        if (task.status === "CREATED" || task.status === "RUNNING") {
          const elapsedSeconds = Math.floor((Date.now() - startedAt) / 1000);
          const nextDelay = elapsedSeconds < 20 ? 4000 : elapsedSeconds < 60 ? 7000 : 10000;
          globalThis.setTimeout(() => poll(attempt + 1), nextDelay);
          return;
        }
        refreshTeachingHistory();
      }).catch((error: Error) => {
        const message = error.message || "";
        const hitRateLimit = message.includes("Rate limit exceeded") || message.includes("429");
        const nextDelay = hitRateLimit ? 12000 : Math.min(12000, 5000 + attempt * 1000);
        globalThis.setTimeout(() => poll(attempt + 1), nextDelay);
      });
    };
    globalThis.setTimeout(() => poll(0), 2000);
  }

  function handleRegisterResource(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const feishuMode = resourceSourceType === "feishu";
    if (feishuMode && !resourceLocation.trim()) {
      setTeacherResourceError("请输入飞书 URL。");
      return;
    }
    if (!feishuMode && resourceFiles.length === 0 && !resourceLocation.trim()) {
      setTeacherResourceError("请上传文件、选择文件夹，或填写服务器本地路径。");
      return;
    }
    const effectiveTitle = deriveTeacherResourceTitle({
      title: resourceTitle,
      files: resourceFiles,
      originalUrl: feishuMode ? resourceLocation : undefined,
      localPath: feishuMode ? undefined : resourceLocation,
    });
    if (effectiveTitle === UNTITLED_TEACHER_RESOURCE_TITLE) {
      setTeacherResourceError("无法识别资源名称，请重新选择文件、文件夹或链接。");
      return;
    }
    setRegisteringResource(true);
    setTeacherResourceError("");
    const registerPromise = feishuMode
      ? api.registerTeacherResource({
        sourceType: resourceSourceType,
        title: effectiveTitle,
        originalUrl: resourceLocation.trim(),
        permissionScope: resourceScope,
        feishuExportFormat: feishuExportFormat,
        parseMode: resourceParseMode,
      })
      : resourceFiles.length > 0
        ? api.uploadTeacherResource({
          sourceType: resourceSourceType,
          title: effectiveTitle,
          permissionScope: resourceScope,
          parseMode: resourceParseMode,
          files: resourceFiles,
        })
        : api.registerTeacherResource({
          sourceType: resourceSourceType,
          title: effectiveTitle,
          localPath: resourceLocation.trim(),
          permissionScope: resourceScope,
          parseMode: resourceParseMode,
        });
    registerPromise
      .then((resource) => {
        setTeacherResources((current) => [resource, ...current]);
        setTeacherSyncJobs((current) => ({ ...current, [resource.documentId]: [] }));
        setResourceTitle("");
        setResourceLocation("");
        setResourceFiles([]);
        setFeishuDiscoveryResult(null);
      })
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
      .finally(() => setRegisteringResource(false));
  }

  /**
   * Keeps the upload list separate from the server-path field so browser uploads and operator-entered server paths can
   * share the same resource form without one mode accidentally leaking stale values into the other.
   */
  function handleResourceFilesChange(files: FileList | null) {
    const nextFiles = files ? Array.from(files) : [];
    const previousDerivedTitle = deriveTeacherResourceTitle({
      files: resourceFiles,
      originalUrl: resourceSourceType === "feishu" ? resourceLocation : undefined,
      localPath: resourceSourceType === "feishu" ? undefined : resourceLocation,
    });
    const nextDerivedTitle = deriveTeacherResourceTitle({ files: nextFiles });
    setResourceFiles(nextFiles);
    setResourceTitle((current) => {
      const normalizedCurrent = current.trim();
      if (
        normalizedCurrent.length > 0
        && normalizedCurrent !== previousDerivedTitle
      ) {
        return current;
      }
      return nextDerivedTitle === UNTITLED_TEACHER_RESOURCE_TITLE ? current : nextDerivedTitle;
    });
    setTeacherResourceError("");
  }

  function handleResourceLocationChange(value: string) {
    const previousDerivedTitle = deriveTeacherResourceTitle({
      originalUrl: resourceSourceType === "feishu" ? resourceLocation : undefined,
      localPath: resourceSourceType === "feishu" ? undefined : resourceLocation,
    });
    const nextDerivedTitle = deriveTeacherResourceTitle({
      originalUrl: resourceSourceType === "feishu" ? value : undefined,
      localPath: resourceSourceType === "feishu" ? undefined : value,
    });
    setResourceLocation(value);
    setResourceTitle((current) => {
      const normalizedCurrent = current.trim();
      if (
        normalizedCurrent.length > 0
        && normalizedCurrent !== previousDerivedTitle
      ) {
        return current;
      }
      return nextDerivedTitle === UNTITLED_TEACHER_RESOURCE_TITLE ? current : nextDerivedTitle;
    });
    setTeacherResourceError("");
  }

  function handleResourceSourceTypeChange(value: string) {
    setResourceSourceType(value);
    setTeacherResourceError("");
    if (value === "feishu") {
      setResourceFiles([]);
      return;
    }
    setFeishuDiscoveryResult(null);
  }

  function handleArchiveResource(documentId: string) {
    setTeacherResourceError("");
    api
      .archiveTeacherResource(documentId)
      .then(() => setTeacherResources((current) => current.filter((r) => r.documentId !== documentId)))
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)));
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
      .catch((error: Error) => setKnowledgeBankError(toUserFacingError(error)))
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
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
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
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
      .finally(() => setSyncingResourceId(""));
  }

  function handleImportTeacherResourceQuestions(documentId: string) {
    setImportingResourceId(documentId);
    setTeacherResourceImportResult(null);
    setTeacherResourceError("");
    api
      .importTeacherResourceQuestions(documentId)
      .then((result) => { setTeacherResourceImportResult(result); setQuestionBankItems((c) => [...result.importedQuestions, ...c]); })
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
      .finally(() => setImportingResourceId(""));
  }

  function handleRebuildTeacherResourceIndex(documentId: string) {
    setRebuildingResourceId(documentId);
    setTeacherResourceIndexRebuildResult(null);
    setTeacherResourceError("");
    api
      .rebuildTeacherResourceVectorIndex(documentId)
      .then((result) => { setTeacherResourceIndexRebuildResult(result); return refreshTeacherResources(); })
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
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
        return api.getTeacherResourceBlockSearchAudit(result.queryId).then(setTeacherBlockSearchAudit).catch((e) => setTeacherResourceError(toUserFacingError(e)));
      })
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
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
      .catch((error: Error) => setTeacherResourceError(toUserFacingError(error)))
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
    setHandoutPreviewPdfBytes(null);
    setHandoutPreviewPdfTaskId("");
    setHandoutPreviewPdfMeta(null);
    api
      .previewTeachingTaskLatex(teachingTask.taskId, handoutVersion)
      .then((latex) => { setHandoutPreviewLatex(latex); setHandoutPreviewTaskId(`${teachingTask.taskId}:${handoutVersion}`); })
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
      .finally(() => setHandoutAction(""));
  }

  function handlePreviewPdf() {
    if (!teachingTask) return;
    previewTeachingTaskPdf(teachingTask.taskId, handoutVersion);
  }

  function previewTeachingTaskPdf(taskId: string, version: TeachingHandoutVersion) {
    setHandoutAction("preview-pdf");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .previewTeachingTaskPdf(taskId, version)
      .then((pdf) => {
        setHandoutPreviewLatex("");
        setHandoutPreviewTaskId("");
        const bytes = new Uint8Array(pdf.bytes.byteLength);
        bytes.set(pdf.bytes);
        setHandoutPreviewPdfBytes(bytes);
        setHandoutPreviewPdfMeta(pdf);
        setHandoutPreviewPdfUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          return URL.createObjectURL(new Blob([bytes], { type: "application/pdf" }));
        });
        setHandoutPreviewPdfTaskId(`${taskId}:${version}`);
      })
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
      .finally(() => setHandoutAction(""));
  }

  function resolvePreviewHandoutVersion(
    task: TeachingTaskResponse,
    preferred: TeachingHandoutVersion,
  ) {
    const hasTeacher = Boolean(task.teacherHandoutLatex?.trim());
    const hasStudent = Boolean(task.studentHandoutLatex?.trim());
    const hasLecture = Boolean(task.lectureHandoutLatex?.trim());
    if (preferred === "lecture" && hasLecture) return "lecture";
    if (preferred === "teacher" && hasTeacher) return "teacher";
    if (preferred === "student" && hasStudent) return "student";
    if (hasLecture) return "lecture";
    if (hasTeacher) return "teacher";
    if (hasStudent) return "student";
    return preferred;
  }

  function focusTeachingTask(task: TeachingTaskResponse) {
    window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
    syncSelectedTeachingTemplate(task);
    setTeachingTask(task);
    loadTeachingFeedbackHistory(task.taskId);
    upsertHandoutCollaborationTask(task);
  }

  function previewHandoutTaskPdf(task: TeachingTaskResponse) {
    const resolvedVersion = resolvePreviewHandoutVersion(task, handoutVersion);
    if (resolvedVersion !== handoutVersion) {
      setHandoutVersion(resolvedVersion);
    }
    focusTeachingTask(task);
    previewTeachingTaskPdf(task.taskId, resolvedVersion);
  }

  function previewHandoutTaskLatex(task: TeachingTaskResponse) {
    const resolvedVersion = resolvePreviewHandoutVersion(task, handoutVersion);
    if (resolvedVersion !== handoutVersion) {
      setHandoutVersion(resolvedVersion);
    }
    focusTeachingTask(task);
    setHandoutAction("preview");
    setTeachingError("");
    setHandoutExportMessage("");
    setHandoutPreviewPdfUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setHandoutPreviewPdfBytes(null);
    setHandoutPreviewPdfTaskId("");
    setHandoutPreviewPdfMeta(null);
    api
      .previewTeachingTaskLatex(task.taskId, resolvedVersion)
      .then((latex) => {
        setHandoutPreviewLatex(latex);
        setHandoutPreviewTaskId(`${task.taskId}:${resolvedVersion}`);
        globalThis.setTimeout(() => {
          document.getElementById("handout-review-panel")?.scrollIntoView({ behavior: "smooth", block: "start" });
        }, 80);
      })
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
      .finally(() => setHandoutAction(""));
  }

  function exportHandoutTaskPdf(task: TeachingTaskResponse) {
    const resolvedVersion = resolvePreviewHandoutVersion(task, handoutVersion);
    if (resolvedVersion !== handoutVersion) {
      setHandoutVersion(resolvedVersion);
    }
    focusTeachingTask(task);
    setHandoutAction("pdf");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskPdf(task.taskId, resolvedVersion)
      .then((pdf) => {
        downloadBytes(`${task.taskId}-${resolvedVersion}.pdf`, pdf.bytes, "application/pdf");
        setHandoutExportMessage(
          pdf.renderer === "xelatex"
            ? "PDF 已下载，当前使用 XeLaTeX 渲染。"
            : "PDF 已下载，当前使用后备排版。",
        );
      })
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
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
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
      .finally(() => setHandoutAction(""));
  }

  function handleExportPdf() {
    if (!teachingTask) return;
    setHandoutAction("pdf");
    setTeachingError("");
    setHandoutExportMessage("");
    api
      .exportTeachingTaskPdf(teachingTask.taskId, handoutVersion)
      .then((pdf) => {
        downloadBytes(`${teachingTask.taskId}-${handoutVersion}.pdf`, pdf.bytes, "application/pdf");
        setHandoutExportMessage(`PDF 讲义已下载。${pdf.renderer === "xelatex" ? "已使用 XeLaTeX 编译。" : "当前使用后备排版。"}`);
      })
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
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
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
      .finally(() => setHandoutAction(""));
  }

  function clearHandoutPreview() {
    setHandoutPreviewLatex("");
    setHandoutPreviewTaskId("");
    setHandoutPreviewPdfUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setHandoutPreviewPdfBytes(null);
    setHandoutPreviewPdfTaskId("");
    setHandoutPreviewPdfMeta(null);
  }

  function handleSelectTeachingHistory(task: TeachingTaskResponse) {
    window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, task.taskId);
    setOpeningTeachingHistoryTaskId(task.taskId);
    setLoadingTeachingTask(true);
    clearHandoutPreview();
    setHandoutExportMessage("");
    setFeedbackMessage("");
    setTeachingError("");
    api
      .getTeachingTask(task.taskId)
      .then((latestTask) => {
        window.localStorage.setItem(TEACHING_TASK_STORAGE_KEY, latestTask.taskId);
        syncSelectedTeachingTemplate(latestTask);
        setTeachingTask(latestTask);
        upsertHandoutCollaborationTask(latestTask);
        setTeachingHistory((current) => [latestTask, ...current.filter((item) => item.taskId !== latestTask.taskId)]);
        loadTeachingFeedbackHistory(latestTask.taskId);
        if (latestTask.status === "CREATED" || latestTask.status === "RUNNING") {
          pollTeachingTask(latestTask.taskId);
        }
      })
      .catch((error: Error) => {
        if (isBackendNotFound(error)) {
          setTeachingHistory((current) => current.filter((item) => item.taskId !== task.taskId));
          if (window.localStorage.getItem(TEACHING_TASK_STORAGE_KEY) === task.taskId) {
            window.localStorage.removeItem(TEACHING_TASK_STORAGE_KEY);
          }
          if (teachingTask?.taskId === task.taskId) {
            setTeachingTask(null);
          }
          setTeachingError("这条历史讲义记录已经失效，已从列表移除。");
          return;
        }
        setTeachingError(toUserFacingError(error));
      })
      .finally(() => {
        setOpeningTeachingHistoryTaskId("");
        setLoadingTeachingTask(false);
      });
  }

  function syncSelectedTeachingTemplate(task: TeachingTaskResponse) {
    const templateCode = task.selectedTemplate?.templateCode;
    if (templateCode) {
      setSelectedTeachingTemplateCode(templateCode);
    }
  }

  function handleSubmitFeedback(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teachingTask) return;
    const selectedDraft = handoutDraftForVersion(teachingTask, handoutVersion);
    const reviewContext = buildTeachingFeedbackReviewContext(
      teachingTask,
      handoutVersion,
      selectedDraft,
      handoutPreviewPdfMeta,
      handoutPreviewPdfTaskId,
      captureTeachingPdfPreviewVisualEvidence(teachingTask.taskId, handoutVersion),
    );
    setSubmittingFeedback(true);
    setTeachingError("");
    setFeedbackMessage("");
    api
      .submitTeachingHumanFeedback(teachingTask.taskId, {
        rating: feedbackRating,
        decision: feedbackDecision,
        comment: feedbackComment.trim(),
        reviewContext,
      })
      .then((feedback) => {
        setFeedbackMessage(`反馈已记录：${decisionLabel(feedback.decision)} / ${feedback.rating} 星`);
        setFeedbackHistory((current) => [feedback, ...current.filter((item) => item.feedbackId !== feedback.feedbackId)]);
        setFeedbackComment("");
      })
      .catch((error: Error) => setTeachingError(toUserFacingError(error)))
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

  function refreshMcpState() {
    if (!authSession) {
      setMcpKeys([]);
      setMcpConfiguration(null);
      return;
    }
    setMcpLoadingKeys(true);
    setMcpError("");
    Promise.all([
      api.listMcpKeys().then(setMcpKeys),
      api
        .getMyMcpConfiguration()
        .then(setMcpConfiguration)
        .catch((error: Error) => {
          if (error.message.includes("no active MCP key")) {
            setMcpConfiguration(null);
            return;
          }
          throw error;
        }),
    ])
      .catch((error: Error) => setMcpError(error.message))
      .finally(() => setMcpLoadingKeys(false));
  }

  function handleCreateMcpKey() {
    setMcpCreating(true);
    setMcpError("");
    setMcpCopyMessage("");
    api
      .createMcpKey()
      .then((created) => {
        setMcpLatestCreatedKey(created);
        setMcpConfiguration(created.configuration);
        return api.listMcpKeys().then(setMcpKeys);
      })
      .catch((error: Error) => setMcpError(error.message))
      .finally(() => setMcpCreating(false));
  }

  function handleTestMcpConnection() {
    if (!mcpLatestCreatedKey?.secretKey) {
      setMcpTestError("请先创建一个新的 MCP key，再用这次返回的真实 secret 做握手测试。");
      setMcpTestResult(null);
      return;
    }
    setMcpTesting(true);
    setMcpTestError("");
    setMcpTestResult(null);
    api
      .testMcpConnection(mcpConfiguration?.url ?? `${DEFAULT_BACKEND_URL}/api/mcp`, mcpLatestCreatedKey.secretKey)
      .then(setMcpTestResult)
      .catch((error: Error) => setMcpTestError(error.message))
      .finally(() => setMcpTesting(false));
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

  function handleRevokeMcpKey(keyId: string) {
    setMcpRevokingKeyId(keyId);
    setMcpError("");
    api
      .revokeMcpKey(keyId)
      .then(() => {
        if (mcpLatestCreatedKey?.keyId === keyId) {
          setMcpLatestCreatedKey(null);
          setMcpTestResult(null);
        }
        refreshMcpState();
      })
      .catch((error: Error) => setMcpError(error.message))
      .finally(() => setMcpRevokingKeyId(""));
  }

  function handleCopyMcpConfiguration() {
    if (!mcpConfiguration?.configJson) return;
    if (!navigator.clipboard?.writeText) { setMcpCopyMessage("当前浏览器不支持剪贴板写入。"); return; }
    navigator.clipboard.writeText(mcpConfiguration.configJson).then(() => setMcpCopyMessage("MCP JSON 已复制。")).catch((error: Error) => setMcpCopyMessage(error.message));
  }

  function handleCopyLatestMcpSecret() {
    if (!mcpLatestCreatedKey?.secretKey) return;
    if (!navigator.clipboard?.writeText) { setMcpCopyMessage("当前浏览器不支持剪贴板写入。"); return; }
    navigator.clipboard.writeText(mcpLatestCreatedKey.secretKey).then(() => setMcpCopyMessage("MCP secret 已复制。")).catch((error: Error) => setMcpCopyMessage(error.message));
  }

  function handleLogout() {
    globalThis.localStorage?.removeItem("math-agent:auth-session");
    setAuthSession(null);
    setMcpKeys([]);
    setMcpConfiguration(null);
    setMcpLatestCreatedKey(null);
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
    { id: "teaching", label: "AI 讲题", icon: <BookOpen size={16} /> },
    { id: "agents", label: "AI 控制台", icon: <Bot size={16} /> },
    { id: "streaming", label: "讲义生成", icon: <GitBranch size={16} /> },
    { id: "knowledge", label: "知识库", icon: <BrainCircuit size={16} /> },
    { id: "mcp", label: "MCP 接入", icon: <Globe size={16} /> },
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
                  <button className="dropdown-item" onClick={() => navigate("mcp")}>
                    <Globe size={14} />
                    MCP 接入
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
          {activePage === "mcp" && renderMcp()}
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
                <button className="btn btn-secondary" onClick={() => navigate("teaching")}><BookOpen size={14} /> AI 讲题</button>
                <button className="btn btn-secondary" onClick={() => navigate("agents")}><Bot size={14} /> AI 控制台</button>
                <button className="btn btn-secondary" onClick={() => navigate("streaming")}><GitBranch size={14} /> 讲义生成</button>
              </div>
            </div>
          </div>
        </div>
      </>
    );
  }

  function renderRequiresAuth(children: React.ReactNode, loginReturnPage: PageId = "settings") {
    if (!hasVerifiedSession) {
      return <LoginPrompt onLogin={() => navigate(loginReturnPage)} />;
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
      <TeachingConversationPanel
        value={teachingConversationInput}
        entries={teachingConversationEntries}
        history={teachingConversationHistory}
        loading={submittingTeachingConversation}
        loadingHistory={loadingTeachingConversationHistory}
        error={teachingError}
        imageDraft={teachingConversationImageDraft}
        uploadingImage={uploadingTeachingConversationImage}
        imageError={teachingConversationImageError}
        onValueChange={setTeachingConversationInput}
        onSubmit={handleTeachingConversation}
        onImageSelect={handleTeachingConversationImageUpload}
        onClearImage={clearTeachingConversationImage}
      />,
    );
  }

  function renderAgents() {
    return renderRequiresAuth(
      <>
        <div className="page-header">
          <h1 className="page-title">AI 能力控制台</h1>
          <p className="page-subtitle">管理模型选择、真实调用、讲义生成能力和执行记录</p>
        </div>
        <div className="card-grid agent-console-grid">
          <div className="card agent-config-card">
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
          <div className="card agent-plan-card">
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
          <div className="card card-full agent-trace-card">
            <div className="card-header">
              <h2 className="card-title"><Network size={16} /> 调用记录</h2>
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
      <section className="handout-studio-shell">
        <div className="handout-studio-header">
          <div>
            <h1 className="page-title">讲义生成</h1>
            <p className="page-subtitle">教师讲义、学生讲义、PDF 预览和人工审查在同一工作区完成。</p>
          </div>
        </div>

        <div className="handout-studio-grid">
          <div className="handout-studio-main">
            <section className="handout-studio-pane">
              <HandoutCollaborationPanel
                learningGoal={learningGoal}
                questionText={teachingQuestion}
                evidenceLimit={limit}
                selectedTemplateName={
                  teachingTemplates.find((item) => item.templateCode === selectedTeachingTemplateCode)?.displayName
                  ?? "标准讲义模板"
                }
                currentTaskId={teachingTask?.taskId ?? ""}
                version={handoutVersion}
                entries={handoutCollaborationEntries}
                history={teachingHistory}
                loading={submittingTeachingTask}
                loadingHistory={loadingTeachingHistory}
                error={teachingError}
                onLearningGoalChange={setLearningGoal}
                onQuestionTextChange={setTeachingQuestion}
                onEvidenceLimitChange={setLimit}
                onSubmit={handleTeachingTask}
                onSelectHistory={handleSelectTeachingHistory}
                onPreviewPdf={previewHandoutTaskPdf}
                onPreviewLatex={previewHandoutTaskLatex}
                onExportPdf={exportHandoutTaskPdf}
              />
            </section>

            <section className="handout-studio-pane handout-template-pane">
              <div className="handout-pane-head">
                <h2 className="card-title"><BookOpen size={16} /> 模板书架</h2>
                <span>参考真实讲义版式，当前仅影响结构与排版风格。</span>
              </div>
              <TemplateShelf
                templates={teachingTemplates}
                selectedCode={selectedTeachingTemplateCode}
                loading={loadingTeachingTemplates}
                onSelect={setSelectedTeachingTemplateCode}
                loadPreviewImage={api.getTeachingHandoutTemplatePreviewImage}
                loadReferencePdf={api.getTeachingHandoutTemplateReferencePdf}
              />
            </section>
          </div>

          <aside className="handout-studio-pane handout-preview-pane">
            <div className="handout-pane-head">
              <h2 className="card-title"><Eye size={16} /> 讲义预览</h2>
              <span>支持真实 PDF 翻页、结构审查和教师/学生双版本切换。</span>
            </div>
            <div className="handout-preview-pane-body">
              <HandoutWorkspacePreviewPanel
                task={teachingTask}
                version={handoutVersion}
                previewLatex={handoutPreviewLatex}
                previewTaskKey={handoutPreviewTaskId}
                previewPdfUrl={handoutPreviewPdfUrl}
                previewPdfBytes={handoutPreviewPdfBytes}
                previewPdfMeta={handoutPreviewPdfMeta}
                previewPdfTaskKey={handoutPreviewPdfTaskId}
                action={handoutAction}
                exportMessage={handoutExportMessage}
                feedbackRating={feedbackRating}
                feedbackDecision={feedbackDecision}
                feedbackComment={feedbackComment}
                submittingFeedback={submittingFeedback}
                feedbackMessage={feedbackMessage}
                feedbackHistory={feedbackHistory}
                loadingFeedbackHistory={loadingFeedbackHistory}
                onVersionChange={setHandoutVersion}
                onPreviewPdf={handlePreviewPdf}
                onPreviewLatex={handlePreviewLatex}
                onExportPdf={handleExportPdf}
                onFeedbackRatingChange={setFeedbackRating}
                onFeedbackDecisionChange={setFeedbackDecision}
                onFeedbackCommentChange={setFeedbackComment}
                onSubmitFeedback={handleSubmitFeedback}
              />
            </div>
          </aside>
        </div>
      </section>
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

  function renderMcp() {
    return renderRequiresAuth(
      <>
        <div className="page-header">
          <h1 className="page-title">MCP 接入</h1>
          <p className="page-subtitle">后端按当前登录态生成个人 MCP key 和标准配置，前端不再传递身份或 secret 参数</p>
        </div>
        <div className="mcp-page-grid">
          <div className="card card-full mcp-identity-card">
            <div className="card-header">
              <h2 className="card-title"><ShieldCheck size={16} /> 当前账号边界</h2>
            </div>
            <div className="card-body">
              <McpIdentityBoundaryCard
                username={authSession?.username}
                userId={authSession?.userId}
                roleLabel={sessionRoleLabel(authSession?.role)}
                tenantId={authSession?.tenantId}
              />
            </div>
          </div>
          <div className="card mcp-config-card">
            <div className="card-header">
              <h2 className="card-title"><Globe size={16} /> Key 管理</h2>
            </div>
            <div className="card-body">
              <McpConfigurationForm
                creating={mcpCreating}
                loadingKeys={mcpLoadingKeys}
                error={mcpError}
                latestCreatedKey={mcpLatestCreatedKey}
                onCreate={handleCreateMcpKey}
                onRefresh={refreshMcpState}
              />
              <McpKeyVaultPanel
                keys={mcpKeys}
                latestCreatedKey={mcpLatestCreatedKey}
                revokingKeyId={mcpRevokingKeyId}
                loading={mcpLoadingKeys}
                copyMessage={mcpCopyMessage}
                onCopyLatestSecret={handleCopyLatestMcpSecret}
                onRevokeKey={handleRevokeMcpKey}
              />
            </div>
          </div>
          <div className="card mcp-result-card">
            <div className="card-header">
              <h2 className="card-title"><Network size={16} /> 配置与连接</h2>
            </div>
            <div className="card-body">
              <McpConnectionTestPanel
                testing={mcpTesting}
                result={mcpTestResult}
                error={mcpTestError}
                onTest={handleTestMcpConnection}
                ready={Boolean(mcpLatestCreatedKey?.secretKey)}
              />
              <McpConfigurationPanel
                configuration={mcpConfiguration}
                copyMessage={mcpCopyMessage}
                onCopy={handleCopyMcpConfiguration}
              />
            </div>
          </div>
        </div>
      </>,
      "mcp",
    );
  }

  function renderSettings() {
    return (
      <>
        <div className="page-header">
          <h1 className="page-title">系统设置</h1>
          <p className="page-subtitle">后端连接、当前会话与教师资源管理</p>
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
                files={resourceFiles}
                sourceType={resourceSourceType}
                scope={resourceScope}
                feishuExportFormat={feishuExportFormat}
                parseMode={resourceParseMode}
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
                onLocationChange={handleResourceLocationChange}
                onFilesChange={handleResourceFilesChange}
                onSourceTypeChange={handleResourceSourceTypeChange}
                onScopeChange={setResourceScope}
                onFeishuExportFormatChange={setFeishuExportFormat}
                onParseModeChange={setResourceParseMode}
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

function readStoredTeachingConversation(): {
  conversationId: string;
  entries: TeachingConversationThreadItem[];
} {
  try {
    const value = globalThis.localStorage?.getItem(TEACHING_CONVERSATION_STORAGE_KEY);
    if (!value) {
      return { conversationId: "", entries: [] };
    }
    const parsed = JSON.parse(value) as {
      conversationId?: string;
      entries?: TeachingConversationThreadItem[];
    };
    return {
      conversationId: parsed.conversationId ?? "",
      entries: Array.isArray(parsed.entries) ? sanitizeTeachingConversationEntries(parsed.entries) : [],
    };
  } catch {
    return { conversationId: "", entries: [] };
  }
}

function sanitizeTeachingConversationEntries(entries: TeachingConversationThreadItem[]) {
  return entries
    .filter(isRestorableTeachingConversationEntry)
    .slice(-24)
    .map((entry) => ({
      ...entry,
      imagePreviewUrl: undefined,
    }));
}

function isRestorableTeachingConversationEntry(entry: TeachingConversationThreadItem) {
  if (entry.role === "user") {
    return Boolean(entry.questionText?.trim() || entry.imageFileName?.trim());
  }
  if (entry.loading) {
    return false;
  }
  if (entry.error) {
    return false;
  }
  if (!entry.response) {
    return false;
  }
  return !isNoisyTeachingConversationText([
    entry.questionText,
    entry.response.questionText,
    ...(entry.response.cards ?? []).flatMap((card) => [
      card.title,
      card.summary,
      ...(card.items ?? []),
    ]),
  ]);
}

function isNoisyTeachingConversationText(values: Array<string | undefined>) {
  return values.some((value) => {
    const text = value ?? "";
    return /API_ACCESS_DENIED|Endpoint requires|没有执行这个操作的权限|权限不足|MODEL_CALL|JSON_PARSE|bearer|MCP|requestHash|idempotencyKey/i.test(text);
  });
}

function readStoredHandoutCollaboration(): HandoutCollaborationThreadItem[] {
  try {
    const value = globalThis.localStorage?.getItem(HANDOUT_COLLABORATION_STORAGE_KEY);
    if (!value) {
      return [];
    }
    const parsed = JSON.parse(value) as HandoutCollaborationThreadItem[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
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

function isBackendNotFound(error: Error) {
  return (error.message || "").includes("Backend request failed: 404");
}

function toUserFacingError(error: Error) {
  const message = error.message || "";
  if (message.includes("Backend request failed: 403")) {
    return "当前会话没有执行这个操作的权限，请重新登录后重试。";
  }
  if (message.includes("Backend request failed: 404")) {
    return "后端没有找到对应记录，请刷新页面后重试。";
  }
  if (message.includes("Backend request failed: 429")) {
    return "当前请求过于频繁，请稍后再试。";
  }
  if (message.includes("Backend request failed: 400")) {
    return "后端拒绝了本次请求，请刷新页面或重启后端后重试；如果是 16:10 讲解版 PDF，通常是后端能力白名单尚未加载最新代码。";
  }
  if (message.includes("Failed to fetch") || message.includes("NetworkError")) {
    return "无法连接后端服务，请确认本机后端已经启动。";
  }
  return message.replace(/^Backend request failed:\s*/i, "").slice(0, 220);
}

function toImageUploadError(error: Error) {
  const message = error.message || "";
  if (message.includes("Backend request failed: 403")) {
    return "图片上传需要有效登录态。管理员、教师和学生都可以上传；请重新登录后再试。";
  }
  if (message.includes("Backend request failed: 413") || message.includes("exceeds max size")) {
    return "图片太大，请换一张更小的题图。";
  }
  if (message.includes("Only image uploads")) {
    return "只能上传图片文件。";
  }
  return toUserFacingError(error);
}

function downloadText(fileName: string, content: string, mimeType: string) {
  downloadBlob(fileName, new Blob([content], { type: mimeType }));
}

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === "string" ? reader.result : "");
    reader.onerror = () => reject(reader.error ?? new Error("文件预览失败"));
    reader.readAsDataURL(file);
  });
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

type TemplateShelfFilter = "all" | "local" | "skill" | "teacher" | "student" | "exam";

export function TemplateShelf({
  templates,
  selectedCode,
  loading,
  onSelect,
  loadPreviewImage,
  loadReferencePdf,
}: {
  templates: TeachingHandoutTemplateResponse[];
  selectedCode: string;
  loading: boolean;
  onSelect: (templateCode: string) => void;
  loadPreviewImage?: (templateCode: string) => Promise<Uint8Array>;
  loadReferencePdf?: (templateCode: string) => Promise<Uint8Array>;
}) {
  const [expanded, setExpanded] = useState(false);
  const [filter, setFilter] = useState<TemplateShelfFilter>("all");
  const selectedTemplate = templates.find((template) => template.templateCode === selectedCode) ?? templates[0];
  const shouldLoadReferenceMedia = canLoadTemplateReferenceMedia(selectedTemplate);
  const [previewImageState, setPreviewImageState] = useState<"idle" | "loading" | "ready" | "failed">(
    () => (shouldLoadReferenceMedia && loadPreviewImage ? "loading" : "idle"),
  );
  const [previewImageUrl, setPreviewImageUrl] = useState("");
  const [referencePdfState, setReferencePdfState] = useState<"idle" | "loading" | "ready" | "failed">(
    () => (shouldLoadReferenceMedia && loadReferencePdf ? "loading" : "idle"),
  );
  const [referencePdfBytes, setReferencePdfBytes] = useState<Uint8Array | null>(null);
  const [referencePdfUrl, setReferencePdfUrl] = useState("");

  useEffect(() => {
    if (!selectedTemplate || !shouldLoadReferenceMedia) {
      setPreviewImageState("idle");
      setReferencePdfState("idle");
      setPreviewImageUrl((current) => {
        if (current) URL.revokeObjectURL(current);
        return "";
      });
      setReferencePdfUrl((current) => {
        if (current) URL.revokeObjectURL(current);
        return "";
      });
      setReferencePdfBytes(null);
      return undefined;
    }
    let cancelled = false;
    setPreviewImageUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setReferencePdfUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return "";
    });
    setReferencePdfBytes(null);

    if (loadPreviewImage) {
      setPreviewImageState("loading");
      loadPreviewImage(selectedTemplate.templateCode)
        .then((bytes) => {
          if (cancelled || !bytes?.byteLength) {
            return;
          }
          const safeBytes = new Uint8Array(bytes.byteLength);
          safeBytes.set(bytes);
          const url = URL.createObjectURL(new Blob([safeBytes], { type: "image/png" }));
          setPreviewImageUrl(url);
          setPreviewImageState("ready");
        })
        .catch(() => {
          if (!cancelled) {
            setPreviewImageState("failed");
          }
        });
    } else {
      setPreviewImageState("idle");
    }

    if (loadReferencePdf) {
      setReferencePdfState("loading");
      loadReferencePdf(selectedTemplate.templateCode)
        .then((bytes) => {
          if (cancelled || !bytes?.byteLength) {
            return;
          }
          const safeBytes = new Uint8Array(bytes.byteLength);
          safeBytes.set(bytes);
          const url = URL.createObjectURL(new Blob([safeBytes], { type: "application/pdf" }));
          setReferencePdfBytes(safeBytes);
          setReferencePdfUrl(url);
          setReferencePdfState("ready");
        })
        .catch(() => {
          if (!cancelled) {
            setReferencePdfState("failed");
          }
        });
    } else {
      setReferencePdfState("idle");
    }

    return () => {
      cancelled = true;
    };
  }, [selectedTemplate, shouldLoadReferenceMedia, loadPreviewImage, loadReferencePdf]);

  useEffect(() => () => {
    if (previewImageUrl) {
      URL.revokeObjectURL(previewImageUrl);
    }
    if (referencePdfUrl) {
      URL.revokeObjectURL(referencePdfUrl);
    }
  }, [previewImageUrl, referencePdfUrl]);

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
  if (!templates.length || !selectedTemplate) {
    return (
      <div className="template-shelf">
        <div className="template-shelf-head">
          <span>讲义模板</span>
          <small>后端未返回模板</small>
        </div>
      </div>
    );
  }

  const filterOptions = templateShelfFilters(templates);
  const filteredTemplates = templates
    .filter((template) => templateMatchesShelfFilter(template, filter))
    .sort(compareTemplateShelfPriority);
  const shelfTemplates = filteredTemplates.length ? filteredTemplates : templates;
  const recommendedTemplates = shelfTemplates
    .filter((template) => template.templateCode !== selectedTemplate.templateCode)
    .slice(0, 5);
  const visibleTemplates = expanded ? shelfTemplates : uniqueTemplates([selectedTemplate, ...recommendedTemplates]);
  return (
    <div className="template-shelf">
      <div className="template-shelf-head">
        <div>
          <span>讲义模板书架</span>
          <small>
            当前：{safeTemplateText(selectedTemplate.displayName, "未命名模板")} · {expanded ? `展开 ${shelfTemplates.length}` : `精选 ${visibleTemplates.length}`} / {templates.length}
          </small>
        </div>
        <button type="button" className="template-shelf-toggle" onClick={() => setExpanded((value) => !value)}>
          {expanded ? "收起书架" : `展开更多模板`}
        </button>
      </div>
      <div className="template-filter-row" aria-label="模板筛选">
        {filterOptions.map((option) => (
          <button
            type="button"
            className={filter === option.value ? "active" : ""}
            key={option.value}
            onClick={() => {
              setFilter(option.value);
              setExpanded(false);
            }}
          >
            <span>{option.label}</span>
            <em>{option.count}</em>
          </button>
        ))}
      </div>
      <TemplateHandoutPreview
        template={selectedTemplate}
        previewImageState={previewImageState}
        previewImageUrl={previewImageUrl}
        referencePdfState={referencePdfState}
        referencePdfBytes={referencePdfBytes}
        referencePdfUrl={referencePdfUrl}
      />
      <div className={expanded ? "template-shelf-grid expanded" : "template-shelf-grid compact"}>
        {visibleTemplates.map((template) => {
          const selected = template.templateCode === selectedCode;
          const dynamicSkill = template.sourceType === "skill_config";
          const localReference = !dynamicSkill && (template.sourceType === "local_reference" || Boolean(template.referenceTitle));
          const displayName = safeTemplateText(template.displayName, "未命名模板");
          const description = safeTemplateText(template.description, "按讲义结构生成内容");
          const referenceTitle = safeTemplateText(template.referenceTitle, displayName);
          return (
            <article
              key={template.templateCode}
              className={[
                "template-card",
                selected ? "selected" : "",
                localReference ? "template-card-reference" : "",
                dynamicSkill ? "template-card-skill" : "",
              ].filter(Boolean).join(" ")}
            >
              <button type="button" className="template-card-select" onClick={() => onSelect(template.templateCode)}>
                <div className="template-card-top">
                  <span>{safeTemplateText(template.category, audienceLabel(template.audience))}</span>
                  <strong>{safeTemplateText(template.visualStyle, sourceTypeLabel(template.sourceType))}</strong>
                </div>
                <h3>{displayName}</h3>
                <p>{compactText(description, 88)}</p>
                <TemplateMiniPaper template={template} />
                {localReference ? (
                  <div className="template-reference-strip">
                    <span>本机 PDF</span>
                    <strong>{referenceTitle}</strong>
                  </div>
                ) : null}
                {dynamicSkill ? (
                  <div className="template-reference-strip template-skill-strip">
                    <span>动态 Skill</span>
                    <strong>{safeTemplateText(template.referenceTitle, "可配置提示词模板")}</strong>
                  </div>
                ) : null}
                <div className="template-chip-row">
                  <span>{audienceLabel(template.audience)}</span>
                  {(template.difficultyBands ?? []).slice(0, 3).map((item) => <span key={item}>{safeTemplateText(item, "难度")}</span>)}
                  <span>留白 {template.blankSpaceEm ?? 6}em</span>
                  <span>题距 {template.questionGapEm ?? 4}em</span>
                </div>
                {template.tags?.length ? (
                  <div className="template-tag-row">
                    {template.tags.slice(0, 3).map((tag) => <small key={tag}>{safeTemplateText(tag, "标签")}</small>)}
                  </div>
                ) : null}
              </button>
              {template.referencePreview?.trim() ? (
                <details className="template-reference-preview">
                  <summary>参考来源</summary>
                  <p>{compactText(safeTemplateText(template.referencePreview, "本机参考讲义"), 120)}</p>
                </details>
              ) : null}
            </article>
          );
        })}
      </div>
    </div>
  );
}

function TemplateMiniPaper({ template }: { template: TeachingHandoutTemplateResponse }) {
  const sections = templatePreviewSections(template).slice(0, 4);
  return (
    <div className="template-card-paper" aria-hidden="true">
      <div className="template-card-paper-head">
        <span />
        <span />
      </div>
      {sections.map((section) => (
        <div className="template-card-paper-row" key={section.title}>
          <strong>{section.index}</strong>
          <span>{section.title}</span>
        </div>
      ))}
      <div className="template-card-paper-lines">
        <i />
        <i />
        <i />
      </div>
    </div>
  );
}

function TemplateHandoutPreview({
  template,
  previewImageState,
  previewImageUrl,
  referencePdfState,
  referencePdfBytes,
  referencePdfUrl,
}: {
  template: TeachingHandoutTemplateResponse;
  previewImageState: "idle" | "loading" | "ready" | "failed";
  previewImageUrl: string;
  referencePdfState: "idle" | "loading" | "ready" | "failed";
  referencePdfBytes: Uint8Array | null;
  referencePdfUrl: string;
}) {
  const sections = templatePreviewSections(template);
  const displayName = safeTemplateText(template.displayName, "讲义模板");
  const referenceTitle = safeTemplateText(template.referenceTitle, "模板结构");
  const tags = (template.tags ?? []).map((tag) => safeTemplateText(tag, "")).filter(Boolean).slice(0, 5);
  const difficultyBands = (template.difficultyBands ?? []).map((item) => safeTemplateText(item, "")).filter(Boolean).slice(0, 4);
  const isStudentOnly = (template.audience ?? "").toLowerCase() === "student";
  const mathPreview = templatePreviewFormula(template);
  const showReferenceMedia = canLoadTemplateReferenceMedia(template);
  return (
    <section className="template-selected-preview" aria-label="当前模板预览">
      <div className="template-preview-meta">
        <span>{audienceLabel(template.audience)}</span>
        <span>{sourceTypeLabel(template.sourceType)}</span>
        {safeTemplateText(template.category, "") ? <span>{safeTemplateText(template.category, "")}</span> : null}
      </div>
      <div className="template-preview-paper">
        <div className="template-preview-title">
          <div>
            <small>{isStudentOnly ? "学生练习讲义预览" : "教师备课讲义预览"}</small>
            <h3>{displayName}</h3>
            <p>{compactText(safeTemplateText(template.description, "按模板组织知识点、例题、讲解流程和反馈审查。"), 120)}</p>
          </div>
          <div className="template-preview-stamp">
            <strong>{isStudentOnly ? "无答案" : "含答案"}</strong>
            <span>{difficultyBands[0] ?? "动态难度"}</span>
          </div>
        </div>

        <div className="template-preview-grid">
          {sections.map((section) => (
            <article className={section.kind === "answer" && isStudentOnly ? "template-preview-section muted" : "template-preview-section"} key={section.title}>
              <span>{section.index}</span>
              <div>
                <strong>{section.title}</strong>
                <p>{section.description}</p>
              </div>
            </article>
          ))}
        </div>

        <div className="template-preview-workspace">
          <div>
            <div className="template-preview-lines" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
          </div>
          <div className="template-preview-example">
            <small>公式预览</small>
            <InlineMathPreview latex={mathPreview} />
          </div>
        </div>

        <div className="template-preview-footer">
          <span>来源：{referenceTitle}</span>
          <span>{tags.length ? tags.join(" / ") : "知识点、题型、难度可动态拼装"}</span>
        </div>
      </div>

      {showReferenceMedia ? (
        <section className="template-reference-callout" aria-label="模板参考内容">
          <strong>模板参考来源</strong>
          <p>这里显示模板对应的真实首屏和原始 PDF。模板选择只负责风格与结构，真正的讲义内容仍由讲义生成链路按教材、题库和教师资料重组。</p>
        </section>
      ) : null}

      {showReferenceMedia ? (
        <section className="template-reference-hero" aria-label="模板首屏参考图">
          <div className="template-reference-hero-head">
            <div>
              <small>真实模板首屏</small>
              <strong>{referenceTitle}</strong>
            </div>
            <span>{previewImageState === "ready" ? "已加载" : previewImageState === "failed" ? "不可用" : "加载中"}</span>
          </div>
          {previewImageState === "ready" && previewImageUrl ? (
            <img className="template-reference-hero-image" src={previewImageUrl} alt={`${displayName} 模板首屏`} />
          ) : previewImageState === "failed" ? (
            <div className="template-reference-hero-placeholder">
              <FileText size={18} />
              <strong>未获取到首屏缩略图</strong>
              <span>这个模板仍可继续使用，讲义结构和提示词不会受影响。</span>
            </div>
          ) : (
            <div className="template-reference-hero-placeholder">
              <Loader2 className="spin" size={18} />
              <strong>正在加载缩略图</strong>
              <span>优先读取真实模板首屏，方便先看版式和题目密度。</span>
            </div>
          )}
        </section>
      ) : null}

      {showReferenceMedia ? (
        <section className="template-reference-pdf" aria-label="模板参考讲义 PDF 预览">
          <div className="template-reference-pdf-head">
            <div>
              <small>模板参考讲义 PDF 预览</small>
              <strong>{referenceTitle}</strong>
              <span>支持多页翻看，核对真实模板的题号密度、留白和版式节奏。</span>
            </div>
            <div className="template-reference-pdf-nav">
              {referencePdfUrl ? (
                <a href={referencePdfUrl} target="_blank" rel="noreferrer">打开原始 PDF</a>
              ) : null}
            </div>
          </div>
          {referencePdfState === "ready" && referencePdfUrl && referencePdfBytes ? (
            <PdfCanvasPreview
              pdfBytes={referencePdfBytes}
              pdfUrl={referencePdfUrl}
              title="模板原稿 PDF"
              canvasLabel={`${displayName} 模板 PDF 预览`}
            />
          ) : referencePdfState === "failed" ? (
            <div className="template-reference-pdf-status failed">
              <FileText size={18} />
              <strong>未获取到参考 PDF</strong>
              <span>当前模板仍可选用，但暂时无法展示原始多页 PDF。</span>
            </div>
          ) : (
            <div className="template-reference-pdf-status">
              <Loader2 className="spin" size={18} />
              <strong>正在加载参考 PDF</strong>
              <span>只通过受控模板接口读取，不直接暴露本机路径。</span>
            </div>
          )}
        </section>
      ) : null}
    </section>
  );
}

function InlineMathPreview({ latex }: { latex: string }) {
  const html = useMemo(() => {
    try {
      return katex.renderToString(latex, { throwOnError: false, displayMode: false, output: "html" });
    } catch {
      return latex;
    }
  }, [latex]);
  return <span className="template-preview-formula" dangerouslySetInnerHTML={{ __html: html }} />;
}

function templatePreviewSections(template: TeachingHandoutTemplateResponse) {
  const audience = (template.audience ?? "").toLowerCase();
  const searchable = `${template.displayName} ${template.description} ${template.category ?? ""} ${(template.tags ?? []).join(" ")}`.toLowerCase();
  const examMode = searchable.includes("高考") || searchable.includes("压轴") || searchable.includes("题型");
  if (audience === "student") {
    return [
      { index: "01", kind: "topic", title: "知识速记", description: "只保留定义、公式和易错提醒。" },
      { index: "02", kind: "question", title: "例题任务", description: "题目连续编号，保留完整作答空间。" },
      { index: "03", kind: "hint", title: "思路提示", description: "给方向，不直接暴露答案。" },
      { index: "04", kind: "workspace", title: "订正记录", description: "学生课后补充错因和二次解法。" },
    ];
  }
  if (examMode) {
    return [
      { index: "01", kind: "source", title: "题源定位", description: "绑定高考题、变式题和知识点来源。" },
      { index: "02", kind: "method", title: "方法拆解", description: "按审题、建模、计算、检验拆步骤。" },
      { index: "03", kind: "answer", title: "答案与评分点", description: "教师版保留关键得分点和扣分提醒。" },
      { index: "04", kind: "question", title: "变式梯度", description: "基础、提高、压轴动态组卷。" },
    ];
  }
  return [
    { index: "01", kind: "source", title: "知识定位", description: "列出教材、题库和教师资料来源。" },
    { index: "02", kind: "method", title: "讲解流程", description: "形成可直接上课使用的板书顺序。" },
    { index: "03", kind: "answer", title: "例题详解", description: "教师版展示答案、思路和评分口径。" },
    { index: "04", kind: "question", title: "课堂追问", description: "预设追问、变式和学生反馈节点。" },
  ];
}

function templatePreviewFormula(template: TeachingHandoutTemplateResponse) {
  const text = `${template.displayName} ${template.description} ${(template.tags ?? []).join(" ")}`;
  if (/双曲线|圆锥|椭圆/.test(text)) return "c^2=a^2+b^2";
  if (/反比例|函数/.test(text)) return "y=\\frac{k}{x}";
  if (/导数/.test(text)) return "f'(x)=\\lim_{\\Delta x\\to0}\\frac{f(x+\\Delta x)-f(x)}{\\Delta x}";
  return "a_n=a_1+(n-1)d";
}

function canLoadTemplateReferenceMedia(template: TeachingHandoutTemplateResponse | undefined) {
  if (!template) {
    return false;
  }
  const referenceTitle = template.referenceTitle?.trim() ?? "";
  return template.sourceType === "local_reference"
    || /\.pdf$/i.test(referenceTitle)
    || Boolean(template.referencePath?.trim());
}

function safeTemplateText(value: string | null | undefined, fallback: string) {
  const normalized = (value ?? "").replace(/\s+/g, " ").trim();
  if (!normalized || /\?{2,}|�/.test(normalized)) {
    return fallback;
  }
  return normalized;
}

function uniqueTemplates(templates: TeachingHandoutTemplateResponse[]) {
  const seen = new Set<string>();
  return templates.filter((template) => {
    if (seen.has(template.templateCode)) {
      return false;
    }
    seen.add(template.templateCode);
    return true;
  });
}

function compareTemplateShelfPriority(left: TeachingHandoutTemplateResponse, right: TeachingHandoutTemplateResponse) {
  return templateShelfPriority(right) - templateShelfPriority(left);
}

function templateShelfPriority(template: TeachingHandoutTemplateResponse) {
  const searchable = `${template.templateCode} ${template.displayName} ${template.description} ${template.referenceTitle ?? ""} ${(template.tags ?? []).join(" ")}`.toLowerCase();
  let score = 0;
  if ((template.sourceType ?? "").toLowerCase() === "local_reference") score += 30;
  if ((template.audience ?? "").toLowerCase() === "student") score += 18;
  if (searchable.includes("真实")) score += 24;
  if (searchable.includes("反比例函数")) score += 26;
  if (searchable.includes("赵礼显")) score += 18;
  if (searchable.includes("学生版")) score += 16;
  if (searchable.includes("pdf")) score += 4;
  return score;
}

function templateShelfFilters(templates: TeachingHandoutTemplateResponse[]): Array<{ value: TemplateShelfFilter; label: string; count: number }> {
  const options: Array<{ value: TemplateShelfFilter; label: string; count: number }> = [
    { value: "all", label: "全部", count: templates.length },
    { value: "local", label: "本机参考", count: templates.filter((template) => templateMatchesShelfFilter(template, "local")).length },
    { value: "skill", label: "动态 Skill", count: templates.filter((template) => templateMatchesShelfFilter(template, "skill")).length },
    { value: "teacher", label: "教师版", count: templates.filter((template) => templateMatchesShelfFilter(template, "teacher")).length },
    { value: "student", label: "学生版", count: templates.filter((template) => templateMatchesShelfFilter(template, "student")).length },
    { value: "exam", label: "高考压轴", count: templates.filter((template) => templateMatchesShelfFilter(template, "exam")).length },
  ];
  return options.filter((option) => option.value === "all" || option.count > 0);
}

function templateMatchesShelfFilter(template: TeachingHandoutTemplateResponse, filter: TemplateShelfFilter) {
  const sourceType = (template.sourceType ?? "").toLowerCase();
  const audience = (template.audience ?? "").toLowerCase();
  const tags = (template.tags ?? []).join(" ");
  const difficulty = (template.difficultyBands ?? []).join(" ");
  const searchable = `${template.displayName} ${template.description} ${template.category ?? ""} ${template.visualStyle ?? ""} ${tags} ${difficulty}`.toLowerCase();
  if (filter === "all") {
    return true;
  }
  if (filter === "local") {
    return sourceType === "local_reference" || sourceType === "pdf" || sourceType === "latex";
  }
  if (filter === "skill") {
    return sourceType === "skill_config";
  }
  if (filter === "teacher") {
    return audience === "teacher" || audience === "mixed" || searchable.includes("教师");
  }
  if (filter === "student") {
    return audience === "student" || audience === "mixed" || searchable.includes("学生");
  }
  return searchable.includes("高考") || searchable.includes("压轴") || searchable.includes("竞赛");
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
    skill_config: "动态 Skill",
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

function handoutDraftForVersion(task: TeachingTaskResponse, version: TeachingHandoutVersion) {
  if (version === "lecture") {
    return task.lectureHandoutLatex ?? task.teacherHandoutLatex ?? task.handoutLatex ?? "";
  }
  if (version === "student") {
    return task.studentHandoutLatex ?? task.handoutLatex ?? "";
  }
  return task.teacherHandoutLatex ?? task.handoutLatex ?? "";
}

export function buildTeachingFeedbackReviewContext(
  task: TeachingTaskResponse,
  version: TeachingHandoutVersion,
  latex: string,
  pdfMeta: TeachingHandoutPdfResponse | null,
  pdfPreviewKey: string,
  visualEvidence?: TeachingPdfPreviewVisualEvidence,
) {
  const plainText = latex
    .replace(/\\[a-zA-Z]+\*?\{([^{}]*)\}/g, "$1")
    .replace(/[%#*_`>$]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  const sectionCount = (latex.match(/\\section\*?\{/g) ?? []).length;
  const hasMath = /(\${1,2}[^$]+\${1,2}|\\\(.+?\\\)|\\\[.+?\\\]|[A-Za-z][_^][{]?\d)/.test(latex);
  const hasWorkspace = /\\vspace|\\underline\{\\hspace|___/.test(latex);
  const answerLeak = /答案与评分点|参考答案|评分标准|答案[:：]|答案为|故答案|因此答案|得分/.test(plainText);
  const teacherHasAnswer = /答案与评分点|答案|解析|讲评|评分/.test(plainText);
  const evidenceCount = task.evidence.length;
  const evidenceScopes = Array.from(new Set(task.evidence.map((item) => item.sourceScope).filter(Boolean)));
  const pdfPreviewKeyMatches = pdfPreviewKey === `${task.taskId}:${version}`;
  const pdfPreviewReady = Boolean(pdfMeta) && pdfPreviewKeyMatches;
  const visualEvidenceVersionMatches = !visualEvidence || visualEvidence.version === version;
  const sourceTraceable = evidenceCount > 0;
  const internalDebugLeak = /MODEL_CALL|JSON_PARSE|tokens?=|模型健康|model health|debug|调试|作为\s*AI|as an AI/i.test(plainText);
  const layoutRuleLeak = /页眉|页脚|颜色|PDF\s*版式要求|PDF\s*规则|渲染引擎/.test(plainText);
  const studentAnswerIsolated = version !== "student" || !answerLeak;
  const teacherAnswerPresent = version !== "teacher" || teacherHasAnswer;
  const groups = version === "lecture" ? [
    ["横版讲解卡", "16:10 横版讲解卡", "课堂投屏", "讲解卡"],
    ["核心公式", "核心公式与方法卡", "公式", "方法"],
    ["课堂引导", "课堂追问", "追问", "引导"],
  ] : version === "teacher" ? [
    ["讲义信息", "学习目标", "本讲任务", "课前定位"],
    ["来源索引", "知识点归属", "知识定位", "教材", "题库", "证据"],
    ["板书流程", "板书", "方法步骤", "讲解路径", "方法卡片", "讲解"],
    ["例题与答案", "例题详解", "答案与评分点", "评分点", "解析"],
    ["课堂追问", "追问与变式训练", "变式", "问题预设", "互动练习"],
    ["课后订正", "反馈记录", "易错提醒", "订正记录", "反馈"],
  ] : [
    ["第 1 讲", "学习主题", "学习目标", "专题标题"],
    ["知识点", "知识速记", "核心定义", "核心方法"],
    ["题型", "例题任务", "题目", "连续编号"],
    ["思路提示", "作答提醒", "注意", "方法提示"],
    ["课堂练习", "练习任务", "课后巩固"],
    ["连续编号", "练习任务", "订正记录", "错因"],
  ];
  const matchedCoreColumns = groups.filter((group) => group.some((keyword) => plainText.includes(keyword))).length;
  const pdfRenderer = pdfMeta?.renderer ?? "";
  const pdfPageCount = pdfMeta?.pageCount ?? 0;
  const coreColumnCoverage = `${matchedCoreColumns}/${groups.length}`;
  const pdfVisualEvidenceCaptured = Boolean(
    visualEvidence?.captured
    && visualEvidence.previewImageDataUrl
    && pdfPreviewReady
    && visualEvidenceVersionMatches,
  );
  const evidenceSummary = buildTeachingFeedbackEvidenceSummary(task);
  const pdfImageRef = visualEvidence?.imageRef ?? `teaching-task:${task.taskId}:${version}:pdf-page:1`;
  return {
    schemaVersion: "teaching-feedback-review-v2",
    handoutVersion: version,
    taskStatus: task.status,
    taskSnapshot: {
      taskId: task.taskId,
      learningGoal: safeReviewText(task.learningGoal, 120),
      questionPreview: safeReviewText(task.questionText, 120),
      hasQuestionText: Boolean(task.questionText?.trim()),
      subjectType: task.subjectType,
      subjectId: task.subjectId,
    },
    templateCode: task.selectedTemplate?.templateCode ?? "default_standard",
    templateName: task.selectedTemplate?.displayName ?? "标准讲义",
    templateSnapshot: {
      templateCode: task.selectedTemplate?.templateCode ?? "default_standard",
      templateName: task.selectedTemplate?.displayName ?? "标准讲义",
      sourceType: task.selectedTemplate?.sourceType ?? "builtin",
      audience: task.selectedTemplate?.audience ?? "mixed",
      category: task.selectedTemplate?.category ?? "",
      visualStyle: task.selectedTemplate?.visualStyle ?? "",
      referenceTitle: task.selectedTemplate?.referenceTitle ?? "",
    },
    pdfRenderer,
    pdfPageCount,
    pdfPreviewReady,
    pdfRendererIsXeLaTeX: pdfRenderer === "xelatex",
    evidenceCount,
    evidenceScopes,
    evidenceSummary,
    sourceTraceable,
    aiReviewBrief: [
      `版本：${version === "teacher" ? "教师版" : version === "lecture" ? "讲解版" : "学生版"}`,
      `模板：${task.selectedTemplate?.displayName ?? "标准讲义"}`,
      `结构：${coreColumnCoverage} 核心栏目`,
      `PDF：${pdfPreviewReady ? `${pdfRenderer || "unknown"} / ${pdfPageCount}页` : "未预览"}`,
      `预览图：${pdfVisualEvidenceCaptured ? "已记录首屏渲染证据" : "未记录"}`,
      `安全：${internalDebugLeak || layoutRuleLeak ? "需复核内部词泄漏" : "未发现内部词泄漏"}`,
    ],
    aiReviewInputPlan: {
      purpose: "后续AI复核应同时读取结构化审校上下文、短来源摘要和PDF页面图像。",
      imageRequired: true,
      imageRefs: [pdfImageRef],
      attachPdfPreviewImage: pdfVisualEvidenceCaptured,
      inlinePreviewIncluded: pdfVisualEvidenceCaptured,
      textPayloadFields: [
        "taskSnapshot",
        "templateSnapshot",
        "reviewEvidence.handoutText",
        "reviewEvidence.safety",
        "evidenceSummary",
      ],
      doNotSendFields: ["rawLatex", "base64Image", "fullOcrText", "localFilePath"],
    },
    reviewEvidence: {
      pdfPreview: {
        artifactType: "pdf_preview",
        version,
        previewReady: pdfPreviewReady,
        versionBound: pdfPreviewKeyMatches,
        visualEvidenceVersionBound: visualEvidenceVersionMatches,
        renderer: pdfRenderer,
        pageCount: pdfPageCount,
        visualEvidence: visualEvidence ?? null,
      },
      handoutText: {
        latexLength: latex.length,
        plainTextLength: plainText.length,
        sectionCount,
        hasMath,
        hasWorkspace,
        coreColumnCoverage,
      },
      sources: {
        sourceTraceable,
        evidenceCount,
        evidenceScopes,
        evidenceSummary,
      },
      safety: {
        internalDebugLeak,
        layoutRuleLeak,
        answerLeak,
        studentAnswerIsolated,
        teacherAnswerPresent,
      },
    },
    checks: {
      sectionCount,
      matchedCoreColumns,
      coreColumnTotal: groups.length,
      coreColumnCoverage,
      hasMath,
      hasWorkspace,
      teacherHasAnswer,
      answerLeak,
      internalDebugLeak,
      layoutRuleLeak,
      studentAnswerIsolated,
      teacherAnswerPresent,
      pdfPreviewReady,
      pdfVisualEvidenceCaptured,
      sourceTraceable,
    },
  };
}

function buildTeachingFeedbackEvidenceSummary(task: TeachingTaskResponse) {
  return (task.evidence ?? []).slice(0, 8).map((item, index) => ({
    index: index + 1,
    scope: item.sourceScope,
    title: safeReviewText(item.sourceTitle, 120),
    pageNo: item.pageNo,
    sourceRef: safeReviewText(item.chunkId, 120),
    snippetPreview: safeReviewText(cleanEvidenceSnippetForReview(item.snippet), 140),
  }));
}

function cleanEvidenceSnippetForReview(value: string) {
  return (value ?? "")
    .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
    .replace(/\.\.\/\.\.\/pages\/[^\s，。；;)]*/g, " ")
    .replace(/formula_text|source_page_image/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function safeReviewText(value: string | undefined, maxLength: number) {
  const text = (value ?? "")
    .replace(/\s+/g, " ")
    .replace(/\?{4,}|�/g, "")
    .trim();
  return text.length <= maxLength ? text : `${text.slice(0, Math.max(0, maxLength - 1)).trim()}…`;
}

function captureTeachingPdfPreviewVisualEvidence(
  taskId: string,
  version: TeachingHandoutVersion,
): TeachingPdfPreviewVisualEvidence {
  const selector = ".pdf-page-canvas";
  const canvas = document.querySelector<HTMLCanvasElement>(selector);
  const preview = document.querySelector<HTMLElement>(".pdf-canvas-preview");
  const pageFrame = document.querySelector<HTMLElement>(".pdf-canvas-page");
  const previewState = preview?.dataset.previewState ?? "missing";
  const page = Number(canvas?.dataset.page || pageFrame?.dataset.currentPage || "1") || 1;
  const base = {
    artifactType: "browser_pdf_canvas" as const,
    captured: false,
    selector,
    version,
    imageRef: `teaching-task:${taskId}:${version}:pdf-page:${page}`,
    previewState,
    page,
    pixelWidth: 0,
    pixelHeight: 0,
    cssWidth: 0,
    cssHeight: 0,
    attachToAiReview: false,
    aiAttachmentPlan: "AI复核时按 imageRef 重新加载任务 PDF，并渲染对应页作为图片输入。",
  };
  if (!canvas) {
    return { ...base, reason: "PDF canvas not mounted" };
  }
  const rect = canvas.getBoundingClientRect();
  const captured = previewState === "ready" && canvas.width > 0 && canvas.height > 0;
  const previewImageDataUrl = captured ? captureCanvasPreviewDataUrl(canvas) : "";
  return {
    ...base,
    captured,
    previewImageDataUrl,
    pixelWidth: canvas.width,
    pixelHeight: canvas.height,
    cssWidth: Math.round(rect.width),
    cssHeight: Math.round(rect.height),
    attachToAiReview: captured && Boolean(previewImageDataUrl),
    reason: captured ? undefined : "PDF canvas not ready",
  };
}

function captureCanvasPreviewDataUrl(canvas: HTMLCanvasElement) {
  try {
    const maxWidth = 900;
    const scale = canvas.width > maxWidth ? maxWidth / canvas.width : 1;
    if (scale >= 1) {
      return canvas.toDataURL("image/png");
    }
    const preview = document.createElement("canvas");
    preview.width = Math.max(1, Math.round(canvas.width * scale));
    preview.height = Math.max(1, Math.round(canvas.height * scale));
    const context = preview.getContext("2d");
    if (!context) {
      return "";
    }
    context.drawImage(canvas, 0, 0, preview.width, preview.height);
    return preview.toDataURL("image/png");
  } catch {
    return "";
  }
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
