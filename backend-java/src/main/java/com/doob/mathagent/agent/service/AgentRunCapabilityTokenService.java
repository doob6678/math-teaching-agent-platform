package com.doob.mathagent.agent.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Creates short-lived, signed capability tokens for a single Python Agent run.
 *
 * <p>Unlike a browser capability, this token is intentionally reusable for the small bounded set of read-only
 * tool calls in one run.  Its HMAC binds run, tenant, subject and allowed tools, while Java remains the only code
 * that can read protected resources.  Tokens contain no source paths, SQL, provider key, or private document text.</p>
 */
@Service
public class AgentRunCapabilityTokenService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final String signingSecret;
    private final Clock clock;

    @Autowired
    public AgentRunCapabilityTokenService(
            @Value("${math-agent.agent-worker.capability-secret:${MATH_AGENT_AGENT_WORKER_CAPABILITY_SECRET:}}") String signingSecret) {
        this(signingSecret, Clock.systemUTC());
    }

    /** Explicit constructor keeps expiry and signature tests independent from process configuration. */
    public AgentRunCapabilityTokenService(String signingSecret, Clock clock) {
        this.signingSecret = signingSecret == null ? "" : signingSecret.strip();
        this.clock = clock;
    }

    /** Issues a token after Java has calculated the exact tool set from authenticated business context. */
    public String issue(String runId, RequestSubject subject, List<String> allowedTools) {
        if (signingSecret.isBlank()) {
            throw new IllegalStateException("MATH_AGENT_AGENT_WORKER_CAPABILITY_SECRET is required");
        }
        RequestSubject normalized = subject.normalize();
        List<String> tools = allowedTools == null ? List.of() : allowedTools.stream()
                .filter(tool -> tool != null && !tool.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        if (runId == null || runId.isBlank() || tools.isEmpty()) {
            throw new IllegalArgumentException("Agent run and granted tools are required");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("runId", runId.strip());
        claims.put("tenantId", normalized.tenantId());
        claims.put("subjectType", normalized.subjectType());
        claims.put("subjectId", normalized.subjectId());
        claims.put("allowedTools", tools);
        claims.put("expiresAt", Instant.now(clock).plus(TOKEN_TTL).toEpochMilli());
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    OBJECT_MAPPER.writeValueAsBytes(claims));
            return payload + "." + signature(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to issue Agent capability token", exception);
        }
    }

    /** Verifies every broker invocation against the original subject and one concrete registered tool. */
    public Verification verify(String token, String runId, RequestSubject subject, String requiredTool) {
        if (signingSecret.isBlank() || token == null || token.isBlank()) {
            return Verification.deny("Agent capability token is unavailable");
        }
        String[] segments = token.split("\\.", -1);
        if (segments.length != 2 || !MessageDigest.isEqual(
                signature(segments[0]).getBytes(StandardCharsets.US_ASCII),
                segments[1].getBytes(StandardCharsets.US_ASCII))) {
            return Verification.deny("Agent capability token signature is invalid");
        }
        try {
            Map<String, Object> claims = OBJECT_MAPPER.readValue(
                    Base64.getUrlDecoder().decode(segments[0]), MAP_TYPE);
            RequestSubject normalized = subject.normalize();
            if (!text(claims.get("runId")).equals(text(runId))
                    || !text(claims.get("tenantId")).equals(normalized.tenantId())
                    || !text(claims.get("subjectType")).equals(normalized.subjectType())
                    || !text(claims.get("subjectId")).equals(normalized.subjectId())) {
                return Verification.deny("Agent capability token subject or run mismatch");
            }
            long expiresAt = Long.parseLong(text(claims.get("expiresAt")));
            if (Instant.now(clock).toEpochMilli() > expiresAt) {
                return Verification.deny("Agent capability token expired");
            }
            Object rawTools = claims.get("allowedTools");
            if (!(rawTools instanceof List<?> values) || !new LinkedHashSet<>(values.stream().map(String::valueOf).toList())
                    .contains(requiredTool)) {
                return Verification.deny("Agent capability token tool is not granted");
            }
            return Verification.allow();
        } catch (Exception exception) {
            return Verification.deny("Agent capability token is malformed");
        }
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify Agent capability token", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    /** Result intentionally carries no claims so callers cannot accidentally re-expose the token payload. */
    public record Verification(boolean allowed, String reason) {
        private static Verification allow() { return new Verification(true, ""); }
        private static Verification deny(String reason) { return new Verification(false, reason); }
    }
}
