package com.doob.mathagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.auth.service.LocalAccount;
import com.doob.mathagent.auth.vo.TeacherAccountProvisionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Prevents administrator-facing teacher provisioning from serializing an internal password hash.
 */
class TeacherAccountProvisionResponseTest {

    @Test
    void exposesOnlySafeTeacherAccountMetadata() throws Exception {
        TeacherAccountProvisionResponse response = TeacherAccountProvisionResponse.from(
                new LocalAccount("teacher-001", "Math Teacher", "pbkdf2-secret-hash", "teacher", "school-a"));

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("teacher-001", "Math Teacher", "school-a", "teacher");
        assertThat(json).doesNotContain("password", "pbkdf2-secret-hash");
    }
}
