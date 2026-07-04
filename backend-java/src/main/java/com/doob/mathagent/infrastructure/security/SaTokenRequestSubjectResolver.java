package com.doob.mathagent.infrastructure.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.doob.mathagent.protocol.service.McpClientRegistryProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves request identity from Sa-Token login state and session attributes.
 */
@Component
public class SaTokenRequestSubjectResolver implements RequestSubjectResolver {

    private static final String TENANT_SESSION_KEY = "tenantId";
    private static final String ROLE_SESSION_KEY = "role";
    private final McpClientRegistryProperties mcpClientRegistryProperties;

    /**
     * Creates a resolver with an empty MCP registry for isolated unit tests.
     */
    public SaTokenRequestSubjectResolver() {
        this(new McpClientRegistryProperties());
    }

    /**
     * Creates a resolver with the configured MCP client registry.
     *
     * @param mcpClientRegistryProperties registered MCP client keys
     */
    @Autowired
    public SaTokenRequestSubjectResolver(McpClientRegistryProperties mcpClientRegistryProperties) {
        this.mcpClientRegistryProperties = mcpClientRegistryProperties == null
                ? new McpClientRegistryProperties()
                : mcpClientRegistryProperties;
    }

    /**
     * Resolves identity from Sa-Token; HTTP identity headers are ignored because they are client-controlled.
     *
     * @param request HTTP request, or null for direct local tests
     * @return authenticated subject or anonymous subject
     */
    @Override
    public RequestSubject resolve(HttpServletRequest request) {
        if (request == null) {
            return RequestSubject.anonymous("default", "unknown-device");
        }
        RequestSubject mcpSubject = resolveMcpBearerSubject(request);
        if (mcpSubject != null) {
            return mcpSubject.normalize();
        }
        if (!isLoggedIn()) {
            return RequestSubject.anonymous("default", deviceId(request));
        }
        String subjectId = StpUtil.getLoginIdAsString();
        SaSession session = StpUtil.getSession();
        String tenantId = stringSessionValue(session, TENANT_SESSION_KEY, "default");
        String role = stringSessionValue(session, ROLE_SESSION_KEY, primaryRole());
        return new RequestSubject(tenantId, role, subjectId, deviceId(request)).normalize();
    }

    /**
     * Resolves a trusted backend subject from a registered MCP Bearer secret before Sa-Token fallback.
     */
    private RequestSubject resolveMcpBearerSubject(HttpServletRequest request) {
        String secret = bearerSecretOrNull(request.getHeader("Authorization"));
        if (secret == null) {
            return null;
        }
        return mcpClientRegistryProperties.findEnabledClientBySecret(secret)
                .map(client -> new RequestSubject(
                        client.tenantId(),
                        client.profile(),
                        client.subjectId(),
                        "mcp:" + client.clientId()))
                .orElse(null);
    }

    /**
     * Checks login state without throwing when no token is present.
     *
     * @return true when Sa-Token has an active login
     */
    private static boolean isLoggedIn() {
        try {
            return StpUtil.isLogin();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Returns the first role from Sa-Token role list, falling back to student for authenticated users.
     */
    private static String primaryRole() {
        try {
            List<String> roles = StpUtil.getRoleList();
            return roles.isEmpty() ? "student" : roles.getFirst();
        } catch (RuntimeException ignored) {
            return "student";
        }
    }

    /**
     * Reads a string session value with a fallback.
     */
    private static String stringSessionValue(SaSession session, String key, String fallback) {
        Object value = session.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).strip();
    }

    /**
     * Reads a non-authoritative device id for rate-limit and audit grouping only.
     */
    private static String deviceId(HttpServletRequest request) {
        String value = request.getHeader("X-Device-Id");
        return value == null || value.isBlank() ? "unknown-device" : value.strip();
    }

    /**
     * Extracts a Bearer secret when the Authorization header is present and well-formed.
     */
    private static String bearerSecretOrNull(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String normalized = authorizationHeader.strip();
        if (!normalized.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return null;
        }
        String secret = normalized.substring("Bearer ".length()).strip();
        return secret.isBlank() ? null : secret;
    }
}
