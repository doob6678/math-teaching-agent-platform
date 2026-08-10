package com.doob.mathagent.infrastructure.security;

/**
 * 单次 API 请求的安全身份快照。
 */
public record ApiRequestIdentity(
        /** HTTP 方法，例如 GET、POST、OPTIONS。 */
        String method,
        /** 请求路径，例如 /api/retrieval/textbooks/search。 */
        String path,
        /** 租户标识；当前单租户阶段默认 default。 */
        String tenantId,
        /** 调用主体类型，例如 anonymous、guest、teacher、admin。 */
        String subjectType,
        /** 调用主体 ID；未登录或游客场景允许为空。 */
        String subjectId,
        /** 客户端 IP，用于限流和风险排查。 */
        String ip,
        /** 设备 ID，用于避免只按 IP 限流被绕过。 */
        String deviceId,
        /** User-Agent，用于审计调用来源。 */
        String userAgent) {

    /**
     * 返回规范化身份，补齐默认租户、匿名主体、未知 IP 和设备 ID。
     */
    public ApiRequestIdentity normalize() {
        return new ApiRequestIdentity(
                blankToDefault(method, "GET").toUpperCase(),
                blankToDefault(path, "/"),
                blankToDefault(tenantId, "default"),
                blankToDefault(subjectType, "anonymous").toLowerCase(),
                blankToNull(subjectId),
                blankToDefault(ip, "unknown-ip"),
                blankToDefault(deviceId, "unknown-device"),
                blankToNull(userAgent));
    }

    /**
     * 返回限流主体，登录请求只按后端解析出的用户主体聚合。
     *
     * <p>匿名请求没有可验证的用户主体，只能退回到服务端看到的连接地址；绝不能把客户端自报的设备
     * 标识当成第二个认证因子，否则攻击者可以为每次请求生成新值来拆散限流窗口。</p>
     */
    public String rateLimitSubject() {
        ApiRequestIdentity normalized = normalize();
        if (normalized.subjectId() != null) {
            return normalized.subjectType() + ":" + normalized.subjectId();
        }
        return normalized.subjectType() + ":" + normalized.ip();
    }

    /**
     * 将空白字符串转换为默认值。
     */
    private static String blankToDefault(String value, String defaultValue) {
        String normalized = blankToNull(value);
        return normalized == null ? defaultValue : normalized;
    }

    /**
     * 将空白字符串转换为 null，避免限流 key 出现不可见差异。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
