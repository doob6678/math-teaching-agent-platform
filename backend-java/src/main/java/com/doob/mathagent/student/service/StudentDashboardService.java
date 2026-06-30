package com.doob.mathagent.student.service;

import com.doob.mathagent.student.dto.StudentDashboardQuery;
import com.doob.mathagent.student.vo.StudentDashboardResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds student learning dashboards from recoverable task, textbook, Feishu, and exam-analysis signals.
 *
 * <p>This stage returns a deterministic baseline so the frontend and API contract can be verified. Later stages will
 * replace the static signals with MyBatis/Milvus/RAG reads while keeping the response contract stable.</p>
 */
@Service
public class StudentDashboardService {

    private final StudentLearningSnapshotStore snapshotStore;
    private final ObjectMapper objectMapper;

    /**
     * Creates a dashboard service for Spring runtime.
     *
     * @param snapshotStore store for persisted student learning snapshots
     * @param objectMapper JSON mapper used to decode persisted snapshot payloads
     */
    @Autowired
    public StudentDashboardService(StudentLearningSnapshotStore snapshotStore, ObjectMapper objectMapper) {
        this.snapshotStore = snapshotStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a dashboard service for focused tests that exercise fallback aggregation only.
     */
    public StudentDashboardService() {
        this(new EmptyStudentLearningSnapshotStore(), new ObjectMapper());
    }

    /**
     * Builds the dashboard for the target student after applying viewer isolation rules.
     *
     * @param query viewer and requested student query
     * @return student dashboard response
     */
    public StudentDashboardResponse dashboard(StudentDashboardQuery query) {
        StudentDashboardQuery normalized = query.normalize();
        String studentId = normalized.targetStudentId();
        StudentLearningSnapshotRecord snapshot = snapshotStore
                .findLatest(normalized.tenantId(), studentId)
                .orElse(null);
        if (snapshot != null) {
            return dashboardFromSnapshot(normalized, snapshot);
        }
        List<StudentDashboardResponse.KnowledgeProgress> progress = knowledgeProgress(studentId);
        List<StudentDashboardResponse.WeakPoint> weakPointList = weakPoints();
        return new StudentDashboardResponse(
                normalized.tenantId(),
                studentId,
                normalized.viewerRole(),
                normalized.viewerSubjectId(),
                normalized.adminView(),
                progress,
                weakPointList,
                recentQuestions(studentId),
                scoreTrend(),
                resourceScopes(normalized.viewerRole()),
                StudentKnowledgeGraphAssembler.knowledgeGraph(progress, weakPointList, normalized.viewerRole()));
    }

    /**
     * Builds a dashboard response from a persisted MySQL snapshot.
     *
     * @param normalized backend-normalized dashboard query
     * @param snapshot persisted student snapshot
     * @return dashboard response backed by snapshot JSON
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
            List<StudentDashboardResponse.KnowledgeProgress> progress = knowledgeProgress(normalized.targetStudentId());
            List<StudentDashboardResponse.WeakPoint> weakPoints = weakPoints();
            return new StudentDashboardResponse(
                    normalized.tenantId(),
                    normalized.targetStudentId(),
                    normalized.viewerRole(),
                    normalized.viewerSubjectId(),
                    normalized.adminView(),
                    progress,
                    weakPoints,
                    recentQuestions(normalized.targetStudentId()),
                    scoreTrend(),
                    resourceScopes(normalized.viewerRole()),
                    StudentKnowledgeGraphAssembler.knowledgeGraph(progress, weakPoints, normalized.viewerRole()));
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

    /**
     * Returns knowledge graph progress arranged by textbook and Feishu anchors.
     *
     * @param studentId student id used for deterministic record ids
     * @return knowledge progress list
     */
    private static List<StudentDashboardResponse.KnowledgeProgress> knowledgeProgress(String studentId) {
        return List.of(
                new StudentDashboardResponse.KnowledgeProgress(
                        "math-vector-dot-product",
                        "空间向量数量积",
                        "选择性必修第一册 / 空间向量 / 第 35 页",
                        "feishu://math/vector-dot-product",
                        68),
                new StudentDashboardResponse.KnowledgeProgress(
                        "math-function-piecewise",
                        "分段函数与定义域",
                        "必修第一册 / 函数概念 / 第 101 页",
                        "feishu://math/function-piecewise?student=" + studentId,
                        82),
                new StudentDashboardResponse.KnowledgeProgress(
                        "math-solid-geometry",
                        "立体几何线面关系",
                        "必修第二册 / 立体几何初步 / 第 74 页",
                        "feishu://math/solid-geometry",
                        54));
    }

    /**
     * Returns weak points inferred from current learning records.
     *
     * @return weak point list
     */
    private static List<StudentDashboardResponse.WeakPoint> weakPoints() {
        return List.of(
                new StudentDashboardResponse.WeakPoint(
                        "math-vector-dot-product",
                        "空间向量数量积",
                        4,
                        "最近 5 道空间向量题中，投影与夹角转化错误 3 次。"),
                new StudentDashboardResponse.WeakPoint(
                        "math-solid-geometry",
                        "立体几何线面关系",
                        3,
                        "证明题中实线/虚线关系和辅助线选择不稳定。"));
    }

    /**
     * Returns recent recoverable question records.
     *
     * @param studentId student id used for deterministic record ids
     * @return recent question list
     */
    private static List<StudentDashboardResponse.RecentQuestion> recentQuestions(String studentId) {
        return List.of(
                new StudentDashboardResponse.RecentQuestion(
                        studentId + "-task-001",
                        "teaching_task",
                        "空间向量数量积与夹角计算",
                        "空间向量数量积",
                        "COMPLETED"),
                new StudentDashboardResponse.RecentQuestion(
                        studentId + "-image-002",
                        "uploaded_image",
                        "图片题：立体几何垂直证明",
                        "立体几何线面关系",
                        "REVIEW_REQUIRED"));
    }

    /**
     * Returns recent exam score trend points.
     *
     * @return score trend list
     */
    private static List<StudentDashboardResponse.ScorePoint> scoreTrend() {
        return List.of(
                new StudentDashboardResponse.ScorePoint("月考一", 108, 126, 6),
                new StudentDashboardResponse.ScorePoint("期中考试", 116, 94, 4),
                new StudentDashboardResponse.ScorePoint("最近一次周测", 121, 78, 3));
    }

    /**
     * Returns allowed resource scopes for the viewer role.
     *
     * @param viewerRole current viewer role
     * @return resource scope list
     */
    private static List<StudentDashboardResponse.ResourceScope> resourceScopes(String viewerRole) {
        if ("admin".equals(viewerRole) || "teacher".equals(viewerRole)) {
            return List.of(
                    new StudentDashboardResponse.ResourceScope("PUBLIC_TEXTBOOK", "公开教材", "所有学生可读"),
                    new StudentDashboardResponse.ResourceScope("MATH_VIP", "数学 VIP 资源", "数学 VIP 学生和教师可读"),
                    new StudentDashboardResponse.ResourceScope("TEACHER_FEISHU", "教师飞书讲义", "教师和管理员可读"));
        }
        return List.of(
                new StudentDashboardResponse.ResourceScope("PUBLIC_TEXTBOOK", "公开教材", "所有学生可读"),
                new StudentDashboardResponse.ResourceScope("MATH_VIP", "数学 VIP 资源", "数学 VIP 学生和教师可读"));
    }
}
