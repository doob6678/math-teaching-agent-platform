import { beforeEach, describe, expect, it, vi } from "vitest";
import { createTextbookApiClient } from "./textbookApi";

describe("textbookApi", () => {
  const storage = new Map<string, string>();

  beforeEach(() => {
    storage.clear();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });
  });

  it("logs in and stores backend issued token", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        userId: "local-student",
        username: "student",
        role: "student",
        tenantId: "default",
        tokenName: "satoken",
        tokenValue: "token-1",
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
          "X-Device-Id": "local-browser-console",
        }),
        body: JSON.stringify({ username: "student", password: "student-123456" }),
      }),
    );
    expect(response.tokenName).toBe("satoken");
    expect(globalThis.localStorage.getItem("math-agent:auth-session")).toContain("token-1");
  });

  it("uses saved backend token instead of client supplied identity headers", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "local-student",
        username: "student",
        role: "student",
        tenantId: "default",
        tokenName: "satoken",
        tokenValue: "token-1",
      }),
    );
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
          satoken: "token-1",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
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

  it("searches textbooks with encoded query and limit", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        queryId: "audit-query-1",
        query: "分段函数",
        limit: 3,
        retrievalStrategy: "local_bm25_first",
        total: 1,
        hits: [{ chunkId: "c1", pageQualityLabel: "content_page" }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080/", fetchMock);

    const response = await client.search("分段函数", 3);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/retrieval/textbooks/search?query=%E5%88%86%E6%AE%B5%E5%87%BD%E6%95%B0&limit=3",
      expect.objectContaining({
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
        queryText: "分段函数",
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
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(audit.queryText).toBe("分段函数");
    expect(audit.hits[0].rankNo).toBe(1);
  });

  it("submits teaching task with recoverable client request id", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        taskId: "task-1",
        clientRequestId: "client-1",
        status: "COMPLETED",
        nodes: [],
        reactTrace: [],
        evidence: [],
        handoutLatex: "\\section{学习目标}",
        interactiveSuggestions: [],
        memoryReuse: {
          reused: true,
          memoryId: "memory-1",
          reuseScope: "private",
          answer: "先看定义域再代入。",
          similarity: 0.91,
          reason: "Reusable memory matched",
        },
        stageTimings: [{ stage: "memory_reuse", elapsedMs: 2 }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const task = await client.submitTeachingTask({
      clientRequestId: "client-1",
      questionText: "我想学 D(-1)",
      learningGoal: "理解函数新定义题",
      evidenceLimit: 3,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teaching/tasks",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
        }),
        body: JSON.stringify({
          clientRequestId: "client-1",
          questionText: "我想学 D(-1)",
          learningGoal: "理解函数新定义题",
          evidenceLimit: 3,
        }),
      }),
    );
    expect(task.taskId).toBe("task-1");
    expect(task.status).toBe("COMPLETED");
    expect(task.memoryReuse?.reused).toBe(true);
    expect(task.stageTimings?.[0].stage).toBe("memory_reuse");
  });

  it("loads teaching task by task id for page resume", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        taskId: "task-1",
        clientRequestId: "client-1",
        status: "COMPLETED",
        nodes: [{ code: "LEARNING_GOAL", name: "学习目标识别", status: "completed", summary: "识别目标" }],
        reactTrace: [],
        evidence: [],
        handoutLatex: "\\section{学习目标}",
        interactiveSuggestions: [],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const task = await client.getTeachingTask("task-1");

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/teaching/tasks/task-1",
      expect.objectContaining({
        headers: expect.objectContaining({ "X-Device-Id": "local-browser-console" }),
      }),
    );
    expect(task.nodes[0].code).toBe("LEARNING_GOAL");
  });

  it("loads student dashboard without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        tenantId: "default",
        studentId: "local-student",
        viewerRole: "student",
        viewerSubjectId: "local-student",
        isAdminView: false,
        knowledgeProgress: [{ knowledgePointName: "空间向量数量积", progressPercent: 68 }],
        weakPoints: [],
        recentQuestions: [],
        scoreTrend: [],
        resourceScopes: [{ scopeCode: "PUBLIC_TEXTBOOK" }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const dashboard = await client.getStudentDashboard();

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/students/dashboard",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(dashboard.studentId).toBe("local-student");
    expect(dashboard.knowledgeProgress[0].progressPercent).toBe(68);
  });

  it("manages teacher resources without client supplied identity headers", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ([{ documentId: "doc-1", title: "空间向量讲义", syncStatus: "registered" }]),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ documentId: "doc-2", title: "飞书题库", syncStatus: "registered" }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ documentId: "doc-2", title: "飞书题库", syncStatus: "archived" }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const list = await client.listTeacherResources();
    const created = await client.registerTeacherResource({
      sourceType: "feishu",
      title: "飞书题库",
      originalUrl: "https://example.feishu.cn/docs/doc1",
      permissionScope: "TEACHER_PRIVATE",
    });
    const archived = await client.archiveTeacherResource("doc-2");

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/teacher/resources",
      expect.objectContaining({
        headers: expect.not.objectContaining({ "X-Subject-Type": expect.any(String) }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teacher/resources",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
        }),
        body: JSON.stringify({
          sourceType: "feishu",
          title: "飞书题库",
          originalUrl: "https://example.feishu.cn/docs/doc1",
          permissionScope: "TEACHER_PRIVATE",
        }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "http://127.0.0.1:8080/api/teacher/resources/doc-2",
      expect.objectContaining({ method: "DELETE" }),
    );
    expect(list[0].documentId).toBe("doc-1");
    expect(created.syncStatus).toBe("registered");
    expect(archived.syncStatus).toBe("archived");
  });
});
