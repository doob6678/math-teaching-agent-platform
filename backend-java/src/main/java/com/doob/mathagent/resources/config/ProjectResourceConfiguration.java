package com.doob.mathagent.resources.config;

import com.doob.mathagent.resources.ProjectResourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Project resource path configuration.
 */
@Configuration
public class ProjectResourceConfiguration {

    /**
     * Creates local project resource path properties.
     *
     * @param environment Spring environment containing application.yml and environment variables
     * @return normalized project resource paths
     */
    @Bean
    ProjectResourceProperties projectResourceProperties(Environment environment) {
        return ProjectResourceProperties.fromSpringEnvironment(environment);
    }
}
