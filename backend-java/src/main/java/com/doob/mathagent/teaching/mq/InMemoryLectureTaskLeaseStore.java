package com.doob.mathagent.teaching.mq;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Deterministic implementation of the same CAS rules used by the MySQL adapter. */
public class InMemoryLectureTaskLeaseStore implements LectureTaskLeaseStore {
    private final Map<String, Entry> entries = new HashMap<>();

    public synchronized void create(String taskId) { entries.put(taskId, new Entry(LectureTaskLeaseStatus.PENDING)); }
    @Override public synchronized LectureTaskLease tryAcquire(String taskId, String workerId, Instant now, Duration duration) {
        Entry entry = entries.get(taskId);
        if (entry == null || entry.status == LectureTaskLeaseStatus.COMPLETED || entry.status == LectureTaskLeaseStatus.FAILED
                || (entry.status == LectureTaskLeaseStatus.RUNNING && !entry.expiresAt.isBefore(now))) return null;
        entry.status = LectureTaskLeaseStatus.RUNNING; entry.workerId = workerId; entry.token = UUID.randomUUID().toString();
        entry.expiresAt = now.plus(duration); entry.retryCount++;
        return new LectureTaskLease(taskId, entry.token, workerId, entry.retryCount, entry.expiresAt);
    }
    @Override public synchronized boolean complete(LectureTaskLease lease) {
        Entry entry = validRunning(lease); if (entry == null) return false;
        entry.status = LectureTaskLeaseStatus.COMPLETED; entry.token = null; entry.expiresAt = null; return true;
    }
    @Override public synchronized java.util.List<String> reclaimExpired(Instant now, int limit) {
        java.util.List<String> reclaimed = new java.util.ArrayList<>();
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            if (reclaimed.size() >= limit) break;
            Entry entry = candidate.getValue();
            if (entry.status == LectureTaskLeaseStatus.RUNNING && entry.expiresAt.isBefore(now)) {
                entry.status = LectureTaskLeaseStatus.RETRYING;
                entry.workerId = null;
                entry.token = null;
                entry.expiresAt = null;
                reclaimed.add(candidate.getKey());
            }
        }
        return java.util.List.copyOf(reclaimed);
    }
    @Override public synchronized boolean renew(LectureTaskLease lease, Instant expiresAt) {
        Entry entry = validRunning(lease); if (entry == null) return false;
        entry.expiresAt = expiresAt; return true;
    }
    @Override public synchronized boolean failOrRetry(LectureTaskLease lease, String error, int maximumAttempts) {
        Entry entry = validRunning(lease); if (entry == null) return false;
        entry.lastError = error; entry.token = null; entry.expiresAt = null;
        boolean retry = lease.retryCount() < maximumAttempts; entry.status = retry ? LectureTaskLeaseStatus.RETRYING : LectureTaskLeaseStatus.FAILED; return retry;
    }
    public synchronized LectureTaskLeaseStatus status(String taskId) { return entries.get(taskId).status; }
    public synchronized String lastError(String taskId) { return entries.get(taskId).lastError; }
    private Entry validRunning(LectureTaskLease lease) { Entry entry = entries.get(lease.taskId()); return entry != null && entry.status == LectureTaskLeaseStatus.RUNNING && lease.token().equals(entry.token) ? entry : null; }
    private static final class Entry { private LectureTaskLeaseStatus status; private String token; private String workerId; private int retryCount; private Instant expiresAt; private String lastError; private Entry(LectureTaskLeaseStatus status) { this.status = status; } }
}
