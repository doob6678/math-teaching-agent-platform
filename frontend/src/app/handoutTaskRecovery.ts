import { TeachingTaskResponse } from "../shared/api/textbookApi";

export type HandoutTaskRecoverySession = {
  userId: string;
  role: string;
  tenantId: string;
};

const TERMINAL_TASK_STATUSES = new Set([
  "COMPLETED",
  "FAILED",
  "WAITING_REVIEW",
  "DRAFT_ONLY",
]);

/**
 * Persists only an opaque durable task ID and its authenticated session partition. The task payload remains owned by
 * the backend, so a refresh must always reload it through the current session's authorization checks.
 */
export function persistRecoverableHandoutTask(
  storage: Storage | undefined,
  storageKey: string,
  session: HandoutTaskRecoverySession | null,
  task: Pick<TeachingTaskResponse, "taskId" | "status">,
) {
  if (!storage || !session || isTerminalHandoutTaskStatus(task.status)) return;
  storage.setItem(storageKey, JSON.stringify({
    taskId: task.taskId,
    session: sessionPartition(session),
  }));
}

/** Returns a durable task ID only when it belongs to the current browser session partition. */
export function readRecoverableHandoutTaskId(
  storage: Storage | undefined,
  storageKey: string,
  session: HandoutTaskRecoverySession | null,
) {
  if (!storage || !session) return "";
  try {
    const stored = JSON.parse(storage.getItem(storageKey) || "{}") as { taskId?: unknown; session?: unknown };
    return typeof stored.taskId === "string" && stored.taskId.trim()
      && stored.session === sessionPartition(session)
      ? stored.taskId.trim()
      : "";
  } catch {
    return "";
  }
}

/** Removes stale or terminal recovery metadata without touching task data stored by the backend. */
export function clearRecoverableHandoutTask(storage: Storage | undefined, storageKey: string) {
  storage?.removeItem(storageKey);
}

export function isTerminalHandoutTaskStatus(status: string | undefined) {
  return TERMINAL_TASK_STATUSES.has((status || "").toUpperCase());
}

function sessionPartition(session: HandoutTaskRecoverySession) {
  return `${session.role}:${session.tenantId}:${session.userId}`;
}
