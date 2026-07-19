package com.doob.mathagent.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索请求上下文，用于把调用方身份和设备线索写入审计日志。
 */
public record RetrievalRequestContext(
        /** 租户标识；当前单租户阶段默认 default，后续用于学校/机构隔离。 */
        String tenantId,
        /** 主体类型；例如 guest、student、teacher、admin、api_key。 */
        String subjectType,
        /** 主体 ID；登录体系接入前允许为空。 */
        String subjectId,
        /** 客户端 IP；用于后续风险评分、限流和异常排查。 */
        String ip,
        /** 设备标识；用于识别同设备高频检索或高价值接口滥用。 */
        String deviceId,
        /** User-Agent；用于审计调用来源和辅助风险判断。 */
        String userAgent,
        /** API endpoint；用于按接口维度统计检索和成本行为。 */
        String endpoint) {

    public static RetrievalRequestContext defaultTextbookSearch() {
        return new RetrievalRequestContext(
                "default",
                null,
                null,
                null,
                null,
                null,
                "/api/retrieval/textbooks/search");
    }

    public RetrievalRequestContext normalize() {
        return new RetrievalRequestContext(
                blankToDefault(tenantId, "default"),
                blankToNull(subjectType),
                blankToNull(subjectId),
                blankToNull(ip),
                blankToNull(deviceId),
                blankToNull(userAgent),
                blankToDefault(endpoint, "/api/retrieval/textbooks/search"));
    }

    Map<String, String> toAuditMap() {
        RetrievalRequestContext normalized = normalize();
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, "tenant_id", normalized.tenantId());
        putIfPresent(values, "subject_type", normalized.subjectType());
        putIfPresent(values, "subject_id", normalized.subjectId());
        putIfPresent(values, "ip", normalized.ip());
        putIfPresent(values, "device_id", normalized.deviceId());
        putIfPresent(values, "user_agent", normalized.userAgent());
        putIfPresent(values, "endpoint", normalized.endpoint());
        return values;
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
