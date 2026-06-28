package com.doob.mathagent.securityrisk;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.securityrisk.entity.CapabilityAuditLogEntity;
import com.doob.mathagent.securityrisk.mapper.CapabilityAuditLogMapper;
import com.doob.mathagent.securityrisk.service.CapabilityAuditEvent;
import com.doob.mathagent.securityrisk.service.MyBatisCapabilityAuditStore;
import java.lang.reflect.Proxy;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MyBatisCapabilityAuditStoreTest {

    @Test
    void recordsCapabilityAuditEventWithTokenHashInsteadOfRawToken() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisCapabilityAuditStore store = new MyBatisCapabilityAuditStore(mapper.proxy());

        store.record(new CapabilityAuditEvent(
                "event-1",
                Instant.parse("2026-06-28T08:00:00Z"),
                "school-a",
                "student",
                "student-1",
                "teaching:submit",
                "/api/teaching/tasks",
                "hash-1",
                "client-1",
                "raw-token-value",
                "issued",
                "Capability token issued"));

        assertThat(mapper.inserted.getEventId()).isEqualTo("event-1");
        assertThat(mapper.inserted.getTenantId()).isEqualTo("school-a");
        assertThat(mapper.inserted.getSubjectType()).isEqualTo("student");
        assertThat(mapper.inserted.getSubjectId()).isEqualTo("student-1");
        assertThat(mapper.inserted.getAction()).isEqualTo("teaching:submit");
        assertThat(mapper.inserted.getPath()).isEqualTo("/api/teaching/tasks");
        assertThat(mapper.inserted.getRequestHash()).isEqualTo("hash-1");
        assertThat(mapper.inserted.getIdempotencyKey()).isEqualTo("client-1");
        assertThat(mapper.inserted.getTokenHash()).isNotBlank();
        assertThat(mapper.inserted.getTokenHash()).isNotEqualTo("raw-token-value");
        assertThat(mapper.inserted.getDecision()).isEqualTo("issued");
        assertThat(mapper.inserted.getReason()).contains("issued");
    }

    private static final class CapturingMapper {

        private CapabilityAuditLogEntity inserted;

        CapabilityAuditLogMapper proxy() {
            return (CapabilityAuditLogMapper) Proxy.newProxyInstance(
                    CapabilityAuditLogMapper.class.getClassLoader(),
                    new Class<?>[] {CapabilityAuditLogMapper.class},
                    (proxy, method, args) -> {
                        if ("insert".equals(method.getName())) {
                            inserted = (CapabilityAuditLogEntity) args[0];
                            return 1;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
