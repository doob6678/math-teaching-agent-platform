package com.doob.mathagent.agent;

import com.doob.mathagent.agent.service.AgentConcurrencyGuard;
import com.doob.mathagent.agent.service.AgentRunClient;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.agent.vo.AgentRunExecuteResponse;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import java.time.Clock;

final class AgentRunExecutionServiceFixture {

    private AgentRunExecutionServiceFixture() {
    }

    static AgentRunExecutionService deterministicModelService(AgentTraceStore traceStore) {
        return deterministicModelService(traceStore, new InMemoryAgentConcurrencyGuard());
    }

    static AgentRunExecutionService deterministicModelService(AgentTraceStore traceStore, AgentConcurrencyGuard guard) {
        return new AgentRunExecutionService(traceStore, guard, deterministicClient(), providerCatalog(), Clock.systemUTC());
    }

    static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("openai");
        properties.getOpenai().setEnabled(true);
        properties.getOpenai().setChatModel("gpt-5.6-luna");
        return new AiProviderCatalog(properties);
    }

    private static AgentRunClient deterministicClient() {
        return (traceId, request, plan) -> new AgentRunClient.Result(
                plan.providerName(), plan.modelCode(), new AgentRunExecuteResponse.TokenUsage(5, 3, 8),
                "deterministic Python facade response",
                "{\"teacherExplanation\":\"deterministic Python facade draft\"}",
                -1.0d,
                false);
    }
}
