package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.agent.dto.MultiAgentWritingRequest;
import com.doob.mathagent.teaching.dto.TeachingTaskRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keeps the retired writing entry point on the teaching-task request contract instead of creating a second workflow.
 */
class HandoutTaskFacadeContractTest {

    @Test
    void mapsWritingRequestToOneDeterministicTeachingTaskRequest() {
        MultiAgentWritingRequest writing = new MultiAgentWritingRequest(
                "Prepare a teacher handout", "Find the angle between two lines", List.of("evidence-a", "evidence-b"),
                false, "openai", "gpt-5.6-luna");

        TeachingTaskRequest task = HandoutTaskFacade.toTeachingTaskRequest(writing);

        assertThat(task.clientRequestId()).startsWith("writing-");
        assertThat(task.questionText()).isEqualTo("Find the angle between two lines");
        assertThat(task.learningGoal()).isEqualTo("Prepare a teacher handout");
        assertThat(task.evidenceLimit()).isEqualTo(2);
        assertThat(task.aiProviderName()).isEqualTo("openai");
        assertThat(task.aiModelCode()).isEqualTo("gpt-5.6-luna");
    }
}
