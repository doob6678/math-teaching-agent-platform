package com.doob.mathagent.infrastructure.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Catalog of configured AI providers.
 *
 * <p>The catalog is intentionally separate from Spring AI model beans. It lets workflows record which provider is
 * allowed for a task and prevents a provider with a missing API key from being selected silently.</p>
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
     * Returns enabled providers in deterministic priority order.
     *
     * @return enabled providers
     */
    public List<Provider> enabledProviders() {
        return List.of(properties.getOpenai(), properties.getDeepseek(), properties.getArk()).stream()
                .filter(AiProviderCatalog::hasUsableCredentials)
                .map(AiProviderCatalog::toProvider)
                .toList();
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
}
