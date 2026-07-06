package com.doob.mathagent.student.service;

import com.doob.mathagent.memory.service.StudentMemoryEntry;
import com.doob.mathagent.memory.service.StudentMemoryStore;
import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Refreshes persisted student dashboard snapshots from backend-owned learning signals.
 */
@Service
public class StudentLearningSnapshotRefreshService {

    private static final int GLOBAL_DASHBOARD_MEMORY_LIMIT = 200;

    private final StudentMemoryStore memoryStore;
    private final StudentLearningSnapshotStore snapshotStore;
    private final StudentDashboardSubjectResolver subjectResolver;
    private final ObjectMapper objectMapper;

    /**
     * Creates a snapshot refresh service.
     *
     * @param memoryStore student memory store used as the first real aggregation source
     * @param snapshotStore snapshot persistence store
     * @param subjectResolver resolves the represented subject role from backend identity data
     * @param objectMapper JSON mapper for snapshot payloads
     */
    @Autowired
    public StudentLearningSnapshotRefreshService(
            StudentMemoryStore memoryStore,
            StudentLearningSnapshotStore snapshotStore,
            StudentDashboardSubjectResolver subjectResolver,
            ObjectMapper objectMapper) {
        this.memoryStore = memoryStore;
        this.snapshotStore = snapshotStore;
        this.subjectResolver = subjectResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Refreshes one student dashboard snapshot using backend-normalized identity rules.
     *
     * @param query dashboard query built from the backend request subject
     * @return dashboard response generated from the saved snapshot payload
     */
    public StudentDashboardResponse refresh(StudentDashboardQuery query) {
        StudentDashboardQuery normalized = query.normalize();
        String tenantId = normalized.tenantId();
        String studentId = normalized.targetStudentId();
        List<StudentMemoryEntry> entries = normalized.globalView()
                ? activeTenantMemoryEntries(tenantId)
                : activeMemoryEntries(tenantId, studentId);
        List<StudentDashboardResponse.KnowledgeProgress> progress = progress(entries);
        List<StudentDashboardResponse.WeakPoint> weakPoints = List.of();
        List<StudentDashboardResponse.RecentQuestion> recentQuestions = recentQuestions(entries);
        List<StudentDashboardResponse.ScorePoint> scoreTrend = List.of();
        List<StudentDashboardResponse.ResourceScope> resourceScopes = resourceScopes(entries);
        String sourceSummary = sourceSummary(entries, progress);
        StudentDashboardResponse.KnowledgeGraph graph =
                StudentKnowledgeGraphAssembler.knowledgeGraphFromProgressOnly(progress, weakPoints, sourceSummary);
        StudentDashboardResponse response = new StudentDashboardResponse(
                tenantId,
                studentId,
                subjectResolver.resolveSubjectRole(normalized),
                normalized.viewerRole(),
                normalized.viewerSubjectId(),
                normalized.adminView(),
                progress,
                weakPoints,
                recentQuestions,
                scoreTrend,
                resourceScopes,
                graph);
        snapshotStore.save(toRecord(response, sourceSummary));
        return response;
    }

    /**
     * Loads active memory entries visible to the target student.
     */
    private List<StudentMemoryEntry> activeMemoryEntries(String tenantId, String studentId) {
        return memoryStore.candidates(tenantId, studentId).stream()
                .filter(entry -> tenantId.equals(entry.tenantId()))
                .filter(entry -> "active".equals(entry.status()))
                .filter(entry -> "public".equals(entry.memoryScope()) || studentId.equals(entry.studentId()))
                .sorted(Comparator
                        .comparing(StudentMemoryEntry::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(StudentMemoryEntry::memoryId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Loads active tenant-wide learning signals for teacher/admin global overview.
     */
    private List<StudentMemoryEntry> activeTenantMemoryEntries(String tenantId) {
        return memoryStore.tenantCandidates(tenantId, GLOBAL_DASHBOARD_MEMORY_LIMIT).stream()
                .filter(entry -> tenantId.equals(entry.tenantId()))
                .filter(entry -> "active".equals(entry.status()))
                .sorted(Comparator
                        .comparing(StudentMemoryEntry::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(StudentMemoryEntry::memoryId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * Builds knowledge progress from actual memory coverage by knowledge point.
     */
    private static List<StudentDashboardResponse.KnowledgeProgress> progress(List<StudentMemoryEntry> entries) {
        Map<String, List<StudentMemoryEntry>> byKnowledgePoint = new LinkedHashMap<>();
        for (StudentMemoryEntry entry : entries) {
            String knowledgePoint = clean(entry.knowledgePointName());
            if (!knowledgePoint.isBlank()) {
                byKnowledgePoint.computeIfAbsent(knowledgePoint, ignored -> new ArrayList<>()).add(entry);
            }
        }
        return byKnowledgePoint.entrySet().stream()
                .map(item -> new StudentDashboardResponse.KnowledgeProgress(
                        knowledgePointId(item.getKey()),
                        item.getKey(),
                        "student_memory_entry_count=" + item.getValue().size(),
                        "",
                        progressPercent(item.getValue().size())))
                .toList();
    }

    /**
     * Builds recent question records from actual memory entries.
     */
    private static List<StudentDashboardResponse.RecentQuestion> recentQuestions(List<StudentMemoryEntry> entries) {
        return entries.stream()
                .limit(8)
                .map(entry -> new StudentDashboardResponse.RecentQuestion(
                        entry.memoryId(),
                        "student_memory",
                        truncate(clean(entry.questionText()), 80),
                        clean(entry.knowledgePointName()),
                        entry.status()))
                .toList();
    }

    /**
     * Builds visible resource scopes from actual memory scopes.
     */
    private static List<StudentDashboardResponse.ResourceScope> resourceScopes(List<StudentMemoryEntry> entries) {
        boolean hasPrivate = entries.stream().anyMatch(entry -> "private".equals(entry.memoryScope()));
        boolean hasPublic = entries.stream().anyMatch(entry -> "public".equals(entry.memoryScope()));
        List<StudentDashboardResponse.ResourceScope> scopes = new ArrayList<>();
        if (hasPrivate) {
            scopes.add(new StudentDashboardResponse.ResourceScope(
                    "STUDENT_MEMORY_PRIVATE",
                    "Private student memory",
                    "Visible to the owning student and privileged inspectors after backend identity checks"));
        }
        if (hasPublic) {
            scopes.add(new StudentDashboardResponse.ResourceScope(
                    "STUDENT_MEMORY_PUBLIC",
                    "Public reusable memory",
                    "Visible within the same tenant when the memory entry is marked public"));
        }
        return List.copyOf(scopes);
    }

    /**
     * Converts a dashboard response to a persisted snapshot record.
     */
    private StudentLearningSnapshotRecord toRecord(StudentDashboardResponse response, String sourceSummary) {
        return new StudentLearningSnapshotRecord(
                UUID.randomUUID().toString(),
                response.tenantId(),
                response.studentId(),
                null,
                json(response.knowledgeProgress()),
                json(response.knowledgeGraph()),
                json(response.weakPoints()),
                json(response.recentQuestions()),
                json(response.scoreTrend()),
                json(response.resourceScopes()),
                sourceSummary);
    }

    /**
     * Serializes a snapshot payload.
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Student learning snapshot JSON serialization failed", exception);
        }
    }

    /**
     * Builds a concise source summary for audit and frontend display.
     */
    private static String sourceSummary(
            List<StudentMemoryEntry> entries,
            List<StudentDashboardResponse.KnowledgeProgress> progress) {
        long privateCount = entries.stream().filter(entry -> "private".equals(entry.memoryScope())).count();
        long publicCount = entries.stream().filter(entry -> "public".equals(entry.memoryScope())).count();
        return "student_memory_entry:total=%d,private=%d,public=%d,knowledgePoints=%d"
                .formatted(entries.size(), privateCount, publicCount, progress.size());
    }

    /**
     * Derives a conservative coverage percent from the count of real memory entries.
     */
    private static int progressPercent(int memoryCount) {
        return Math.min(95, 40 + Math.max(0, memoryCount) * 15);
    }

    /**
     * Builds a stable id from a knowledge point label.
     */
    private static String knowledgePointId(String knowledgePointName) {
        return "memory-" + Integer.toUnsignedString(knowledgePointName.toLowerCase(Locale.ROOT).hashCode(), 16);
    }

    /**
     * Normalizes text fields without inventing replacement content.
     */
    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Truncates long question titles for compact dashboard display.
     */
    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
