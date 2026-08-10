package com.doob.mathagent.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP API 访问控制过滤器，统一拦截 /api/** 请求。
 */
@Component
public class ApiAccessControlFilter extends OncePerRequestFilter {

    private final ApiAccessControlService accessControlService;
    private final RequestSubjectResolver subjectResolver;
    private final boolean trustedProxyHeaders;

    /**
     * 注入访问控制服务。
     */
    public ApiAccessControlFilter(
            ApiAccessControlService accessControlService,
            RequestSubjectResolver subjectResolver) {
        this(accessControlService, subjectResolver, false);
    }

    /** Production constructor; forwarded headers are accepted only when an operator explicitly trusts a proxy. */
    @Autowired
    public ApiAccessControlFilter(
            ApiAccessControlService accessControlService,
            RequestSubjectResolver subjectResolver,
            @Value("${math-agent.security.trusted-proxy-headers:false}") boolean trustedProxyHeaders) {
        this.accessControlService = accessControlService;
        this.subjectResolver = subjectResolver;
        this.trustedProxyHeaders = trustedProxyHeaders;
    }

    /**
     * 非 /api/** 请求不进入业务安全链路。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /**
     * 对 API 请求执行权限隔离和次数限流。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        ApiAccessDecision decision = accessControlService.evaluate(
                identityFrom(request, subjectResolver.resolve(request), trustedProxyHeaders));
        response.setHeader("X-Api-Access-Level", decision.level().name());
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Used", String.valueOf(decision.used()));
        if (!decision.allowed()) {
            writeDeniedResponse(response, decision);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 从 HTTP 请求头和连接信息中提取调用身份。
     */
    private static ApiRequestIdentity identityFrom(
            HttpServletRequest request, RequestSubject subject, boolean trustedProxyHeaders) {
        RequestSubject normalized = subject.normalize();
        return new ApiRequestIdentity(
                request.getMethod(),
                request.getRequestURI(),
                normalized.tenantId(),
                normalized.subjectType(),
                normalized.subjectId(),
                clientIp(request, trustedProxyHeaders),
                normalized.deviceId(),
                request.getHeader("User-Agent"));
    }

    /**
     * 写出统一的拒绝响应。
     */
    private static void writeDeniedResponse(HttpServletResponse response, ApiAccessDecision decision) throws IOException {
        response.setStatus(decision.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"code":"API_ACCESS_DENIED","message":"%s","limit":%d,"used":%d}
                """.formatted(escapeJson(decision.reason()), decision.limit(), decision.used()).strip());
    }

    /**
     * 提取客户端 IP；只有明确配置可信代理时才读取 X-Forwarded-For，否则使用真实 TCP 对端地址。
     */
    private static String clientIp(HttpServletRequest request, boolean trustedProxyHeaders) {
        String forwardedFor = headerOrNull(request, "X-Forwarded-For");
        if (trustedProxyHeaders && forwardedFor != null) {
            return forwardedFor.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    /**
     * 读取请求头，空白时使用默认值。
     */
    private static String headerOrNull(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * 对 JSON 字符串字段做最小转义。
     */
    private static String escapeJson(String value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
