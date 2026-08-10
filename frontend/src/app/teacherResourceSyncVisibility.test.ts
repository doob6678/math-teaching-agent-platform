import { describe, expect, it } from "vitest";
import { canLoadTeacherResourceSyncJobs } from "./teacherResourceSyncVisibility";

describe("canLoadTeacherResourceSyncJobs", () => {
  it("does not request private sync details for another teacher's tenant-visible resource", () => {
    expect(canLoadTeacherResourceSyncJobs(
      { ownerSubjectId: "teacher-owner" },
      { userId: "teacher-viewer", role: "teacher" },
    )).toBe(false);
  });

  it("allows a teacher to load sync details for their own resource", () => {
    expect(canLoadTeacherResourceSyncJobs(
      { ownerSubjectId: "teacher-owner" },
      { userId: "teacher-owner", role: "teacher" },
    )).toBe(true);
  });

  it("allows an administrator to load every visible resource's sync details", () => {
    expect(canLoadTeacherResourceSyncJobs(
      { ownerSubjectId: "teacher-owner" },
      { userId: "admin-owner", role: "admin" },
    )).toBe(true);
  });
});
