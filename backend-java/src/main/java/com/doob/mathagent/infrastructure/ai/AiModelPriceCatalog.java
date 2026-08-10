package com.doob.mathagent.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves deployment-owned prices for OpenAI-compatible provider/model pairs.
 *
 * <p>The relay does not expose a trustworthy monetary bill in every response, so the application must not infer a
 * price from a model name.  A missing price deliberately returns {@code -1} while token ceilings remain active.</p>
 *
 * <p>Expected JSON shape:
 * {@code {"openai/gpt-5.6-luna":{"inputPerMillion":1.0,"outputPerMillion":3.0}}}.</p>
 */
@Component
public final class AiModelPriceCatalog {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int COST_SCALE = 8;

    private final Map<String, ModelPrice> prices;

    /**
     * Keeps component creation compatible with Spring configurations that discover this component without constructor
     * metadata. The namespaced environment value is the same deployment input as the injected property path, while an
     * empty value preserves the intentionally unpriced default used by local deployments.
     */
    public AiModelPriceCatalog() {
        this(System.getenv().getOrDefault("MATH_AGENT_AI_PRICES_JSON", ""));
    }

    /** Creates a catalog from the environment-backed JSON property. */
    @Autowired
    public AiModelPriceCatalog(@Value("${math-agent.ai.pricing-json:}") String pricingJson) {
        this.prices = parse(pricingJson);
    }

    private AiModelPriceCatalog(Map<String, ModelPrice> prices) {
        this.prices = Map.copyOf(prices);
    }

    /** Creates an explicitly unpriced catalog for focused tests. */
    public static AiModelPriceCatalog empty() {
        return new AiModelPriceCatalog(Map.of());
    }

    /** Calculates provider-reported input/output cost, or {@code -1} when the deployment has no matching price. */
    public double estimate(String providerName, String modelCode, int inputTokens, int outputTokens) {
        Optional<ModelPrice> price = find(providerName, modelCode);
        if (price.isEmpty()) {
            return -1.0d;
        }
        BigDecimal input = BigDecimal.valueOf(Math.max(0, inputTokens))
                .multiply(price.get().inputPerMillion())
                .divide(BigDecimal.valueOf(1_000_000L), COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(Math.max(0, outputTokens))
                .multiply(price.get().outputPerMillion())
                .divide(BigDecimal.valueOf(1_000_000L), COST_SCALE, RoundingMode.HALF_UP);
        return input.add(output).setScale(COST_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /** Returns whether a concrete provider/model price is configured. */
    public boolean isConfigured(String providerName, String modelCode) {
        return find(providerName, modelCode).isPresent();
    }

    private Optional<ModelPrice> find(String providerName, String modelCode) {
        String provider = normalize(providerName);
        String model = normalize(modelCode);
        return Optional.ofNullable(prices.get(provider + "/" + model))
                .or(() -> Optional.ofNullable(prices.get(model)));
    }

    private static Map<String, ModelPrice> parse(String pricingJson) {
        if (pricingJson == null || pricingJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(pricingJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("MATH_AGENT_AI_PRICES_JSON must be a JSON object");
            }
            Map<String, ModelPrice> parsed = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                double input = value.path("inputPerMillion").asDouble(-1.0d);
                double output = value.path("outputPerMillion").asDouble(-1.0d);
                if (input < 0.0d || output < 0.0d) {
                    throw new IllegalArgumentException("Price entries require non-negative input/outputPerMillion");
                }
                parsed.put(normalize(field.getKey()), new ModelPrice(
                        BigDecimal.valueOf(input), BigDecimal.valueOf(output)));
            }
            return parsed;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid MATH_AGENT_AI_PRICES_JSON configuration", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /** Immutable price pair expressed in deployment currency per million tokens. */
    public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }
}
