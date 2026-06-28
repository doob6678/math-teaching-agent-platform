package com.doob.mathagent.infrastructure.security;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * API 访问控制服务，统一执行接口分级、主体权限和固定窗口限流。
 */
@Service
public class ApiAccessControlService {

    private final ApiRateLimiter rateLimiter;
    private final Clock clock;
    private final ApiAccessPolicy policy;

    /**
     * 创建生产默认访问控制服务。
     */
    @Autowired
    public ApiAccessControlService(ApiRateLimiter rateLimiter) {
        this(rateLimiter, Clock.systemUTC(), ApiAccessPolicy.defaultRules());
    }

    /**
     * 创建可测试访问控制服务。
     */
    ApiAccessControlService(ApiRateLimiter rateLimiter, Clock clock, ApiAccessPolicy policy) {
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.policy = policy;
    }

    /**
     * 评估请求是否允许访问，并同步统计固定窗口次数。
     */
    public ApiAccessDecision evaluate(ApiRequestIdentity identity) {
        ApiRequestIdentity normalized = identity.normalize();
        return policy.findRule(normalized.path())
                .map(rule -> evaluateRule(normalized, rule))
                .orElseGet(() -> ApiAccessDecision.deny(
                        403,
                        ApiAccessLevel.ADMIN,
                        0,
                        0,
                        "No API access rule matched path: " + normalized.path()));
    }

    /**
     * 针对单条规则执行主体权限和限流检查。
     */
    private ApiAccessDecision evaluateRule(ApiRequestIdentity identity, ApiAccessRule rule) {
        if (!rule.allowsSubject(identity.subjectType())) {
            return ApiAccessDecision.deny(
                    403,
                    rule.level(),
                    rule.limit(),
                    0,
                    "Endpoint requires subject type in " + rule.allowedSubjectTypes());
        }
        RateLimitUsage usage = rateLimiter.check(
                rule.rateLimitKey(identity),
                rule.limit(),
                rule.window(),
                clock.instant());
        if (usage.exceeded()) {
            return ApiAccessDecision.deny(
                    429,
                    rule.level(),
                    usage.limit(),
                    usage.used(),
                    "Rate limit exceeded");
        }
        return ApiAccessDecision.allow(rule.level(), usage.limit(), usage.used());
    }
}
