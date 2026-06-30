import { AlertCircle, BookOpen, Database, Loader2, Search, ShieldCheck } from "lucide-react";
import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  AgentRunExecuteResponse,
  AgentModelHealthResponse,
  AgentModelCatalogResponse,
  AgentRunPlanResponse,
  AgentTraceResponse,
  AgentTraceUsageSummaryResponse,
  KnowledgePointResponse,
  McpConfigurationResponse,
  QuestionBankItemResponse,
  RetrievalAuditDetail,
  StudentDashboardResponse,
  TeachingTaskResponse,
  TeacherFeishuDiscoveryCandidate,
  TeacherFeishuDiscoveryResponse,
  TeacherBlockQuestionImportResponse,
  TeacherResourceBlockSearchResponse,
  TeacherResourceDocumentResponse,
  TeacherSourceSyncCheckpointResponse,
  TeacherSourceSyncJobResponse,
  TextbookSearchHit,
  TextbookSearchResponse,
  TextbookSummary,
  LoginResponse,
  createTextbookApiClient,
} from "../shared/api/textbookApi";
import {
  MCP_PROMPT_OPTIONS,
  MCP_TOOL_OPTIONS,
  defaultMcpExposureSelection,
  toggleMcpExposureOption,
} from "./mcpExposureSelection";

const DEFAULT_BACKEND_URL = import.meta.env.VITE_BACKEND_URL ?? "http://127.0.0.1:8080";
const DEFAULT_FEISHU_ROOT_URL = "https://my.feishu.cn/drive/folder/XVn7fXppJlQMK5dkuOkc1ePan2f";
const TEACHING_TASK_STORAGE_KEY = "math-agent:last-teaching-task-id";
const AGENT_MODEL_OPTIONS: Record<string, string[]> = {
  openai: ["gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano"],
  dashscope: ["qwen3.6-flash", "qwen3.7-plus", "qwen3.7-max"],
  deepseek: ["deepseek-v4-flash", "deepseek-v4-pro"],
  ark: ["doubao-seed-2-0-lite-260428", "doubao-seed-2.0-mini"],
};

/**
 * 教材检索控制台。当前阶段面向教师端/后台资料搜索，用来验证 BM25-first 检索证据是否可审计。
 */
export function App() {
  const api = useMemo(() => createTextbookApiClient(DEFAULT_BACKEND_URL), []);
  const [summary, setSummary] = useState<TextbookSummary | null>(null);
  const [summaryError, setSummaryError] = useState("");
  const [query, setQuery] = useState("分段函数");
  const [limit, setLimit] = useState(5);
  const [searchResult, setSearchResult] = useState<TextbookSearchResponse | null>(null);
  const [auditDetail, setAuditDetail] = useState<RetrievalAuditDetail | null>(null);
  const [searchError, setSearchError] = useState("");
  const [auditError, setAuditError] = useState("");
  const [teachingQuestion, setTeachingQuestion] = useState("已知函数 f(x) 的定义域为 R，求 D(-1)");
  const [learningGoal, setLearningGoal] = useState("理解函数新概念综合题");
  const [teachingTask, setTeachingTask] = useState<TeachingTaskResponse | null>(null);
  const [studentDashboard, setStudentDashboard] = useState<StudentDashboardResponse | null>(null);
  const [teacherResources, setTeacherResources] = useState<TeacherResourceDocumentResponse[]>([]);
  const [teacherSyncJobs, setTeacherSyncJobs] = useState<Record<string, TeacherSourceSyncJobResponse[]>>({});
  const [teacherSyncCheckpoints, setTeacherSyncCheckpoints] =
    useState<Record<string, TeacherSourceSyncCheckpointResponse>>({});
  const [knowledgePoints, setKnowledgePoints] = useState<KnowledgePointResponse[]>([]);
  const [questionBankItems, setQuestionBankItems] = useState<QuestionBankItemResponse[]>([]);
  const [teacherResourceSearchQuery, setTeacherResourceSearchQuery] = useState("space vector");
  const [teacherBlockSearchResult, setTeacherBlockSearchResult] = useState<TeacherResourceBlockSearchResponse | null>(null);
  const [feishuDiscoveryQuery, setFeishuDiscoveryQuery] = useState("空间向量");
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
  const [planningAgent, setPlanningAgent] = useState(false);
  const [executingAgent, setExecutingAgent] = useState(false);
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
  const [agentProvider, setAgentProvider] = useState("openai");
  const [agentModel, setAgentModel] = useState("gpt-5.4");
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
  const [resourceTitle, setResourceTitle] = useState("空间向量讲义");
  const [resourceLocation, setResourceLocation] = useState("");
  const [resourceSourceType, setResourceSourceType] = useState("local_path");
  const [resourceScope, setResourceScope] = useState("MATH_VIP");
  const [knowledgePointName, setKnowledgePointName] = useState("space vector dot product");
  const [knowledgeChapterPath, setKnowledgeChapterPath] = useState("selective compulsory / space vector");
  const [questionTitle, setQuestionTitle] = useState("vector angle");
  const [questionText, setQuestionText] = useState("Find the angle between two space vectors.");
  const [questionBankQuery, setQuestionBankQuery] = useState("vector");
  const [batchFolderPath, setBatchFolderPath] = useState("handouts/latest");
  const [loginUsername, setLoginUsername] = useState("teacher");
  const [loginPassword, setLoginPassword] = useState("teacher-123456");
  const [authSession, setAuthSession] = useState<LoginResponse | null>(() => readStoredAuthSession());
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
  const [teacherResourceImportResult, setTeacherResourceImportResult] =
    useState<TeacherBlockQuestionImportResponse | null>(null);
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
    loadStudentDashboard();
  }, [api]);

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
    refreshTeacherResources();
  }, [api]);

  useEffect(() => {
    refreshKnowledgeQuestionBank();
  }, [api]);

  useEffect(() => {
    refreshAgentTraces();
  }, [api]);

  useEffect(() => {
    api
      .getAgentModelCatalog()
      .then((catalog) => {
        setAgentModelCatalog(catalog);
        setAgentProvider(catalog.defaultProviderName);
        setAgentModel(catalog.defaultModelCode);
        setAgentModelCatalogError("");
      })
      .catch((error: Error) => setAgentModelCatalogError(error.message));
  }, [api]);

  useEffect(() => {
    refreshAgentModelHealth();
  }, [api]);

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
        return api.getAgentTraceUsageSummary({ limit: 100 }).then(setAgentUsageSummary);
      })
      .catch((error: Error) => setAgentTraceError(error.message))
      .finally(() => setLoadingAgentTraces(false));
  }

  function refreshKnowledgeQuestionBank() {
    setKnowledgeBankError("");
    Promise.all([
      api.listKnowledgePoints(),
      api.searchQuestionBankItems(questionBankQuery, 8),
    ])
      .then(([points, questions]) => {
        setKnowledgePoints(points);
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
      .then(setAuthSession)
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
      })
      .then((resource) => {
        setTeacherResources((current) => [resource, ...current]);
        setTeacherSyncJobs((current) => ({ ...current, [resource.documentId]: [] }));
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
        sourceSummary: "manual",
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

  function handleTeacherBlockSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!teacherResourceSearchQuery.trim()) {
      setTeacherResourceError("请输入要检索的资料关键词。");
      return;
    }
    setSearchingTeacherBlocks(true);
    setTeacherResourceError("");
    api
      .searchTeacherResourceBlocks(teacherResourceSearchQuery.trim(), 8)
      .then(setTeacherBlockSearchResult)
      .catch((error: Error) => setTeacherResourceError(error.message))
      .finally(() => setSearchingTeacherBlocks(false));
  }

  function handleDiscoverFeishu(mode: "list" | "search") {
    if (mode === "search" && !feishuDiscoveryQuery.trim()) {
      setTeacherResourceError("请输入飞书查找关键词。");
      return;
    }
    const rootUrl = resourceSourceType === "feishu" && resourceLocation.trim()
      ? resourceLocation.trim()
      : DEFAULT_FEISHU_ROOT_URL;
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
    setResourceTitle(candidate.name || candidate.path || "Feishu resource");
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
        userInputSummary: `Generate teacher handout for ${learningGoal}`,
        evidenceRefs: teachingTask ? [`teaching-task:${teachingTask.taskId}`] : ["textbook:planned-context"],
        dryRun: false,
      })
      .then((execution) => {
        setAgentExecution(execution);
        refreshAgentTraces();
      })
      .catch((error: Error) => setAgentExecutionError(error.message))
      .finally(() => setExecutingAgent(false));
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
              <select
                value={loginUsername}
                onChange={(event) => {
                  const value = event.target.value;
                  setLoginUsername(value);
                  setLoginPassword(`${value}-123456`);
                }}
              >
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
              <input value={query} onChange={(event) => setQuery(event.target.value)} />
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
              <input value={learningGoal} onChange={(event) => setLearningGoal(event.target.value)} />
            </label>
            <label>
              <span>题目/问题</span>
              <input value={teachingQuestion} onChange={(event) => setTeachingQuestion(event.target.value)} />
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
            <button type="submit" disabled={planningAgent}>
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
            loading={loadingTeacherResources}
            registering={registeringResource}
            searchingBlocks={searchingTeacherBlocks}
            syncingResourceId={syncingResourceId}
            importingResourceId={importingResourceId}
            importResult={teacherResourceImportResult}
            syncJobsByDocument={teacherSyncJobs}
            syncCheckpointsByJob={teacherSyncCheckpoints}
            blockSearchQuery={teacherResourceSearchQuery}
            blockSearchResult={teacherBlockSearchResult}
            feishuDiscoveryQuery={feishuDiscoveryQuery}
            feishuDiscoveryResult={feishuDiscoveryResult}
            discoveringFeishu={discoveringFeishu}
            error={teacherResourceError}
            onTitleChange={setResourceTitle}
            onLocationChange={setResourceLocation}
            onSourceTypeChange={setResourceSourceType}
            onScopeChange={setResourceScope}
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
          />
        </aside>

        <section className="result-panel">
          <StudentDashboardPanel
            dashboard={studentDashboard}
            loading={loadingStudentDashboard}
            error={studentDashboardError}
            onRefresh={handleRefreshStudentDashboard}
          />

          <KnowledgeQuestionBankPanel
            knowledgePoints={knowledgePoints}
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

          <AgentTracePanel
            traces={agentTraces}
            usageSummary={agentUsageSummary}
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

