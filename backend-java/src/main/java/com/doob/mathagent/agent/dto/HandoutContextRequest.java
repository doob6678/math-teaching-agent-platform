package com.doob.mathagent.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Python 讲义图发起的一次仅限本运行证据的上下文请求。 */
public record HandoutContextRequest(
        @NotBlank String runId,
        // A fresh Java task can legitimately contain no initial evidence. The Python plan writer must still start
        // under this run ID, then request its own teacher-resource search through the separately authorized broker.
        @Size(max = 24) List<@NotBlank @Size(max = 80) String> evidenceRefs,
        @Min(1) @Max(20) int limit) {
}
