package com.doob.mathagent.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 为 Python worker 签发不含密钥和端点的短期 provider route grant。 */
@Component
public class ProviderRouteGrantSigner {

    private static final ObjectMapper JSON = new ObjectMapper();
    // A handout grant is consumed after bounded authorization and document curation, which can legitimately take a
    // significant fraction of the 900-second fenced lecture lease before the first provider call reaches Python.
    private static final long DEFAULT_ROUTE_GRANT_TTL_SECONDS = 900L;
    private final Environment environment;

    public ProviderRouteGrantSigner(Environment environment) {
        this.environment = environment;
    }

    public String sign(String runId, String workload, List<ProviderRoute> routes) {
        String secret = configuredValue("route-grant-secret", "");
        if (secret.isBlank()) {
            throw new IllegalStateException("Python provider route grant secret is not configured");
        }
        long expiresAt = System.currentTimeMillis() / 1000L
                + configuredLongValue("route-grant-ttl-seconds", DEFAULT_ROUTE_GRANT_TTL_SECONDS);
        Map<String, Object> payload = Map.of(
                "runId", bounded(runId, 128),
                "workload", bounded(workload, 64),
                "expiresAt", expiresAt,
                "routes", routes.stream().map(route -> Map.of("name", route.name(), "model", route.model())).toList());
        try {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    JSON.writeValueAsBytes(payload));
            byte[] signature = hmac(secret.getBytes(StandardCharsets.UTF_8), encoded.getBytes(StandardCharsets.US_ASCII));
            return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign provider route grant", exception);
        }
    }

    public record ProviderRoute(String name, String model) {
    }

    private static byte[] hmac(byte[] secret, byte[] value) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(value);
    }

    /** Uses deployment-owned AI settings while retaining the original worker prefix for existing callers. */
    private String configuredValue(String suffix, String fallback) {
        return environment.getProperty(
                "math-agent.ai." + suffix,
                environment.getProperty("math-agent.python-agent." + suffix, fallback));
    }

    private long configuredLongValue(String suffix, long fallback) {
        return environment.getProperty(
                "math-agent.ai." + suffix,
                Long.class,
                environment.getProperty("math-agent.python-agent." + suffix, Long.class, fallback));
    }

    private static String bounded(String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
