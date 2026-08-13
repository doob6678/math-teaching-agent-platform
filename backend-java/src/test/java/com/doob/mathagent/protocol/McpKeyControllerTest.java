package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import com.doob.mathagent.protocol.controller.McpKeyController;
import com.doob.mathagent.protocol.service.InMemoryMcpClientKeyStore;
import com.doob.mathagent.protocol.service.McpClientKeyService;
import com.doob.mathagent.protocol.service.ProtocolDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class McpKeyControllerTest {

    @Test
    void createsListsBuildsConfigurationAndRevokesForCurrentSessionUser() {
        McpKeyController controller = new McpKeyController(
                new McpClientKeyService(
                        new InMemoryMcpClientKeyStore(),
                        new ProtocolDiscoveryService()),
                fixedSubject("school-a", "teacher", "teacher-001"));
        MockHttpServletRequest request = request("http", "math.example.com", 8080, "/api/mcp/keys");

        var created = controller.createKey(request);
        var keys = controller.keys(request);
        var configuration = controller.currentConfiguration(request("https", "math.example.com", 443, "/api/mcp/configuration/me"));
        var revoked = controller.revokeKey(created.keyId(), request);

        assertThat(created.secretKey()).startsWith("mcp_");
        assertThat(created.secretKeyPreview()).contains("...");
        assertThat(created.configuration().url()).isEqualTo("http://math.example.com:8080/api/mcp");
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst().ownerUserId()).isEqualTo("teacher-001");
        assertThat(configuration.keyProfile()).isEqualTo("teacher");
        assertThat(configuration.exposedTools()).contains("start_multi_agent_writing");
        assertThat(revoked.status()).isEqualTo("revoked");
        assertThat(controller.keys(request).getFirst().status()).isEqualTo("revoked");
    }

    @Test
    void onlyListsKeysOwnedByCurrentSessionUser() {
        McpClientKeyService service = new McpClientKeyService(
                new InMemoryMcpClientKeyStore(),
                new ProtocolDiscoveryService());
        service.createKey(new RequestSubject("school-a", "teacher", "teacher-001", "device-1"), "https://math.example.com/api/mcp");
        service.createKey(new RequestSubject("school-a", "teacher", "teacher-002", "device-2"), "https://math.example.com/api/mcp");
        McpKeyController controller = new McpKeyController(service, fixedSubject("school-a", "teacher", "teacher-001"));

        var keys = controller.keys(request("https", "math.example.com", 443, "/api/mcp/keys"));

        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst().ownerUserId()).isEqualTo("teacher-001");
    }

    @Test
    void usesConfiguredPublicUrlWhenAPIPassesThroughAHostRewritingProxy() {
        McpKeyController controller = new McpKeyController(
                new McpClientKeyService(new InMemoryMcpClientKeyStore(), new ProtocolDiscoveryService()),
                fixedSubject("school-a", "teacher", "teacher-001"),
                "http://127.0.0.1:8080/proxy-path");

        var created = controller.createKey(request("http", "proxy-host", 80, "/api/mcp/keys"));

        assertThat(created.configuration().url()).isEqualTo("http://127.0.0.1:8080/api/mcp");
    }

    private static RequestSubjectResolver fixedSubject(String tenantId, String role, String userId) {
        return request -> new RequestSubject(tenantId, role, userId, "device-fixed");
    }

    private static MockHttpServletRequest request(String scheme, String serverName, int port, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme(scheme);
        request.setServerName(serverName);
        request.setServerPort(port);
        request.setRequestURI(uri);
        return request;
    }
}
