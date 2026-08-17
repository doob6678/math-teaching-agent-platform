import { describe, expect, it } from "vitest";
import {
  clearRecoverableHandoutTask,
  persistRecoverableHandoutTask,
  readRecoverableHandoutTaskId,
} from "./handoutTaskRecovery";

const key = "task-recovery";
const teacher = { userId: "teacher-1", role: "teacher", tenantId: "school-a" };

function storage() {
  const entries = new Map<string, string>();
  return {
    get length() { return entries.size; },
    clear: () => entries.clear(),
    getItem: (name: string) => entries.get(name) ?? null,
    key: (index: number) => [...entries.keys()][index] ?? null,
    setItem: (name: string, value: string) => { entries.set(name, value); },
    removeItem: (name: string) => { entries.delete(name); },
  } satisfies Storage;
}

describe("handoutTaskRecovery", () => {
  it("restores only an in-progress task for the authenticated session partition", () => {
    const localStorage = storage();
    persistRecoverableHandoutTask(localStorage, key, teacher, { taskId: "task-running", status: "RETRYING" });

    expect(readRecoverableHandoutTaskId(localStorage, key, teacher)).toBe("task-running");
    expect(readRecoverableHandoutTaskId(localStorage, key, { ...teacher, userId: "teacher-2" })).toBe("");
    expect(readRecoverableHandoutTaskId(localStorage, key, { ...teacher, tenantId: "school-b" })).toBe("");
  });

  it("does not retain terminal task metadata", () => {
    const localStorage = storage();
    persistRecoverableHandoutTask(localStorage, key, teacher, { taskId: "task-completed", status: "COMPLETED" });
    expect(readRecoverableHandoutTaskId(localStorage, key, teacher)).toBe("");

    persistRecoverableHandoutTask(localStorage, key, teacher, { taskId: "task-running", status: "RUNNING" });
    clearRecoverableHandoutTask(localStorage, key);
    expect(readRecoverableHandoutTaskId(localStorage, key, teacher)).toBe("");
  });
});
