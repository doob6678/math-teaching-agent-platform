package com.doob.mathagent;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Separate Java Worker entry point: run this process with Worker runtime environment variables and no HTTP server. */
public final class AgentWorkerApplication {
    private AgentWorkerApplication() { }
    public static void main(String[] args) {
        // A system property has higher precedence than application.yml, guaranteeing this dedicated entry point
        // activates Worker-only listeners even though the control-plane application's default is disabled.
        System.setProperty("math-agent.agent-worker.runtime.enabled", "true");
        new SpringApplicationBuilder(MathAgentApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
