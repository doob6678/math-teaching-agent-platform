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
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("multi-agent-writing-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.initialize();
        return executor;
    }
}
