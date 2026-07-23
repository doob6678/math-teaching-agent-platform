package com.doob.mathagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.knowledge.service.KnowledgePointRecord;
import com.doob.mathagent.knowledge.service.KnowledgeQuestionBankService;
import com.doob.mathagent.learning.dto.StudentLearningIntentRequest;
import com.doob.mathagent.learning.service.StudentLearningIntentService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the model JSON contract, visible entity validation, and student-only access. */
class StudentLearningIntentServiceTest {
    @Test
    void usesModelIntentAndAcceptsOnlyVisibleKnowledgePointId() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "kp-1", "tenant-a", "teacher-a", "PUBLIC_TEXTBOOK", "函数单调性", "高中数学", "active", "教材"));
        CapturingGateway gateway = new CapturingGateway(
                "{\"intentCode\":\"LEARNING_PATH\",\"confidence\":0.91,\"knowledgePointId\":\"kp-1\"}");
        StudentLearningIntentService service = service(store, gateway);

        var result = service.recognize(
                new RequestSubject("tenant-a", "student", "student-a", "device-a"),
                new StudentLearningIntentRequest("请帮我安排这个知识点的学习顺序"));

        assertThat(result.intentCode()).isEqualTo("LEARNING_PATH");
        assertThat(result.confidence()).isEqualTo(0.91);
        assertThat(result.knowledgePointId()).isEqualTo("kp-1");
        assertThat(result.suggestedApi()).isEqualTo("/api/students/learning/path");
        assertThat(result.recognizedBy()).contains("model_openai:gpt-5.6-luna");
        assertThat(gateway.requests()).singleElement().satisfies(request -> {
            assertThat(request.agentCode()).isEqualTo("StudentLearningIntentAgent");
            assertThat(request.userInputSummary()).contains("只能从下面的 intentCode");
        });
    }

    @Test
    void invalidModelIntentBecomesUnknownAndCannotExposeHiddenPoint() {
        InMemoryKnowledgeQuestionBankStore store = new InMemoryKnowledgeQuestionBankStore();
        store.saveKnowledgePoint(new KnowledgePointRecord(
                "private-kp", "tenant-a", "teacher-a", "TEACHER_PRIVATE", "教师私有点", "内部", "active", "教师"));
        CapturingGateway gateway = new CapturingGateway(
                "{\"intentCode\":\"NOT_ALLOWED\",\"confidence\":1,\"knowledgePointId\":\"private-kp\"}");

        var result = service(store, gateway).recognize(
                new RequestSubject("tenant-a", "student", "student-a", "device-a"),
                new StudentLearningIntentRequest("请安排学习"));

        assertThat(result.intentCode()).isEqualTo("UNKNOWN");
        assertThat(result.knowledgePointId()).isNull();
        assertThat(result.suggestedApi()).isNull();
    }

    @Test
    void rejectsTeacherOrMissingMessageBeforeCallingModel() {
        CapturingGateway gateway = new CapturingGateway("{}");
        StudentLearningIntentService service = service(new InMemoryKnowledgeQuestionBankStore(), gateway);

        assertThatThrownBy(() -> service.recognize(
                new RequestSubject("tenant-a", "teacher", "teacher-a", "device-a"),
                new StudentLearningIntentRequest("查看薄弱点")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Student role");
        assertThatThrownBy(() -> service.recognize(
                new RequestSubject("tenant-a", "student", "student-a", "device-a"),
                new StudentLearningIntentRequest(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
        assertThat(gateway.requests()).isEmpty();
    }

    private static StudentLearningIntentService service(
            InMemoryKnowledgeQuestionBankStore store, CapturingGateway gateway) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.setOpenai(new AiProviderProperties.Provider(
                "openai", "https://api.example.test/v1", "test-key", "gpt-5.6-luna"));
        return new StudentLearningIntentService(
                new KnowledgeQuestionBankService(store), gateway, new AiProviderCatalog(properties));
    }

    private static final class CapturingGateway implements AiChatGateway {
        private final String response;
        private final List<AiChatRequest> requests = new ArrayList<>();

        private CapturingGateway(String response) {
            this.response = response;
        }

        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            return new AiChatResult("openai", "gpt-5.6-luna", 10, 10, 20, "test", response);
        }

        private List<AiChatRequest> requests() {
            return requests;
        }
    }
}
