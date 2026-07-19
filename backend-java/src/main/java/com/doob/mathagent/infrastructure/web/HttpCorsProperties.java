package com.doob.mathagent.infrastructure.web;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * HTTP CORS 配置字段。
 *
 * <p>字段含义：
 * <ul>
 *   <li>allowedOrigins：允许跨域访问后端 API 的前端源地址列表。</li>
 * </ul>
 */
public record HttpCorsProperties(List<String> allowedOrigins) {

    private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
            "http://127.0.0.1:5173",
            "http://127.0.0.1:5174",
            "http://127.0.0.1:5175",
            "http://127.0.0.1:5176",
            "http://127.0.0.1:5177",
            "http://127.0.0.1:5178",
            "http://127.0.0.1:5179",
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:5175",
            "http://localhost:5176",
            "http://localhost:5177",
            "http://localhost:5178",
            "http://localhost:5179");

    public static HttpCorsProperties fromEnvironment(Map<String, String> environment) {
        String configuredOrigins = environment.getOrDefault("MATH_AGENT_CORS_ALLOWED_ORIGINS", "");
        if (configuredOrigins.isBlank()) {
            return new HttpCorsProperties(DEFAULT_ALLOWED_ORIGINS);
        }
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isBlank())
                .toList();
        return new HttpCorsProperties(origins.isEmpty() ? DEFAULT_ALLOWED_ORIGINS : origins);
    }
}
