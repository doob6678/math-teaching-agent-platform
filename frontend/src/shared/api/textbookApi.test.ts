import { describe, expect, it, vi } from "vitest";
import { createTextbookApiClient } from "./textbookApi";

describe("textbookApi", () => {

  it("logs in while leaving the backend session in the HttpOnly cookie", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        userId: "local-student",
        username: "student",
        role: "student",
        tenantId: "default",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.login({ username: "student", password: "student-123456" });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/auth/login",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
        }),
        body: JSON.stringify({ username: "student", password: "student-123456" }),
      }),
    );
    expect(response).toEqual({
      userId: "local-student",
      username: "student",
      role: "student",
      tenantId: "default",
    });
  });

  it("registers a student account without persisting the backend session token", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        userId: "student-new",
        username: "new-student",
        role: "student",
        tenantId: "default",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.register({
      username: "new-student",
      password: "student-123456",
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/auth/register",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
        }),
        body: JSON.stringify({ username: "new-student", password: "student-123456" }),
      }),
    );
    expect(response.role).toBe("student");
  });

  it("provisions a teacher through the authenticated administrator session without sending tenant or identity fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        userId: "teacher-1",
        username: "new-teacher",
        role: "teacher",
        tenantId: "default",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.provisionTeacher({ username: "new-teacher", password: "teacher-123456" });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/auth/teachers",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ username: "new-teacher", password: "teacher-123456" }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(response).toEqual({ userId: "teacher-1", username: "new-teacher", role: "teacher", tenantId: "default" });
    expect(JSON.stringify(response)).not.toContain("password");
  });

  it("uses saved backend token instead of client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ tenantId: "default", studentId: "local-student", knowledgeProgress: [] }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    await client.getStudentDashboard();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/dashboard",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
  });

  it("keeps the browser session when backend returns a non-authentication policy 403", async () => {
    const dispatchEvent = vi.fn();
    vi.stubGlobal("dispatchEvent", dispatchEvent);
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      text: async () => JSON.stringify({
        code: "API_ACCESS_DENIED",
        message: "Endpoint requires subject type in [student, teacher, admin]",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    await expect(client.getStudentDashboard()).rejects.toThrow("Endpoint requires subject type");

    expect(dispatchEvent).not.toHaveBeenCalled();
  });

  it("sends authenticated business mutations directly with the backend session", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ taskId: "task-1", status: "queued" }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    await client.submitTeachingTask({
      clientRequestId: "client-1",
      questionText: "解三角形面积公式",
      learningGoal: "生成讲义",
      evidenceLimit: 3,
    });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8080/api/teaching/tasks");
  });

  it("creates legacy handout writing as the same durable teaching task id", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        taskId: "task-compat-1",
        clientRequestId: "writing-client-1",
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        status: "CREATED",
        nodes: [],
        reactTrace: [],
        evidence: [],
        handoutLatex: "",
        interactiveSuggestions: [],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const workflow = await client.startAsyncMultiAgentWriting({
      writingGoal: "生成三角函数讲义",
      questionText: "正弦定理",
      evidenceRefs: ["textbook:chapter-1"],
      preferredProviderName: "openai",
      preferredModelCode: "gpt-5.6-terra",
    });

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8080/api/teaching/tasks");
    const submittedRequest = fetchMock.mock.calls[0][1];
    expect(submittedRequest?.method).toBe("POST");
    expect(JSON.parse(String(submittedRequest?.body)).clientRequestId).toMatch(/^writing-/);
    expect(fetchMock.mock.calls[0][0]).not.toContain("/api/agents/writing/");
    expect(workflow.workflowId).toBe("task-compat-1");
  });

  it("routes the synchronous legacy writing method through the teaching task too", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        taskId: "task-compat-sync-1",
        clientRequestId: "writing-client-sync-1",
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        status: "CREATED",
        nodes: [],
        reactTrace: [],
        evidence: [],
        handoutLatex: "",
        interactiveSuggestions: [],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const workflow = await client.runMultiAgentWriting({
      writingGoal: "生成函数讲义",
      questionText: "二次函数",
      evidenceRefs: [],
    });

    expect(fetchMock.mock.calls[0][0]).toBe("http://127.0.0.1:8080/api/teaching/tasks");
    expect(fetchMock.mock.calls[0][0]).not.toContain("/api/agents/writing");
    expect(workflow.workflowId).toBe("task-compat-sync-1");
  });

  it("loads textbook summary from backend", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ bookCount: 7, totalChunkCount: 1169, totalPageCount: 1118, books: [] }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const summary = await client.getSummary();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/resources/textbooks/summary",
      expect.objectContaining({
        headers: expect.not.objectContaining({ "X-Subject-Type": expect.any(String) }),
      }),
    );
    expect(summary.bookCount).toBe(7);
    expect(summary.totalChunkCount).toBe(1169);
  });

  it("posts configurable textbook RAG inputs", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        queryId: "audit-query-1",
        query: "piecewise function",
        limit: 3,
        retrievalStrategy: "local_bm25_first",
        total: 1,
        hits: [{ chunkId: "c1", pageQualityLabel: "content_page" }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080/", fetchMock);

    const response = await client.search({
      query: "piecewise function",
      formulaQuery: "x^2+y^2=1",
      limit: 3,
      documentIds: ["book-a"],
      retrievalMode: "formula_bge",
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/retrieval/textbooks/search",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          query: "piecewise function",
          formulaQuery: "x^2+y^2=1",
          limit: 3,
          documentIds: ["book-a"],
          retrievalMode: "formula_bge",
        }),
        headers: expect.not.objectContaining({ "X-Subject-Type": expect.any(String) }),
      }),
    );
    expect(response.queryId).toBe("audit-query-1");
    expect(response.hits[0].pageQualityLabel).toBe("content_page");
  });

  it("raises readable errors for failed backend requests", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => "backend failed",
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    await expect(client.getSummary()).rejects.toThrow("Backend request failed: 500 backend failed");
  });

  it("loads audit detail by query id", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        queryId: "audit-query-1",
        tenantId: "default",
        queryText: "piecewise function",
        retrievalStrategy: "local_bm25_first",
        requestedLimit: 5,
        hitCount: 1,
        elapsedMs: 12,
        requestContext: { endpoint: "/api/retrieval/textbooks/search" },
        hits: [{ rankNo: 1, chunkId: "chunk-1", score: 10.5 }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const audit = await client.getAudit("audit-query-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/retrieval/audit/audit-query-1",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(audit.queryText).toBe("piecewise function");
    expect(audit.hits[0].rankNo).toBe(1);
  });

  it("loads the student learning path from the authenticated backend subject", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        studentId: "student-a",
        steps: [{ knowledgePointId: "base", relationToNext: "PREREQUISITE_FOR" }],
        generatedFrom: "student_knowledge_mastery+visible_PREREQUISITE_FOR",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.getStudentLearningPath();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/learning/path",
      expect.objectContaining({
        credentials: "include",
      }),
    );
    expect(response.steps[0].relationToNext).toBe("PREREQUISITE_FOR");
  });

  it("routes a student learning message through the backend intent contract", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        intentCode: "LEARNING_PATH",
        confidence: 0.98,
        knowledgePointId: "kp-1",
        suggestedApi: "/api/students/learning/path",
        recognizedBy: "model_openai:gpt-5.6-luna",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.recognizeStudentLearningIntent("函数单调性要先学什么？");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/learning/intent",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ message: "函数单调性要先学什么？" }),
      }),
    );
    expect(response.intentCode).toBe("LEARNING_PATH");
  });

  it("loads teaching task by task id for page resume", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        taskId: "task-1",
        clientRequestId: "client-1",
        status: "COMPLETED",
        nodes: [{ code: "LEARNING_GOAL", name: "Learning goal", status: "completed", summary: "parsed" }],
        reactTrace: [],
        evidence: [],
        handoutLatex: "\\section{Learning Goal}",
        interactiveSuggestions: [],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const task = await client.getTeachingTask("task-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teaching/tasks/task-1",
      expect.objectContaining({
      }),
    );
    expect(task.nodes[0].code).toBe("LEARNING_GOAL");
  });


  it("streams durable teaching progress instead of timer-generated placeholder stages", async () => {
    const encoder = new TextEncoder();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      body: new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode("event: progress\ndata: {\"taskId\":\"task-1\",\"status\":\"RUNNING\",\"nodes\":[{\"code\":\"OUTLINE\",\"name\":\"讲解大纲\",\"status\":\"running\",\"summary\":\"正在整理\"}],\"workflowEvents\":[],\"evidence\":[],\"stageTimings\":[],\"versions\":{\"teacherReady\":false,\"studentReady\":false,\"lectureReady\":false}}\n\n"));
          controller.enqueue(encoder.encode("event: completed\ndata: {\"taskId\":\"task-1\",\"status\":\"COMPLETED\",\"nodes\":[],\"workflowEvents\":[],\"evidence\":[],\"stageTimings\":[],\"versions\":{\"teacherReady\":true,\"studentReady\":true,\"lectureReady\":true}}\n\n"));
          controller.close();
        },
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);
    const events: Array<{ name: string; status: string }> = [];

    await client.streamTeachingTask("task-1", (eventName, progress) => {
      events.push({ name: eventName, status: progress.status });
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/events",
      expect.objectContaining({
        method: "GET",
        cache: "no-store",
        headers: expect.objectContaining({
          Accept: "text/event-stream",
          "Cache-Control": "no-store, no-cache, max-age=0",
          Pragma: "no-cache",
        }),
      }),
    );
    expect(events).toEqual([
      { name: "progress", status: "RUNNING" },
      { name: "completed", status: "COMPLETED" },
    ]);
  });









  it("lists teaching human feedback records for the current task", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ([
        {
          feedbackId: "feedback-1",
          taskId: "task-1",
          tenantId: "default",
          subjectType: "teacher",
          subjectId: "teacher-1",
          rating: 5,
          decision: "helpful",
          comment: "PDF layout is usable.",
          reviewContext: {
            handoutVersion: "teacher",
            pdfRenderer: "xelatex",
            checks: { matchedCoreColumns: 6, coreColumnTotal: 6 },
          },
          createdAt: "2026-06-28T12:00:00Z",
        },
      ]),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const feedback = await client.listTeachingHumanFeedback("task-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/feedback",
      expect.objectContaining({
      }),
    );
    expect(feedback).toHaveLength(1);
    expect(feedback[0].reviewContext?.pdfRenderer).toBe("xelatex");
  });


  it("plans agent run with backend session identity and no client supplied user identity", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        planId: "plan-1",
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        agentCode: "CoursewareAgent",
        providerName: "openai",
        modelCode: "gpt-5.4",
        modelLevel: "reasoning",
        allowedToolScopes: ["tool:courseware:generate"],
        deniedToolScopes: ["tool:search:private"],
        toolPolicyDecisions: [
          {
            scope: "tool:courseware:generate",
            decision: "ALLOWED",
            reason: "Tool is allowed by agent policy and not disabled by request preference",
          },
          {
            scope: "tool:search:private",
            decision: "DISABLED_BY_USER",
            reason: "Tool was removed by this request's user preference",
          },
        ],
        allowedDataScopes: ["TEACHER_PRIVATE"],
        deniedDataScopes: [],
        maxInputTokens: 12000,
        maxOutputTokens: 4000,
        estimatedTotalTokens: 4600,
        estimatedCost: 0.46,
        withinBudget: true,
        routeReason: "courseware_generation uses reasoning model",
        stageTimings: [{ stage: "model_route", elapsedMs: 1 }],
        concurrencyKeys: ["concurrent:user:teacher-1:CoursewareAgent"],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);
    const request = {
      agentCode: "CoursewareAgent",
      taskType: "courseware_generation",
      userVipLevel: "teacher",
      estimatedInputTokens: 3000,
      estimatedOutputTokens: 1600,
      hasImage: false,
      hasFormula: true,
      difficulty: "medium",
      latencyRequirement: "normal",
      costBudget: 2.5,
      previousFailureCount: 0,
      requiredJsonSchema: false,
      requestedToolScopes: ["tool:courseware:generate", "tool:search:private"],
      disabledToolScopes: ["tool:search:private"],
      requestedDataScopes: ["TEACHER_PRIVATE"],
      highValueOperation: true,
      preferredProviderName: "openai",
      preferredModelCode: "gpt-5.4",
    };

    const plan = await client.planAgentRun(request);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/run-plan",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
        }),
        body: JSON.stringify(request),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(plan.allowedToolScopes).toContain("tool:courseware:generate");
    expect(plan.deniedToolScopes).toContain("tool:search:private");
    expect(plan.toolPolicyDecisions[1]).toMatchObject({
      scope: "tool:search:private",
      decision: "DISABLED_BY_USER",
    });
  });

  it("loads agent model catalog from backend session without exposing identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        defaultProviderName: "openai",
        defaultModelCode: "gpt-5.4",
        fallbackProviderOrder: ["openai", "dashscope"],
        providers: [
          {
            name: "openai",
            enabled: true,
            defaultModelCode: "gpt-5.4",
            models: [
              { modelCode: "gpt-5.4", modelLevel: "reasoning", priceTier: "standard" },
              { modelCode: "gpt-5.4-mini", modelLevel: "fast_text", priceTier: "cheap" },
            ],
          },
        ],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const catalog = await client.getAgentModelCatalog();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/model-catalog",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(catalog.defaultModelCode).toBe("gpt-5.4");
    expect(catalog.providers[0].models[0].modelCode).toBe("gpt-5.4");
  });

  it("loads agent model health from backend session without exposing identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        checkedAt: "2026-06-30T05:00:00Z",
        results: [{
          providerName: "openai",
          modelCode: "gpt-5.4",
          configured: true,
          reachable: true,
          statusCode: 200,
          elapsedMs: 123,
          safeReason: "Provider answered the health check.",
          checkedAt: "2026-06-30T05:00:00Z",
        }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const health = await client.getAgentModelHealth();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/model-health",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(JSON.stringify(health)).not.toContain("token-teacher");
    expect(health.results[0]).toMatchObject({
      providerName: "openai",
      modelCode: "gpt-5.4",
      reachable: true,
    });
  });


  it("lists agent traces with backend session identity and no client supplied user identity", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ([{
        traceId: "trace-1",
        planId: "plan-1",
        createdAt: "2026-06-29T00:00:00Z",
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        agentCode: "CoursewareAgent",
        providerName: "openai",
        modelCode: "gpt-5.4",
        status: "COMPLETED",
        estimatedCost: 0.46,
        allowedToolScopes: ["tool:courseware:generate"],
        allowedDataScopes: ["TEACHER_PRIVATE"],
        evidenceRefs: ["textbook:chapter-1"],
        stageTimings: [{ stage: "model_call", elapsedMs: 14 }],
        actualUsage: {
          promptTokens: 123,
          completionTokens: 45,
          totalTokens: 168,
        },
        message: "Live model response recorded with provider usage metadata.",
      }]),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const traces = await client.listAgentTraces({
      agentCode: "CoursewareAgent",
      status: "COMPLETED",
      planId: "task-1",
      limit: 20,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/traces?agentCode=CoursewareAgent&status=COMPLETED&planId=task-1&limit=20",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(traces[0].traceId).toBe("trace-1");
    expect(traces[0].modelCode).toBe("gpt-5.4");
    expect(traces[0].actualUsage.totalTokens).toBe(168);
  });


  it("projects legacy handout controls onto one teaching task without calling retired writing endpoints", async () => {
    const teachingTask = {
      taskId: "task-1",
      clientRequestId: "writing-client-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      status: "COMPLETED",
      nodes: [{ code: "teacher_writer", name: "教师版", status: "COMPLETED", summary: "teacher handout ready" }],
      workflowEvents: [{
        eventId: "event-1",
        sourceType: "agent",
        sourceName: "teacher_writer",
        eventType: "teacher_writer",
        status: "COMPLETED",
        title: "教师版",
        summary: "teacher handout ready",
        artifactRefs: ["teacher"],
      }],
      reactTrace: [],
      evidence: [],
      handoutLatex: "\\\\section{Handout}",
      teacherHandoutLatex: "\\\\section{Teacher}",
      studentHandoutLatex: "\\\\section{Student}",
      lectureHandoutLatex: "\\\\section{Lecture}",
      interactiveSuggestions: [],
      aiDraft: {
        enabled: true,
        providerName: "openai",
        modelCode: "gpt-5.6-terra",
        promptTokens: 10,
        completionTokens: 5,
        totalTokens: 15,
        content: "# Handout",
        message: "ready",
        structured: true,
        teacherExplanation: "teacher",
        studentHint: "student",
        knowledgePoints: [],
        followUpQuestions: [],
        parseError: "",
        retryCount: 0,
        maxRetries: 0,
        recoveredAfterRetry: false,
        recoveryEvents: [],
      },
    };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => teachingTask,
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => teachingTask,
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => teachingTask,
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => teachingTask,
      })
      .mockResolvedValueOnce({
        ok: true,
        headers: new Headers(),
        arrayBuffer: async () => new Uint8Array([37, 80, 68, 70]).buffer,
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const workflow = await client.getMultiAgentWritingWorkflow("task-1");
    const traces = await client.getMultiAgentWritingTraces("task-1");
    const artifact = await client.getMultiAgentWritingArtifact("task-1");
    const resumed = await client.resumeMultiAgentWriting("task-1", {
      writingGoal: "生成讲义",
      questionText: "题目",
      evidenceRefs: [],
    });
    const exported = await client.exportMultiAgentWritingArtifact("task-1", "pdf");

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1",
      expect.objectContaining({
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1",
      expect.objectContaining({
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1",
      expect.objectContaining({}),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/resume",
      expect.objectContaining({ method: "POST" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      5,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/handout/teacher/pdf",
      expect.objectContaining({ credentials: "include" }),
    );
    expect(fetchMock.mock.calls.map(([url]) => url)).not.toContain(expect.stringContaining("/api/agents/writing/"));
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(workflow.status).toBe("COMPLETED");
    expect(workflow.workflowId).toBe("task-1");
    expect(traces.stages[0].planId).toBe("task-1:teacher_writer");
    expect(artifact.stages).toHaveLength(3);
    expect(resumed.workflowId).toBe("task-1");
    expect(exported.sha256).toHaveLength(64);
    expect(exported.base64Content).toBe("JVBERg==");
  });



  it("summarizes visible agent trace usage with backend session identity only", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        agentCode: "CoursewareAgent",
        status: "COMPLETED",
        runCount: 2,
        totalUsage: {
          promptTokens: 246,
          completionTokens: 90,
          totalTokens: 336,
        },
        modelUsages: [
          {
            providerName: "openai",
            modelCode: "gpt-5.4",
            runCount: 2,
            promptTokens: 246,
            completionTokens: 90,
            totalTokens: 336,
          },
        ],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const summary = await client.getAgentTraceUsageSummary({
      agentCode: "CoursewareAgent",
      status: "COMPLETED",
      limit: 20,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/traces/usage-summary?agentCode=CoursewareAgent&status=COMPLETED&limit=20",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(summary.runCount).toBe(2);
    expect(summary.totalUsage.totalTokens).toBe(336);
  });

  it("summarizes visible agent trace diagnostics with backend session identity only", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        tenantId: "school-a",
        subjectType: "teacher",
        subjectId: "teacher-1",
        agentCode: "CoursewareAgent",
        status: "COMPLETED",
        runCount: 2,
        diagnosticEventCount: 6,
        jsonParseFailureCount: 1,
        retryScheduledCount: 1,
        retryRecoveredCount: 1,
        providerRotationCount: 1,
        modelCallFailureCount: 0,
        modelDiagnostics: [
          {
            providerName: "openai",
            modelCode: "gpt-5.4",
            runCount: 2,
            diagnosticEventCount: 6,
            jsonParseFailureCount: 1,
            retryScheduledCount: 1,
            retryRecoveredCount: 1,
            providerRotationCount: 1,
            modelCallFailureCount: 0,
            totalTokens: 336,
          },
        ],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const summary = await client.getAgentTraceDiagnosticSummary({
      agentCode: "CoursewareAgent",
      status: "COMPLETED",
      limit: 20,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/traces/diagnostic-summary?agentCode=CoursewareAgent&status=COMPLETED&limit=20",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(summary.jsonParseFailureCount).toBe(1);
    expect(summary.retryRecoveredCount).toBe(1);
  });

  it("lists session-owned MCP keys without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ([{
        keyId: "key-1",
        name: "teacher-mcp-20260708-120000",
        tenantId: "school-a",
        ownerUserId: "teacher-1",
        keyProfile: "teacher",
        status: "active",
        secretKeyPreview: "mcp_...cdef",
        createdAt: "2026-07-08T12:00:00",
      }]),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const keys = await client.listMcpKeys();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/mcp/keys",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(keys).toHaveLength(1);
    expect(keys[0].ownerUserId).toBe("teacher-1");
  });

  it("creates a backend-generated MCP key without sending secret or identity fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        keyId: "key-1",
        name: "teacher-mcp-20260708-120000",
        tenantId: "school-a",
        ownerUserId: "teacher-1",
        keyProfile: "teacher",
        secretKey: "mcp_secret_1234567890abcdef",
        secretKeyPreview: "mcp_...cdef",
        configuration: {
          serverName: "math-agent-rag",
          url: "https://math.example.com/api/mcp",
          valid: true,
          secretKeyAccepted: true,
          secretKeyPreview: "mcp_...cdef",
          secretEnvName: "MATH_AGENT_MCP_SECRET",
          keyProfile: "teacher",
          exposedTools: ["search_textbook_evidence"],
          exposedPrompts: ["teacher_handout_writer"],
          configJson:
            '{\n  "mcpServers" : {\n    "math-agent-rag" : {\n      "type" : "http",\n      "url" : "https://math.example.com/api/mcp",\n      "headers" : {\n        "Authorization" : "Bearer ${MATH_AGENT_MCP_SECRET}"\n      }\n    }\n  }\n}',
          layers: [],
        },
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const created = await client.createMcpKey();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/mcp/keys",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.body).toBeUndefined();
    expect(created.secretKey).toBe("mcp_secret_1234567890abcdef");
    expect(created.configuration.configJson).toContain("${MATH_AGENT_MCP_SECRET}");
  });

  it("loads my backend-generated MCP configuration without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        serverName: "math-agent-rag",
        url: "https://math.example.com/api/mcp",
        valid: true,
        secretKeyAccepted: true,
        secretKeyPreview: "mcp_...cdef",
        secretEnvName: "MATH_AGENT_MCP_SECRET",
        keyProfile: "teacher",
        exposedTools: ["search_textbook_evidence", "plan_agent_run"],
        exposedPrompts: ["teacher_handout_writer", "student_blank_handout_writer"],
        configJson:
          '{\n  "mcpServers" : {\n    "math-agent-rag" : {\n      "type" : "http",\n      "url" : "https://math.example.com/api/mcp",\n      "headers" : {\n        "Authorization" : "Bearer ${MATH_AGENT_MCP_SECRET}"\n      }\n    }\n  }\n}',
        layers: [],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const config = await client.getMyMcpConfiguration();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/mcp/configuration/me",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(config.configJson).toContain("mcpServers");
    expect(config.configJson).toContain("${MATH_AGENT_MCP_SECRET}");
  });

  it("revokes one session-owned MCP key without sending identity fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        keyId: "key-1",
        status: "revoked",
        revokedAt: "2026-07-08T12:10:00",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const revoked = await client.revokeMcpKey("key-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/mcp/keys/key-1/revoke",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(revoked.status).toBe("revoked");
  });

  it("tests standard MCP connection through initialize and tools/list without platform session headers", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ "MCP-Protocol-Version": "2025-11-25" }),
        text: async () => JSON.stringify({
          jsonrpc: "2.0",
          id: "frontend-init",
          result: {
            protocolVersion: "2025-11-25",
            serverInfo: { name: "math-agent-rag", version: "0.1.0" },
          },
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers({ "MCP-Protocol-Version": "2025-11-25" }),
        text: async () => JSON.stringify({
          jsonrpc: "2.0",
          id: "frontend-tools",
          result: {
            tools: [
              { name: "search_textbook_evidence" },
              { name: "get_teaching_ai_trace" },
            ],
          },
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const result = await client.testMcpConnection(
      "http://127.0.0.1:8080/api/mcp/",
      "teacher_secret_1234567890abcdef",
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/mcp",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          Accept: "application/json, text/event-stream",
          "Content-Type": "application/json",
          "MCP-Protocol-Version": "2025-11-25",
          Authorization: "Bearer teacher_secret_1234567890abcdef",
        }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/mcp",
      expect.objectContaining({
        method: "POST",
        headers: expect.not.objectContaining({
          "X-Subject-Id": expect.any(String),
          "X-Subject-Type": expect.any(String),
        }),
      }),
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1]?.body as string).method).toBe("initialize");
    expect(JSON.parse(fetchMock.mock.calls[1][1]?.body as string).method).toBe("tools/list");
    expect(result.serverName).toBe("math-agent-rag");
    expect(result.protocolVersion).toBe("2025-11-25");
    expect(result.tools).toEqual(["search_textbook_evidence", "get_teaching_ai_trace"]);
    expect(result.toolCount).toBe(2);
  });

  it("loads student dashboard without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        tenantId: "default",
        studentId: "local-student",
        subjectRole: "student",
        viewerRole: "student",
        viewerSubjectId: "local-student",
        isAdminView: false,
        knowledgeProgress: [{ knowledgePointName: "space vector", progressPercent: 68 }],
        weakPoints: [],
        recentQuestions: [],
        scoreTrend: [],
        resourceScopes: [{ scopeCode: "PUBLIC_TEXTBOOK" }],
        knowledgeGraph: {
          generatedFrom: "dashboard_progress+weak_points+textbook_anchor+feishu_anchor",
          nodes: [
            {
              knowledgePointId: "math-vector-dot-product",
              knowledgePointName: "space vector",
              chapterPath: "space vector / page 35",
              masteryPercent: 68,
              riskLevel: "medium",
              evidenceLinks: [{ sourceType: "textbook", title: "page 35", url: "/api/textbooks/search", permissionScope: "PUBLIC_TEXTBOOK" }],
            },
          ],
          edges: [
            {
              edgeId: "edge-1",
              sourceKnowledgePointId: "math-vector-dot-product",
              targetKnowledgePointId: "math-solid-geometry",
              relationType: "PREREQUISITE_FOR",
              evidenceSummary: "dot product supports solid geometry",
            },
          ],
        },
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const dashboard = await client.getStudentDashboard();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/dashboard",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(dashboard.studentId).toBe("local-student");
    expect(dashboard.knowledgeProgress[0].progressPercent).toBe(68);
    expect(dashboard.knowledgeGraph?.nodes[0].masteryPercent).toBe(68);
    expect(dashboard.knowledgeGraph?.edges[0].relationType).toBe("PREREQUISITE_FOR");
  });

  it("submits student explanation request without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        explanationId: "explain-1",
        conversationId: "conversation-1",
        tenantId: "default",
        studentId: "local-student",
        viewerRole: "student",
        questionText: "空间向量数量积求二面角",
        imageStatus: "none",
        imageUnderstanding: {
          enabled: true,
          succeeded: true,
          providerName: "dashscope",
          modelCode: "qwen-vl-plus-latest",
          problemText: "空间向量数量积求二面角",
          confidence: 0.9,
          promptTokens: 12,
          completionTokens: 6,
          totalTokens: 18,
          message: "vision-json",
        },
        generatedBy: "student_explanation_card_orchestrator_v0.1",
        aiDraft: {
          enabled: true,
          providerName: "openai",
          modelCode: "gpt-5.4",
          promptTokens: 10,
          completionTokens: 6,
          totalTokens: 16,
          structured: true,
          message: "ok",
          recoveryEvents: [],
        },
        workflowStages: [{ stageKey: "search_textbook", title: "查教材", status: "completed", detail: "命中 1 条教材证据。", elapsedMs: 8 }],
        cards: [{ cardKey: "source_links", title: "来源", summary: "来源", items: [], sourceUris: [], renderMode: "source_list" }],
        sources: [{ sourceType: "textbook", title: "book p.12", sourceUri: "textbook://book/page/12#chunk=c1", permissionScope: "PUBLIC_TEXTBOOK", snippet: "数量积", score: 1.2 }],
        totalElapsedMs: 15,
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.explainStudentQuestion({
      questionText: "空间向量数量积求二面角",
      imageFileName: "question.png",
      imageContentType: "image/png",
      imageSizeBytes: 1024,
      searchTextbook: true,
      searchKnowledgeGraph: true,
      searchTeacherResources: true,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/explanations",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).not.toHaveProperty("studentId");
    expect(response.sources[0].sourceUri).toBe("textbook://book/page/12#chunk=c1");
    expect(response.aiDraft.modelCode).toBe("gpt-5.4");
  });

  it("creates and reads student-safe targeted practice through its dedicated endpoints", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          taskId: "practice-task-1",
          clientRequestId: "practice-client-1",
          status: "CREATED",
          studentId: "local-student",
          knowledgePointIds: ["function-monotonicity"],
          questionText: "真实题库参考题",
          learningGoal: "针对薄弱知识点生成学生专项练习",
          interactiveSuggestions: [],
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          taskId: "practice-task-1",
          clientRequestId: "practice-client-1",
          status: "COMPLETED",
          studentId: "local-student",
          knowledgePointIds: ["function-monotonicity"],
          questionText: "真实题库参考题",
          learningGoal: "针对薄弱知识点生成学生专项练习",
          studentHandoutLatex: "\\section{专项练习}",
          interactiveSuggestions: [],
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const created = await client.submitTargetedPractice({
      clientRequestId: "practice-client-1",
      knowledgePointId: "function-monotonicity",
      exerciseCount: 5,
      evidenceLimit: 3,
    });
    const completed = await client.getTargetedPractice(created.taskId);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/students/learning/practice",
      expect.objectContaining({ method: "POST" }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/students/learning/practice/practice-task-1",
      expect.objectContaining({
      }),
    );
    expect(completed.studentHandoutLatex).toContain("专项练习");
  });

  it("loads student explanation history without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        items: [
          {
            explanationId: "explain-1",
            conversationId: "conversation-1",
            questionText: "space vector",
            imageStatus: "none",
            imageProblemText: "",
            aiProviderName: "openai",
            aiModelCode: "gpt-5.4",
            totalTokens: 128,
            totalElapsedMs: 45,
            createdAt: "2026-07-02T01:00:00",
          },
        ],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.getStudentExplanationHistory("conversation-1", 5);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/explanations/history?conversationId=conversation-1&limit=5",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(response.items[0].conversationId).toBe("conversation-1");
    expect(response.items[0].aiModelCode).toBe("gpt-5.4");
  });

  it("uploads student explanation image as multipart without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        uploadId: "upload-1",
        originalFileName: "question.png",
        contentType: "image/png",
        sizeBytes: 3,
        expiresAt: "2026-07-01T00:30:00Z",
        imageStatus: "image_uploaded_ocr_not_configured",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);
    const file = new File([new Uint8Array([1, 2, 3])], "question.png", { type: "image/png" });

    const response = await client.uploadStudentExplanationImage(file);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/explanations/images",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
        }),
        body: expect.any(FormData),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("Content-Type");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(response.uploadId).toBe("upload-1");
    expect(response.imageStatus).toBe("image_uploaded_ocr_not_configured");
  });


  it("loads curated knowledge graph spine without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        version: "v0.1",
        tenantId: "school-a",
        viewerRole: "teacher",
        nodeCount: 3,
        edgeCount: 2,
        nodes: [
          {
            id: "spine-module-functions",
            label: "functions",
            nodeType: "MODULE",
            chapterPath: "1. functions",
            permissionScope: "MATH_VIP",
            sourceSummary: "curated spine v0.1",
          },
          {
            id: "spine-topic-function-zero",
            label: "function zeros",
            nodeType: "TOPIC",
            chapterPath: "1.3 function zeros",
            permissionScope: "MATH_VIP",
            sourceSummary: "curated spine v0.1",
          },
          {
            id: "spine-method-numerical-graph",
            label: "number-shape combination",
            nodeType: "METHOD",
            chapterPath: "functions / method",
            permissionScope: "MATH_VIP",
            sourceSummary: "curated spine v0.1",
          },
        ],
        edges: [
          {
            id: "spine-edge-functions-zero",
            source: "spine-module-functions",
            target: "spine-topic-function-zero",
            relationType: "HAS_TOPIC",
            evidenceSummary: "functions include zero problems",
          },
          {
            id: "spine-edge-zero-method",
            source: "spine-topic-function-zero",
            target: "spine-method-numerical-graph",
            relationType: "USES_METHOD",
            evidenceSummary: "zeros often use graph reasoning",
          },
        ],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const graph = await client.getKnowledgeGraphSpine();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/knowledge/graph/spine",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(graph.nodeCount).toBe(3);
    expect(graph.edgeCount).toBe(2);
    expect(graph.nodes[1].nodeType).toBe("TOPIC");
    expect(graph.edges[1].relationType).toBe("USES_METHOD");
  });








  it("loads teacher resource sync checkpoint without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        jobId: "job-2",
        tenantId: "school-a",
        documentId: "doc-1",
        rootToken: "root-token",
        currentFolderToken: "folder-token-2",
        currentPath: "高中数学/空间向量",
        pageToken: "page-token-3",
        visitedFolderTokensJson: "[\"root-token\",\"folder-token-2\"]",
        downloadedItemsJson: "[{\"token\":\"docx-1\"}]",
        failedItemsJson: "[{\"message\":\"ProxyError\",\"retryable\":true}]",
        cursorVersion: 2,
        updatedAt: "2026-06-30T06:00:00Z",
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const checkpoint = await client.getTeacherResourceSyncCheckpoint("doc-1", "job-2");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teacher/resources/doc-1/sync-jobs/job-2/checkpoint",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(checkpoint?.currentPath).toBe("高中数学/空间向量");
    expect(checkpoint?.downloadedItemsJson).toContain("docx-1");
  });

  it("searches parsed teacher resource blocks without client supplied identity headers", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          queryId: "query-1",
          query: "vector theorem",
          limit: 8,
          retrievalMode: "teacher_block_lexical",
          hitCount: 1,
          hits: [{
            documentId: "doc-1",
            documentTitle: "Space vector handout",
            permissionScope: "TEACHER_PRIVATE",
            blockId: "block-1",
            blockType: "text",
            blockOrder: 1,
            chapter: "Vectors",
            section: "Theorem",
            pageNo: null,
            snippet: "Space vector theorem",
            score: 11,
          }],
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.searchTeacherResourceBlocks("vector theorem", 8);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teacher/resources/search?query=vector%20theorem&limit=8",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(response.hits[0].blockId).toBe("block-1");
  });

  it("loads teacher resource block search audit without client supplied identity headers", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          queryId: "query-1",
          tenantId: "school-a",
          subjectType: "teacher",
          subjectId: "teacher-1",
          query: "vector theorem",
          limit: 8,
          retrievalMode: "teacher_block_lexical",
          hitCount: 1,
          elapsedMs: 3,
          endpoint: "/api/teacher/resources/search",
          hits: [{
            documentId: "doc-1",
            documentTitle: "Space vector handout",
            permissionScope: "TEACHER_PRIVATE",
            blockId: "block-1",
            blockType: "text",
            blockOrder: 1,
            pageNo: null,
            score: 11,
          }],
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.getTeacherResourceBlockSearchAudit("query-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teacher/resources/search/audit/query-1",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(response.hits[0].blockId).toBe("block-1");
  });

  it("discovers Feishu candidates without client supplied identity headers or secrets", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          queryId: "feishu-query-1",
          mode: "search_root",
          rootUrl: "https://my.feishu.cn/drive/folder/root-token",
          keyword: "空间向量",
          depth: 5,
          candidateCount: 1,
          candidates: [{
            resourceType: "docx",
            token: "doc-token",
            name: "空间向量数量积",
            path: "必修二/空间向量数量积",
            url: "https://my.feishu.cn/docx/doc-token",
            depth: 2,
            downloadable: true,
          }],
          status: "ok",
          message: "Found 1 Feishu candidates",
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.discoverFeishuResources({
      mode: "search",
      query: "空间向量",
      rootUrl: "https://my.feishu.cn/drive/folder/root-token",
      listDepth: 1,
      maxDepth: 5,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teacher/resources/feishu/discovery?mode=search&query=%E7%A9%BA%E9%97%B4%E5%90%91%E9%87%8F&rootUrl=https%3A%2F%2Fmy.feishu.cn%2Fdrive%2Ffolder%2Froot-token&listDepth=1&maxDepth=5",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(fetchMock.mock.calls[0][0]).not.toContain("APPKEY");
    expect(fetchMock.mock.calls[0][0]).not.toContain("APP_SECRET");
    expect(response.candidates[0].url).toBe("https://my.feishu.cn/docx/doc-token");
  });

  it("loads runtime status without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        deployment: {
          ready: true,
          mode: "deploy_ready",
          blockingIssues: [],
          warnings: [],
        },
        ai: {
          defaultProviderName: "openai",
          defaultModelCode: "gpt-5.4",
          defaultProviderConfigured: true,
          enabledProviderCount: 1,
          providers: [
            {
              providerName: "openai",
              modelCode: "gpt-5.4",
              configured: true,
              baseUrlConfigured: true,
              apiKeyConfigured: true,
              modelConfigured: true,
            },
          ],
        },
        auth: {
          persistentStoreRequired: true,
          mode: "mysql_only",
        },
        database: {
          enabled: true,
          configured: true,
          urlConfigured: true,
          usernameConfigured: true,
          studentExplanationHistoryDurable: true,
          mode: "mysql",
        },
        redis: {
          redissonEnabled: true,
          redissonAddress: "redis://127.0.0.1:6379",
          rateLimitEnabled: true,
          rateLimitKeyPrefix: "math-agent:rate-limit:",
          searchCacheEnabled: true,
          searchCacheKeyPrefix: "math-agent:search:",
          searchCacheTtl: "PT10M",
        },
        vectorIndex: {
          enabled: true,
          configured: false,
          collectionName: "math_agent_resource_blocks",
          dimension: 1536,
          embeddingModel: "text-embedding-3-small",
          milvusUri: "",
          status: "configuration_error",
        },
        feishu: {
          processDownloaderEnabled: true,
          downloaderScriptConfigured: true,
          downloaderScriptExists: true,
          appkeyPathConfigured: true,
          appkeyFileExists: true,
          stagingRootConfigured: true,
          stagingRootExistsOrCreatable: true,
          defaultUrlHost: "my.feishu.cn",
          smokeMaxFiles: 1,
          processTimeoutSeconds: 30,
          mode: "process_ready",
        },
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const status = await client.getSystemRuntimeStatus();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/system/runtime",
      expect.objectContaining({
        headers: expect.objectContaining({
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(status.deployment.ready).toBe(true);
    expect(status.deployment.mode).toBe("deploy_ready");
    expect(status.ai.defaultProviderName).toBe("openai");
    expect(status.ai.defaultProviderConfigured).toBe(true);
    expect(status.auth.mode).toBe("mysql_only");
    expect(status.database.mode).toBe("mysql");
    expect(status.database.studentExplanationHistoryDurable).toBe(true);
    expect(status.redis.searchCacheEnabled).toBe(true);
    expect(status.vectorIndex.status).toBe("configuration_error");
    expect(status.feishu.mode).toBe("process_ready");
    expect(status.feishu.appkeyFileExists).toBe(true);
  });

});
