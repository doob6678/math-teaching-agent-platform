package com.doob.mathagent.knowledge.config;

import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineProperties;
import com.doob.mathagent.knowledge.service.KnowledgeGraphSpineSeedService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Wires the curated display graph spine seed into application startup.
 */
@Configuration
@EnableConfigurationProperties(KnowledgeGraphSpineProperties.class)
public class KnowledgeGraphSpineConfiguration {

    /**
     * Seeds the curated graph after the store implementation is ready.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ApplicationRunner knowledgeGraphSpineSeedRunner(KnowledgeGraphSpineSeedService seedService) {
        return arguments -> seedService.seedIfEnabled();
    }
}
