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
                  "teacherExplanation": "Explain \\\\(D(x_0)\\\\), then substitute x_0=-1.",
                  "studentHint": "Use \\\\[f(x)=x^2-4x+3\\\\] first, then check the condition.",
                  "knowledgePoints": ["new function definition", "\\\\begin{align} f(x)&=x^2-4x+3 \\\\\\\\ &= (x-1)(x-3) \\\\end{align}"],
                  "followUpQuestions": ["How to find D(0)?", "What changes when parameters move?"]
                }
                ```
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation()).contains("$D(x_0)$").doesNotContain("\\(");
        assertThat(parsed.studentHint()).contains("$$").doesNotContain("\\[");
        assertThat(parsed.knowledgePoints().get(1)).contains("$$", "f(x)=x^2-4x+3").doesNotContain("\\begin{align}");
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
        assertThat(gateway.requests().getFirst().userInputSummary())
                .contains("【知识定位】", "【答案与评分点】", "【知识速记】", "never reveal final answers",
                        "AI live explanation belongs to chat/dialogue features",
                        "never expose raw JSON keys");
        assertThat(gateway.requests().get(1).userInputSummary()).contains("JSON schema");
        assertThat(gateway.requests().get(1).userInputSummary())
                .contains("【知识定位】", "【知识速记】", "no answer/scoring/solution leakage",
                        "printable handouts only");
    }

    @Test
    void removesTeacherOnlyAnswerSectionsFromStudentWorksheet() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】函数新定义\\n【题型识别】代入求值\\n【方法步骤】先读定义\\n【例题详解】把 $x_0=-1$ 代入。\\n【答案与评分点】答案为 $2$。\\n【易错提醒】不要代错。\\n【课堂追问】D(0) 呢？",
                  "studentHint": "【知识速记】先找到定义里的自变量位置。\\n【例题详解】把 $x_0=-1$ 代入得到 $2$。\\n【答案与评分点】答案：$2$，写出代入过程得 2 分。\\n【练习任务】完成同类题，过程写在作答区。",
                  "knowledgePoints": ["函数新定义", "代入求值"],
                  "followUpQuestions": ["D(0) 如何处理？", "条件变化时如何分类？"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.studentHint())
                .contains("【知识速记】", "【练习任务】")
                .doesNotContain("【例题详解】", "【答案与评分点】", "答案：", "评分点", "$2$");
        assertThat(parsed.teacherExplanation()).contains("【答案与评分点】", "$2$");
    }

    @Test
    void removesAnswerLeakageFromFollowUpQuestionsUsedByStudentHandouts() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】函数新定义\\n【题型识别】代入求值\\n【方法步骤】先读定义\\n【例题详解】把 $x_0=-1$ 代入。\\n【答案与评分点】答案为 $2$。\\n【易错提醒】不要代错。\\n【课堂追问】D(0) 呢？",
                  "studentHint": "【知识速记】先找到定义里的自变量位置。\\n【练习任务】完成同类题，过程写在作答区。",
                  "knowledgePoints": ["函数新定义", "代入求值"],
                  "followUpQuestions": ["已知 $D(x_0)$，求 $D(0)$。答案：$2$，写出过程得 2 分。", "条件变化时如何分类？评分点：讨论定义域。"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.followUpQuestions())
                .containsExactly("已知 $D(x_0)$，求 $D(0)$。", "条件变化时如何分类？")
                .allSatisfy(item -> assertThat(item).doesNotContain("答案", "评分点", "得分", "$2$"));
    }

    @Test
    void stripsInternalDebugAndLayoutLinesFromParsedHandoutText() {
        TeachingAiDraftService.ParsedDraft parsed = TeachingAiDraftService.parseStructuredDraft("""
                {
                  "teacherExplanation": "【知识定位】双曲线参数关系。\\n页眉展示主题，颜色使用蓝色。\\nMODEL_CALL_SUCCEEDED openai tokens=100\\n【答案与评分点】由 $c^2=a^2+b^2$ 得 $b^2=16$。",
                  "studentHint": "【知识速记】先写 $c^2=a^2+b^2$。\\nJSON_PARSE_SUCCEEDED tokens=20\\n【练习任务】完成参数计算。\\n解：$b^2=16$。",
                  "knowledgePoints": ["参数关系 $c^2=a^2+b^2$", "debug tokens=10"],
                  "followUpQuestions": ["已知焦距为 10，求 c。解：c=5。", "判断焦点在哪个轴。"]
                }
                """);

        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.teacherExplanation())
                .contains("【知识定位】", "【答案与评分点】", "$b^2=16$")
                .doesNotContain("页眉", "颜色", "MODEL_CALL", "tokens");
        assertThat(parsed.studentHint())
                .contains("【知识速记】", "【练习任务】")
                .doesNotContain("JSON_PARSE", "tokens", "解：", "$b^2=16$");
        assertThat(parsed.knowledgePoints())
                .containsExactly("参数关系 $c^2=a^2+b^2$");
        assertThat(parsed.followUpQuestions())
                .containsExactly("已知焦距为 10，求 c。", "判断焦点在哪个轴。");
    }

    @Test
    void formatsQuestionBankEvidenceForPromptWithoutRawAnswerJsonKeys() {
        CapturingGateway gateway = new CapturingGateway(List.of(new AiChatResult(
                "openai",
                "gpt-5.4",
                16,
                8,
                24,
                "ok",
                structuredJson("question bank grounded teacher explanation"))));
        TeachingAiDraftService service = new TeachingAiDraftService(gateway, catalog(true, false), defaultPolicy());
        TeachingEvidence questionBankEvidence = new TeachingEvidence(
                "QUESTION_BANK",
                "双曲线定义与参数关系基础题 / 难度：A 基础",
                "question-1",
                0,
                "已知双曲线焦距为 $10$，且 $2a=6$，求 $a,c,b^2$。\n"
                        + "答案要点：{\"answer\":\"a=3,c=5,b^2=16\",\"scoring\":\"写出参数关系得分\"}");

        service.draft(request(), List.of(questionBankEvidence), memory());

        assertThat(gateway.requests()).hasSize(1);
        assertThat(gateway.requests().getFirst().userInputSummary())
                .contains("QUESTION_BANK", "A 基础", "答案要点", "c=5", "b^2=16")
                .doesNotContain("\"answer\"", "\"scoring\"");
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
