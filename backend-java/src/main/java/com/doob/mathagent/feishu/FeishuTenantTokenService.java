package com.doob.mathagent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 机器人身份 tenant_access_token 的缓存获取器。
 *
 * <p>按租户建库与批量上传使用应用自建凭证（tenant bot），而不是用户 OAuth：用户 token
 * 只有只读 drive 范围（见 FeishuOAuthService），写权限由管理员在开放平台后台授予应用。
 * token 有效期约 2 小时，这里在到期前 60 秒刷新，避免边界竞态；synchronized 足够，
 * 因为上传是低频批处理路径，不做双层缓存抽象。</p>
 */
@Service
public class FeishuTenantTokenService {
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public FeishuTenantTokenService(Environment environment) {
        this.environment = environment;
    }

    /** Returns a valid tenant token, refreshing once per expiry window. */
    public synchronized String token() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }
        String appId = required("FEISHU_APP_ID", "FEISHU_APPID", "APP_ID");
        String appSecret = required("FEISHU_APP_SECRET", "FEISHU_APPSECRET", "APP_SECRET");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(java.util.Map.of("app_id", appId, "app_secret", appSecret)),
                            StandardCharsets.UTF_8))
                    .build();
            JsonNode root = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("FEISHU_TENANT_TOKEN_REJECTED code=" + root.path("code").asInt()
                        + " msg=" + root.path("msg").asText(""));
            }
            cachedToken = root.path("tenant_access_token").asText();
            long expiresIn = Math.max(60, root.path("expire").asLong(7200));
            expiresAt = Instant.now().plusSeconds(expiresIn - 60);
            return cachedToken;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("FEISHU_TENANT_TOKEN_UNAVAILABLE", exception);
        }
    }

    /** 与 FeishuOAuthService.firstConfigured 一致的别名约定：历史部署两种命名都存在。 */
    private String required(String... names) {
        for (String name : names) {
            String value = environment.getProperty(name, "");
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        throw new IllegalStateException(names[0] + " is not configured");
    }
}
