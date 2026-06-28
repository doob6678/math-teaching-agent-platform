import { describe, expect, it, vi } from "vitest";
import { createTextbookApiClient } from "./textbookApi";

describe("textbookApi", () => {
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
        headers: expect.objectContaining({ "X-Subject-Type": "teacher" }),
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
        headers: expect.objectContaining({ "X-Subject-Type": "teacher" }),
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
          "X-Subject-Type": "teacher",
          "X-Subject-Id": "local-teacher-console",
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
          "X-Subject-Type": "teacher",
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
});
