package com.doob.mathagent.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds CORS headers before security filters can reject an API request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpCorsFilter extends OncePerRequestFilter {

    private final HttpCorsProperties corsProperties;

    public HttpCorsFilter() {
        this(HttpCorsProperties.fromEnvironment(System.getenv()));
    }

    HttpCorsFilter(HttpCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (originAllowed(origin, corsProperties.allowedOrigins())) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin.strip());
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE,OPTIONS");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization,Content-Type,MCP-Protocol-Version,X-Device-Id,X-Capability-Token,X-Request-Hash,satoken");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "MCP-Protocol-Version,X-Api-Access-Level,X-RateLimit-Limit,X-RateLimit-Used,X-Handout-Renderer,X-Handout-Page-Count");
            response.setHeader(HttpHeaders.VARY, "Origin");
            response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean originAllowed(String origin, List<String> allowedOrigins) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        String normalized = origin.strip();
        return allowedOrigins.stream().anyMatch(normalized::equals);
    }
}
