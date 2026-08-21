package com.doob.mathagent.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.protocol.service.McpAccessPolicy;
import org.junit.jupiter.api.Test;

/** Keeps discovery-time MCP permissions aligned with implemented owner-scoped resource readers. */
class McpAccessPolicyTest {

    @Test
    void teacherProfileIncludesOwnerScopedTeacherResourceReaders() {
        assertThat(McpAccessPolicy.toolsForProfile("teacher"))
                .contains("list_teacher_resources", "read_teacher_resource_blocks");
        assertThat(McpAccessPolicy.scopesForProfile("teacher"))
                .contains("teacher-resource:read");
    }

    @Test
    void adminProfileIncludesQuestionBankReaderForTenantAcceptanceAudits() {
        assertThat(McpAccessPolicy.toolsForProfile("admin"))
                .contains("search_question_bank_items", "search_multi_source_evidence");
        assertThat(McpAccessPolicy.scopesForProfile("admin"))
                .contains("question-bank:read", "teacher-resource:read");
    }
}
