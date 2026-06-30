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
                  "teacherExplanation": "Explain D(x_0), then substitute x_0=-1.",
                  "studentHint": "Substitute -1 first, then check the condition.",
                  "knowledgePoints": ["new function definition", "domain"],
                  "followUpQuestions": ["How to find D(0)?", "What changes when parameters move?"]
                }
                ```
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation()).contains("D(x_0)");
        assertThat(parsed.knowledgePoints()).containsExactly("new function definition", "domain");
        assertThat(parsed.parseError()).isBlank();
    }

    @Test
    void retriesMalformedJsonAndReturnsRecoveredStructuredDraftWithAccumulatedTokens() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 10, 4, 14, "ok", "teacher explanation: not JSON"),
                new AiChatResult("openai", "gpt-5.4", 12, 8, 20, "ok", structuredJson("repaired teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.maxRetries()).isEqualTo(1);
        assertThat(draft.totalTokens()).isEqualTo(34);
        assertThat(draft.teacherExplanation()).contains("repaired teacher explanation");
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
        assertThat(draft.recoveryEvents().get(1).retryable()).isTrue();
        assertThat(gateway.requests()).hasSize(2);
        assertThat(gateway.requests().get(1).userInputSummary()).contains("JSON schema");
    }

    @Test
    void rotatesToNextProviderWhenJsonRetryStillFails() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}"),
                new AiChatResult("dashscope", "qwen3.6-flash", 7, 5, 12, "ok", structuredJson("qwen fallback"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, true), defaultPolicy());

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
                new AiChatResult("openai", "gpt-5.4", 8, 5, 13, "ok", structuredJson("proxy recovered teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(1);
        assertThat(draft.teacherExplanation()).contains("proxy recovered");
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
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());

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

    @Test
    void honorsConfiguredRetryCountForJsonRepair() {
        CapturingGateway gateway = new CapturingGateway(List.of(
                new AiChatResult("openai", "gpt-5.4", 3, 2, 5, "ok", "bad json"),
                new AiChatResult("openai", "gpt-5.4", 4, 2, 6, "ok", "{\"teacherExplanation\":\"only one field\"}"),
                new AiChatResult("openai", "gpt-5.4", 9, 5, 14, "ok", structuredJson("second repair success"))));
        TeachingAiDraftProperties policy = new TeachingAiDraftProperties();
        policy.setMaxRetries(2);
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), policy);

        TeachingTaskResponse.AiDraft draft = service.draft(request(), evidence(), memory());

        assertThat(draft.structured()).isTrue();
        assertThat(draft.retryCount()).isEqualTo(2);
        assertThat(draft.maxRetries()).isEqualTo(2);
        assertThat(draft.totalTokens()).isEqualTo(25);
        assertThat(draft.recoveredAfterRetry()).isTrue();
        assertThat(draft.recoveryEvents()).extracting(TeachingTaskResponse.AiRecoveryEvent::eventType)
                .containsExactly(
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_FAILED",
                        "RETRY_SCHEDULED",
                        "MODEL_CALL_SUCCEEDED",
                        "JSON_PARSE_SUCCEEDED");
    }

    private static String structuredJson(String teacherExplanation) {
        return """
                {
                  "teacherExplanation": "%s",
                  "studentHint": "Substitute into the definition, then check the condition.",
                  "knowledgePoints": ["new function definition", "domain"],
                  "followUpQuestions": ["How to find D(0)?", "What changes if the sign changes?"]
                }
                """.formatted(teacherExplanation);
    }

    private static TeachingTaskRequest request() {
        return new TeachingTaskRequest(
                "req-ai-structured",
                "Given function f(x) with domain R, find D(-1).",
                "Understand new function definition questions",
                2);
    }

    private static List<TeachingEvidence> evidence() {
        return List.of(new TeachingEvidence(
                "PUBLIC_TEXTBOOK",
                "Textbook A / New function concept",
                "book-a-p101",
                101,
                "For new function definition questions, read condition D(x0), then substitute x0."));
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

    private static TeachingAiDraftProperties defaultPolicy() {
        return new TeachingAiDraftProperties();
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
