package com.doob.mathagent.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemHealthControllerTest {

    @Test
    void failsClosedWhenDependencyProbeCannotRun() {
        SystemHealthController controller = new SystemHealthController(unusedRuntimeStatusService());

        Map<String, String> health = controller.health();

        assertThat(health)
                .containsEntry("status", "DOWN")
                .containsEntry("service", "math-agent-rag-backend")
                .containsEntry("mode", "probe_failed");
    }

    private static SystemRuntimeStatusService unusedRuntimeStatusService() {
        return new SystemRuntimeStatusService(null, null, null, null, null, null, null);
    }
}
