package com.doob.mathagent.student.service;

import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls a real multimodal model to read a temporary uploaded math question image.
 */
@Service
public class StudentExplanationVisionService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** A complete exam page can contain five questions and options; keep enough output budget for valid JSON. */
    private static final int VISION_TRANSCRIPTION_MAX_TOKENS = 1800;
    /** Matches the upload service's bounded image contract so a large teacher scan cannot exhaust vision memory. */
    private static final long MAX_AUTHORIZED_IMAGE_BYTES = 8L * 1024L * 1024L;
    private final AiProviderProperties.Provider openaiProvider;
    private final AiProviderProperties.Provider dashscopeProvider;
    private final AiProviderProperties.Provider arkProvider;
    private final String openaiVisionModel;
    private final String dashscopeVisionModel;
    private final String arkVisionModel;
    private final boolean enabled;
    private final Duration requestTimeout;

    /**
     * Creates the vision service from backend model configuration.
     *
     * @param properties AI provider properties
     * @param visionModel DashScope OpenAI-compatible vision model
     * @param enabled whether image understanding is enabled
     */
    public StudentExplanationVisionService(
            AiProviderProperties properties,
            @Value("${math-agent.student.explanation.openai-vision-model:${OPENAI_VISION_MODEL:${OPENAI_CHAT_MODEL:gpt-5.6-luna}}}")
            String openaiVisionModel,
            @Value("${math-agent.student.explanation.vision-model:${DASHSCOPE_VISION_MODEL:qwen-vl-plus-latest}}")
            String dashscopeVisionModel,
            @Value("${math-agent.student.explanation.ark-vision-model:${ARK_VISION_MODEL:${ARK_CHAT_MODEL:doubao-seed-2-0-lite-260428}}}")
            String arkVisionModel,
            @Value("${math-agent.student.explanation.vision-enabled:true}") boolean enabled,
            @Value("${math-agent.student.explanation.vision-timeout-ms:45000}") long requestTimeoutMs) {
        this.openaiProvider = properties.getOpenai();
        this.dashscopeProvider = properties.getDashscope();
        this.arkProvider = properties.getArk();
        this.openaiVisionModel = textOrDefault(openaiVisionModel, properties.getOpenai().getChatModel());
        this.dashscopeVisionModel = textOrDefault(dashscopeVisionModel, "qwen-vl-plus-latest");
        this.arkVisionModel = textOrDefault(arkVisionModel, properties.getArk().getChatModel());
        this.enabled = enabled;
        this.requestTimeout = Duration.ofMillis(Math.max(1000, requestTimeoutMs));
    }

    /**
     * Uses a real vision model to extract problem text from an uploaded image.
     *
     * @param imageRecord owner-validated image record
     * @return vision analysis result
     */
    public VisionAnalysis analyze(StudentExplanationImageRecord imageRecord) {
        if (!enabled) {
            return VisionAnalysis.skipped("vision-disabled");
        }
        try {
            return analyzeImageBytes(Files.readAllBytes(imageRecord.localPath()), imageRecord.contentType());
        } catch (IOException e) {
            return new VisionAnalysis(
                    true,
                    false,
                    "",
                    "",
                    "",
                    0.0,
                    0,
                    0,
                    0,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Reads a short-lived local image materialized by a server-side authorization boundary.
     *
     * <p>This overload is intentionally not an upload lookup: callers must validate tenant, role, ownership, and
     * asset visibility before passing the path. It is used by teacher-resource handout generation after
     * {@code openVisibleAsset} has performed those checks. The method validates file existence, MIME shape, and byte
     * budget again, preventing the model client from becoming a generic arbitrary-file reader.</p>
     */
    public VisionAnalysis analyzeAuthorizedLocalImage(Path authorizedImage, String contentType) {
        return analyzeAuthorizedLocalImage(authorizedImage, contentType, true);
    }

    /**
     * Reads a permission-checked teacher page with the explicitly configured primary model only.
     *
     * <p>Source transcription is evidence creation, so silently switching providers after a timeout creates mixed
     * OCR provenance and can multiply one page into three long relay waits. The handout pipeline requires the
     * configured gpt-5.6-luna result or records no transcription; it never substitutes another model's text.</p>
     */
    public VisionAnalysis analyzeAuthorizedLocalImageWithPrimaryProvider(Path authorizedImage, String contentType) {
        return analyzeAuthorizedLocalImage(authorizedImage, contentType, false);
    }

    /** Shared authorized-file boundary with an explicit fallback policy for student UI versus source ingestion. */
    private VisionAnalysis analyzeAuthorizedLocalImage(Path authorizedImage, String contentType, boolean allowFallback) {
        if (!enabled) {
            return VisionAnalysis.skipped("vision-disabled");
        }
        if (authorizedImage == null || contentType == null || !contentType.strip().toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            return new VisionAnalysis(true, false, "", "", "", 0.0, 0, 0, 0, "unsupported-image");
        }
        try {
            Path normalized = authorizedImage.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                return new VisionAnalysis(true, false, "", "", "", 0.0, 0, 0, 0, "image-unavailable");
            }
            if (Files.size(normalized) > MAX_AUTHORIZED_IMAGE_BYTES) {
                return new VisionAnalysis(true, false, "", "", "", 0.0, 0, 0, 0, "image-too-large");
            }
            return analyzeImageBytes(Files.readAllBytes(normalized), contentType, allowFallback);
        } catch (IOException exception) {
            return new VisionAnalysis(true, false, "", "", "", 0.0, 0, 0, 0,
                    exception.getClass().getSimpleName());
        }
    }

    /** Shares the real provider path across owned student uploads and permission-checked teacher materializations. */
    private VisionAnalysis analyzeImageBytes(byte[] image, String contentType) {
        return analyzeImageBytes(image, contentType, true);
    }

    /** Executes the primary provider alone for auditable source transcription, otherwise keeps UI fallbacks. */
    private VisionAnalysis analyzeImageBytes(byte[] image, String contentType, boolean allowFallback) {
        String dataUrl = "data:" + contentType + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(image);
        VisionAnalysis lastFailure = null;
        // Try the configured multimodal explanation model first so a question image and its answer share one
        // capability. Existing DashScope and Ark integrations remain real-provider fallbacks.
        List<VisionProviderCandidate> candidates = List.of(
                new VisionProviderCandidate("openai", openaiProvider, openaiVisionModel),
                new VisionProviderCandidate("dashscope", dashscopeProvider, dashscopeVisionModel),
                new VisionProviderCandidate("ark", arkProvider, arkVisionModel));
        for (VisionProviderCandidate candidate : allowFallback ? candidates : List.of(candidates.getFirst())) {
            if (candidate.provider().getApiKey() == null || candidate.provider().getApiKey().isBlank()) {
                lastFailure = new VisionAnalysis(true, false, candidate.name(), candidate.modelCode(), "",
                        0.0, 0, 0, 0, "api-key-missing");
                continue;
            }
            VisionAnalysis analysis = analyzeWithProvider(candidate, dataUrl);
            if (analysis.succeeded()) {
                return analysis;
            }
            lastFailure = analysis;
        }
        return lastFailure == null ? VisionAnalysis.skipped("no-vision-provider") : lastFailure;
    }

    /**
     * Calls one OpenAI-compatible multimodal provider.
     */
    private VisionAnalysis analyzeWithProvider(VisionProviderCandidate candidate, String dataUrl) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", candidate.modelCode(),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                                    Map.of("type", "text", "text", strictVisionPrompt())))),
                    "temperature", 0,
                    "max_tokens", VISION_TRANSCRIPTION_MAX_TOKENS);
            JsonNode response = RestClient.builder()
                    .requestFactory(requestFactory(requestTimeout))
                    .build()
                    .post()
                    // Ark and DashScope are called through their OpenAI-compatible chat-completions endpoints.
                    // Ark /api/v3 configuration reference: https://www.volcengine.com/docs/82379/1330626
                    .uri(chatCompletionsEndpoint(candidate.provider().getBaseUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(candidate.provider().getApiKey()))
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
            String content = firstContent(response);
            ParsedVisionJson parsed = parseVisionJson(content);
            JsonNode usage = response == null ? null : response.path("usage");
            boolean succeeded = !parsed.problemText().isBlank();
            return new VisionAnalysis(
                    true,
                    succeeded,
                    candidate.name(),
                    modelFromResponse(response, candidate.modelCode()),
                    parsed.problemText(),
                    parsed.confidence(),
                    intValue(usage == null ? null : usage.path("prompt_tokens")),
                    intValue(usage == null ? null : usage.path("completion_tokens")),
                    intValue(usage == null ? null : usage.path("total_tokens")),
                    succeeded ? "vision-json" : "empty-vision-text");
        } catch (RuntimeException e) {
            return new VisionAnalysis(
                    true,
                    false,
                    candidate.name(),
                    candidate.modelCode(),
                    "",
                    0.0,
                    0,
                    0,
                    0,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Prompt for extracting only visible problem text from the image.
     */
    private static String visionPrompt() {
        return strictVisionPrompt();
    }

    /**
     * Prompt for extracting only visible problem text from the image.
     */
    private static String strictVisionPrompt() {
        return """
                You are reading a high-school math problem image.
                Extract only visible problem text, formulas, options, and geometry labels from the image.
                Do not solve the problem. Do not guess missing text. Do not invent invisible content.
                Preserve Chinese text and math symbols exactly as visible when possible.
                Return exactly one valid JSON object and no Markdown:
                {"problemText":"...","confidence":0.0}
                """;
    }

    /**
     * Parses the JSON returned by the vision model.
     */
    private static ParsedVisionJson parseVisionJson(String content) {
        if (content == null || content.isBlank()) {
            return new ParsedVisionJson("", 0.0);
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(extractJsonObject(stripCodeFence(content.strip())));
            return new ParsedVisionJson(
                    repairMojibake(node.path("problemText").asText("")),
                    Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble(0.0))));
        } catch (JsonProcessingException e) {
            return new ParsedVisionJson(repairMojibake(content.strip()), 0.5);
        }
    }

    /**
     * Repairs common UTF-8 text that was accidentally interpreted as ISO-8859-1 by a provider.
     */
    public static String repairMojibake(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (!looksLikeMojibake(value)) {
            return value;
        }
        String repaired = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return cjkCount(repaired) > cjkCount(value) ? repaired : value;
    }

    private static boolean looksLikeMojibake(String value) {
        return value.indexOf('\u00e9') >= 0
                || value.indexOf('\u00e8') >= 0
                || value.indexOf('\u00e4') >= 0
                || value.indexOf('\u00e5') >= 0
                || value.indexOf('\u00e3') >= 0
                || value.indexOf('\u00ef') >= 0
                || value.indexOf('\u0098') >= 0
                || value.indexOf('\u0080') >= 0
                || value.indexOf('\u0082') >= 0;
    }

    private static long cjkCount(String value) {
        return value.codePoints()
                .filter(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                .count();
    }

    /**
     * Builds a hard-timeout request factory so provider or proxy instability cannot hang the explanation request.
     */
    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * Extracts the first assistant message content from an OpenAI-compatible response.
     */
    private static String firstContent(JsonNode response) {
        if (response == null) {
            return "";
        }
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * Returns model code from provider response or a configured fallback.
     */
    private static String modelFromResponse(JsonNode response, String fallbackModel) {
        String model = response == null ? "" : response.path("model").asText("");
        return model.isBlank() ? fallbackModel : model;
    }

    /**
     * Builds a chat completions endpoint from a provider base URL.
     */
    private static String chatCompletionsEndpoint(String baseUrl) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                : baseUrl.strip();
        return normalized.endsWith("/chat/completions")
                ? normalized
                : normalized.replaceAll("/+$", "") + "/chat/completions";
    }

    /**
     * Removes Markdown fences around JSON.
     */
    private static String stripCodeFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int firstLineEnd = content.indexOf('\n');
        int lastFenceStart = content.lastIndexOf("```");
        if (firstLineEnd >= 0 && lastFenceStart > firstLineEnd) {
            return content.substring(firstLineEnd + 1, lastFenceStart).strip();
        }
        return content;
    }

    /**
     * Extracts the first JSON object envelope.
     */
    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : content;
    }

    /**
     * Reads a JSON integer node.
     */
    private static int intValue(JsonNode node) {
        return node == null || !node.isNumber() ? 0 : Math.max(0, node.asInt());
    }

    /**
     * Returns fallback when text is blank.
     */
    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * Parsed vision JSON payload.
     */
    private record ParsedVisionJson(String problemText, double confidence) {
    }

    /**
     * One provider/model candidate for image understanding.
     */
    private record VisionProviderCandidate(
            String name,
            AiProviderProperties.Provider provider,
            String modelCode) {
    }

    /**
     * Safe vision analysis metadata used by the student explanation workflow.
     */
    public record VisionAnalysis(
            boolean enabled,
            boolean succeeded,
            String providerName,
            String modelCode,
            String problemText,
            double confidence,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            String message) {

        /**
         * Builds a skipped result.
         */
        public static VisionAnalysis skipped(String message) {
            return new VisionAnalysis(false, false, "", "", "", 0.0, 0, 0, 0, message);
        }
    }
}
