package com.doob.mathagent.teaching;

import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import com.doob.mathagent.teaching.service.TeachingAiDraftProperties;
import com.doob.mathagent.teaching.service.TeachingAiDraftService;

final class TeachingAiDraftServiceFixture {

    private TeachingAiDraftServiceFixture() {
    }

    static TeachingAiDraftService disabled() {
        return new TeachingAiDraftService(
                request -> {
                    throw new IllegalStateException("Test AI gateway is disabled");
                },
                new AiProviderCatalog(new AiProviderProperties()),
                new TeachingAiDraftProperties());
    }
}
