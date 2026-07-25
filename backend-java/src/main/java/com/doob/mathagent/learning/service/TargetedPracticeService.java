package com.doob.mathagent.learning.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.learning.StudentKnowledgeMastery;
import com.doob.mathagent.learning.StudentLearningLoopService;
import com.doob.mathagent.learning.dto.TargetedPracticeRequest;
import com.doob.mathagent.learning.vo.StudentPracticeTaskResponse;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.LectureTaskSubmissionService;
import com.doob.mathagent.teaching.service.TeachingWorkflowService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Converts a student's diagnosed weak points into a durable, student-owned practice task.
 *
 * <p>Real linked question-bank rows are grounding examples. The model may create variations only inside the
 * existing teaching worker, while the student-facing projection never contains teacher answers.</p>
 */
@Service
public class TargetedPracticeService {
    private static final int MAX_EXERCISES = 10;
    private static final int MAX_EVIDENCE = 8;
    private static final int MAX_EXAMPLE_ROWS = 3;

    private final StudentLearningLoopService learningLoopService;
    private final KnowledgeQuestionBankService questionBankService;
    private final PracticeTaskGateway taskGateway;

    @Autowired
    public TargetedPracticeService(
            StudentLearningLoopService learningLoopService,
            KnowledgeQuestionBankService questionBankService,
            LectureTaskSubmissionService submissionService,
            TeachingWorkflowService workflowService) {
        this(learningLoopService, questionBankService, new PracticeTaskGateway() {
            @Override
            public TeachingTaskResponse submit(TeachingTaskRequest request, TeachingRequestContext context) {
                return submissionService.submit(request, context);
            }

            @Override
            public Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context) {
                return workflowService.get(taskId, context);
            }
        });
    }

    public TargetedPracticeService(
            StudentLearningLoopService learningLoopService,
            KnowledgeQuestionBankService questionBankService,
            PracticeTaskGateway taskGateway) {
        this.learningLoopService = Objects.requireNonNull(learningLoopService);
        this.questionBankService = Objects.requireNonNull(questionBankService);
        this.taskGateway = Objects.requireNonNull(taskGateway);
    }

    /** Creates an idempotent practice task from current mastery facts and visible source examples. */
    public StudentPracticeTaskResponse submit(TargetedPracticeRequest request, RequestSubject subject) {
        RequestSubject normalizedSubject = requireStudent(subject);
        TargetedPracticeRequest normalizedRequest = normalize(request);
        List<StudentKnowledgeMastery> weakPoints = weakPoints(normalizedRequest, normalizedSubject);
        if (weakPoints.isEmpty()) {
            throw new IllegalArgumentException("No diagnosed weak point matches the requested scope");
        }

        List<QuestionBankItemResponse> examples = linkedExamples(normalizedSubject, weakPoints);
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("No visible source question is linked to the diagnosed weak point");
        }
        String pointSummary = weakPoints.stream()
                .map(item -> item.knowledgePointId() + "（掌握度 " + item.masteryPercent()
                        + "%，错误 " + item.incorrectCount() + " 次）")
                .reduce((left, right) -> left + "、" + right)
                .orElseThrow();
        String sourceExamples = examples.stream()
                .map(item -> "题库参考题 " + item.questionId() + "：" + safe(item.questionText()))
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        String questionText = "薄弱知识点：" + pointSummary + "\n真实题库参考题：\n" + sourceExamples;
        String requirements = "本任务是学生专项练习生成。请基于所附真实题库参考题和教材 RAG 证据，生成 "
                + normalizedRequest.exerciseCount() + " 道全新、可独立作答的变式练习，覆盖基础到提高；"
                + "题目必须围绕薄弱知识点，不能照抄参考题，不能凭空引入无关知识点。学生版只展示题目和作答空间，"
                + "不得展示答案、评分点、教师讲解或内部提示。教师答案只保存在后端任务内部，不能进入学生响应。";
        TeachingTaskRequest teachingRequest = new TeachingTaskRequest(
                normalizedRequest.clientRequestId(),
                questionText,
                "针对薄弱知识点生成学生专项练习：" + pointSummary,
                normalizedRequest.evidenceLimit(),
                null,
                null,
                null,
                null,
                requirements);
        TeachingRequestContext context = new TeachingRequestContext(
                normalizedSubject.tenantId(), normalizedSubject.subjectType(), normalizedSubject.subjectId(),
                normalizedSubject.deviceId());
        return project(taskGateway.submit(teachingRequest, context), normalizedSubject.subjectId(),
                weakPoints.stream().map(StudentKnowledgeMastery::knowledgePointId).toList());
    }

    /** Reads one owned task and strips every teacher-facing field before it reaches a student. */
    public StudentPracticeTaskResponse get(String taskId, RequestSubject subject) {
        RequestSubject normalizedSubject = requireStudent(subject);
        TeachingRequestContext context = new TeachingRequestContext(
                normalizedSubject.tenantId(), normalizedSubject.subjectType(), normalizedSubject.subjectId(),
                normalizedSubject.deviceId());
        TeachingTaskResponse task = taskGateway.get(taskId, context)
                .orElseThrow(() -> new IllegalArgumentException("Practice task not found"));
        return project(task, normalizedSubject.subjectId(), List.of());
    }

    private List<StudentKnowledgeMastery> weakPoints(TargetedPracticeRequest request, RequestSubject subject) {
        return learningLoopService.tenantWeakPoints(subject.tenantId(), subject.subjectId()).stream()
                .filter(item -> request.knowledgePointId() == null
                        || request.knowledgePointId().equals(item.knowledgePointId()))
                .filter(item -> item.weaknessLevel() > 0)
                .toList();
    }

    private List<QuestionBankItemResponse> linkedExamples(
            RequestSubject subject, List<StudentKnowledgeMastery> weakPoints) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<QuestionBankItemResponse> examples = new ArrayList<>();
        for (StudentKnowledgeMastery point : weakPoints) {
            for (QuestionBankItemResponse item : questionBankService.searchQuestionsByKnowledgePoint(
                    subject.tenantId(), "student", subject.subjectId(), point.knowledgePointId(), MAX_EXAMPLE_ROWS)) {
                if (seen.add(item.questionId())) {
                    examples.add(item);
                }
            }
        }
        return List.copyOf(examples);
    }

    private static StudentPracticeTaskResponse project(
            TeachingTaskResponse task, String studentId, List<String> knowledgePointIds) {
        return new StudentPracticeTaskResponse(
                task.taskId(), task.clientRequestId(), task.status(), studentId,
                List.copyOf(knowledgePointIds), task.questionText(), task.learningGoal(), task.studentHandoutLatex(),
                task.interactiveSuggestions(), task.errorMessage());
    }

    private static TargetedPracticeRequest normalize(TargetedPracticeRequest request) {
        if (request == null || request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        String point = request.knowledgePointId() == null || request.knowledgePointId().isBlank()
                ? null : request.knowledgePointId().strip();
        return new TargetedPracticeRequest(
                request.clientRequestId().strip(), point,
                Math.max(1, Math.min(MAX_EXERCISES, request.exerciseCount())),
                Math.max(1, Math.min(MAX_EVIDENCE, request.evidenceLimit())));
    }

    private static RequestSubject requireStudent(RequestSubject subject) {
        RequestSubject normalized = subject == null ? RequestSubject.anonymous("default", "unknown-device") : subject.normalize();
        if (!"student".equals(normalized.subjectType()) || normalized.subjectId() == null) {
            throw new IllegalArgumentException("Student role required");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "（题干缺失）" : value.replaceAll("\\s+", " ").strip();
    }

    public interface PracticeTaskGateway {
        TeachingTaskResponse submit(TeachingTaskRequest request, TeachingRequestContext context);

        Optional<TeachingTaskResponse> get(String taskId, TeachingRequestContext context);
    }
}
