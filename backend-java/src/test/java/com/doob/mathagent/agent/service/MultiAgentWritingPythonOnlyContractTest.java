package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Locks the production route decision: a new handout defaults to Python and never silently selects Java AI. */
class MultiAgentWritingPythonOnlyContractTest {

    @Test
    void pythonHandoutIsTheOnlyRuntimeAndDisablingItFailsClosed() {
        MockEnvironment environment = new MockEnvironment();
        assertThat(MultiAgentWritingService.pythonHandoutEnabled(environment)).isTrue();

        environment.setProperty("math-agent.python-handout.enabled", "false");
        assertThat(MultiAgentWritingService.pythonHandoutEnabled(environment)).isFalse();
    }
}