export function StudentDashboardPanel({
  dashboard,
  loading,
  error,
  onRefresh,
}: {
  dashboard: StudentDashboardResponse | null;
  loading: boolean;
  error: string;
  onRefresh?: () => void;
}) {
  const latestScore = dashboard?.scoreTrend.at(-1);
  return (
    <section className="student-dashboard">
      <div className="result-header">
        <div>
          <p className="eyebrow">Student Profile</p>
          <h2>学习画像</h2>
        </div>
        <div className="result-actions">
          {dashboard ? <div className="strategy-pill">{dashboard.viewerRole}</div> : null}
          {onRefresh ? (
            <button type="button" className="inline-action" onClick={onRefresh} disabled={loading}>
              {loading ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>Refresh snapshot</span>
            </button>
          ) : null}
        </div>
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取学习画像中" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {dashboard ? (
        <div className="dashboard-grid">
          <div className="profile-strip">
            <div>
              <span>学生</span>
              <strong>{dashboard.studentId}</strong>
            </div>
            <div>
              <span>查看者</span>
              <strong>{dashboard.viewerSubjectId}</strong>
            </div>
            <div>
              <span>最近成绩</span>
              <strong>{latestScore ? `${latestScore.score} / ${latestScore.rankInGrade}名` : "未记录"}</strong>
            </div>
          </div>

          <div className="knowledge-panel">
            <h3>知识点进度</h3>
            <div className="progress-list">
              {dashboard.knowledgeProgress.map((item) => (
                <div className="progress-item" key={item.knowledgePointId ?? item.knowledgePointName}>
                  <div className="progress-head">
                    <strong>{item.knowledgePointName}</strong>
                    <span>{item.progressPercent}%</span>
                  </div>
                  <div className="progress-track" aria-label={`${item.knowledgePointName} ${item.progressPercent}%`}>
                    <div className="progress-fill" style={{ width: `${boundedPercent(item.progressPercent)}%` }} />
                  </div>
                  <p>{item.textbookAnchor}</p>
                </div>
              ))}
            </div>
          </div>

          {dashboard.knowledgeGraph ? (
            <div className="knowledge-graph-panel">
              <div className="knowledge-graph-head">
                <h3>Knowledge Graph</h3>
                <span>{dashboard.knowledgeGraph.generatedFrom}</span>
              </div>
              <div className="knowledge-graph-nodes">
                {dashboard.knowledgeGraph.nodes.map((node) => (
                  <article className={`knowledge-node risk-${node.riskLevel}`} key={node.knowledgePointId}>
                    <div className="knowledge-node-main">
                      <strong>{node.knowledgePointName}</strong>
                      <span>{node.masteryPercent}%</span>
                    </div>
                    <p>{node.chapterPath}</p>
                    <div className="knowledge-node-meta">
                      <span>{node.riskLevel}</span>
                      {node.evidenceLinks.map((link) => (
                        <a href={link.url} key={`${node.knowledgePointId}:${link.sourceType}`}>
                          {link.sourceType}
                        </a>
                      ))}
                    </div>
                  </article>
                ))}
              </div>
              <div className="knowledge-graph-edges">
                {dashboard.knowledgeGraph.edges.map((edge) => (
                  <div className="knowledge-edge" key={edge.edgeId}>
                    <strong>{edge.relationType}</strong>
                    <span>
                      {edge.sourceKnowledgePointId} -&gt; {edge.targetKnowledgePointId}
                    </span>
                    <p>{edge.evidenceSummary}</p>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          <div className="dashboard-column">
            <h3>薄弱点</h3>
            {dashboard.weakPoints.map((item) => (
              <div className="weak-item" key={item.knowledgePointId ?? item.knowledgePointName}>
                <div>
                  <strong>{item.knowledgePointName}</strong>
                  <span>等级 {item.weaknessLevel}</span>
                </div>
                <p>{item.evidenceSummary}</p>
              </div>
            ))}
          </div>

          <div className="dashboard-column">
            <h3>历史问题</h3>
            {dashboard.recentQuestions.map((item) => (
              <div className="question-item" key={item.recordId}>
                <strong>{item.questionTitle}</strong>
                <span>{item.sourceType} / {item.status}</span>
              </div>
            ))}
          </div>

          <div className="dashboard-column score-column">
            <h3>成绩趋势</h3>
            {dashboard.scoreTrend.map((item) => (
              <div className="score-item" key={item.examName}>
                <span>{item.examName}</span>
                <div className="score-bar">
                  <div style={{ width: `${boundedPercent((item.score / 150) * 100)}%` }} />
                </div>
                <strong>{item.score}</strong>
              </div>
            ))}
          </div>

          <div className="scope-list">
            {dashboard.resourceScopes.map((scope) => (
              <span key={scope.scopeCode}>{scope.scopeName ?? scope.scopeCode}</span>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}

function KnowledgeQuestionBankPanel({
  knowledgePoints,
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
  return (
    <section className="agent-plan-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Knowledge Bank</p>
          <h2>Knowledge points and questions</h2>
        </div>
        <div className="strategy-pill">{knowledgePoints.length} points</div>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      <div className="agent-plan-grid">
        <form className="search-form" onSubmit={onCreateKnowledgePoint}>
          <label>
            <span>Knowledge point</span>
            <input value={knowledgePointName} onChange={(event) => onKnowledgePointNameChange(event.target.value)} />
          </label>
          <label>
            <span>Chapter path</span>
            <input value={chapterPath} onChange={(event) => onChapterPathChange(event.target.value)} />
          </label>
          <button type="submit" disabled={saving}>
            {saving ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>Add point</span>
          </button>
        </form>
        <form className="search-form" onSubmit={onCreateQuestion}>
          <label>
            <span>Question title</span>
            <input value={questionTitle} onChange={(event) => onQuestionTitleChange(event.target.value)} />
          </label>
          <label>
            <span>Question text</span>
            <input value={questionText} onChange={(event) => onQuestionTextChange(event.target.value)} />
          </label>
          <button type="submit" disabled={saving}>
            {saving ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
            <span>Add question</span>
          </button>
        </form>
        <form className="resource-block-search" onSubmit={onSearchQuestions}>
          <label>
            <span>Question search</span>
            <input value={query} onChange={(event) => onQueryChange(event.target.value)} />
          </label>
          <button type="submit">
            <Search size={16} />
            <span>Search</span>
          </button>
        </form>
        <div className="tool-decision-list compact">
          {knowledgePoints.slice(0, 6).map((point) => (
            <div className="tool-decision allowed" key={point.knowledgePointId}>
              <strong>{point.knowledgePointName}</strong>
              <span>{point.permissionScope}</span>
              <p>{point.chapterPath}</p>
            </div>
          ))}
        </div>
        <div className="resource-search-results">
          {questions.map((question) => (
            <article className="resource-search-hit" key={question.questionId}>
              <strong>{question.questionTitle}</strong>
              <span>{question.permissionScope ?? "scope"} / {question.difficulty ?? "medium"}</span>
              <p>{question.questionText}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function McpConfigurationForm({
  url,
  secretKey,
  secretEnvName,
  selectedTools,
  selectedPrompts,
  building,
  error,
  onUrlChange,
  onSecretKeyChange,
  onSecretEnvNameChange,
  onToolToggle,
  onPromptToggle,
  onSubmit,
}: {
  url: string;
  secretKey: string;
  secretEnvName: string;
  selectedTools: string[];
  selectedPrompts: string[];
  building: boolean;
  error: string;
  onUrlChange: (value: string) => void;
  onSecretKeyChange: (value: string) => void;
  onSecretEnvNameChange: (value: string) => void;
  onToolToggle: (option: string, checked: boolean) => void;
  onPromptToggle: (option: string, checked: boolean) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <section className="mcp-config-form">
      <PanelTitle icon={<ShieldCheck size={18} />} title="MCP 配置" />
      <form className="search-form" onSubmit={onSubmit}>
        <label>
          <span>URL</span>
          <input value={url} onChange={(event) => onUrlChange(event.target.value)} />
        </label>
        <label>
          <span>secretKey</span>
          <input
            type="password"
            value={secretKey}
            onChange={(event) => onSecretKeyChange(event.target.value)}
            placeholder="mcp_secret_..."
          />
        </label>
        <label>
          <span>环境变量</span>
          <input value={secretEnvName} onChange={(event) => onSecretEnvNameChange(event.target.value)} />
        </label>
        <McpOptionGroup
          title="Tools"
          options={MCP_TOOL_OPTIONS}
          selected={selectedTools}
          onToggle={onToolToggle}
        />
        <McpOptionGroup
          title="Prompts"
          options={MCP_PROMPT_OPTIONS}
          selected={selectedPrompts}
          onToggle={onPromptToggle}
        />
        <button type="submit" disabled={building}>
          {building ? <Loader2 className="spin" size={17} /> : <ShieldCheck size={17} />}
          <span>生成 JSON</span>
        </button>
      </form>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
    </section>
  );
}

function McpOptionGroup({
  title,
  options,
  selected,
  onToggle,
}: {
  title: string;
  options: readonly string[];
  selected: string[];
  onToggle: (option: string, checked: boolean) => void;
}) {
  return (
    <fieldset className="mcp-option-group">
      <legend>{title}</legend>
      {options.map((option) => (
        <label className="toggle-row" key={option}>
          <input
            type="checkbox"
            checked={selected.includes(option)}
            onChange={(event) => onToggle(option, event.target.checked)}
          />
          <span>{option}</span>
        </label>
      ))}
    </fieldset>
  );
}

function McpConfigurationPanel({
  configuration,
  copyMessage,
  onCopy,
}: {
  configuration: McpConfigurationResponse | null;
  copyMessage: string;
  onCopy: () => void;
}) {
  return (
    <section className="mcp-config-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">MCP</p>
          <h2>外部客户端配置</h2>
        </div>
        {configuration ? <div className="strategy-pill">{configuration.keyProfile}</div> : null}
      </div>
      {configuration ? (
        <div className="mcp-config-grid">
          <div className="profile-strip">
            <div>
              <span>URL</span>
              <strong>{configuration.url}</strong>
            </div>
            <div>
              <span>Secret</span>
              <strong>{configuration.secretKeyPreview}</strong>
            </div>
            <div>
              <span>Env</span>
              <strong>{configuration.secretEnvName}</strong>
            </div>
          </div>
          <div className="mcp-exposure-list">
            <McpExposureColumn title="Tools" items={configuration.exposedTools} />
            <McpExposureColumn title="Prompts" items={configuration.exposedPrompts} />
          </div>
          <div className="mcp-layer-list">
            {configuration.layers.map((layer) => (
              <div className="mcp-layer" key={layer.code}>
                <strong>{layer.name}</strong>
                <span>{layer.description}</span>
              </div>
            ))}
          </div>
          <div className="mcp-json-head">
            <strong>config.json</strong>
            <button type="button" onClick={onCopy}>复制</button>
          </div>
          {copyMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={copyMessage} /> : null}
          <pre className="formula-block mcp-json">{configuration.configJson}</pre>
        </div>
      ) : (
        <div className="empty-state compact">生成配置后，这里展示后端过滤后的 MCP tools、prompts 和可复制 JSON。</div>
      )}
    </section>
  );
}

function McpExposureColumn({ title, items }: { title: string; items: string[] }) {
  return (
    <div className="mcp-exposure-column">
      <strong>{title}</strong>
      {items.map((item) => (
        <span key={item}>{item}</span>
      ))}
    </div>
  );
}

function TeacherResourcePanel({
  resources,
  title,
  location,
  sourceType,
  scope,
  loading,
  registering,
  searchingBlocks,
  syncingResourceId,
  importingResourceId,
  importResult,
  syncJobsByDocument,
  syncCheckpointsByJob,
  blockSearchQuery,
  blockSearchResult,
  feishuDiscoveryQuery,
  feishuDiscoveryResult,
  discoveringFeishu,
  error,
  onTitleChange,
  onLocationChange,
  onSourceTypeChange,
  onScopeChange,
  onBlockSearchQueryChange,
  onBlockSearch,
  onFeishuDiscoveryQueryChange,
  onDiscoverFeishu,
  onUseFeishuCandidate,
  onRegister,
  onArchive,
  onSync,
  onResume,
  onImportQuestions,
}: {
  resources: TeacherResourceDocumentResponse[];
  title: string;
  location: string;
  sourceType: string;
  scope: string;
  loading: boolean;
  registering: boolean;
  searchingBlocks: boolean;
  syncingResourceId: string;
  importingResourceId: string;
  importResult: TeacherBlockQuestionImportResponse | null;
  syncJobsByDocument: Record<string, TeacherSourceSyncJobResponse[]>;
  syncCheckpointsByJob: Record<string, TeacherSourceSyncCheckpointResponse>;
  blockSearchQuery: string;
  blockSearchResult: TeacherResourceBlockSearchResponse | null;
  feishuDiscoveryQuery: string;
  feishuDiscoveryResult: TeacherFeishuDiscoveryResponse | null;
  discoveringFeishu: boolean;
  error: string;
  onTitleChange: (value: string) => void;
  onLocationChange: (value: string) => void;
  onSourceTypeChange: (value: string) => void;
  onScopeChange: (value: string) => void;
  onBlockSearchQueryChange: (value: string) => void;
  onBlockSearch: (event: FormEvent<HTMLFormElement>) => void;
  onFeishuDiscoveryQueryChange: (value: string) => void;
  onDiscoverFeishu: (mode: "list" | "search") => void;
  onUseFeishuCandidate: (candidate: TeacherFeishuDiscoveryCandidate) => void;
  onRegister: (event: FormEvent<HTMLFormElement>) => void;
  onArchive: (documentId: string) => void;
  onSync: (documentId: string) => void;
  onResume: (documentId: string, jobId: string) => void;
  onImportQuestions: (documentId: string) => void;
}) {
  return (
    <section className="teacher-resource-panel">
      <PanelTitle icon={<Database size={18} />} title="教师资料源" />
      <form className="search-form" onSubmit={onRegister}>
        <label>
          <span>标题</span>
          <input value={title} onChange={(event) => onTitleChange(event.target.value)} />
        </label>
        <label>
          <span>来源</span>
          <select value={sourceType} onChange={(event) => onSourceTypeChange(event.target.value)}>
            <option value="local_path">本地路径</option>
            <option value="feishu">飞书 URL</option>
          </select>
        </label>
        <label>
          <span>{sourceType === "feishu" ? "飞书 URL" : "本地路径"}</span>
          <input value={location} onChange={(event) => onLocationChange(event.target.value)} />
        </label>
        <label>
          <span>权限域</span>
          <select value={scope} onChange={(event) => onScopeChange(event.target.value)}>
            <option value="TEACHER_PRIVATE">教师私有</option>
            <option value="MATH_VIP">数学 VIP</option>
            <option value="PUBLIC_TEXTBOOK">公开教材</option>
          </select>
        </label>
        <button type="submit" disabled={registering}>
          {registering ? <Loader2 className="spin" size={17} /> : <Database size={17} />}
          <span>登记资料</span>
        </button>
      </form>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="读取教师资料源中" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {importResult ? (
        <StatusLine
          icon={<Database size={16} />}
          text={`Imported ${importResult.importedQuestionCount}, skipped ${importResult.skippedBlockCount}, duplicate ${importResult.duplicateBlockCount}`}
        />
      ) : null}
      <div className="feishu-discovery-panel">
        <label>
          <span>飞书查找</span>
          <input value={feishuDiscoveryQuery} onChange={(event) => onFeishuDiscoveryQueryChange(event.target.value)} />
        </label>
        <div className="feishu-discovery-actions">
          <button type="button" onClick={() => onDiscoverFeishu("list")} disabled={discoveringFeishu}>
            {discoveringFeishu ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
            <span>List</span>
          </button>
          <button type="button" onClick={() => onDiscoverFeishu("search")} disabled={discoveringFeishu}>
            {discoveringFeishu ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            <span>Search</span>
          </button>
        </div>
        {feishuDiscoveryResult ? (
          <div className="feishu-candidate-list">
            <div className="resource-search-summary">
              <span>{feishuDiscoveryResult.mode}</span>
              <span>{feishuDiscoveryResult.candidateCount} candidates</span>
            </div>
            {feishuDiscoveryResult.candidates.map((candidate) => (
              <article className="feishu-candidate" key={`${candidate.resourceType}:${candidate.token}`}>
                <div>
                  <strong>{candidate.name}</strong>
                  <span>{candidate.resourceType} / {candidate.path}</span>
                </div>
                <button type="button" onClick={() => onUseFeishuCandidate(candidate)}>
                  Use
                </button>
              </article>
            ))}
          </div>
        ) : null}
      </div>
      <form className="resource-block-search" onSubmit={onBlockSearch}>
        <label>
          <span>资料块检索</span>
          <input value={blockSearchQuery} onChange={(event) => onBlockSearchQueryChange(event.target.value)} />
        </label>
        <button type="submit" disabled={searchingBlocks}>
          {searchingBlocks ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>Search</span>
        </button>
      </form>
      {blockSearchResult ? (
        <div className="resource-search-results">
          <div className="resource-search-summary">
            <span>{blockSearchResult.retrievalMode}</span>
            <span>{blockSearchResult.hitCount} hits</span>
          </div>
          {blockSearchResult.hits.map((hit) => (
            <article className="resource-search-hit" key={`${hit.documentId}:${hit.blockId}`}>
              <strong>{hit.documentTitle}</strong>
              <span>
                {hit.permissionScope} / {hit.blockType}
                {hit.pageNo ? ` / p.${hit.pageNo}` : ""}
              </span>
              <p>{hit.snippet}</p>
            </article>
          ))}
        </div>
      ) : null}
      <div className="resource-list">
        {resources.map((resource) => {
          const latestJob = syncJobsByDocument[resource.documentId]?.[0];
          const latestCheckpoint = latestJob ? syncCheckpointsByJob[latestJob.jobId] : undefined;
          return (
          <article className="resource-item" key={resource.documentId}>
            <div>
              <strong>{resource.title}</strong>
              <span>{resource.sourceType} / {resource.permissionScope}</span>
            </div>
            <div className="resource-status">
              <span>{latestJob?.status ?? resource.syncStatus}</span>
              <span>{latestJob?.phase ?? resource.indexStatus ?? "waiting_rebuild"}</span>
            </div>
            {latestJob ? (
              <p>{latestJob.operation}: {latestJob.message ?? latestJob.createdAt ?? latestJob.jobId}</p>
            ) : resource.previewFiles?.length ? (
              <p>{resource.previewFiles.map((file) => file.fileName).join("、")}</p>
            ) : null}
            {latestCheckpoint ? <SyncCheckpointView checkpoint={latestCheckpoint} /> : null}
            <button
              type="button"
              onClick={() => onSync(resource.documentId)}
              disabled={syncingResourceId === resource.documentId}
            >
              {syncingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
              <span>Sync</span>
            </button>
            {latestJob?.status === "paused" ? (
              <button
                type="button"
                onClick={() => onResume(resource.documentId, latestJob.jobId)}
                disabled={syncingResourceId === resource.documentId}
              >
                {syncingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
                <span>Resume</span>
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => onImportQuestions(resource.documentId)}
              disabled={importingResourceId === resource.documentId}
            >
              {importingResourceId === resource.documentId ? <Loader2 className="spin" size={15} /> : <Database size={15} />}
              <span>Import questions</span>
            </button>
            <button type="button" onClick={() => onArchive(resource.documentId)}>
              归档
            </button>
          </article>
          );
        })}
      </div>
    </section>
  );
}

function SyncCheckpointView({ checkpoint }: { checkpoint: TeacherSourceSyncCheckpointResponse }) {
  return (
    <div className="sync-checkpoint">
      <div>
        <span>Checkpoint</span>
        <strong>{checkpoint.currentPath || checkpoint.currentFolderToken}</strong>
      </div>
      <div className="sync-checkpoint-grid">
        <span>{checkpoint.pageToken ? `cursor ${checkpoint.pageToken}` : "cursor none"}</span>
        <span>{countJsonArray(checkpoint.downloadedItemsJson)} downloaded</span>
        <span>{countJsonArray(checkpoint.failedItemsJson)} failed</span>
        <span>v{checkpoint.cursorVersion}</span>
      </div>
    </div>
  );
}

function TeachingTaskPanel({
  task,
  loading,
  error,
  version,
  previewLatex,
  action,
  exportMessage,
  feedbackRating,
  feedbackDecision,
  feedbackComment,
  submittingFeedback,
  feedbackMessage,
  batchFolderPath,
  onVersionChange,
  onBatchFolderPathChange,
  onPreviewLatex,
  onExportLatex,
  onExportPdf,
  onExportBatchZip,
  onFeedbackRatingChange,
  onFeedbackDecisionChange,
  onFeedbackCommentChange,
  onSubmitFeedback,
}: {
  task: TeachingTaskResponse | null;
  loading: boolean;
  error: string;
  version: "teacher" | "student";
  previewLatex: string;
  action: string;
  exportMessage: string;
  feedbackRating: number;
  feedbackDecision: string;
  feedbackComment: string;
  submittingFeedback: boolean;
  feedbackMessage: string;
  batchFolderPath: string;
  onVersionChange: (value: "teacher" | "student") => void;
  onBatchFolderPathChange: (value: string) => void;
  onPreviewLatex: () => void;
  onExportLatex: () => void;
  onExportPdf: () => void;
  onExportBatchZip: () => void;
  onFeedbackRatingChange: (value: number) => void;
  onFeedbackDecisionChange: (value: string) => void;
  onFeedbackCommentChange: (value: string) => void;
  onSubmitFeedback: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const busy = Boolean(action);
  const selectedDraft = version === "student"
    ? task?.studentHandoutLatex ?? task?.handoutLatex ?? ""
    : task?.teacherHandoutLatex ?? task?.handoutLatex ?? "";
  return (
    <section className="teaching-task">
      <div className="result-header">
        <div>
          <p className="eyebrow">Teaching DAG</p>
          <h2>可恢复教学任务</h2>
        </div>
        {task ? <div className="strategy-pill">{task.status}</div> : null}
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="正在恢复上次教学任务" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {!task && !loading && !error ? (
        <div className="empty-state compact">提交教学任务后，这里会展示 DAG、ReAct 轨迹、教材证据和 LaTeX 讲义草稿。</div>
      ) : null}
      {task ? (
        <div className="teaching-grid">
          <div className="task-meta">
            <span>Task</span>
            <strong>{task.taskId}</strong>
            <span>Learning goal</span>
            <strong>{task.learningGoal}</strong>
          </div>
          <div className="memory-strip">
            <div>
              <span>Memory</span>
              <strong>{task.memoryReuse?.reused ? "reused" : "not reused"}</strong>
            </div>
            <div>
              <span>Scope</span>
              <strong>{task.memoryReuse?.reuseScope ?? "none"}</strong>
            </div>
            <div>
              <span>Similarity</span>
              <strong>{formatSimilarity(task.memoryReuse?.similarity)}</strong>
            </div>
            <p>{task.memoryReuse?.reason ?? "未记录记忆复用决策。"}</p>
          </div>
          {task.stageTimings?.length ? (
            <div className="timing-list">
              {task.stageTimings.map((timing) => (
                <div className="timing-item" key={timing.stage}>
                  <span>{stageLabel(timing.stage)}</span>
                  <strong>{timing.elapsedMs} ms</strong>
                </div>
              ))}
            </div>
          ) : null}
          <div className="node-list">
            {task.nodes.map((node) => (
              <div className="node-item" key={node.code}>
                <strong>{node.name}</strong>
                <span>{node.summary}</span>
              </div>
            ))}
          </div>
          <div className="react-list">
            {task.reactTrace.map((step, index) => (
              <div className="react-item" key={`${step.phase}-${index}`}>
                <strong>{step.phase}</strong>
                <span>{step.toolName ? `${step.toolName}: ` : ""}{step.content}</span>
              </div>
            ))}
          </div>
          <div className="hit-list">
            {task.evidence.map((item) => (
              <article className="evidence-card teaching-evidence-card" key={item.chunkId}>
                <div className="scope-badge">{item.sourceScope}</div>
                <div className="card-main">
                  <div className="card-head">
                    <h3>{item.sourceTitle}</h3>
                  </div>
                  <div className="meta-row">
                    <span>{item.chunkId}</span>
                    <span>PDF {item.pageNo}</span>
                  </div>
                  <p className="snippet">{item.snippet}</p>
                </div>
              </article>
            ))}
          </div>
          <div className="handout-version-row">
            <label>
              <span>Handout version</span>
              <select
                value={version}
                onChange={(event) => onVersionChange(event.target.value as "teacher" | "student")}
              >
                <option value="teacher">Teacher</option>
                <option value="student">Student</option>
              </select>
            </label>
          </div>
          <div className="handout-toolbar">
            <button type="button" onClick={onPreviewLatex} disabled={busy}>
              {action === "preview" ? <Loader2 className="spin" size={16} /> : <BookOpen size={16} />}
              <span>Preview LaTeX</span>
            </button>
            <button type="button" onClick={onExportLatex} disabled={busy}>
              {action === "latex" ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>Export TeX</span>
            </button>
            <button type="button" onClick={onExportPdf} disabled={busy}>
              {action === "pdf" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>Export PDF</span>
            </button>
          </div>
          <div className="batch-export-row">
            <label>
              <span>ZIP folder</span>
              <input
                value={batchFolderPath}
                onChange={(event) => onBatchFolderPathChange(event.target.value)}
                placeholder={`handouts/${task.taskId}`}
              />
            </label>
            <button type="button" onClick={onExportBatchZip} disabled={busy}>
              {action === "zip" ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              <span>Export ZIP</span>
            </button>
          </div>
          {exportMessage ? <StatusLine icon={<ShieldCheck size={16} />} text={exportMessage} /> : null}
          {previewLatex ? (
            <pre className="formula-block handout preview">{previewLatex}</pre>
          ) : (
            <pre className="formula-block handout">{selectedDraft}</pre>
          )}
          <form className="human-feedback-panel" onSubmit={onSubmitFeedback}>
            <div className="feedback-head">
              <strong>Human feedback</strong>
              {feedbackMessage ? <span>{feedbackMessage}</span> : null}
            </div>
            <div className="feedback-grid">
              <label>
                <span>Rating</span>
                <input
                  type="number"
                  min={1}
                  max={5}
                  value={feedbackRating}
                  onChange={(event) => onFeedbackRatingChange(Number(event.target.value))}
                />
              </label>
              <label>
                <span>Decision</span>
                <select value={feedbackDecision} onChange={(event) => onFeedbackDecisionChange(event.target.value)}>
                  <option value="helpful">Helpful</option>
                  <option value="confusing">Confusing</option>
                  <option value="needs_revision">Needs revision</option>
                </select>
              </label>
            </div>
            <label>
              <span>Comment</span>
              <textarea
                value={feedbackComment}
                onChange={(event) => onFeedbackCommentChange(event.target.value)}
                placeholder="Record what should be improved or kept."
              />
            </label>
            <button type="submit" disabled={submittingFeedback}>
              {submittingFeedback ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>Submit feedback</span>
            </button>
          </form>
        </div>
      ) : null}
    </section>
  );
}

function AgentPlanPanel({
  plan,
  execution,
  loading,
  executing,
  error,
  onExecute,
}: {
  plan: AgentRunPlanResponse | null;
  execution: AgentRunExecuteResponse | null;
  loading: boolean;
  executing: boolean;
  error: string;
  onExecute: () => void;
}) {
  return (
    <section className="agent-plan-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Agent Policy</p>
          <h2>Dynamic tool injection</h2>
        </div>
        {plan ? <div className="strategy-pill">{plan.agentCode}</div> : null}
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="Planning agent tool policy" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {plan ? (
        <div className="agent-plan-grid">
          <div className="profile-strip">
            <div>
              <span>Provider</span>
              <strong>{plan.providerName}</strong>
            </div>
            <div>
              <span>Model</span>
              <strong>{plan.modelCode}</strong>
            </div>
            <div>
              <span>Capability</span>
              <strong>{plan.capabilityRequired ? plan.capabilityAction : "not required"}</strong>
            </div>
            <div>
              <span>Est. tokens</span>
              <strong>{plan.estimatedTotalTokens}</strong>
            </div>
          </div>
          <div className="tool-decision-list">
            {plan.toolPolicyDecisions.map((decision) => (
              <div className={`tool-decision ${decision.decision.toLowerCase()}`} key={decision.scope}>
                <strong>{decision.scope}</strong>
                <span>{decision.decision}</span>
                <p>{decision.reason}</p>
              </div>
            ))}
          </div>
          <div className="agent-execution-panel">
            <button type="button" onClick={onExecute} disabled={executing}>
              {executing ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              <span>Execute model</span>
            </button>
            {execution ? (
              <div className="profile-strip">
                <div>
                  <span>Actual provider</span>
                  <strong>{execution.providerName}</strong>
                </div>
                <div>
                  <span>Actual model</span>
                  <strong>{execution.modelCode}</strong>
                </div>
                <div>
                  <span>Token usage</span>
                  <strong>
                    {execution.actualUsage.totalTokens} total / {execution.actualUsage.promptTokens} prompt /{" "}
                    {execution.actualUsage.completionTokens} completion
                  </strong>
                </div>
                <div>
                  <span>Status</span>
                  <strong>{execution.status}</strong>
                </div>
              </div>
            ) : null}
            {execution ? (
              <div className="execution-trace">
                <p>{execution.message}</p>
                <div className="tool-decision-list compact">
                  {execution.stageTimings.map((timing) => (
                    <div className="tool-decision allowed" key={timing.stage}>
                      <strong>{timing.stage}</strong>
                      <span>{timing.elapsedMs} ms</span>
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        </div>
      ) : (
        <div className="empty-state compact">Plan an agent run to see which tools the backend will inject.</div>
      )}
    </section>
  );
}

function AgentModelHealthPanel({
  health,
  error,
  loading,
  expanded,
  onToggle,
  onRefresh,
}: {
  health: AgentModelHealthResponse | null;
  error: string;
  loading: boolean;
  expanded: boolean;
  onToggle: () => void;
  onRefresh: () => void;
}) {
  const reachableCount = health?.results.filter((result) => result.reachable).length ?? 0;
  const totalCount = health?.results.length ?? 0;
  const summary = totalCount > 0 ? `${reachableCount}/${totalCount} reachable` : "not checked";
  return (
    <div className="agent-health-panel">
      <div className="agent-health-head">
        <button type="button" className="inline-action compact" onClick={onToggle}>
          <ShieldCheck size={15} />
          <span>Model health</span>
          <strong>{summary}</strong>
        </button>
        <button type="button" className="inline-action icon-only" onClick={onRefresh} disabled={loading}>
          {loading ? <Loader2 className="spin" size={15} /> : <Search size={15} />}
        </button>
      </div>
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {expanded && health ? (
        <div className="agent-health-list">
          {health.results.map((result) => (
            <div
              className={`agent-health-row ${result.reachable ? "reachable" : "unreachable"}`}
              key={`${result.providerName}:${result.modelCode}`}
            >
              <div>
                <strong>
                  {result.providerName} / {result.modelCode}
                </strong>
                <span>{result.safeReason}</span>
              </div>
              <em>
                {result.statusCode ?? "n/a"} · {result.elapsedMs} ms
              </em>
            </div>
          ))}
          <span className="agent-health-time">{formatDateTime(health.checkedAt)}</span>
        </div>
      ) : null}
    </div>
  );
}

export function AgentTracePanel({
  traces,
  usageSummary,
  loading,
  error,
  onRefresh,
}: {
  traces: AgentTraceResponse[];
  usageSummary: AgentTraceUsageSummaryResponse | null;
  loading: boolean;
  error: string;
  onRefresh: () => void;
}) {
  return (
    <section className="agent-trace-panel">
      <div className="result-header">
        <div>
          <p className="eyebrow">Run Recovery</p>
          <h2>Agent execution history</h2>
        </div>
        <button type="button" className="inline-action" onClick={onRefresh} disabled={loading}>
          {loading ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
          <span>Refresh</span>
        </button>
      </div>
      {loading ? <StatusLine icon={<Loader2 className="spin" size={16} />} text="Loading recoverable traces" /> : null}
      {error ? <StatusLine icon={<AlertCircle size={16} />} text={error} tone="danger" /> : null}
      {usageSummary ? (
        <div className="agent-usage-summary">
          <div className="result-header compact">
            <div>
              <p className="eyebrow">Usage summary</p>
              <h3>{usageSummary.runCount} runs</h3>
            </div>
            <strong>
              {usageSummary.totalUsage.totalTokens} total / {usageSummary.totalUsage.promptTokens} prompt /{" "}
              {usageSummary.totalUsage.completionTokens} completion
            </strong>
          </div>
          <div className="trace-badge-row">
            <span>Models</span>
            <div>
              {usageSummary.modelUsages.map((usage) => (
                <strong key={`${usage.providerName}:${usage.modelCode}`}>
                  {usage.providerName}/{usage.modelCode}: {usage.totalTokens} total, {usage.promptTokens} prompt,{" "}
                  {usage.completionTokens} completion
                </strong>
              ))}
            </div>
          </div>
        </div>
      ) : null}
      {traces.length > 0 ? (
        <div className="agent-trace-list">
          {traces.map((trace) => (
            <article className="agent-trace-item" key={trace.traceId}>
              <div className="card-head">
                <div>
                  <h3>{trace.agentCode}</h3>
                  <p>{formatDateTime(trace.createdAt)}</p>
                </div>
                <span className="quality-badge good">{trace.status}</span>
              </div>
              <div className="profile-strip">
                <div>
                  <span>Trace</span>
                  <strong>{trace.traceId}</strong>
                </div>
                <div>
                  <span>Model</span>
                  <strong>
                    {trace.providerName} / {trace.modelCode}
                  </strong>
                </div>
                <div>
                  <span>Backend subject</span>
                  <strong>
                    {trace.subjectType}:{trace.subjectId}
                  </strong>
                </div>
                <div>
                  <span>Token usage</span>
                  <strong>
                    {trace.actualUsage.totalTokens} total / {trace.actualUsage.promptTokens} prompt /{" "}
                    {trace.actualUsage.completionTokens} completion
                  </strong>
                </div>
              </div>
              <div className="execution-trace">
                <p>{trace.message}</p>
                <div className="tool-decision-list compact">
                  {trace.stageTimings.map((timing) => (
                    <div className="tool-decision allowed" key={`${trace.traceId}:${timing.stage}`}>
                      <strong>{timing.stage}</strong>
                      <span>{timing.elapsedMs} ms</span>
                    </div>
                  ))}
                </div>
              </div>
              <TraceBadgeRow label="Tools" values={trace.allowedToolScopes} />
              <TraceBadgeRow label="Data" values={trace.allowedDataScopes} />
              <TraceBadgeRow label="Evidence" values={trace.evidenceRefs} />
            </article>
          ))}
        </div>
      ) : !loading ? (
        <div className="empty-state compact">No recoverable agent traces for the current backend session.</div>
      ) : null}
    </section>
  );
}

function TraceBadgeRow({ label, values }: { label: string; values: string[] }) {
  return (
    <div className="trace-badge-row">
      <span>{label}</span>
      <div>
        {values.length > 0 ? values.map((value) => <strong key={value}>{value}</strong>) : <strong>none</strong>}
      </div>
    </div>
  );
}

function boundedPercent(value: number) {
  return Math.max(0, Math.min(100, value));
}

function readStoredAuthSession() {
  try {
    const value = globalThis.localStorage?.getItem("math-agent:auth-session");
    return value ? (JSON.parse(value) as LoginResponse) : null;
  } catch {
    return null;
  }
}

function formatSimilarity(value?: number) {
  return value === undefined ? "0.0000" : value.toFixed(4);
}

function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function countJsonArray(value: string) {
  try {
    const parsed = JSON.parse(value || "[]");
    return Array.isArray(parsed) ? parsed.length : 0;
  } catch {
    return 0;
  }
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

function stageLabel(stage: string) {
  const labels: Record<string, string> = {
    memory_reuse: "记忆复用",
    reuse_short_circuit: "复用短路",
    textbook_retrieval: "教材检索",
    react_trace: "ReAct 轨迹",
    handout_generation: "讲义生成",
  };
  return labels[stage] ?? stage;
}

function PanelTitle({ icon, title }: { icon: React.ReactNode; title: string }) {
  return (
    <div className="panel-title">
      {icon}
      <span>{title}</span>
    </div>
  );
}

function agentProviders(catalog: AgentModelCatalogResponse | null): string[] {
  const providers = catalog?.providers.filter((provider) => provider.enabled).map((provider) => provider.name) ?? [];
  return providers.length > 0 ? providers : Object.keys(AGENT_MODEL_OPTIONS);
}

function agentModelsForProvider(
  catalog: AgentModelCatalogResponse | null,
  providerName: string,
): { modelCode: string; modelLevel: string; priceTier: string }[] {
  const provider = catalog?.providers.find((candidate) => candidate.name === providerName && candidate.enabled);
  if (provider && provider.models.length > 0) {
    return provider.models;
  }
  return (AGENT_MODEL_OPTIONS[providerName] ?? []).map((modelCode) => ({
    modelCode,
    modelLevel: modelCode.includes("mini") || modelCode.includes("flash") || modelCode.includes("lite")
      ? "fast_text"
      : "reasoning",
    priceTier: modelCode.includes("mini") || modelCode.includes("flash") || modelCode.includes("lite")
      ? "cheap"
      : "standard",
  }));
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value.toLocaleString("zh-CN")}</strong>
    </div>
  );
}

function StatusLine({
  icon,
  text,
  tone = "muted",
}: {
  icon: React.ReactNode;
  text: string;
  tone?: "muted" | "danger";
}) {
  return (
    <div className={`status-line ${tone}`}>
      {icon}
      <span>{text}</span>
    </div>
  );
}

function AuditDetailPanel({ audit }: { audit: RetrievalAuditDetail }) {
  const firstHit = audit.hits[0];
  return (
    <section className="audit-detail">
      <div className="audit-detail-grid">
        <Metric label="耗时 ms" value={audit.elapsedMs} />
        <Metric label="命中" value={audit.hitCount} />
        <Metric label="Top K" value={audit.requestedLimit} />
      </div>
      <div className="audit-detail-row">
        <span>Endpoint</span>
        <strong>{audit.requestContext?.endpoint || "未记录"}</strong>
      </div>
      <div className="audit-detail-row">
        <span>主体</span>
        <strong>
          {audit.subjectType || "anonymous"}
          {audit.subjectId ? ` / ${audit.subjectId}` : ""}
        </strong>
      </div>
      {firstHit ? (
        <div className="audit-detail-row">
          <span>Top hit</span>
          <strong>
            #{firstHit.rankNo} {firstHit.chunkId} / {firstHit.pageQualityLabel}
          </strong>
        </div>
      ) : null}
    </section>
  );
}

function EvidenceCard({ hit, rank }: { hit: TextbookSearchHit; rank: number }) {
  return (
    <article className="evidence-card">
      <div className="card-rank">{rank}</div>
      <div className="card-main">
        <div className="card-head">
          <div>
            <h3>{hit.sectionTitle || hit.bookName}</h3>
            <p>
              {hit.bookName} / {hit.volume}
            </p>
          </div>
          <QualityBadge label={hit.pageQualityLabel} />
        </div>
        <div className="meta-row">
          <span>PDF {hit.pageNo}</span>
          <span>印刷页 {hit.printedPageNo || "未识别"}</span>
          <span>Score {hit.score.toFixed(4)}</span>
          <span>{hit.retrievalStrategy}</span>
        </div>
        <p className="chapter-path">{hit.chapterPath.join(" / ")}</p>
        <p className="snippet">{hit.textSnippet}</p>
        {hit.formulaText ? <pre className="formula-block">{hit.formulaText.slice(0, 360)}</pre> : null}
        <div className="source-row">
          <span>{hit.chunkId}</span>
          <span>{hit.sourcePageImage}</span>
        </div>
      </div>
    </article>
  );
}

function QualityBadge({ label }: { label: string }) {
  const tone = label === "content_page" ? "good" : "warn";
  return <span className={`quality-badge ${tone}`}>{label}</span>;
}
