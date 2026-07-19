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
