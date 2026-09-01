package com.doob.mathagent.student;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.student.dto.StudentExplanationRequest;
import com.doob.mathagent.student.entity.StudentExplanationMessageEntity;
import com.doob.mathagent.student.entity.StudentExplanationSessionEntity;
import com.doob.mathagent.student.mapper.StudentExplanationMessageMapper;
import com.doob.mathagent.student.mapper.StudentExplanationSessionMapper;
import com.doob.mathagent.student.service.MyBatisStudentExplanationHistoryStore;
import com.doob.mathagent.student.service.StudentExplanationHistorySummary;
import com.doob.mathagent.student.vo.StudentExplanationResponse;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisStudentExplanationHistoryStoreTest {

    @Test
    void saveUpsertsSessionAndPersistsCompleteMessagePayload() {
        CapturingSessionMapper sessionMapper = new CapturingSessionMapper();
        CapturingMessageMapper messageMapper = new CapturingMessageMapper();
        MyBatisStudentExplanationHistoryStore store =
                new MyBatisStudentExplanationHistoryStore(sessionMapper.proxy(), messageMapper.proxy());

        store.save(request("conversation-1"), subject(), null, response("explain-1", "conversation-1"));

        assertThat(sessionMapper.inserted).hasSize(1);
        assertThat(sessionMapper.updated).hasSize(1);
        StudentExplanationSessionEntity session = sessionMapper.updated.getFirst();
        assertThat(session.getConversationId()).isEqualTo("conversation-1");
        assertThat(session.getTenantId()).isEqualTo("school-a");
        assertThat(session.getSubjectType()).isEqualTo("student");
        assertThat(session.getSubjectId()).isEqualTo("student-1");
        assertThat(session.getTotalMessages()).isEqualTo(1);

        assertThat(messageMapper.inserted).hasSize(1);
        StudentExplanationMessageEntity message = messageMapper.inserted.getFirst();
        assertThat(message.getExplanationId()).isEqualTo("explain-1");
        assertThat(message.getConversationId()).isEqualTo("conversation-1");
        assertThat(message.getRequestJson()).contains("space vector");
        assertThat(message.getCardsJson()).contains("step_by_step");
        assertThat(message.getSourcesJson()).contains("textbook://book/page/12#chunk=c1");
        assertThat(message.getTotalTokens()).isEqualTo(14);
    }

    @Test
    void findRecentScopesByBackendSubjectAndConversation() {
        CapturingSessionMapper sessionMapper = new CapturingSessionMapper();
        CapturingMessageMapper messageMapper = new CapturingMessageMapper();
        messageMapper.rows.add(message("explain-old", "conversation-1", "school-a", "student", "student-1",
                LocalDateTime.parse("2026-07-01T08:00:00")));
        messageMapper.rows.add(message("explain-new", "conversation-1", "school-a", "student", "student-1",
                LocalDateTime.parse("2026-07-01T09:00:00")));
        messageMapper.rows.add(message("explain-other", "conversation-2", "school-a", "student", "student-1",
                LocalDateTime.parse("2026-07-01T10:00:00")));
        messageMapper.rows.add(message("explain-leak", "conversation-1", "school-a", "student", "student-2",
                LocalDateTime.parse("2026-07-01T11:00:00")));
        MyBatisStudentExplanationHistoryStore store =
                new MyBatisStudentExplanationHistoryStore(sessionMapper.proxy(), messageMapper.proxy());

        List<StudentExplanationHistorySummary> history =
                store.findRecent("school-a", "student", "student-1", "conversation-1", 10);

        assertThat(history).extracting(StudentExplanationHistorySummary::explanationId)
                .containsExactly("explain-new", "explain-old");
    }

    private static StudentExplanationRequest request(String conversationId) {
        return new StudentExplanationRequest(
                conversationId,
                "space vector angle",
                null,
                null,
                null,
                null,
                true,
                true,
                false,
                5,
                5);
    }

    private static RequestSubject subject() {
        return new RequestSubject("school-a", "student", "student-1", "device-1");
    }

    private static StudentExplanationResponse response(String explanationId, String conversationId) {
        return new StudentExplanationResponse(
                explanationId,
                conversationId,
                "school-a",
                "student-1",
                "student",
                "space vector angle",
                "none",
                StudentExplanationResponse.ImageUnderstanding.none(),
                "student_explanation_card_orchestrator_v0.1",
                new StudentExplanationResponse.AiDraft(
                        true,
                        "openai",
                        "gpt-5.4",
                        5,
                        9,
                        14,
                        true,
                        "ok",
                        List.of(),
                        ""),
                List.of(new StudentExplanationResponse.WorkflowStage(
                        "persist_history", "save", "completed", "ok", 1)),
                List.of(new StudentExplanationResponse.ExplanationCard(
                        "step_by_step", "Steps", "Use vectors.", List.of("Build coordinates"), List.of(), "formula")),
                List.of(new StudentExplanationResponse.ExplanationSource(
                        "textbook", "book p.12", "textbook://book/page/12#chunk=c1", "PUBLIC_TEXTBOOK", "vector", 1.0,
                        "空间向量 / 第 12 页", "")),
                22L);
    }

    private static StudentExplanationMessageEntity message(
            String explanationId,
            String conversationId,
            String tenantId,
            String subjectType,
            String subjectId,
            LocalDateTime createdAt) {
        StudentExplanationMessageEntity entity = new StudentExplanationMessageEntity();
        entity.setExplanationId(explanationId);
        entity.setConversationId(conversationId);
        entity.setTenantId(tenantId);
        entity.setSubjectType(subjectType);
        entity.setSubjectId(subjectId);
        entity.setStudentId(subjectId);
        entity.setViewerRole(subjectType);
        entity.setQuestionText("space vector angle");
        entity.setImageStatus("none");
        entity.setAiProviderName("openai");
        entity.setAiModelCode("gpt-5.4");
        entity.setTotalTokens(14);
        entity.setTotalElapsedMs(22L);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private static class CapturingSessionMapper {
        private final List<StudentExplanationSessionEntity> inserted = new ArrayList<>();
        private final List<StudentExplanationSessionEntity> updated = new ArrayList<>();

        StudentExplanationSessionMapper proxy() {
            return (StudentExplanationSessionMapper) Proxy.newProxyInstance(
                    StudentExplanationSessionMapper.class.getClassLoader(),
                    new Class<?>[] {StudentExplanationSessionMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectById" -> inserted.stream()
                                .filter(row -> row.getConversationId().equals(args[0]))
                                .findFirst()
                                .orElse(null);
                        case "insert" -> {
                            inserted.add((StudentExplanationSessionEntity) args[0]);
                            yield 1;
                        }
                        case "updateById" -> {
                            updated.add((StudentExplanationSessionEntity) args[0]);
                            yield 1;
                        }
                        case "selectBatchIds" -> {
                            @SuppressWarnings("unchecked")
                            java.util.Collection<String> ids = (java.util.Collection<String>) args[0];
                            yield inserted.stream()
                                    .filter(row -> ids.contains(row.getConversationId()))
                                    .toList();
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static class CapturingMessageMapper {
        private final List<StudentExplanationMessageEntity> rows = new ArrayList<>();
        private final List<StudentExplanationMessageEntity> inserted = new ArrayList<>();

        StudentExplanationMessageMapper proxy() {
            return (StudentExplanationMessageMapper) Proxy.newProxyInstance(
                    StudentExplanationMessageMapper.class.getClassLoader(),
                    new Class<?>[] {StudentExplanationMessageMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> {
                            inserted.add((StudentExplanationMessageEntity) args[0]);
                            yield 1;
                        }
                        case "selectPage" -> selectPage(
                                (Page<StudentExplanationMessageEntity>) args[0],
                                (Wrapper<StudentExplanationMessageEntity>) args[1]);
                        // store 现用 .last("LIMIT n") + selectList 把上限落到 SQL 层；仿真按同一过滤排序返回。
                        case "selectList" -> selectList();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private List<StudentExplanationMessageEntity> selectList() {
            return rows.stream()
                    .filter(row -> "school-a".equals(row.getTenantId()))
                    .filter(row -> "student".equals(row.getSubjectType()))
                    .filter(row -> "student-1".equals(row.getSubjectId()))
                    .filter(row -> "conversation-1".equals(row.getConversationId()))
                    .sorted(Comparator.comparing(StudentExplanationMessageEntity::getCreatedAt).reversed())
                    .limit(200)
                    .toList();
        }

        private Page<StudentExplanationMessageEntity> selectPage(
                Page<StudentExplanationMessageEntity> page,
                Wrapper<StudentExplanationMessageEntity> ignored) {
            page.setRecords(rows.stream()
                    .filter(row -> "school-a".equals(row.getTenantId()))
                    .filter(row -> "student".equals(row.getSubjectType()))
                    .filter(row -> "student-1".equals(row.getSubjectId()))
                    .filter(row -> "conversation-1".equals(row.getConversationId()))
                    .sorted(Comparator.comparing(StudentExplanationMessageEntity::getCreatedAt).reversed())
                    .limit(page.getSize())
                    .toList());
            return page;
        }
    }
}
