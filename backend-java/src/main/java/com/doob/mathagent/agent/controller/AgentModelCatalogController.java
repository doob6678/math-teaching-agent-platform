package com.doob.mathagent.agent.controller;

import com.doob.mathagent.agent.vo.AgentModelCatalogResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API for exposing backend-validated AI provider/model options to the frontend.
 */
@RestController
public class AgentModelCatalogController {

    private final AiProviderCatalog providerCatalog;

    /**
     * Creates the controller.
     *
     * @param providerCatalog backend provider catalog
     */
    public AgentModelCatalogController(AiProviderCatalog providerCatalog) {
        this.providerCatalog = providerCatalog;
    }

    /**
     * Returns enabled providers and allow-listed models without exposing secrets.
     *
     * @return model catalog
     */
    @GetMapping("/api/agents/model-catalog")
    public AgentModelCatalogResponse modelCatalog() {
        AiProviderCatalog.ModelCatalog catalog = providerCatalog.modelCatalog();
        return new AgentModelCatalogResponse(
                catalog.defaultProviderName(),
                catalog.defaultModelCode(),
                catalog.fallbackProviderOrder(),
                catalog.providers().stream()
                        .map(provider -> new AgentModelCatalogResponse.Provider(
                                provider.name(),
                                provider.enabled(),
                                provider.defaultModelCode(),
                                provider.models().stream()
                                        .map(model -> new AgentModelCatalogResponse.Model(
                                                model.modelCode(),
                                                model.modelLevel(),
                                                model.priceTier()))
                                        .toList()))
                        .toList());
    }
}
