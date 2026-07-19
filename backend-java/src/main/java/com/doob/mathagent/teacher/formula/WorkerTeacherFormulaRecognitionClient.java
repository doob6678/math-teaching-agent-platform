package com.doob.mathagent.teacher.formula;

import com.doob.mathagent.vector.service.VectorHttpResponse;
import com.doob.mathagent.vector.service.VectorHttpTransport;
import com.doob.mathagent.vector.service.VectorIndexProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Calls the authenticated local worker, which owns the provider API key and executes the real visual-model request.
 */
@Service
public class WorkerTeacherFormulaRecognitionClient implements TeacherFormulaRecognitionClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /* DOCX equations are frequently stored as WMF. The worker rasterizes WMF in memory before calling the provider. */
    private static final Set<String> VISION_IMAGE_MIME_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
            "image/wmf",
            "image/x-wmf");

    private final VectorIndexProperties workerProperties;
    private final VectorHttpTransport transport;

    public WorkerTeacherFormulaRecognitionClient(VectorIndexProperties workerProperties, VectorHttpTransport transport) {
        this.workerProperties = workerProperties;
        this.transport = transport;
    }

    @Override
    public FormulaRecognitionResult recognize(byte[] image, String mimeType) {
        String normalizedMimeType = mimeType == null ? "" : mimeType.strip().toLowerCase(java.util.Locale.ROOT);
        if (!VISION_IMAGE_MIME_TYPES.contains(normalizedMimeType)) {
            return FormulaRecognitionResult.notRecognized("unsupported_image_format", normalizedMimeType);
        }
        if (image == null || image.length == 0) {
            return FormulaRecognitionResult.notRecognized("empty_image", "extracted image contains no bytes");
        }
        if (!workerProperties.enabled()
                || workerProperties.embeddingBaseUrl() == null || workerProperties.embeddingBaseUrl().isBlank()
                || workerProperties.embeddingApiKey() == null || workerProperties.embeddingApiKey().isBlank()) {
            return FormulaRecognitionResult.notRecognized("worker_unavailable", "worker endpoint or key is not configured");
        }
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("mimeType", normalizedMimeType);
            request.put("imageDataUrl", "data:" + normalizedMimeType + ";base64," + Base64.getEncoder().encodeToString(image));
            VectorHttpResponse response = transport.postJson(
                    formulaRecognitionEndpoint(workerProperties.embeddingBaseUrl()),
                    Map.of("Authorization", "Bearer " + workerProperties.embeddingApiKey()),
                    OBJECT_MAPPER.writeValueAsString(request),
                    Duration.ofMillis(workerProperties.normalizedTimeoutMs()));
            if (!response.success2xx()) {
                return FormulaRecognitionResult.notRecognized("vision_rejected", abbreviate(response.body()));
            }
            JsonNode data = OBJECT_MAPPER.readTree(response.body()).path("data");
            String status = data.path("status").asText("");
            String latex = data.path("latex").asText("");
            String plainText = data.path("plainText").asText("");
            double confidence = data.path("confidence").asDouble(0.0d);
            String model = OBJECT_MAPPER.readTree(response.body()).path("model").asText("");
            if (!"recognized".equals(status) || latex.isBlank() || plainText.isBlank() || confidence <= 0.0d) {
                return FormulaRecognitionResult.notRecognized("vision_invalid_response", "worker returned incomplete recognition data");
            }
            return new FormulaRecognitionResult(status, latex, plainText, confidence, model, "");
        } catch (Exception exception) {
            return FormulaRecognitionResult.notRecognized("vision_unavailable", exception.getClass().getSimpleName());
        }
    }

    @Override
    public List<PageFormulaRecognitionResult> recognizePages(List<PageImage> pages) {
        if (pages == null || pages.isEmpty() || !workerProperties.enabled()
                || workerProperties.embeddingBaseUrl() == null || workerProperties.embeddingBaseUrl().isBlank()
                || workerProperties.embeddingApiKey() == null || workerProperties.embeddingApiKey().isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> requestPages = new ArrayList<>();
            for (PageImage page : pages) {
                String mimeType = page.mimeType() == null ? "" : page.mimeType().strip().toLowerCase(java.util.Locale.ROOT);
                if (!VISION_IMAGE_MIME_TYPES.contains(mimeType) || page.image() == null || page.image().length == 0) {
                    continue;
                }
                requestPages.add(Map.of(
                        "mimeType", mimeType,
                        "imageDataUrl", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(page.image())));
            }
            if (requestPages.isEmpty()) {
                return List.of();
            }
            VectorHttpResponse response = transport.postJson(
                    URI.create(workerProperties.embeddingBaseUrl().strip().replaceAll("/+$", "") + "/formula-page-batch"),
                    Map.of("Authorization", "Bearer " + workerProperties.embeddingApiKey()),
                    OBJECT_MAPPER.writeValueAsString(Map.of("pages", requestPages)),
                    Duration.ofMillis(workerProperties.normalizedTimeoutMs()));
            if (!response.success2xx()) {
                return List.of();
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            String model = root.path("model").asText("");
            List<PageFormulaRecognitionResult> results = new ArrayList<>();
            for (JsonNode page : root.path("data")) {
                int index = page.path("pageIndex").asInt(-1);
                if (index < 0 || index >= pages.size()) {
                    continue;
                }
                List<FormulaRecognitionResult> formulas = new ArrayList<>();
                for (JsonNode formula : page.path("formulas")) {
                    String latex = formula.path("latex").asText("");
                    String plainText = formula.path("plainText").asText("");
                    double confidence = formula.path("confidence").asDouble(0.0d);
                    if (!latex.isBlank() && !plainText.isBlank() && confidence > 0.0d) {
                        formulas.add(new FormulaRecognitionResult("recognized", latex, plainText, confidence, model, ""));
                    }
                }
                if (!formulas.isEmpty()) {
                    results.add(new PageFormulaRecognitionResult(index, List.copyOf(formulas), model));
                }
            }
            return List.copyOf(results);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static URI formulaRecognitionEndpoint(String baseUrl) {
        return URI.create(baseUrl.strip().replaceAll("/+$", "") + "/formula-recognition");
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
