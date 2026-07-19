package com.doob.mathagent.infrastructure.security;

/**
 * API 安全检查结果，供 Filter 转换为 HTTP 响应。
 */
public record ApiAccessDecision(
        /** 是否允许继续调用业务接口。 */
        boolean allowed,
        /** 拒绝时使用的 HTTP 状态码，允许时为 200。 */
        int httpStatus,
        /** 安全等级，便于响应头和审计展示。 */
        ApiAccessLevel level,
        /** 本窗口限流上限。 */
        int limit,
        /** 当前窗口已经使用的次数。 */
        int used,
        /** 拒绝或放行原因。 */
        String reason) {

    /**
     * 构造允许访问的结果。
     */
    public static ApiAccessDecision allow(ApiAccessLevel level, int limit, int used) {
        return new ApiAccessDecision(true, 200, level, limit, used, "allowed");
    }

    /**
     * 构造拒绝访问的结果。
     */
    public static ApiAccessDecision deny(int httpStatus, ApiAccessLevel level, int limit, int used, String reason) {
        return new ApiAccessDecision(false, httpStatus, level, limit, used, reason);
    }
}
