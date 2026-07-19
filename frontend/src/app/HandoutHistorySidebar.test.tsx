import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { HandoutHistorySidebar, replaceHistoryTaskInPlace } from "./components/HandoutHistorySidebar";
import { TeachingTaskResponse } from "../shared/api/textbookApi";

function task(taskId: string, title: string): TeachingTaskResponse {
  return {
    taskId,
    clientRequestId: `client-${taskId}`,
    tenantId: "school-a",
    subjectType: "teacher",
    subjectId: "teacher-001",
    selectedTemplate: undefined,
    status: "COMPLETED",
    questionText: "",
    learningGoal: title,
    nodes: [],
    workflowEvents: [],
    reactTrace: [],
    evidence: [],
    handoutLatex: "\\section{讲义} 已完成内容。",
    teacherHandoutLatex: "\\section{教师版} 已完成内容。",
    studentHandoutLatex: "\\section{学生版} 留白。",
    lectureHandoutLatex: "\\section{16:10} 讲解。",
    interactiveSuggestions: [],
  };
}

describe("HandoutHistorySidebar", () => {
  it("updates a selected task in place without changing server history order", () => {
    const first = task("task-1", "第一份讲义");
    const second = task("task-2", "第二份讲义");
    const refreshedSecond = task("task-2", "第二份讲义（最新）");

    expect(replaceHistoryTaskInPlace([first, second], refreshedSecond).map((item) => item.taskId)).toEqual([
      "task-1",
      "task-2",
    ]);
    expect(replaceHistoryTaskInPlace([first, second], refreshedSecond)[1].learningGoal).toBe("第二份讲义（最新）");
  });

  it("shows the selected history item while its detail request is still opening", () => {
    const html = renderToStaticMarkup(
      <HandoutHistorySidebar
        history={[task("task-1", "第一份讲义"), task("task-2", "第二份讲义")]}
        currentTaskId=""
        loading={false}
        openingTaskId="task-2"
        isOpen
        onToggle={vi.fn()}
        onSelect={vi.fn()}
        onRemove={vi.fn()}
      />,
    );

    expect(html).toContain("当前查看");
    expect(html).toContain('aria-current="page"');
    expect(html).toContain("handout-resource-list");
  });

  it("keeps a running task visible so its durable checkpoint can be reopened or recovered", () => {
    const running = { ...task("task-running", "正在生成的涂色讲义"), status: "RUNNING" as const };
    const html = renderToStaticMarkup(
      <HandoutHistorySidebar
        history={[running]}
        currentTaskId="task-running"
        loading={false}
        openingTaskId=""
        isOpen
        onToggle={vi.fn()}
        onSelect={vi.fn()}
        onRemove={vi.fn()}
      />,
    );

    expect(html).toContain("正在生成的涂色讲义");
    expect(html).toContain("生成中");
  });
});
