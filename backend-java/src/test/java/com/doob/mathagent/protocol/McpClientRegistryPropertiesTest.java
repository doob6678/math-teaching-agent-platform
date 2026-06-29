package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpClientRegistryPropertiesTest {

    @Test
    void keepsRegistryNullSafeAndStoresOnlySecretHashes() {
        McpClientRegistryProperties properties = new McpClientRegistryProperties();

        properties.setClients(List.of(new McpClientRegistryProperties.Client(
                "student-client",
                "student",
                "sha256:abc123",
                true)));

        assertThat(properties.getClients()).hasSize(1);
        assertThat(properties.getClients().getFirst().clientId()).isEqualTo("student-client");
        assertThat(properties.getClients().getFirst().profile()).isEqualTo("student");
        assertThat(properties.getClients().getFirst().secretHash()).isEqualTo("sha256:abc123");
        assertThat(properties.getClients().getFirst().secretHash()).doesNotContain("secret_");

        properties.setClients(null);

        assertThat(properties.getClients()).isEmpty();
    }
}
