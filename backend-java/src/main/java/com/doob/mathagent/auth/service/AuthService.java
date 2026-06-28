package com.doob.mathagent.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.vo.LoginResponse;
import org.springframework.stereotype.Service;

/**
 * Authentication service that creates Sa-Token sessions from backend account data.
 */
@Service
public class AuthService {

    private final LocalAccountStore accountStore;

    /**
     * Creates an auth service.
     *
     * @param accountStore account lookup store
     */
    public AuthService(LocalAccountStore accountStore) {
        this.accountStore = accountStore;
    }

    /**
     * Logs in a local account and stores trusted identity fields in the backend session.
     *
     * @param request login request
     * @return login response with token data
     */
    public LoginResponse login(LoginRequest request) {
        LocalAccount account = accountStore.findByUsername(request.username())
                .filter(found -> found.password().equals(request.password()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        StpUtil.login(account.userId());
        StpUtil.getSession().set("tenantId", account.tenantId());
        StpUtil.getSession().set("role", account.role());
        return new LoginResponse(
                account.userId(),
                account.username(),
                account.role(),
                account.tenantId(),
                StpUtil.getTokenName(),
                StpUtil.getTokenValue());
    }
}
