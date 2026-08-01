package com.doob.mathagent.teacher.sync;

import org.springframework.core.env.Environment;
import java.util.Arrays;
import java.util.List;

/**
 * Explicit background-sync authority. The scheduler is disabled by default and can run only under a configured
 * administrator service identity, never by impersonating the teacher who originally registered a resource.
 */
public record TeacherSourceSyncSchedulerProperties(
        boolean enabled,
        long fixedDelayMilliseconds,
        String tenantId,
        String serviceRole,
        String serviceSubjectId,
        List<String> documentIds,
        long workerLeaseTimeoutSeconds) {

    private static final long DEFAULT_FIXED_DELAY_MILLISECONDS = 300000L;
    private static final long MINIMUM_FIXED_DELAY_MILLISECONDS = 1L;

    public static TeacherSourceSyncSchedulerProperties fromSpringEnvironment(Environment environment) {
        return new TeacherSourceSyncSchedulerProperties(
                Boolean.parseBoolean(environment.getProperty("math-agent.teacher.sync.scheduler.enabled", "false")),
                longProperty(environment, "math-agent.teacher.sync.scheduler.fixed-delay-ms", DEFAULT_FIXED_DELAY_MILLISECONDS),
                text(environment, "math-agent.teacher.sync.scheduler.tenant-id", "default"),
                text(environment, "math-agent.teacher.sync.scheduler.service-role", "admin"),
                text(environment, "math-agent.teacher.sync.scheduler.service-subject-id", ""),
                commaSeparated(environment.getProperty("math-agent.teacher.sync.scheduler.document-ids", "")),
                longProperty(environment, "math-agent.teacher.sync.scheduler.worker-lease-timeout-seconds", 1800L));
    }

    public TeacherSourceSyncSchedulerProperties {
        fixedDelayMilliseconds = Math.max(MINIMUM_FIXED_DELAY_MILLISECONDS, fixedDelayMilliseconds);
        tenantId = textValue(tenantId, "default");
        serviceRole = textValue(serviceRole, "admin").toLowerCase();
        serviceSubjectId = serviceSubjectId == null ? "" : serviceSubjectId.strip();
        workerLeaseTimeoutSeconds = Math.max(60L, workerLeaseTimeoutSeconds);
        documentIds = documentIds == null ? List.of() : documentIds.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct().toList();
        if (enabled && (!"admin".equals(serviceRole) || serviceSubjectId.isBlank())) {
            throw new IllegalArgumentException(
                    "Enabled Feishu scheduler requires an explicit admin service-role and service-subject-id");
        }
    }

    private static long longProperty(Environment environment, String key, long defaultValue) {
        try {
            return Long.parseLong(environment.getProperty(key, String.valueOf(defaultValue)).strip());
        } catch (RuntimeException exception) {
            return defaultValue;
        }
    }

    private static String text(Environment environment, String key, String defaultValue) {
        return textValue(environment.getProperty(key), defaultValue);
    }

    private static String textValue(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }

    private static List<String> commaSeparated(String value) {
        return Arrays.stream(textValue(value, "").split(","))
                .map(String::strip).filter(item -> !item.isBlank()).toList();
    }
}
