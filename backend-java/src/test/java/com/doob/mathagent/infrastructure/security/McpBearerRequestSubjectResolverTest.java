package com.doob.mathagent.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class McpBearerRequestSubjectResolverTest {

    @Test
    void resolvesRegisteredMcpBearerSecretBeforeFallingBackToAnonymous() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "school-a",
                "teacher-mcp-client",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        SaTokenRequestSubjectResolver resolver = new SaTokenRequestSubjectResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/mcp");
        request.addHeader("Authorization", "Bearer teacher_secret_1234567890abcdef");
        request.addHeader("X-Subject-Id", "spoofed-user");

        RequestSubject subject = resolver.resolve(request);

        assertThat(subject.tenantId()).isEqualTo("school-a");
        assertThat(subject.subjectType()).isEqualTo("teacher");
        assertThat(subject.subjectId()).isEqualTo("teacher-mcp-client");
    }

    @Test
    void ignoresMcpBearerSecretOnInternalSessionRoutes() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();
        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "workbuddy-teacher",
                "teacher",
                "school-a",
                "teacher-mcp-client",
                McpClientRegistryProperties.secretHash("teacher_secret_1234567890abcdef"),
                true,
                List.of("search_textbook_evidence"),
                List.of("PUBLIC_TEXTBOOK"))));
        SaTokenRequestSubjectResolver resolver = new SaTokenRequestSubjectResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/mcp/configuration/me");
        request.addHeader("Authorization", "Bearer teacher_secret_1234567890abcdef");

        RequestSubject subject = resolver.resolve(request);

        assertThat(subject.subjectType()).isEqualTo("anonymous");
        assertThat(subject.subjectId()).isNull();
    }
}
