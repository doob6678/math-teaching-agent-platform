import { describe, expect, it } from "vitest";
import { beginCurrentHandoutRun, replaceCurrentHandoutTask } from "./handoutWorkspaceState";

describe("handoutWorkspaceState", () => {
  it("replaces restored collaboration entries when a new handout run begins", () => {
    const entries = beginCurrentHandoutRun({
      requestId: "request-new",
      learningGoal: "涂色问题分类讨论",
      questionText: "保留原图作为题目资源",
      templateName: "标准讲义",
      evidenceLimit: 6,
      createdAt: "2026-07-12T08:00:00Z",
    });

    expect(entries).toHaveLength(2);
    expect(entries.map((entry) => entry.id)).toEqual([
      "user:request-new",
      "assistant-pending:request-new",
    ]);
  });

  it("keeps exactly one assistant card for the current task after progress updates", () => {
    const initial = beginCurrentHandoutRun({
      requestId: "request-new",
      learningGoal: "涂色问题分类讨论",
      questionText: "",
      templateName: "标准讲义",
      evidenceLimit: 6,
      createdAt: "2026-07-12T08:00:00Z",
    });

    const firstProgress = replaceCurrentHandoutTask(initial, {
      taskId: "task-1",
      learningGoal: "涂色问题分类讨论",
      status: "RUNNING",
    });
    const latestProgress = replaceCurrentHandoutTask(firstProgress, {
      taskId: "task-1",
      learningGoal: "涂色问题分类讨论",
      status: "COMPLETED",
    });

    expect(latestProgress).toHaveLength(2);
    expect(latestProgress.filter((entry) => entry.role === "assistant")).toHaveLength(1);
    expect(latestProgress.find((entry) => entry.role === "assistant")).toMatchObject({
      id: "assistant:task-1",
      taskId: "task-1",
      task: { status: "COMPLETED" },
    });
  });

  it("rebuilds the original user request when an independently persisted history task is opened", () => {
    const restored = replaceCurrentHandoutTask([], {
      taskId: "task-history-1",
      learningGoal: "地图着色的分类计数",
      questionText: "五个相邻区域使用四种颜色，相邻区域不能同色。",
      selectedTemplate: { displayName: "赵礼显专题讲义" },
      status: "COMPLETED",
    });

    expect(restored).toEqual([
      expect.objectContaining({
        id: "user:history:task-history-1",
        role: "user",
        learningGoal: "地图着色的分类计数",
        questionText: "五个相邻区域使用四种颜色，相邻区域不能同色。",
        templateName: "赵礼显专题讲义",
      }),
      expect.objectContaining({ id: "assistant:task-history-1", role: "assistant", taskId: "task-history-1" }),
    ]);
  });

  it("replaces a prior workspace pair with the selected history task instead of mixing their requests", () => {
    const currentTaskEntries = replaceCurrentHandoutTask([], {
      taskId: "task-current",
      learningGoal: "二次函数最值",
      questionText: "求抛物线顶点坐标。",
      selectedTemplate: { displayName: "赵礼显专题讲义" },
      status: "COMPLETED",
    });

    const selectedHistoryEntries = replaceCurrentHandoutTask(currentTaskEntries, {
      taskId: "task-history",
      learningGoal: "地图着色的分类计数",
      questionText: "五个相邻区域使用四种颜色，相邻区域不能同色。",
      selectedTemplate: { displayName: "赵礼显专题讲义" },
      status: "COMPLETED",
    });

    expect(selectedHistoryEntries).toEqual([
      expect.objectContaining({
        id: "user:history:task-history",
        learningGoal: "地图着色的分类计数",
        questionText: "五个相邻区域使用四种颜色，相邻区域不能同色。",
      }),
      expect.objectContaining({ id: "assistant:task-history", taskId: "task-history" }),
    ]);
  });
});
