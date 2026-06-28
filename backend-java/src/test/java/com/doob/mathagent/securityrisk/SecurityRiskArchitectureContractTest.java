package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.memory.dto.StudentMemoryRequest;
import com.doob.mathagent.securityrisk.entity.CapabilityAuditLogEntity;
import com.doob.mathagent.securityrisk.mapper.CapabilityAuditLogMapper;
import com.doob.mathagent.teacher.dto.TeacherResourceRegistrationRequest;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecurityRiskArchitectureContractTest {

    @Test
    void securityRiskModuleUsesLayeredPackages() {
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/controller")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/dto")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/vo")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/service")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/entity")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/mapper")).isDirectory();
        assertThat(Path.of("src/main/java/com/doob/mathagent/securityrisk/config")).isDirectory();
    }

    @Test
    void capabilityAuditMapperUsesCapabilityAuditLogTable() {
        assertThat(BaseMapper.class).isAssignableFrom(CapabilityAuditLogMapper.class);
        assertThat(CapabilityAuditLogEntity.class.getAnnotation(TableName.class).value())
                .isEqualTo("capability_audit_log");
    }

    @Test
    void requestBodyDtosDoNotExposeBackendIdentityFields() {
        Set<String> forbiddenFields = Set.of(
                "tenantId",
                "viewerRole",
                "viewerSubjectId",
                "subjectType",
                "subjectId",
                "studentId",
                "role",
                "userId");

        assertThat(recordComponentNames(StudentMemoryRequest.class))
                .doesNotContainAnyElementsOf(forbiddenFields);
        assertThat(recordComponentNames(TeacherResourceRegistrationRequest.class))
                .doesNotContainAnyElementsOf(forbiddenFields);
    }

    private static Set<String> recordComponentNames(Class<? extends Record> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }
}
