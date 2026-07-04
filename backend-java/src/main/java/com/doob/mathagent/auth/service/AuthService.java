package com.doob.mathagent.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.session.SaSession;
import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.dto.RegisterRequest;
import com.doob.mathagent.auth.vo.LoginResponse;
import org.springframework.stereotype.Service;

/**
 * Authentication service that creates Sa-Token sessions from backend account data.
 */
@Service
public class AuthService {

    private final LocalAccountStore accountStore;
    private final PasswordHashService passwordHashService;

    /**
     * Creates an auth service.
     *
     * @param accountStore account lookup store
     */
    public AuthService(LocalAccountStore accountStore, PasswordHashService passwordHashService) {
        this.accountStore = accountStore;
        this.passwordHashService = passwordHashService;
    }

    /**
     * Logs in a local account and stores trusted identity fields in the backend session.
     *
     * @param request login request
     * @return login response with token data
     */
    public LoginResponse login(LoginRequest request) {
        LocalAccount account = accountStore.findByUsername(request.username())
                .filter(found -> passwordHashService.matches(request.password(), found.password()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        return loginAccount(account);
    }

    /**
     * Registers a student account and immediately creates a backend session.
     *
     * @param request registration request
     * @return login response with token data
     */
    public LoginResponse register(RegisterRequest request) {
        LocalAccount account = accountStore.createStudent(
                request.username(),
                passwordHashService.encode(request.password()),
                request.tenantId());
        return loginAccount(account);
    }

    /**
     * Returns the current backend session when the submitted Sa-Token is still valid.
     */
    public LoginResponse currentSession() {
        if (!StpUtil.isLogin()) {
            throw new IllegalArgumentException("Session is not authenticated");
        }
        SaSession session = StpUtil.getSession();
        String userId = StpUtil.getLoginIdAsString();
        String username = stringValue(session.get("username"), userId);
        String role = stringValue(session.get("role"), "student");
        String tenantId = stringValue(session.get("tenantId"), "default");
        return new LoginResponse(
                userId,
                username,
                role,
                tenantId,
                StpUtil.getTokenName(),
                StpUtil.getTokenValue());
    }

    /**
     * Creates a Sa-Token session from a trusted account record.
     */
    private static LoginResponse loginAccount(LocalAccount account) {
        StpUtil.login(account.userId());
        StpUtil.getSession().set("tenantId", account.tenantId());
        StpUtil.getSession().set("role", account.role());
        StpUtil.getSession().set("username", account.username());
        return new LoginResponse(
                account.userId(),
                account.username(),
                account.role(),
                account.tenantId(),
                StpUtil.getTokenName(),
                StpUtil.getTokenValue());
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }
}
