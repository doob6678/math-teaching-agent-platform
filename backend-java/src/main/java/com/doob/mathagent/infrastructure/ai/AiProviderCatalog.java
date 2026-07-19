package com.doob.mathagent.infrastructure.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Catalog of configured AI providers.
 *
 * <p>The catalog is intentionally separate from Spring AI model beans. It lets workflows record which provider is
 * allowed for a task, exposes a frontend-safe model catalog, and prevents a provider with a missing API key from being
 * selected silently.</p>
 */
@Component
public class AiProviderCatalog {

    private final AiProviderProperties properties;

    /**
     * Creates the provider catalog.
     *
     * @param properties environment-backed provider properties
     */
    public AiProviderCatalog(AiProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns enabled providers in backend fallback order.
     *
     * @return enabled providers
     */
    public List<Provider> enabledProviders() {
        return configuredProviders()
                .stream()
                .filter(AiProviderCatalog::hasUsableCredentials)
                .map(AiProviderCatalog::toProvider)
                .sorted((left, right) -> Integer.compare(
                        providerOrder(properties.getDefaultProvider(), left.name()),
                        providerOrder(properties.getDefaultProvider(), right.name())))
                .toList();
    }

    /**
     * Returns a frontend-safe model catalog built from backend configuration and allow-lists.
     *
     * @return provider/model catalog without API keys
     */
    public ModelCatalog modelCatalog() {
        Provider defaultProvider = defaultProvider();
        List<ModelProvider> providers = enabledProviders().stream()
                .map(provider -> new ModelProvider(
                        provider.name(),
                        true,
                        provider.chatModel(),
                        allowedModelOptions(provider.name())))
                .sorted((left, right) -> Integer.compare(
                        providerOrder(defaultProvider.name(), left.name()),
                        providerOrder(defaultProvider.name(), right.name())))
                .toList();
        return new ModelCatalog(
                defaultProvider.name(),
                defaultProvider.chatModel(),
                providers.stream().map(ModelProvider::name).toList(),
                providers);
    }

    /**
     * Looks up an enabled provider by name.
     *
     * @param name provider name
     * @return enabled provider when configured
     */
    public Optional<Provider> provider(String name) {
        String normalized = normalize(name);
        return enabledProviders().stream()
                .filter(provider -> provider.name().equals(normalized))
                .findFirst();
    }

    /**
     * Looks up an enabled provider and validates that the requested model belongs to that provider's allow-list.
     *
     * @param providerName provider name requested by a user preference
     * @param modelCode model code requested by a user preference
     * @return provider with the requested model when allowed
     */
    public Optional<Provider> preferredProvider(String providerName, String modelCode) {
        String normalizedModel = safeText(modelCode);
        if (normalizedModel.isBlank()) {
            return Optional.empty();
        }
        return provider(providerName)
                .filter(provider -> allowedModels(provider.name()).contains(normalizedModel))
                .map(provider -> new Provider(provider.name(), provider.baseUrl(), normalizedModel));
    }

    /**
     * Returns the configured default provider.
     *
     * @return default provider
     */
    public Provider defaultProvider() {
        return provider(properties.getDefaultProvider())
                .or(() -> enabledProviders().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No AI provider is enabled by environment variables"));
    }

    /**
     * Returns configured providers in the desired fallback order.
     */
    private List<AiProviderProperties.Provider> configuredProviders() {
        return List.of(
                properties.getOpenai(),
                properties.getDashscope(),
                properties.getDeepseek(),
                properties.getArk());
    }

    /**
     * Checks whether provider settings are complete enough to be used.
     *
     * @param provider provider settings
     * @return true when name, base URL, API key, and chat model are all present
     */
    private static boolean hasUsableCredentials(AiProviderProperties.Provider provider) {
        return hasText(provider.getName())
                && hasText(provider.getBaseUrl())
                && hasText(provider.getApiKey())
                && hasText(provider.getChatModel());
    }

    /**
     * Converts mutable configuration properties to an immutable runtime provider.
     *
     * @param provider provider settings
     * @return runtime provider
     */
    private static Provider toProvider(AiProviderProperties.Provider provider) {
        return new Provider(
                normalize(provider.getName()),
                provider.getBaseUrl().strip(),
                provider.getChatModel().strip());
    }

    /**
     * Normalizes provider names for stable comparisons.
     *
     * @param value provider name
     * @return normalized provider name
     */
    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns an allow-list of model codes the frontend may request for one provider.
     *
     * @param providerName normalized provider name
     * @return allowed model codes
     */
    private static List<String> allowedModels(String providerName) {
        return switch (normalize(providerName)) {
            // gpt-5.6-luna is the verified multimodal default for student explanations; keep it selectable rather
            // than silently falling back to an unrelated text-only catalog entry.
            case "openai" -> List.of("gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano");
            case "dashscope" -> List.of("qwen3.6-flash", "qwen3.7-plus", "qwen3.7-max");
            case "deepseek" -> List.of("deepseek-v4-flash", "deepseek-v4-pro");
            case "ark" -> List.of("doubao-seed-2-0-lite-260428", "doubao-seed-2.0-mini");
            default -> List.of();
        };
    }

    /**
     * Returns frontend-safe model options with coarse capability and cost labels.
     */
    private static List<ModelOption> allowedModelOptions(String providerName) {
        return allowedModels(providerName).stream()
                .map(model -> new ModelOption(model, modelLevel(model), priceTier(model)))
                .toList();
    }

    /**
     * Keeps the default provider first and all other providers in configured fallback order.
     */
    private static int providerOrder(String defaultProviderName, String providerName) {
        if (normalize(defaultProviderName).equals(normalize(providerName))) {
            return -1;
        }
        return switch (normalize(providerName)) {
            case "openai" -> 0;
            case "dashscope" -> 1;
            case "deepseek" -> 2;
            case "ark" -> 3;
            default -> 99;
        };
    }

    /**
     * Maps an allow-listed model to a coarse routing capability label.
     */
    private static String modelLevel(String modelCode) {
        String normalized = normalize(modelCode);
        if (normalized.contains("max") || normalized.equals("gpt-5.4") || normalized.contains("gpt-5.6") || normalized.endsWith("-pro")) {
            return "reasoning";
        }
        if (normalized.contains("mini") || normalized.contains("nano")
                || normalized.contains("flash") || normalized.contains("lite")) {
            return "fast_text";
        }
        return "general";
    }

    /**
     * Maps model codes to coarse price labels used only for UI hints.
     */
    private static String priceTier(String modelCode) {
        String normalized = normalize(modelCode);
        Set<String> cheapHints = Set.of("mini", "nano", "flash", "lite", "turbo");
        return cheapHints.stream().anyMatch(normalized::contains) ? "cheap" : "standard";
    }

    /**
     * Returns stripped text or an empty string.
     *
     * @param value text value
     * @return stripped text
     */
    private static String safeText(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    /**
     * Returns whether a string contains non-whitespace text.
     *
     * @param value text value
     * @return true when non-blank
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Runtime provider view safe for logging and audit.
     *
     * @param name provider name
     * @param baseUrl OpenAI-compatible base URL
     * @param chatModel chat model name
     */
    public record Provider(String name, String baseUrl, String chatModel) {
    }

    /**
     * Frontend-safe model catalog with no provider secrets.
     *
     * @param defaultProviderName backend default provider
     * @param defaultModelCode backend default model
     * @param fallbackProviderOrder provider rotation order
     * @param providers enabled providers and model options
     */
    public record ModelCatalog(
            String defaultProviderName,
            String defaultModelCode,
            List<String> fallbackProviderOrder,
            List<ModelProvider> providers) {
    }

    /**
     * Frontend-safe provider model list.
     *
     * @param name provider name
     * @param enabled whether credentials are configured
     * @param defaultModelCode provider default model
     * @param models allowed model options
     */
    public record ModelProvider(
            String name,
            boolean enabled,
            String defaultModelCode,
            List<ModelOption> models) {
    }

    /**
     * One allow-listed model option.
     *
     * @param modelCode provider model code
     * @param modelLevel coarse model capability label
     * @param priceTier coarse price label
     */
    public record ModelOption(String modelCode, String modelLevel, String priceTier) {
    }
}
