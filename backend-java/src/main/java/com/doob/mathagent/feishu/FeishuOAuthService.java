package com.doob.mathagent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Implements Feishu user OAuth while keeping app credentials and provider tokens server-side. */
@Service
public class FeishuOAuthService {
    private final Environment environment;
    private final FeishuCredentialService credentials;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, PendingState> states = new ConcurrentHashMap<>();
    public FeishuOAuthService(Environment environment, FeishuCredentialService credentials) { this.environment=environment; this.credentials=credentials; }

    /** Creates a one-time state tied to the backend-resolved tenant and subject. */
    public String authorizationUrl(String tenantId, String subjectId) {
        requireIdentity(tenantId, subjectId);
        String state=UUID.randomUUID().toString(); states.put(state,new PendingState(tenantId,subjectId,Instant.now().plusSeconds(300)));
        String appId=requireConfig("FEISHU_APP_ID"); String redirect=requireConfig("FEISHU_OAUTH_REDIRECT_URI");
        return "https://open.feishu.cn/open-apis/authen/v1/authorize?app_id="+encode(appId)+"&redirect_uri="+encode(redirect)+"&state="+encode(state)+"&scope="+encode(environment.getProperty("FEISHU_OAUTH_SCOPES","drive:drive:readonly docx:document:readonly"));
    }

    /** Exchanges the provider code and atomically replaces the encrypted user credential. */
    public FeishuCredential callback(String state, String code) {
        PendingState pending=states.remove(state);
        if(pending==null || pending.expiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("OAuth state expired");
        if(code==null || code.isBlank()) throw new IllegalArgumentException("OAuth code is required");
        try {
            String body=mapper.writeValueAsString(Map.of("app_id",requireConfig("FEISHU_APP_ID"),"app_secret",requireConfig("FEISHU_APP_SECRET"),"grant_type","authorization_code","code",code));
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://open.feishu.cn/open-apis/authen/v1/access_token"))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode root=mapper.readTree(http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body());
            JsonNode data=root.path("data"); String access=data.path("access_token").asText(""); String refresh=data.path("refresh_token").asText("");
            if(access.isBlank()) throw new IllegalArgumentException("Feishu OAuth did not return an access token");
            long expires=data.path("expires_in").asLong(3600); return credentials.save(pending.tenantId(),pending.subjectId(),access,refresh,Instant.now().plusSeconds(expires));
        } catch (Exception exception) { if(exception instanceof IllegalArgumentException iae) throw iae; throw new IllegalStateException("Feishu OAuth exchange failed",exception); }
    }
    public FeishuCredential status(String tenantId,String subjectId){return credentials.findActive(tenantId,subjectId);}
    public String successRedirectUri(){return environment.getProperty("FEISHU_OAUTH_SUCCESS_REDIRECT_URI","/");}
    private String requireConfig(String key){String value=environment.getProperty(key);if(value==null||value.isBlank())throw new IllegalStateException(key+" is not configured");return value.strip();}
    private static void requireIdentity(String tenant,String subject){if(tenant==null||tenant.isBlank()||subject==null||subject.isBlank())throw new IllegalArgumentException("authenticated tenant and user are required");}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private record PendingState(String tenantId,String subjectId,Instant expiresAt){}
}
