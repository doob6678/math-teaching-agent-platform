package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Direct HTTP gateway for OpenAI-compatible chat providers.
 */
@Primary
@Component
public class SpringAiOpenAiCompatibleGateway implements AiChatGateway {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiProviderProperties properties;
    private final Duration requestTimeout;

    /**
     * Creates the gateway from environment-backed provider properties.
     *
     * @param properties provider properties
     */
    public SpringAiOpenAiCompatibleGateway(AiProviderProperties properties) {
        this(properties, 45000);
    }

    /**
     * Creates the gateway from environment-backed provider properties.
     *
     * @param properties provider properties
     * @param requestTimeoutMs hard timeout for one provider request
     */
    @Autowired
    public SpringAiOpenAiCompatibleGateway(
            AiProviderProperties properties,
            @Value("${math-agent.ai.chat.request-timeout-ms:45000}") long requestTimeoutMs) {
        this.properties = properties;
        this.requestTimeout = Duration.ofMillis(Math.max(1000, requestTimeoutMs));
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
        // Ark follows the OpenAI-compatible /api/v3 contract documented by Volcengine:
        // https://www.volcengine.com/docs/82379/1330626. The official Chat API is still the
        // message-list chat completion surface: https://www.volcengine.com/docs/82379/1494384.
        // We append /chat/completions ourselves and parse stable fields only, because provider
        // extension fields can break typed SDK response models even when the account/model works.
        byte[] responseBody = RestClient.builder()
                .requestFactory(requestFactory(requestTimeout))
                .build()
                .post()
                .uri(chatCompletionsUri(provider.getBaseUrl()))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(provider.getApiKey()))
                .body(chatCompletionBody(request))
                .retrieve()
                .body(byte[].class);
        return resultFromBody(request, readJson(responseBody));
    }

    static AiChatResult resultFromBody(AiChatRequest request, JsonNode body) {
        JsonNode usage = body.path("usage");
        return new AiChatResult(
                request.providerName(),
                textOrDefault(body.path("model").asText(), request.modelCode()),
                intValue(usage.path("prompt_tokens").asInt(0)),
                intValue(usage.path("completion_tokens").asInt(0)),
                intValue(usage.path("total_tokens").asInt(0)),
                "Live model response recorded with provider usage metadata.",
                firstContent(body));
    }

    private static Map<String, Object> chatCompletionBody(AiChatRequest request) {
        return Map.of(
                "model", request.modelCode(),
                "temperature", 0.2d,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt(request)),
                        Map.of("role", "user", "content", userPrompt(request))));
    }

    private static String chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("AI provider base URL is required");
        }
        return normalized.replaceAll("/+$", "") + "/chat/completions";
    }

    static JsonNode readJson(byte[] responseBody) {
        try {
            return OBJECT_MAPPER.readTree(responseBody == null ? new byte[0] : responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Provider returned non-JSON chat completion response", e);
        }
    }

    static JsonNode readJson(String responseBody) {
        return readJson(responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8));
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
    static String systemPrompt(AiChatRequest request) {
        if (requiresStrictJsonOutput(request)) {
            return "You are a math teaching agent. Follow the user's JSON schema exactly. "
                    + "Return only one valid JSON object and no Markdown.";
        }
        return "You are a math teaching agent. Return concise Chinese classroom-ready guidance for " + request.agentCode()
                + ". Do not include hidden reasoning or raw tool traces.";
    }

    /**
     * Builds a sanitized user prompt without storing raw private documents.
     */
    static String userPrompt(AiChatRequest request) {
        if (requiresStrictJsonOutput(request)) {
            return """
                    %s
                    Evidence references: %s
                    """.formatted(request.userInputSummary(), request.evidenceRefs());
        }
        return """
                Task summary: %s
                Evidence references: %s
                Return classroom-ready Chinese teaching content. Keep it concise, structured, and directly usable.
                """.formatted(request.userInputSummary(), request.evidenceRefs());
    }

    /**
     * Keeps strict JSON agents from being wrapped with prose-only classroom instructions.
     */
    static boolean requiresStrictJsonOutput(AiChatRequest request) {
        String agentCode = request.agentCode() == null ? "" : request.agentCode().strip();
        if ("StudentExplanationAgent".equals(agentCode) || "CoursewareAgent".equals(agentCode)) {
            return true;
        }
        String prompt = request.userInputSummary() == null ? "" : request.userInputSummary().toLowerCase(Locale.ROOT);
        return prompt.contains("json schema")
                || prompt.contains("only one valid json object")
                || prompt.contains("return only json")
                || prompt.contains("\u53ea\u8f93\u51fa") && prompt.contains("json")
                || prompt.contains("\u552f\u4e00 json")
                || prompt.contains("json \u5bf9\u8c61");
    }

    /**
     * Extracts the first assistant message content from the provider response.
     */
    private static String firstContent(JsonNode body) {
        JsonNode choices = body.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    if (!joined.isEmpty()) {
                        joined.append('\n');
                    }
                    joined.append(text);
                }
            }
            return joined.toString();
        }
        return "";
    }

    /**
     * Converts nullable provider token counts to non-negative integers.
     */
    private static int intValue(int value) {
        return Math.max(0, value);
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Builds a hard-timeout request factory so AI provider instability cannot hang the request.
     */
    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
