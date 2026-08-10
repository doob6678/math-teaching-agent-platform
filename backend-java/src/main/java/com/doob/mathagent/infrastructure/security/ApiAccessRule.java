package com.doob.mathagent.infrastructure.security;

import java.time.Duration;
import java.util.Set;

/**
 * 单条接口访问规则，描述路径、允许主体和固定窗口限流参数。
 */
public record ApiAccessRule(
        /** 路径前缀；命中后应用该规则。 */
        String pathPrefix,
        /** 接口安全等级。 */
        ApiAccessLevel level,
        /** 允许访问的主体类型集合。 */
        Set<String> allowedSubjectTypes,
        /** 固定窗口内允许的最大请求次数。 */
        int limit,
        /** 固定窗口时长。 */
        Duration window) {

    /**
     * 判断请求路径是否命中当前规则。
     */
    public boolean matches(String path) {
        return path != null && path.startsWith(pathPrefix);
    }

    /**
     * 判断主体类型是否允许访问当前规则。
     */
    public boolean allowsSubject(String subjectType) {
        return allowedSubjectTypes.contains("*") || allowedSubjectTypes.contains(subjectType);
    }

    /**
     * 构造稳定的限流 key，按租户、endpoint 和后端解析的主体聚合。
     */
    public String rateLimitKey(ApiRequestIdentity identity) {
        ApiRequestIdentity normalized = identity.normalize();
        return String.join("|",
                normalized.tenantId(),
                pathPrefix,
                normalized.rateLimitSubject());
    }
}
