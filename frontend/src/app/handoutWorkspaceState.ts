/**
 * Owns the transient collaboration entries for one handout workspace. Keeping this state separate from persisted
 * history prevents an old task card from being rendered beside the new task after a user submits once.
 */
export type HandoutWorkspaceEntry<TTask extends HandoutTaskIdentity = HandoutTaskIdentity> =
  | {
      id: string;
      role: "user";
      createdAt: string;
      learningGoal: string;
      questionText?: string;
      templateName?: string;
      evidenceLimit: number;
    }
  | {
      id: string;
      role: "assistant";
      createdAt: string;
      taskId?: string;
      loading?: boolean;
      error?: string;
      task?: TTask;
    };

/** The smallest task contract required to replace a pending collaboration card. */
export type HandoutTaskIdentity = {
  taskId: string;
  learningGoal?: string;
  questionText?: string;
  selectedTemplate?: { displayName?: string };
  status: string;
};

/** Input captured at submission time and intentionally kept alongside the pending card. */
export type BeginHandoutRunInput = {
  requestId: string;
  learningGoal: string;
  questionText?: string;
  templateName: string;
  evidenceLimit: number;
  createdAt: string;
};

/**
 * Starts a new workspace conversation. A workspace is deliberately not a history feed: history is rendered by its
 * own sidebar, so retaining old assistant cards here would make a single submission look like duplicate generation.
 */
export function beginCurrentHandoutRun<TTask extends HandoutTaskIdentity = HandoutTaskIdentity>(
  input: BeginHandoutRunInput,
): HandoutWorkspaceEntry<TTask>[] {
  return [
    {
      id: `user:${input.requestId}`,
      role: "user",
      createdAt: input.createdAt,
      learningGoal: input.learningGoal,
      questionText: input.questionText || undefined,
      templateName: input.templateName,
      evidenceLimit: input.evidenceLimit,
    },
    {
      id: `assistant-pending:${input.requestId}`,
      role: "assistant",
      createdAt: input.createdAt,
      loading: true,
    },
  ];
}

/**
 * Replaces the pending card with the latest durable task snapshot. Repeated SSE events and recovery reads therefore
 * update one assistant card instead of appending another teacher-version-looking result.
 */
export function replaceCurrentHandoutTask<TTask extends HandoutTaskIdentity>(
  entries: HandoutWorkspaceEntry<TTask>[],
  task: TTask,
): HandoutWorkspaceEntry<TTask>[] {
  const assistantEntry = entries.find((entry) => entry.role === "assistant");
  // Retain the submission pair only while its assistant card is genuinely pending or already owns this task. A
  // history selection supplies a different task id, so reusing the first workspace user card would display the old
  // request beside the selected result and make the sidebar appear to reorder conversations arbitrarily.
  const sameWorkspaceRun = Boolean(
    assistantEntry?.loading
      || assistantEntry?.taskId === task.taskId
      || assistantEntry?.task?.taskId === task.taskId,
  );
  const existingUserEntry = sameWorkspaceRun ? entries.find((entry) => entry.role === "user") : undefined;
  // A persisted history task has no matching in-memory pending card after refresh. Rebuild the user side from its
  // durable request fields so selecting history never renders an orphan assistant answer or pairs a fresh result
  // with a previous task's question.
  const userEntry = existingUserEntry ?? {
    id: `user:history:${task.taskId}`,
    role: "user" as const,
    createdAt: new Date().toISOString(),
    learningGoal: task.learningGoal?.trim() || "历史讲义任务",
    questionText: task.questionText?.trim() || undefined,
    templateName: task.selectedTemplate?.displayName?.trim() || undefined,
    evidenceLimit: 0,
  };
  const updatedAssistant: HandoutWorkspaceEntry<TTask> = {
    id: `assistant:${task.taskId}`,
    role: "assistant",
    taskId: task.taskId,
    task,
    createdAt: sameWorkspaceRun ? assistantEntry?.createdAt ?? new Date().toISOString() : new Date().toISOString(),
  };
  return userEntry ? [userEntry, updatedAssistant] : [updatedAssistant];
}
