package com.doob.mathagent.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemHealthControllerTest {

    @Test
    void exposesApplicationHealthForLocalDevelopment() {
        SystemHealthController controller = new SystemHealthController(unusedRuntimeStatusService());

        Map<String, String> health = controller.health();

        assertThat(health)
                .containsEntry("status", "UP")
                .containsEntry("service", "math-agent-rag-backend");
    }

    private static SystemRuntimeStatusService unusedRuntimeStatusService() {
        return new SystemRuntimeStatusService(null, null, null, null, null, null, null, null);
    }
}
