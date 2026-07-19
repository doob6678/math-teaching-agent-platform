package com.doob.mathagent.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HttpCorsFilterTest {

    @Test
    void addsCorsHeadersBeforeDownstreamApiRejection() throws Exception {
        HttpCorsFilter filter = new HttpCorsFilter(new HttpCorsProperties(List.of("http://127.0.0.1:5173")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/teacher/resources");
        request.addHeader(HttpHeaders.ORIGIN, "http://127.0.0.1:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse).setStatus(HttpServletResponse.SC_FORBIDDEN);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://127.0.0.1:5173");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains("X-Api-Access-Level");
    }

    @Test
    void handlesCorsPreflightWithoutEnteringBusinessFilters() throws Exception {
        HttpCorsFilter filter = new HttpCorsFilter(new HttpCorsProperties(List.of("http://127.0.0.1:5173")));
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/mcp");
        request.addHeader(HttpHeaders.ORIGIN, "http://127.0.0.1:5173");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> continued.set(true));

        assertThat(continued).isFalse();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://127.0.0.1:5173");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).contains("MCP-Protocol-Version", "satoken");
    }

    @Test
    void defaultCorsOriginsAllowViteFallbackPort() {
        HttpCorsProperties properties = HttpCorsProperties.fromEnvironment(java.util.Map.of());

        assertThat(properties.allowedOrigins()).contains("http://127.0.0.1:5174", "http://localhost:5174");
    }
}
