package com.doob.mathagent.teaching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.memory.vo.StudentMemoryResponse;
import com.doob.mathagent.teaching.TeachingEvidence;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import com.doob.mathagent.teaching.vo.TeachingTaskResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingAiDraftServiceTest {

    @Test
    void parsesStructuredJsonFromCodeFence() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                ```json
                {
                  "teacherExplanation": "先理解 D(x_0) 的定义，再代入 x_0=-1。",
                  "studentHint": "先把 -1 代入定义，再判断不等式。",
                  "knowledgePoints": ["函数新定义", "定义域"],
                  "followUpQuestions": ["D(0) 怎么求？", "参数变化时条件会怎样？"]
                }
                ```
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation()).contains("D(x_0)");
        assertThat(parsed.knowledgePoints()).containsExactly("函数新定义", "定义域");
        assertThat(parsed.parseError()).isBlank();
    }

    @Test
    void retriesMalformedJsonAndReturnsRecoveredStructuredDraftWithAccumulatedTokens() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 10, 4, 14, "ok", "教师讲解：不是 JSON"),
                new AiChatResult("openai", "gpt-5.4", 12, 8, 20, "ok", structuredJson("修复后的教师讲解"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false));

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.maxRetries()).isEqualTo(1);
        assertThat(draft.totalTokens()).isEqualTo(34);
        assertThat(draft.teacherExplanation()).contains("修复后的教师讲解");
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents().get(1).retryable()).isTrue();
        assertThat(gateway.requests()).hasSize(2);
        assertThat(gateway.requests().get(1).userInputSummary()).contains("上一次输出没有通过后端 JSON schema 解析");
    }

    @Test
    void rotatesToNextProviderWhenJsonRetryStillFails() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 7, 5, 12, "ok", structuredJson("千问接管"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, true));

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.providerName()).isEqualTo("dashscope");
        assertThat(draft.modelCode()).isEqualTo("qwen3.6-flash");
        assertThat(draft.retryCount()).isZero();
        assertThat(draft.totalTokens()).isEqualTo(23);
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .contains(
                        "JSON_PARSE_FAILED",
                        "PROVIDER_ROTATED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents()).filteredOn(event -> "PROVIDER_ROTATED".equals(event.eventType()))
                .extracting(TeachingTaskResponse.AiRecoveryEvent::providerName)
                .containsExactly("dashscope");
        assertThat(gateway.requests()).extracting(AiChatRequest::providerName)
                .containsExactly("openai", "openai", "dashscope");
    }

    @Test
    void retriesTransientGatewayFailureBeforeProviderRotation() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new IllegalStateException("proxy connection reset"),
                new AiChatResult("openai", "gpt-5.4", 8, 5, 13, "ok", structuredJson("代理恢复后的教师讲解"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false));

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.teacherExplanation()).contains("代理恢复");
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents().getFirst().message()).isEqualTo("IllegalStateException");
    }

    @Test
    void keepsRawContentAndParseErrorWhenAllProvidersReturnInvalidJson() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}")));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false));

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isFalse();
        assertThat(draft.content()).contains("teacherExplanation");
        assertThat(draft.parseError()).contains("required nonblank teaching fields");
        assertThat(draft.message()).contains("Structured parse failed after 1 retry");
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.recoveredAfterRetry()).isFalse();
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED");
        assertThat(draft.recoveryEvents().getLast().retryable()).isFalse();
    }

    private static String structuredJson(String teacherExplanation) {
        return """
                {
                  "teacherExplanation": "%s",
                  "studentHint": "先代入定义，再判断条件。",
                  "knowledgePoints": ["函数新定义", "定义域"],
                  "followUpQuestions": ["D(0) 怎么求？", "条件换成小于号会怎样？"]
                }
                """.formatted(teacherExplanation);
    }

    private static TeachingTaskRequest request() {
        return new TeachingTaskRequest(
                "req-ai-structured",
                "已知函数 f(x) 的定义域为 R，求 D(-1)",
                "理解函数新定义题",
                2);
    }

    private static List<TeachingEvidence> evidence() {
        return List.of(new TeachingEvidence(
                "PUBLIC_TEXTBOOK",
                "教材A / 函数新概念",
                "book-a-p101",
                101,
                "函数新定义题要先读清集合 D(x0) 的条件，再代入具体 x0。"));
    }

    private static StudentMemoryResponse memory() {
        return new StudentMemoryResponse(false, "", "", "", 0.0, "No reusable memory", List.of());
    }

    private static AiProviderCatalog catalog(boolean openaiEnabled, boolean dashscopeEnabled) {
        AiProviderProperties properties = new AiProviderProperties();
        properties.getOpenai().setApiKey(openaiEnabled ? "test-openai-key" : "");
        properties.getDashscope().setApiKey(dashscopeEnabled ? "test-dashscope-key" : "");
        return new AiProviderCatalog(properties);
    }

    private static final class CapturingGateway implements AiChatGateway {

        private final List<Object> outcomes;
        private final List<AiChatRequest> requests = new ArrayList<>();
        private int index;

        private CapturingGateway(List<Object> outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public AiChatResult call(AiChatRequest request) {
            requests.add(request);
            if (index >= outcomes.size()) {
                throw new IllegalStateException("No test result configured for request " + requests.size());
            }
            Object outcome = outcomes.get(index++);
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return (AiChatResult) outcome;
        }

        private List<AiChatRequest> requests() {
            return requests;
        }
    }
}
