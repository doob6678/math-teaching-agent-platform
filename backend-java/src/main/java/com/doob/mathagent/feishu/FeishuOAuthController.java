package com.doob.mathagent.feishu;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.infrastructure.security.RequestSubjectResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Browser-facing OAuth endpoints expose status and redirect URLs only; secrets never cross this boundary. */
@RestController
public class FeishuOAuthController {
    private final FeishuOAuthService oauth; private final RequestSubjectResolver subjects;
    public FeishuOAuthController(FeishuOAuthService oauth, RequestSubjectResolver subjects){this.oauth=oauth;this.subjects=subjects;}
    @GetMapping("/api/feishu/oauth/authorize")
    public OAuthAuthorizeResponse authorize(HttpServletRequest request){RequestSubject s=subjects.resolve(request).normalize(); requireUser(s); return new OAuthAuthorizeResponse(oauth.authorizationUrl(s.tenantId(),s.subjectId()));}
    @GetMapping("/api/feishu/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam String state,@RequestParam String code){oauth.callback(state,code);return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND).location(URI.create(oauth.successRedirectUri())).build();}
    @GetMapping("/api/feishu/oauth/status")
    public OAuthStatusResponse status(HttpServletRequest request){RequestSubject s=subjects.resolve(request).normalize();requireUser(s);FeishuCredential c=oauth.status(s.tenantId(),s.subjectId());if(c==null)return new OAuthStatusResponse("AUTH_REQUIRED",null);if(c.expired(Instant.now()))return new OAuthStatusResponse("AUTH_REQUIRED",c.expiresAt());return new OAuthStatusResponse("AUTHORIZED",c.expiresAt());}
    private static void requireUser(RequestSubject s){if(s.subjectId()==null||s.subjectId().isBlank()||"anonymous".equals(s.subjectType()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"authenticated user required");}
    public record OAuthAuthorizeResponse(String authorizationUrl){}
    public record OAuthStatusResponse(String status,Instant expiresAt){}
}
