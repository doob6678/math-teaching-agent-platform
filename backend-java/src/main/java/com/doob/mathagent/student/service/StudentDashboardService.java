package com.doob.mathagent.student.service;

import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds student dashboards from persisted snapshots and backend-owned learning signals.
 */
@Service
public class StudentDashboardService {

    private final StudentLearningSnapshotStore snapshotStore;
    private final StudentLearningSnapshotRefreshService refreshService;
    private final StudentDashboardSubjectResolver subjectResolver;
    private final ObjectMapper objectMapper;

    /**
     * Creates a dashboard service for Spring runtime.
     *
     * @param snapshotStore store for persisted student learning snapshots
     * @param refreshService real aggregation service used when no valid snapshot exists
     * @param subjectResolver resolves the represented subject role from backend account records
     * @param objectMapper JSON mapper used to decode persisted snapshot payloads
     */
    @Autowired
    public StudentDashboardService(
            StudentLearningSnapshotStore snapshotStore,
            StudentLearningSnapshotRefreshService refreshService,
            StudentDashboardSubjectResolver subjectResolver,
            ObjectMapper objectMapper) {
        this.snapshotStore = snapshotStore;
        this.refreshService = refreshService;
        this.subjectResolver = subjectResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the dashboard for the target student after applying viewer isolation rules.
     *
     * @param query viewer and requested student query
     * @return student dashboard response
     */
    public StudentDashboardResponse dashboard(StudentDashboardQuery query) {
        StudentDashboardQuery normalized = query.normalize();
        if (normalized.selectionRequired()) {
            return emptyDashboard(normalized, "selection_required");
        }
        if (!subjectResolver.isStudentTarget(normalized)) {
            return emptyDashboard(normalized, "non_student_target");
        }
        return snapshotStore
                .findLatest(normalized.tenantId(), normalized.targetStudentId())
                .map(snapshot -> dashboardFromSnapshot(normalized, snapshot))
                .orElseGet(() -> refreshService.refresh(normalized));
    }

    /**
     * Builds a dashboard response from a persisted MySQL snapshot. Invalid JSON is not papered over with demo data; the
     * service refreshes from real backend-owned signals instead.
     *
     * @param normalized backend-normalized dashboard query
     * @param snapshot persisted student snapshot
     * @return dashboard response backed by snapshot JSON or a real refreshed snapshot
     */
    private StudentDashboardResponse dashboardFromSnapshot(
            StudentDashboardQuery normalized,
            StudentLearningSnapshotRecord snapshot) {
        try {
            List<StudentDashboardResponse.KnowledgeProgress> progress = objectMapper.readValue(
                    jsonOrEmptyArray(snapshot.knowledgeProgressJson()),
                    new TypeReference<>() {
                    });
            List<StudentDashboardResponse.WeakPoint> weakPoints = objectMapper.readValue(
                    jsonOrEmptyArray(snapshot.weakPointsJson()),
                    new TypeReference<>() {
                    });
            List<StudentDashboardResponse.RecentQuestion> recentQuestions = objectMapper.readValue(
                    jsonOrEmptyArray(snapshot.recentQuestionsJson()),
                    new TypeReference<>() {
                    });
            List<StudentDashboardResponse.ScorePoint> scoreTrend = objectMapper.readValue(
                    jsonOrEmptyArray(snapshot.scoreTrendJson()),
                    new TypeReference<>() {
                    });
            List<StudentDashboardResponse.ResourceScope> resourceScopes = objectMapper.readValue(
                    jsonOrEmptyArray(snapshot.resourceScopesJson()),
                    new TypeReference<>() {
                    });
            StudentDashboardResponse.KnowledgeGraph graph = objectMapper.readValue(
                    jsonOrDefaultGraph(snapshot.knowledgeGraphJson(), snapshot.sourceSummary()),
                    StudentDashboardResponse.KnowledgeGraph.class);
            return new StudentDashboardResponse(
                    normalized.tenantId(),
                    normalized.targetStudentId(),
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
        } catch (Exception exception) {
            return refreshService.refresh(normalized);
        }
    }

    /**
     * Defaults blank JSON array payloads.
     *
     * @param value persisted JSON value
     * @return JSON array string
     */
    private static String jsonOrEmptyArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    /**
     * Defaults blank graph payloads.
     *
     * @param value persisted graph JSON
     * @param sourceSummary fallback source summary
     * @return graph JSON object string
     */
    private static String jsonOrDefaultGraph(String value, String sourceSummary) {
        if (value != null && !value.isBlank()) {
            return value;
        }
        String generatedFrom = sourceSummary == null || sourceSummary.isBlank() ? "mysql_snapshot" : sourceSummary;
        return "{\"nodes\":[],\"edges\":[],\"generatedFrom\":\"" + generatedFrom.replace("\"", "\\\"") + "\"}";
    }

    private StudentDashboardResponse emptyDashboard(StudentDashboardQuery normalized, String generatedFrom) {
        return new StudentDashboardResponse(
                normalized.tenantId(),
                "",
                subjectResolver.resolveSubjectRole(normalized),
                normalized.viewerRole(),
                normalized.viewerSubjectId(),
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new StudentDashboardResponse.KnowledgeGraph(List.of(), List.of(), generatedFrom));
    }
}
