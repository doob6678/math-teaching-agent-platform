package com.doob.mathagent.agent.vo;

import java.util.List;

/**
 * Frontend-safe AI model catalog returned by the backend.
 *
 * @param defaultProviderName backend default provider selected from environment
 * @param defaultModelCode backend default model selected from environment
 * @param fallbackProviderOrder provider fallback rotation order
 * @param providers enabled providers and allow-listed models
 */
public record AgentModelCatalogResponse(
        String defaultProviderName,
        String defaultModelCode,
        List<String> fallbackProviderOrder,
        List<Provider> providers) {

    /**
     * Provider entry safe for display.
     *
     * @param name provider name
     * @param enabled whether backend credentials are configured
     * @param defaultModelCode provider default model
     * @param models allow-listed model options
     */
    public record Provider(
            String name,
            boolean enabled,
            String defaultModelCode,
            List<Model> models) {
    }

    /**
     * Model option safe for display and request preferences.
     *
     * @param modelCode provider model code
     * @param modelLevel coarse capability label
     * @param priceTier coarse price label
     */
    public record Model(String modelCode, String modelLevel, String priceTier) {
    }
}
