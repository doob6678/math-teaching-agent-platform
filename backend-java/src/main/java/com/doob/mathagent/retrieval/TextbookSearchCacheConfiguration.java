package com.doob.mathagent.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Textbook search cache configuration.
 */
@Configuration
@EnableConfigurationProperties(RedisTextbookSearchCacheProperties.class)
public class TextbookSearchCacheConfiguration {
}
