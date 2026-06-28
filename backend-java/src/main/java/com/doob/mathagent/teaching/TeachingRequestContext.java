package com.doob.mathagent.teaching;

/**
 * 教学任务调用身份上下文。
 *
 * @param tenantId 租户 ID，用于隔离学校、机构或部署空间的数据。
 * @param subjectType 调用主体类型，例如 student、teacher、admin。
 * @param subjectId 调用主体 ID，用于隔离个人私有任务、飞书资料和历史结果。
 * @param deviceId 设备 ID，用于前端离开页面后的任务恢复和风控分析。
 */
public record TeachingRequestContext(
        String tenantId,
        String subjectType,
        String subjectId,
        String deviceId) {

    /**
     * 本地开发默认身份，供不经过 HTTP 的单元测试和控制器便捷方法使用。
     */
    public static TeachingRequestContext localTeacher() {
        return new TeachingRequestContext("default", "teacher", "local-teacher-console", "local-browser-console");
    }

    /**
     * 归一化空身份字段，保证任务隔离 key 稳定。
     */
    public TeachingRequestContext normalize() {
        return new TeachingRequestContext(
                blankToDefault(tenantId, "default"),
                blankToDefault(subjectType, "anonymous"),
                blankToDefault(subjectId, "anonymous"),
                blankToDefault(deviceId, "unknown-device"));
    }

    /**
     * 生成任务归属 key；只有同一租户、主体类型、主体 ID 才能读取任务。
     */
    public String ownerKey() {
        TeachingRequestContext normalized = normalize();
        return "%s:%s:%s".formatted(normalized.tenantId(), normalized.subjectType(), normalized.subjectId());
    }

    /**
     * 生成幂等 key；同一主体重复提交同一 clientRequestId 时复用已有任务。
     */
    public String idempotencyKey(String clientRequestId) {
        return ownerKey() + ":" + blankToDefault(clientRequestId, "missing-client-request-id");
    }

    /**
     * 空白字符串归一化工具。
     */
    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
