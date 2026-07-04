package com.doob.mathagent.vector.config;

import com.doob.mathagent.vector.service.VectorIndexProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Vector index configuration.
 */
@Configuration
@EnableConfigurationProperties(VectorIndexProperties.class)
public class VectorIndexConfiguration {
}
