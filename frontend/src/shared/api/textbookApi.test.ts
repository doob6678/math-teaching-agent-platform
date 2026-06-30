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
        query: "piecewise function",
        limit: 3,
        retrievalStrategy: "local_bm25_first",
        total: 1,
        hits: [{ chunkId: "c1", pageQualityLabel: "content_page" }],
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080/", fetchMock);

    const response = await client.search("piecewise function", 3);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/retrieval/textbooks/search?query=piecewise+function&limit=3",
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
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(audit.queryText).toBe("piecewise function");
    expect(audit.hits[0].rankNo).toBe(1);
  });

  it("loads capability audits without client supplied identity", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ([{
        eventId: "event-1",
        tenantId: "school-a",
        subjectType: "student",
        subjectId: "student-1",
        action: "teaching:submit",
        path: "/api/teaching/tasks",
        requestHash: "hash-1",
        idempotencyKey: "client-1",
        tokenHash: "token-hash-1",
        decision: "issued",
        reason: "Capability token issued",
      }]),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const audits = await client.listCapabilityAudits({
      subjectType: "student",
      subjectId: "student-1",
      action: "teaching:submit",
      decision: "issued",
      limit: 25,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/security/capability-audits?subjectType=student&subjectId=student-1&action=teaching%3Asubmit&decision=issued&limit=25",
      expect.objectContaining({
        headers: expect.not.objectContaining({
          "X-Subject-Type": expect.any(String),
          "X-Subject-Id": expect.any(String),
        }),
      }),
    );
    expect(audits[0].tokenHash).toBe("token-hash-1");
    expect(audits[0]).not.toHaveProperty("token");
  });

  it("submits teaching task with one-time capability token bound to request hash", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "capability-1",
          action: "teaching:submit",
          path: "/api/teaching/tasks",
          requestHash: "hash-from-client",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          taskId: "task-1",
          clientRequestId: "client-1",
          status: "COMPLETED",
          nodes: [],
          reactTrace: [],
          evidence: [],
          handoutLatex: "\\section{Learning Goal}",
          interactiveSuggestions: [],
          memoryReuse: {
            reused: true,
            memoryId: "memory-1",
            reuseScope: "private",
            answer: "Review the domain before substitution.",
            similarity: 0.91,
            reason: "Reusable memory matched",
          },
          stageTimings: [{ stage: "memory_reuse", elapsedMs: 2 }],
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);
    const request = {
      clientRequestId: "client-1",
      questionText: "Find f(-1)",
      learningGoal: "Understand new function definitions",
      evidenceLimit: 3,
    };

    const task = await client.submitTeachingTask(request);

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/security/capabilities",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(capabilityBody).toEqual({
      action: "teaching:submit",
      path: "/api/teaching/tasks",
      requestHash: expect.any(String),
      idempotencyKey: "client-1",
      maxCost: 3,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
          "X-Capability-Token": "capability-1",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
        body: JSON.stringify(request),
      }),
    );
    expect(task.taskId).toBe("task-1");
    expect(task.status).toBe("COMPLETED");
    expect(task.memoryReuse?.reused).toBe(true);
    expect(task.stageTimings?.[0].stage).toBe("memory_reuse");
  });

  it("remembers student memory with one-time capability token and backend identity", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "memory-capability",
          action: "student-memory:remember",
          path: "/api/students/memory/remember",
          requestHash: "hash-from-server",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          reused: false,
          memoryId: "memory-1",
          reuseScope: "private",
          answer: "Use a dot b = |a||b|cos(theta) first.",
          similarity: 1,
          reason: "Memory stored",
          stageTimings: [{ stage: "write_memory", elapsedMs: 1 }],
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);
    const request = {
      questionText: "vector dot product angle",
      answerText: "Use a dot b = |a||b|cos(theta) first.",
      knowledgePointName: "vector dot product",
      memoryScope: "private" as const,
      bypassReuse: false,
    };

    const response = await client.rememberStudentMemory(request);

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "student-memory:remember",
      path: "/api/students/memory/remember",
      requestHash: expect.any(String),
      idempotencyKey: expect.stringContaining("student-memory-remember:"),
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/students/memory/remember",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Capability-Token": "memory-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
        body: JSON.stringify(request),
      }),
    );
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(response.memoryId).toBe("memory-1");
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
        headers: expect.objectContaining({ "X-Device-Id": "local-browser-console" }),
      }),
    );
    expect(task.nodes[0].code).toBe("LEARNING_GOAL");
  });

  it("exports teaching task latex with one-time capability token", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "latex-capability",
          action: "teaching-handout:export-latex",
          path: "/api/teaching/tasks/task-1/handout/latex",
          requestHash: "hash-empty",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        text: async () => "\\section{Learning Goal}",
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const latex = await client.exportTeachingTaskLatex("task-1");

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teaching-handout:export-latex",
      path: "/api/teaching/tasks/task-1/handout/teacher/latex",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-handout-export-latex:task-1:teacher",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/handout/teacher/latex",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({
          "X-Capability-Token": "latex-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    expect(latex).toContain("\\section");
  });

  it("exports teaching task pdf with one-time capability token", async () => {
    const pdfBytes = new Uint8Array([37, 80, 68, 70, 45, 49, 46, 52]).buffer;
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "pdf-capability",
          action: "teaching-handout:export-pdf",
          path: "/api/teaching/tasks/task-1/handout/pdf",
          requestHash: "hash-empty",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        arrayBuffer: async () => pdfBytes,
        text: async () => "",
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const pdf = await client.exportTeachingTaskPdf("task-1");

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teaching-handout:export-pdf",
      path: "/api/teaching/tasks/task-1/handout/teacher/pdf",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-handout-export-pdf:task-1:teacher",
      maxCost: 2,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/handout/teacher/pdf",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({
          "X-Capability-Token": "pdf-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    expect(Array.from(pdf.slice(0, 4))).toEqual([37, 80, 68, 70]);
  });

  it("previews teaching task latex with one-time capability token", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "preview-capability",
          action: "teaching-handout:preview-latex",
          path: "/api/teaching/tasks/task-1/handout/latex/preview",
          requestHash: "hash-empty",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        text: async () => "\\section{Learning Goal}",
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const latex = await client.previewTeachingTaskLatex("task-1");

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teaching-handout:preview-latex",
      path: "/api/teaching/tasks/task-1/handout/teacher/latex/preview",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-handout-preview-latex:task-1:teacher",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/handout/teacher/latex/preview",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({
          "X-Capability-Token": "preview-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    expect(latex).toContain("\\section");
  });

  it("previews student handout latex with a version-bound capability token", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "student-preview-capability",
          action: "teaching-handout:preview-latex",
          path: "/api/teaching/tasks/task-1/handout/student/latex/preview",
          requestHash: "hash-empty",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        text: async () => "\\section{student-version}",
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const latex = await client.previewTeachingTaskLatex("task-1", "student");

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teaching-handout:preview-latex",
      path: "/api/teaching/tasks/task-1/handout/student/latex/preview",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-handout-preview-latex:task-1:student",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/handout/student/latex/preview",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({
          "X-Capability-Token": "student-preview-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    expect(latex).toContain("\\section");
  });

  it("creates and downloads teaching handout batch zip with capability tokens", async () => {
    const zipBytes = new Uint8Array([80, 75, 3, 4, 20, 0]).buffer;
    const batchRequest = {
      taskIds: ["task-1", "task-2"],
      folderIds: ["folder-algebra"],
      folderPaths: ["grade-10/functions"],
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "batch-export-capability",
          action: "teaching-handout:batch-export-zip",
          path: "/api/teaching/handouts/batch/zip",
          requestHash: "hash-batch",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          batchId: "batch-1",
          status: "COMPLETED",
          subjectType: "teacher",
          requestedCount: 2,
          exportedCount: 2,
          taskIds: ["task-1", "task-2"],
          folderIds: ["folder-algebra"],
          folderPaths: ["grade-10/functions"],
          expiresAt: "2026-06-28T12:30:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "batch-download-capability",
          action: "teaching-handout:batch-download-zip",
          path: "/api/teaching/handouts/batch/zip/batch-1/download",
          requestHash: "hash-empty",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        arrayBuffer: async () => zipBytes,
        text: async () => "",
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const batch = await client.createTeachingHandoutBatchZip(batchRequest);
    const zip = await client.downloadTeachingHandoutBatchZip(batch.batchId);

    const createCapabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(createCapabilityBody).toEqual({
      action: "teaching-handout:batch-export-zip",
      path: "/api/teaching/handouts/batch/zip",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-handout-batch-export-zip:folder-algebra:grade-10/functions:task-1,task-2",
      maxCost: 2,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/handouts/batch/zip",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Capability-Token": "batch-export-capability",
          "X-Request-Hash": createCapabilityBody.requestHash,
        }),
        body: JSON.stringify(batchRequest),
      }),
    );
    const downloadCapabilityBody = JSON.parse(fetchMock.mock.calls[2][1]?.body as string);
    expect(downloadCapabilityBody).toEqual({
      action: "teaching-handout:batch-download-zip",
      path: "/api/teaching/handouts/batch/zip/batch-1/download",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-handout-batch-download-zip:batch-1",
      maxCost: 2,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      "http://127.0.0.1:8080/api/teaching/handouts/batch/zip/batch-1/download",
      expect.objectContaining({
        method: "GET",
        headers: expect.objectContaining({
          "X-Capability-Token": "batch-download-capability",
          "X-Request-Hash": downloadCapabilityBody.requestHash,
        }),
      }),
    );
    expect(batch.exportedCount).toBe(2);
    expect(Array.from(zip.slice(0, 2))).toEqual([80, 75]);
  });

  it("submits teaching human feedback with a task-bound capability token", async () => {
    const request = {
      rating: 4,
      decision: "needs_revision",
      comment: "Step two explanation needs more detail.",
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "feedback-capability",
          action: "teaching-feedback:submit",
          path: "/api/teaching/tasks/task-1/feedback",
          requestHash: "hash-feedback",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          feedbackId: "feedback-1",
          taskId: "task-1",
          tenantId: "default",
          subjectType: "student",
          subjectId: "student-1",
          rating: 4,
          decision: "needs_revision",
          comment: "Step two explanation needs more detail.",
          createdAt: "2026-06-28T12:00:00Z",
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const feedback = await client.submitTeachingHumanFeedback("task-1", request);

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teaching-feedback:submit",
      path: "/api/teaching/tasks/task-1/feedback",
      requestHash: expect.any(String),
      idempotencyKey: "teaching-feedback-submit:task-1:needs_revision",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/tasks/task-1/feedback",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Capability-Token": "feedback-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
        body: JSON.stringify(request),
      }),
    );
    expect(feedback.feedbackId).toBe("feedback-1");
    expect(feedback.rating).toBe(4);
  });

  it("binds batch zip capability idempotency to selected folder paths", async () => {
    const batchRequest = {
      taskIds: ["task-1", "task-2"],
      folderIds: ["folder-algebra", "folder-geometry"],
      folderPaths: ["grade-10/functions", "grade-10/vectors"],
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "batch-export-capability",
          action: "teaching-handout:batch-export-zip",
          path: "/api/teaching/handouts/batch/zip",
          requestHash: "hash-batch",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          batchId: "batch-2",
          status: "COMPLETED",
          subjectType: "teacher",
          requestedCount: 2,
          exportedCount: 2,
          taskIds: ["task-1", "task-2"],
          folderIds: ["folder-algebra", "folder-geometry"],
          folderPaths: ["grade-10/functions", "grade-10/vectors"],
          expiresAt: "2026-06-28T12:30:00Z",
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    await client.createTeachingHandoutBatchZip(batchRequest);

    const createCapabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(createCapabilityBody).toEqual({
      action: "teaching-handout:batch-export-zip",
      path: "/api/teaching/handouts/batch/zip",
      requestHash: expect.any(String),
      idempotencyKey:
        "teaching-handout-batch-export-zip:folder-algebra,folder-geometry:grade-10/functions,grade-10/vectors:task-1,task-2",
      maxCost: 2,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teaching/handouts/batch/zip",
      expect.objectContaining({
        body: JSON.stringify(batchRequest),
      }),
    );
  });

  it("plans agent run with backend session identity and no client supplied user identity", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
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
        capabilityRequired: true,
        capabilityAction: "agent-run:CoursewareAgent",
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
          satoken: "token-teacher",
          "X-Device-Id": "local-browser-console",
        }),
        body: JSON.stringify(request),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(plan.capabilityRequired).toBe(true);
    expect(plan.allowedToolScopes).toContain("tool:courseware:generate");
    expect(plan.deniedToolScopes).toContain("tool:search:private");
    expect(plan.toolPolicyDecisions[1]).toMatchObject({
      scope: "tool:search:private",
      decision: "DISABLED_BY_USER",
    });
  });

  it("loads agent model catalog from backend session without exposing identity headers", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
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
          satoken: "token-teacher",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(catalog.defaultModelCode).toBe("gpt-5.4");
    expect(catalog.providers[0].models[0].modelCode).toBe("gpt-5.4");
  });

  it("loads agent model health from backend session without exposing identity headers", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
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
          satoken: "token-teacher",
          "X-Device-Id": "local-browser-console",
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

  it("executes high-value agent run with capability token and no client supplied identity", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
    const plan = {
      planId: "plan-1",
      tenantId: "school-a",
      subjectType: "teacher",
      subjectId: "teacher-1",
      agentCode: "CoursewareAgent",
      providerName: "openai",
      modelCode: "gpt-5.4",
      modelLevel: "reasoning",
      allowedToolScopes: ["tool:courseware:generate"],
      deniedToolScopes: [],
      toolPolicyDecisions: [
        {
          scope: "tool:courseware:generate",
          decision: "ALLOWED" as const,
          reason: "Tool is allowed by agent policy and not disabled by request preference",
        },
      ],
      allowedDataScopes: ["TEACHER_PRIVATE"],
      deniedDataScopes: [],
      capabilityRequired: true,
      capabilityAction: "agent-run:CoursewareAgent",
      maxInputTokens: 12000,
      maxOutputTokens: 4000,
      estimatedTotalTokens: 4600,
      estimatedCost: 0.46,
      withinBudget: true,
      routeReason: "courseware_generation uses reasoning model",
      stageTimings: [{ stage: "model_route", elapsedMs: 1 }],
      concurrencyKeys: ["concurrent:user:teacher-1:CoursewareAgent"],
    };
    const executeRequest = {
      plan,
      userInputSummary: "Generate teacher handout for space vectors",
      evidenceRefs: ["textbook:chapter-1"],
      dryRun: false,
    };
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "agent-capability",
          action: "agent-run:CoursewareAgent",
          path: "/api/agents/execute",
          requestHash: "hash-agent",
          expiresAt: "2026-06-28T12:02:00Z",
          maxCost: 1,
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          traceId: "trace-1",
          planId: "plan-1",
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
          concurrencyKeys: ["concurrent:user:teacher-1:CoursewareAgent"],
          stageTimings: [{ stage: "baseline_execute", elapsedMs: 1 }],
          actualUsage: {
            promptTokens: 123,
            completionTokens: 45,
            totalTokens: 168,
          },
          message: "Live model response recorded with provider usage metadata.",
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.executeAgentRun(executeRequest);

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "agent-run:CoursewareAgent",
      path: "/api/agents/execute",
      requestHash: expect.any(String),
      idempotencyKey: "agent-run:plan-1",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/agents/execute",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          satoken: "token-teacher",
          "X-Capability-Token": "agent-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
        body: JSON.stringify(executeRequest),
      }),
    );
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(response.traceId).toBe("trace-1");
    expect(response.concurrencyKeys).toContain("concurrent:user:teacher-1:CoursewareAgent");
    expect(response.modelCode).toBe("gpt-5.4");
    expect(response.actualUsage.totalTokens).toBe(168);
  });

  it("lists agent traces with backend session identity and no client supplied user identity", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
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

    const traces = await client.listAgentTraces({ agentCode: "CoursewareAgent", status: "COMPLETED", limit: 20 });

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/agents/traces?agentCode=CoursewareAgent&status=COMPLETED&limit=20",
      expect.objectContaining({
        headers: expect.objectContaining({
          satoken: "token-teacher",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(traces[0].traceId).toBe("trace-1");
    expect(traces[0].modelCode).toBe("gpt-5.4");
    expect(traces[0].actualUsage.totalTokens).toBe(168);
  });

  it("summarizes visible agent trace usage with backend session identity only", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
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
          satoken: "token-teacher",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(summary.runCount).toBe(2);
    expect(summary.totalUsage.totalTokens).toBe(336);
  });

  it("builds copyable MCP configuration without client supplied identity headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        serverName: "math-agent-rag",
        url: "https://math.example.com/api/mcp",
        valid: true,
        secretKeyAccepted: true,
        secretKeyPreview: "mcp_...cdef",
        secretEnvName: "MATH_AGENT_MCP_SECRET",
        configJson:
          '{\n  "mcpServers" : {\n    "math-agent-rag" : {\n      "type" : "http",\n      "url" : "https://math.example.com/api/mcp",\n      "headers" : {\n        "Authorization" : "Bearer ${MATH_AGENT_MCP_SECRET}"\n      }\n    }\n  }\n}',
      }),
    });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);
    const request = {
      url: "https://math.example.com/api/mcp",
      secretKey: "mcp_secret_1234567890abcdef",
      secretEnvName: "MATH_AGENT_MCP_SECRET",
      enabledToolNames: ["search_textbook_evidence", "plan_agent_run"],
      enabledPromptNames: ["teacher_handout_writer", "student_blank_handout_writer"],
    };

    const config = await client.buildMcpConfiguration(request);

    expect(fetchMock).toHaveBeenCalledWith(
      "http://127.0.0.1:8080/api/mcp/configuration",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
        }),
        body: JSON.stringify(request),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(config.configJson).toContain("mcpServers");
    expect(config.configJson).toContain("${MATH_AGENT_MCP_SECRET}");
    expect(config.configJson).not.toContain("mcp_secret_1234567890abcdef");
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
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(dashboard.studentId).toBe("local-student");
    expect(dashboard.knowledgeProgress[0].progressPercent).toBe(68);
    expect(dashboard.knowledgeGraph?.nodes[0].masteryPercent).toBe(68);
    expect(dashboard.knowledgeGraph?.edges[0].relationType).toBe("PREREQUISITE_FOR");
  });

  it("refreshes student dashboard snapshot with one-time capability token", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "dashboard-capability",
          action: "student-dashboard:refresh",
          path: "/api/students/dashboard/refresh",
          requestHash: "hash-empty",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          tenantId: "school-a",
          studentId: "student-1",
          viewerRole: "student",
          viewerSubjectId: "student-1",
          isAdminView: false,
          knowledgeProgress: [{ knowledgePointName: "space vector", progressPercent: 70 }],
          weakPoints: [],
          recentQuestions: [{ recordId: "mem-1", sourceType: "student_memory" }],
          scoreTrend: [],
          resourceScopes: [{ scopeCode: "STUDENT_MEMORY_PRIVATE" }],
          knowledgeGraph: { generatedFrom: "student_memory_entry:total=1", nodes: [], edges: [] },
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const dashboard = await client.refreshStudentDashboard();

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "student-dashboard:refresh",
      path: "/api/students/dashboard/refresh",
      requestHash: expect.any(String),
      idempotencyKey: "student-dashboard-refresh:self",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/students/dashboard/refresh",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-Capability-Token": "dashboard-capability",
          "X-Request-Hash": capabilityBody.requestHash,
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(dashboard.knowledgeGraph?.generatedFrom).toContain("student_memory_entry");
  });

  it("manages knowledge points and question bank items with capability tokens", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ([{
          knowledgePointId: "kp-1",
          tenantId: "school-a",
          ownerSubjectId: "teacher-1",
          permissionScope: "TEACHER_PRIVATE",
          knowledgePointName: "function domain",
          chapterPath: "functions/basic",
          status: "active",
          sourceSummary: "manual",
        }]),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "knowledge-capability",
          action: "knowledge-point:create",
          path: "/api/knowledge/points",
          requestHash: "hash-knowledge",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          knowledgePointId: "kp-2",
          tenantId: "school-a",
          ownerSubjectId: "teacher-1",
          permissionScope: "TEACHER_PRIVATE",
          knowledgePointName: "space vector dot product",
          chapterPath: "vectors",
          status: "active",
          sourceSummary: "manual",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "question-capability",
          action: "question-bank:create",
          path: "/api/question-bank/items",
          requestHash: "hash-question",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          questionId: "q-1",
          tenantId: "school-a",
          ownerSubjectId: "teacher-1",
          permissionScope: "TEACHER_PRIVATE",
          questionTitle: "vector angle",
          questionText: "Find the angle.",
          answerJson: "{}",
          difficulty: "medium",
          status: "active",
          knowledgePointIds: ["kp-2"],
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ([{
          questionId: "q-1",
          questionTitle: "vector angle",
          questionText: "Find the angle.",
          permissionScope: "TEACHER_PRIVATE",
          knowledgePointIds: ["kp-2"],
        }]),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const points = await client.listKnowledgePoints();
    const point = await client.createKnowledgePoint({
      knowledgePointName: "space vector dot product",
      chapterPath: "vectors",
      permissionScope: "MATH_VIP",
      sourceSummary: "manual",
    });
    const question = await client.createQuestionBankItem({
      questionTitle: "vector angle",
      questionText: "Find the angle.",
      answerJson: "{}",
      difficulty: "medium",
      permissionScope: "MATH_VIP",
      knowledgePointIds: [point.knowledgePointId],
    });
    const questions = await client.searchQuestionBankItems("vector", 5);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/knowledge/points",
      expect.objectContaining({
        headers: expect.not.objectContaining({ "X-Subject-Id": expect.any(String) }),
      }),
    );
    const knowledgeCapabilityBody = JSON.parse(fetchMock.mock.calls[1][1]?.body as string);
    expect(knowledgeCapabilityBody).toEqual({
      action: "knowledge-point:create",
      path: "/api/knowledge/points",
      requestHash: expect.any(String),
      idempotencyKey: expect.stringContaining("knowledge-point-create:"),
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "http://127.0.0.1:8080/api/knowledge/points",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-Capability-Token": "knowledge-capability",
          "X-Request-Hash": knowledgeCapabilityBody.requestHash,
        }),
      }),
    );
    const questionCapabilityBody = JSON.parse(fetchMock.mock.calls[3][1]?.body as string);
    expect(questionCapabilityBody).toEqual({
      action: "question-bank:create",
      path: "/api/question-bank/items",
      requestHash: expect.any(String),
      idempotencyKey: expect.stringContaining("question-bank-create:"),
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      6,
      "http://127.0.0.1:8080/api/question-bank/items?query=vector&limit=5",
      expect.objectContaining({
        headers: expect.not.objectContaining({ "X-Subject-Type": expect.any(String) }),
      }),
    );
    expect(points[0].knowledgePointId).toBe("kp-1");
    expect(question.knowledgePointIds).toEqual(["kp-2"]);
    expect(questions[0].questionId).toBe("q-1");
  });

  it("imports teacher resource questions with capability token and backend identity", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "import-capability",
          action: "question-bank:import-teacher-resource",
          path: "/api/question-bank/import/teacher-resources/doc-vector",
          requestHash: "hash-import",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          documentId: "doc-vector",
          processedBlockCount: 2,
          importedQuestionCount: 1,
          skippedBlockCount: 1,
          duplicateBlockCount: 0,
          linkedKnowledgePointCount: 1,
          importedQuestions: [{
            questionId: "q-imported",
            questionTitle: "vector angle",
            questionText: "已知空间向量 a,b，求夹角。",
            answerJson: "{}",
            difficulty: "medium",
            status: "active",
            sourceResourceDocumentId: "doc-vector",
            sourceBlockId: "b-1",
            sourceChecksum: "checksum-1",
            knowledgePointIds: ["kp-vector"],
          }],
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const response = await client.importTeacherResourceQuestions("doc-vector");

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "question-bank:import-teacher-resource",
      path: "/api/question-bank/import/teacher-resources/doc-vector",
      requestHash: expect.any(String),
      idempotencyKey: "question-bank-import-teacher-resource:doc-vector",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/question-bank/import/teacher-resources/doc-vector",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-Capability-Token": "import-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(response.importedQuestions[0].sourceBlockId).toBe("b-1");
  });

  it("manages teacher resources with capability tokens and without client supplied identity headers", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ([{ documentId: "doc-1", title: "Space vector handout", syncStatus: "registered" }]),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "register-capability",
          action: "teacher-resource:register",
          path: "/api/teacher/resources",
          requestHash: "hash-register",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ documentId: "doc-2", title: "Feishu question bank", syncStatus: "registered" }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "archive-capability",
          action: "teacher-resource:archive",
          path: "/api/teacher/resources/doc-2",
          requestHash: "hash-archive",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ documentId: "doc-2", title: "Feishu question bank", syncStatus: "archived" }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const list = await client.listTeacherResources();
    const created = await client.registerTeacherResource({
      sourceType: "feishu",
      title: "Feishu question bank",
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
      "http://127.0.0.1:8080/api/security/capabilities",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    const registerCapabilityBody = JSON.parse(fetchMock.mock.calls[1][1]?.body as string);
    expect(registerCapabilityBody).toEqual({
      action: "teacher-resource:register",
      path: "/api/teacher/resources",
      requestHash: expect.any(String),
      idempotencyKey: expect.stringContaining("teacher-resource-register:"),
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "http://127.0.0.1:8080/api/teacher/resources",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "Content-Type": "application/json",
          "X-Device-Id": "local-browser-console",
          "X-Capability-Token": "register-capability",
          "X-Request-Hash": registerCapabilityBody.requestHash,
        }),
        body: JSON.stringify({
          sourceType: "feishu",
          title: "Feishu question bank",
          originalUrl: "https://example.feishu.cn/docs/doc1",
          permissionScope: "TEACHER_PRIVATE",
        }),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      "http://127.0.0.1:8080/api/security/capabilities",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({ "Content-Type": "application/json" }),
      }),
    );
    const archiveCapabilityBody = JSON.parse(fetchMock.mock.calls[3][1]?.body as string);
    expect(archiveCapabilityBody).toEqual({
      action: "teacher-resource:archive",
      path: "/api/teacher/resources/doc-2",
      requestHash: expect.any(String),
      idempotencyKey: "teacher-resource-archive:doc-2",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      5,
      "http://127.0.0.1:8080/api/teacher/resources/doc-2",
      expect.objectContaining({
        method: "DELETE",
        headers: expect.objectContaining({
          "X-Capability-Token": "archive-capability",
          "X-Request-Hash": archiveCapabilityBody.requestHash,
        }),
      }),
    );
    expect(list[0].documentId).toBe("doc-1");
    expect(created.syncStatus).toBe("registered");
    expect(archived.syncStatus).toBe("archived");
  });

  it("creates, executes, and lists teacher resource sync jobs with capability tokens", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ([{
          jobId: "job-1",
          documentId: "doc-1",
          operation: "feishu_download",
          status: "queued",
          phase: "download_pending",
        }]),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "sync-capability",
          action: "teacher-resource:sync",
          path: "/api/teacher/resources/doc-1/sync-jobs",
          requestHash: "hash-sync",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          jobId: "job-2",
          documentId: "doc-1",
          sourceType: "feishu",
          operation: "feishu_download",
          status: "queued",
          phase: "download_pending",
          createdBy: "teacher-1",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "sync-execute-capability",
          action: "teacher-resource:sync-execute",
          path: "/api/teacher/resources/doc-1/sync-jobs/job-2/execute",
          requestHash: "hash-sync-execute",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          jobId: "job-2",
          documentId: "doc-1",
          sourceType: "feishu",
          operation: "feishu_download",
          status: "completed",
          phase: "download_completed",
          createdBy: "teacher-1",
          message: "Downloaded 1 Feishu files; skipped 0",
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const jobs = await client.listTeacherResourceSyncJobs("doc-1");
    const created = await client.createTeacherResourceSyncJob("doc-1");
    const executed = await client.executeTeacherResourceSyncJob("doc-1", created.jobId);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      "http://127.0.0.1:8080/api/teacher/resources/doc-1/sync-jobs",
      expect.objectContaining({
        headers: expect.not.objectContaining({
          "X-Subject-Type": expect.any(String),
          "X-Subject-Id": expect.any(String),
        }),
      }),
    );
    const capabilityBody = JSON.parse(fetchMock.mock.calls[1][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teacher-resource:sync",
      path: "/api/teacher/resources/doc-1/sync-jobs",
      requestHash: expect.any(String),
      idempotencyKey: "teacher-resource-sync:doc-1",
      maxCost: 1,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      "http://127.0.0.1:8080/api/teacher/resources/doc-1/sync-jobs",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-Capability-Token": "sync-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    const executeCapabilityBody = JSON.parse(fetchMock.mock.calls[3][1]?.body as string);
    expect(executeCapabilityBody).toEqual({
      action: "teacher-resource:sync-execute",
      path: "/api/teacher/resources/doc-1/sync-jobs/job-2/execute",
      requestHash: expect.any(String),
      idempotencyKey: "teacher-resource-sync-execute:doc-1:job-2",
      maxCost: 2,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      5,
      "http://127.0.0.1:8080/api/teacher/resources/doc-1/sync-jobs/job-2/execute",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-Capability-Token": "sync-execute-capability",
          "X-Request-Hash": executeCapabilityBody.requestHash,
        }),
      }),
    );
    expect(fetchMock.mock.calls[4][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[4][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(jobs[0].status).toBe("queued");
    expect(created.operation).toBe("feishu_download");
    expect(executed.status).toBe("completed");
    expect(executed.phase).toBe("download_completed");
  });

  it("resumes a paused teacher resource sync job with a resume capability token", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          token: "sync-resume-capability",
          action: "teacher-resource:sync-resume",
          path: "/api/teacher/resources/doc-1/sync-jobs/job-2/resume",
          requestHash: "hash-sync-resume",
          expiresAt: "2026-06-28T12:02:00Z",
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          jobId: "job-2",
          documentId: "doc-1",
          sourceType: "feishu",
          operation: "feishu_download",
          status: "completed",
          phase: "download_completed",
          createdBy: "teacher-1",
          message: "Downloaded 1 Feishu files after resume",
        }),
      });
    const client = createTextbookApiClient("http://127.0.0.1:8080", fetchMock);

    const resumed = await client.resumeTeacherResourceSyncJob("doc-1", "job-2");

    const capabilityBody = JSON.parse(fetchMock.mock.calls[0][1]?.body as string);
    expect(capabilityBody).toEqual({
      action: "teacher-resource:sync-resume",
      path: "/api/teacher/resources/doc-1/sync-jobs/job-2/resume",
      requestHash: expect.any(String),
      idempotencyKey: "teacher-resource-sync-resume:doc-1:job-2",
      maxCost: 2,
    });
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://127.0.0.1:8080/api/teacher/resources/doc-1/sync-jobs/job-2/resume",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "X-Capability-Token": "sync-resume-capability",
          "X-Request-Hash": capabilityBody.requestHash,
        }),
      }),
    );
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[1][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(resumed.status).toBe("completed");
    expect(resumed.phase).toBe("download_completed");
  });

  it("loads teacher resource sync checkpoint without client supplied identity headers", async () => {
    globalThis.localStorage.setItem(
      "math-agent:auth-session",
      JSON.stringify({
        userId: "teacher-1",
        username: "teacher",
        role: "teacher",
        tenantId: "school-a",
        tokenName: "satoken",
        tokenValue: "token-teacher",
      }),
    );
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
          satoken: "token-teacher",
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Capability-Token");
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
          "X-Device-Id": "local-browser-console",
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
          "X-Device-Id": "local-browser-console",
        }),
      }),
    );
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Id");
    expect(fetchMock.mock.calls[0][1]?.headers).not.toHaveProperty("X-Subject-Type");
    expect(fetchMock.mock.calls[0][0]).not.toContain("APPKEY");
    expect(fetchMock.mock.calls[0][0]).not.toContain("APP_SECRET");
    expect(response.candidates[0].url).toBe("https://my.feishu.cn/docx/doc-token");
  });
});
