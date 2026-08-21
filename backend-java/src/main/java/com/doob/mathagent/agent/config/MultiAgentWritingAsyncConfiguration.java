package com.doob.mathagent.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor configuration for background multi-agent writing workflows.
 */
@Configuration
public class MultiAgentWritingAsyncConfiguration {

    /**
     * Creates the bounded executor used by async multi-agent writing jobs.
     *
     * @return task executor for background writing workflows
     */
    @Bean("multiAgentWritingTaskExecutor")
    public TaskExecutor multiAgentWritingTaskExecutor() {
        return boundedExecutor("multi-agent-writing-", 2, 4, 32);
    }

    @Bean("teachingEvidenceTaskExecutor")
    public TaskExecutor teachingEvidenceTaskExecutor() {
        return boundedExecutor("teaching-evidence-", 4, 4, 64);
    }

    /**
     * Isolates synchronous MCP evidence fan-out from student SSE and explanation work.
     *
     * <p>Each multi-source call can occupy one branch per selected library. A dedicated bounded pool prevents a
     * burst of student explanation streams from turning an otherwise fast retrieval into an unobservable queue wait.</p>
     */
    @Bean("mcpRetrievalTaskExecutor")
    public TaskExecutor mcpRetrievalTaskExecutor() {
        return boundedExecutor("mcp-retrieval-", 2, 4, 32);
    }

    /** Shared bounded executor for student retrieval fan-out and SSE orchestration. */
    @Bean("studentExplanationTaskExecutor")
    public TaskExecutor studentExplanationTaskExecutor() {
        return boundedExecutor("student-explanation-", 4, 8, 64);
    }

    private static TaskExecutor boundedExecutor(String threadNamePrefix, int corePoolSize, int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }
}
