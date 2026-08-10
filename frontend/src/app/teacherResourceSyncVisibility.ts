/**
 * Limits sync-job calls to the resource owner for teachers. Tenant-visible resources remain readable/searchable,
 * but their synchronization history contains owner-scoped operational details that another teacher cannot request.
 */
export function canLoadTeacherResourceSyncJobs(
  resource: { ownerSubjectId?: string },
  session: { userId?: string; role?: string } | null,
): boolean {
  if (session?.role === "admin") {
    return true;
  }
  return Boolean(resource.ownerSubjectId && session?.userId && resource.ownerSubjectId === session.userId);
}
