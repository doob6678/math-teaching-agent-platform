package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Spring AI backed gateway for OpenAI-compatible chat providers.
 */
@Primary
@Component
public class SpringAiOpenAiCompatibleGateway implements AiChatGateway {

    private final AiProviderProperties properties;

    /**
     * Creates the gateway from environment-backed provider properties.
     *
     * @param properties provider properties
     */
    public SpringAiOpenAiCompatibleGateway(AiProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * Calls the selected OpenAI-compatible provider and returns only safe metadata plus official usage.
     *
     * @param request sanitized model call request
     * @return provider result with usage
     */
    @Override
    public AiChatResult call(AiChatRequest request) {
        AiProviderProperties.Provider provider = provider(request.providerName());
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(provider.getApiKey())
                .build();
        OpenAiApi.ChatCompletionRequest completionRequest = new OpenAiApi.ChatCompletionRequest(
                List.of(
                        new OpenAiApi.ChatCompletionMessage(systemPrompt(request), OpenAiApi.ChatCompletionMessage.Role.SYSTEM),
                        new OpenAiApi.ChatCompletionMessage(userPrompt(request), OpenAiApi.ChatCompletionMessage.Role.USER)),
                request.modelCode(),
                0.2d);
        ResponseEntity<OpenAiApi.ChatCompletion> response = api.chatCompletionEntity(completionRequest);
        OpenAiApi.ChatCompletion body = response.getBody();
        OpenAiApi.Usage usage = body == null ? null : body.usage();
        return new AiChatResult(
                request.providerName(),
                body == null || body.model() == null || body.model().isBlank() ? request.modelCode() : body.model(),
                intValue(usage == null ? null : usage.promptTokens()),
                intValue(usage == null ? null : usage.completionTokens()),
                intValue(usage == null ? null : usage.totalTokens()),
                "Live model response recorded with provider usage metadata.");
    }

    /**
     * Resolves provider properties by backend-selected provider name.
     */
    private AiProviderProperties.Provider provider(String providerName) {
        return switch (providerName) {
            case "dashscope" -> properties.getDashscope();
            case "openai" -> properties.getOpenai();
            case "deepseek" -> properties.getDeepseek();
            case "ark" -> properties.getArk();
            default -> throw new IllegalArgumentException("Unknown AI provider: " + providerName);
        };
    }

    /**
     * Builds a compact system prompt for agent execution.
     */
    private static String systemPrompt(AiChatRequest request) {
        return "You are a math teaching agent. Return concise Chinese classroom-ready guidance for " + request.agentCode()
                + ". Do not include hidden reasoning or raw tool traces.";
    }

    /**
     * Builds a sanitized user prompt without storing raw private documents.
     */
    private static String userPrompt(AiChatRequest request) {
        return """
                Task summary: %s
                Evidence references: %s
                Return a short safe execution acknowledgement and next teaching action.
                """.formatted(request.userInputSummary(), request.evidenceRefs());
    }

    /**
     * Converts nullable provider token counts to non-negative integers.
     */
    private static int intValue(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
