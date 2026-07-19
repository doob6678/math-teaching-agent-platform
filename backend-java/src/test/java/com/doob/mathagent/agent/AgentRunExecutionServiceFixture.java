package com.doob.mathagent.agent;

import com.doob.mathagent.agent.service.AgentConcurrencyGuard;
import com.doob.mathagent.agent.service.AgentRunExecutionService;
import com.doob.mathagent.agent.service.AgentTraceStore;
import com.doob.mathagent.agent.service.AiChatGateway;
import com.doob.mathagent.agent.service.AiChatRequest;
import com.doob.mathagent.agent.service.AiChatResult;
import com.doob.mathagent.agent.service.InMemoryAgentConcurrencyGuard;
import com.doob.mathagent.infrastructure.ai.AiProviderCatalog;
import com.doob.mathagent.infrastructure.ai.AiProviderProperties;
import java.time.Clock;

final class AgentRunExecutionServiceFixture {

    private AgentRunExecutionServiceFixture() {
    }

    static AgentRunExecutionService deterministicModelService(AgentTraceStore traceStore) {
        return service(traceStore, new InMemoryAgentConcurrencyGuard(), deterministicGateway(), providerCatalog(), Clock.systemUTC());
    }

    static AgentRunExecutionService deterministicModelService(AgentTraceStore traceStore, AgentConcurrencyGuard guard) {
        return service(traceStore, guard, deterministicGateway(), providerCatalog(), Clock.systemUTC());
    }

    static AgentRunExecutionService modelService(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard guard,
            AiChatGateway gateway) {
        return service(traceStore, guard, gateway, providerCatalog(), Clock.systemUTC());
    }

    static AgentRunExecutionService service(
            AgentTraceStore traceStore,
            AgentConcurrencyGuard guard,
            AiChatGateway gateway,
            AiProviderCatalog providerCatalog,
            Clock clock) {
        return new AgentRunExecutionService(traceStore, guard, gateway, providerCatalog, clock);
    }

    static AiProviderCatalog providerCatalog() {
        AiProviderProperties properties = new AiProviderProperties();
        properties.setDefaultProvider("dashscope");
        properties.getDashscope().setApiKey("dashscope-key");
        properties.getDashscope().setChatModel("qwen3.6-flash");
        properties.getOpenai().setApiKey("openai-key");
        properties.getOpenai().setChatModel("gpt-5.4");
        return new AiProviderCatalog(properties);
    }

    private static AiChatGateway deterministicGateway() {
        return new AiChatGateway() {
            @Override
            public AiChatResult call(AiChatRequest request) {
                return new AiChatResult(
                        request.providerName(),
                        request.modelCode(),
                        5,
                        3,
                        8,
                        "deterministic test model response",
                        "{\"teacherExplanation\":\"deterministic\",\"studentHint\":\"hint\","
                                + "\"knowledgePoints\":[\"kp\"],\"followUpQuestions\":[\"q\"]}");
            }
        };
    }
}
