package com.doob.mathagent.learning.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.knowledge.vo.QuestionBankItemResponse;
import com.doob.mathagent.learning.StudentKnowledgeMastery;
import com.doob.mathagent.learning.StudentLearningLoopService;
import com.doob.mathagent.learning.dto.TargetedHandoutRequest;
import com.doob.mathagent.teaching.TeachingRequestContext;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.service.LectureTaskSubmissionService;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Adapts the learning diagnosis into the existing durable teaching-task workflow.
 *
 * <p>The service deliberately selects questions through the normalized question-to-knowledge links.  The model is
 * only invoked later by the teaching worker for composition; it cannot invent the weak points or the exercises that
 * caused this handout to be requested.</p>
 */
@Service
public class TargetedLearningHandoutService {
    private static final int MAX_QUESTIONS = 20;
    private static final int MAX_EVIDENCE = 8;

    private final StudentLearningLoopService learningLoopService;
    private final KnowledgeQuestionBankService questionBankService;
    private final LectureTaskSubmissionService submissionService;

    public TargetedLearningHandoutService(
            StudentLearningLoopService learningLoopService,
            KnowledgeQuestionBankService questionBankService,
            LectureTaskSubmissionService submissionService) {
        this.learningLoopService = Objects.requireNonNull(learningLoopService);
        this.questionBankService = Objects.requireNonNull(questionBankService);
        this.submissionService = Objects.requireNonNull(submissionService);
    }

    /** Creates an idempotent asynchronous task from weak-point facts and real linked questions. */
    public TeachingTaskResponse submit(TargetedHandoutRequest request, RequestSubject subject) {
        RequestSubject normalizedSubject = requireTeacher(subject);
        TargetedHandoutRequest normalizedRequest = normalize(request);
        List<StudentKnowledgeMastery> weakPoints = learningLoopService
                .tenantWeakPoints(normalizedSubject.tenantId(), normalizedRequest.studentId())
                .stream()
                .filter(item -> normalizedRequest.knowledgePointId() == null
                        || normalizedRequest.knowledgePointId().equals(item.knowledgePointId()))
                .sorted(Comparator.comparingInt(StudentKnowledgeMastery::weaknessLevel).reversed()
                        .thenComparingInt(StudentKnowledgeMastery::masteryPercent)
                        .thenComparing(StudentKnowledgeMastery::knowledgePointId))
                .toList();
        if (weakPoints.isEmpty()) {
            throw new IllegalArgumentException("No diagnosed weak point matches the requested scope");
        }

        List<QuestionBankItemResponse> questions = new ArrayList<>();
        LinkedHashSet<String> seenQuestionIds = new LinkedHashSet<>();
        for (StudentKnowledgeMastery weakPoint : weakPoints) {
            List<QuestionBankItemResponse> candidates = questionBankService.searchQuestionsByKnowledgePoint(
                    normalizedSubject.tenantId(), "teacher", normalizedSubject.subjectId(),
                    weakPoint.knowledgePointId(), normalizedRequest.questionLimit());
            for (QuestionBankItemResponse candidate : candidates) {
                if (seenQuestionIds.add(candidate.questionId())) {
                    questions.add(candidate);
                }
                if (questions.size() >= normalizedRequest.questionLimit()) {
                    break;
                }
            }
            if (questions.size() >= normalizedRequest.questionLimit()) {
                break;
            }
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("No visible question-bank item is linked to the requested weak point");
        }

        String target = normalizedRequest.studentId() == null
                ? "班级"
                : "学生 " + normalizedRequest.studentId();
        String weakPointSummary = weakPoints.stream()
                .limit(5)
                .map(item -> item.knowledgePointId() + "（掌握度 " + item.masteryPercent()
                        + "%，薄弱等级 " + item.weaknessLevel() + "）")
                .reduce((left, right) -> left + "、" + right)
                .orElseThrow();
        String questionText = questions.stream()
                .map(question -> "题目 " + question.questionId() + "：" + safe(question.questionText()))
                .reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
        String requirements = "本任务由学习闭环自动生成。薄弱知识点：" + weakPointSummary
                + "。必须优先引用教材 RAG 证据；输出教师版、学生版、答案和评分点；题目只能使用所附真实题库题目，"
                + "不能凭空改题或补造知识点；保留题号与知识点对应关系。";
        TeachingTaskRequest teachingRequest = new TeachingTaskRequest(
                normalizedRequest.clientRequestId(),
                questionText,
                "针对" + target + "的薄弱知识点生成数学针对性讲义",
                normalizedRequest.evidenceLimit(),
                normalizedRequest.handoutTemplateCode(),
                null,
                null,
                null,
                requirements);
        TeachingRequestContext context = new TeachingRequestContext(
                normalizedSubject.tenantId(), normalizedSubject.subjectType(), normalizedSubject.subjectId(),
                normalizedSubject.deviceId());
        return submissionService.submit(teachingRequest, context);
    }

    private static TargetedHandoutRequest normalize(TargetedHandoutRequest request) {
        if (request == null || request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            throw new IllegalArgumentException("clientRequestId is required");
        }
        return new TargetedHandoutRequest(
                request.clientRequestId().strip(),
                clean(request.studentId()),
                clean(request.knowledgePointId()),
                Math.max(1, Math.min(MAX_QUESTIONS, request.questionLimit())),
                clean(request.handoutTemplateCode()),
                Math.max(1, Math.min(MAX_EVIDENCE, request.evidenceLimit())));
    }

    private static RequestSubject requireTeacher(RequestSubject subject) {
        RequestSubject normalized = subject == null ? RequestSubject.anonymous("default", "unknown-device") : subject.normalize();
        if (!("teacher".equals(normalized.subjectType()) || "admin".equals(normalized.subjectType()))
                || normalized.subjectId() == null) {
            throw new IllegalArgumentException("Teacher or admin role required");
        }
        return normalized;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "（题干缺失）" : value.strip();
    }
}
