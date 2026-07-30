package com.doob.mathagent.infrastructure.web;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class HttpCorsConfiguration implements WebMvcConfigurer {

    private final HttpCorsProperties corsProperties;

    public HttpCorsConfiguration() {
        this(HttpCorsProperties.localDefaults());
    }

    HttpCorsConfiguration(HttpCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .exposedHeaders("MCP-Protocol-Version", "X-Api-Access-Level", "X-RateLimit-Limit", "X-RateLimit-Used", "X-Handout-Renderer", "X-Handout-Page-Count")
                .maxAge(3600);
    }
}
