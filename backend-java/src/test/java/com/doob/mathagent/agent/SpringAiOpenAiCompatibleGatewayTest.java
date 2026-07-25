package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringAiOpenAiCompatibleGatewayTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void strictJsonStudentExplanationPromptIsNotWrappedAsProse() {
        AiChatRequest request = new AiChatRequest(
                "ark",
                "doubao-seed-2-0-lite-260428",
                "StudentExplanationAgent",
                """
                        你是高中数学学生端讲解智能体。只输出一个 JSON 对象，不要 Markdown。
                        JSON schema: {"cards":[]}
                        题干: 已知函数 f(x)，求定义域
                        """,
                List.of("textbook://book-a/page/12#chunk=1"));

        String systemPrompt = SpringAiOpenAiCompatibleGateway.systemPrompt(request);
        String userPrompt = SpringAiOpenAiCompatibleGateway.userPrompt(request);

        assertThat(systemPrompt).contains("Return only one valid JSON object");
        assertThat(userPrompt).contains("JSON schema", "textbook://book-a/page/12#chunk=1");
        assertThat(userPrompt).doesNotContain(
                "Task summary:",
                "Return classroom-ready Chinese teaching content");
    }

    @Test
    void strictJsonCoursewarePromptIsNotWrappedAsProse() {
        AiChatRequest request = new AiChatRequest(
                "openai",
                "gpt-5.4",
                "CoursewareAgent",
                """
                        只输出一个 JSON 对象，不要 Markdown。
                        JSON schema: {"teacherExplanation":"","studentHint":""}
                        """,
                List.of("textbook://book-a/page/12#chunk=1"));

        assertThat(SpringAiOpenAiCompatibleGateway.requiresStrictJsonOutput(request)).isTrue();
        assertThat(SpringAiOpenAiCompatibleGateway.userPrompt(request))
                .doesNotContain("Return classroom-ready Chinese teaching content");
    }

    @Test
    void normalAgentKeepsClassroomReadyPromptWrapping() {
        AiChatRequest request = new AiChatRequest(
                "deepseek",
                "deepseek-v4-flash",
                "LessonPlannerAgent",
                "Generate a concise handout outline for vectors",
                List.of("textbook://book-a/page/12#chunk=1"));

        String systemPrompt = SpringAiOpenAiCompatibleGateway.systemPrompt(request);
        String userPrompt = SpringAiOpenAiCompatibleGateway.userPrompt(request);

        assertThat(systemPrompt).contains("Return concise Chinese classroom-ready guidance");
        assertThat(userPrompt).contains(
                "Task summary:",
                "Evidence references:",
                "Return classroom-ready Chinese teaching content");
    }

    @Test
    void ownerValidatedImageIsEncodedAsNativeMultimodalContent() {
        AiChatRequest request = new AiChatRequest(
                "openai", "gpt-5.6-luna", "StudentExplanationAgent", "识别题图并检索", List.of(),
                "data:image/png;base64,aGVsbG8=");

        var message = SpringAiOpenAiCompatibleGateway.userMessage(request);

        assertThat(message.get("role")).isEqualTo("user");
        assertThat(message.get("content")).isInstanceOf(List.class);
        assertThat(message.get("content").toString()).contains("image_url", "data:image/png;base64,aGVsbG8=");
    }

    @Test
    void genericJsonSchemaPromptIsTreatedAsStrictJson() {
        AiChatRequest request = new AiChatRequest(
                "deepseek",
                "deepseek-v4-flash",
                "ReviewerAgent",
                "Return only JSON. JSON schema: {\"ok\":true}",
                List.of());

        assertThat(SpringAiOpenAiCompatibleGateway.requiresStrictJsonOutput(request)).isTrue();
        assertThat(SpringAiOpenAiCompatibleGateway.userPrompt(request)).doesNotContain("Task summary:");
    }

    @Test
    void parsesArkCompatibleResponseWhileIgnoringExtraMessageFields() throws Exception {
        AiChatRequest request = new AiChatRequest(
                "ark",
                "doubao-seed-2-0-lite-260428",
                "ModelHealthCheck",
                "health-check",
                List.of());
        var body = OBJECT_MAPPER.readTree("""
                {
                  "model": "doubao-seed-2-0-lite-260428",
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": "ok",
                        "reasoning_content": "provider extra field",
                        "encrypted_content": "provider extra field"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 11,
                    "completion_tokens": 22,
                    "total_tokens": 33
                  }
                }
                """);

        AiChatResult result = SpringAiOpenAiCompatibleGateway.resultFromBody(request, body);

        assertThat(result.providerName()).isEqualTo("ark");
        assertThat(result.modelCode()).isEqualTo("doubao-seed-2-0-lite-260428");
        assertThat(result.promptTokens()).isEqualTo(11);
        assertThat(result.completionTokens()).isEqualTo(22);
        assertThat(result.totalTokens()).isEqualTo(33);
        assertThat(result.generatedContent()).isEqualTo("ok");
    }

    @Test
    void parsesProviderResponseBytesAsUtf8ChineseText() {
        AiChatRequest request = new AiChatRequest(
                "openai",
                "gpt-5.4",
                "StudentExplanationAgent",
                "Return JSON",
                List.of());
        byte[] body = """
                {
                  "model": "gpt-5.4",
                  "choices": [{"message": {"role": "assistant", "content": "{\\"cards\\":[{\\"title\\":\\"题意理解\\"}]}"}}],
                  "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}
                }
                """.getBytes(StandardCharsets.UTF_8);

        AiChatResult result = SpringAiOpenAiCompatibleGateway.resultFromBody(
                request,
                SpringAiOpenAiCompatibleGateway.readJson(body));

        assertThat(result.generatedContent()).contains("题意理解");
        assertThat(result.generatedContent()).doesNotContain("é¢");
    }

    @Test
    void extractsContentReasoningAndUsageFromOneOpenAiCompatibleSseDelta() throws Exception {
        var delta = SpringAiOpenAiCompatibleGateway.streamDeltaFromBody(OBJECT_MAPPER.readTree("""
                {
                  "model": "deepseek-v4-flash",
                  "choices": [{"delta": {
                    "reasoning_content": "先将方程因式分解。",
                    "content": "令 $(x-2)(x-3)=0$。"
                  }}],
                  "usage": {"prompt_tokens": 8, "completion_tokens": 12, "total_tokens": 20}
                }
                """));

        assertThat(delta.reasoningDelta()).isEqualTo("先将方程因式分解。");
        assertThat(delta.contentDelta()).isEqualTo("令 $(x-2)(x-3)=0$。");
        assertThat(delta.modelCode()).isEqualTo("deepseek-v4-flash");
        assertThat(delta.totalTokens()).isEqualTo(20);
    }
}
