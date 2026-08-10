package com.doob.mathagent.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.auth.entity.AuthAccountEntity;
import com.doob.mathagent.auth.mapper.AuthAccountMapper;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates the explicitly configured local browser-acceptance account after database migrations complete. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnProperty(prefix = "math-agent.local-acceptance-account", name = "enabled", havingValue = "true")
public final class LocalAcceptanceAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAcceptanceAccountBootstrap.class);
    private static final String ACTIVE = "active";
    private final AuthAccountMapper accountMapper;
    private final PasswordHashService passwordHashService;
    private final String username;
    private final String password;
    private final String role;
    private final String tenantId;

    public LocalAcceptanceAccountBootstrap(
            AuthAccountMapper accountMapper,
            PasswordHashService passwordHashService,
            @Value("${math-agent.local-acceptance-account.username:}") String username,
            @Value("${math-agent.local-acceptance-account.password:}") String password,
            @Value("${math-agent.local-acceptance-account.role:admin}") String role,
            @Value("${math-agent.local-acceptance-account.tenant-id:default}") String tenantId) {
        this.accountMapper = accountMapper;
        this.passwordHashService = passwordHashService;
        this.username = username == null ? "" : username.strip();
        this.password = password == null ? "" : password;
        this.role = role == null ? "" : role.strip().toLowerCase(Locale.ROOT);
        this.tenantId = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Local acceptance account username and password must be configured");
        }
        if (!"admin".equals(role) && !"teacher".equals(role) && !"student".equals(role)) {
            throw new IllegalStateException("Local acceptance account role must be admin, teacher, or student");
        }
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        AuthAccountEntity account = accountMapper.selectOne(new LambdaQueryWrapper<AuthAccountEntity>()
                .eq(AuthAccountEntity::getUsernameNormalized, normalizedUsername));
        if (account == null) {
            account = new AuthAccountEntity();
            account.setAccountId(UUID.randomUUID().toString());
            account.setUserId("local-acceptance-" + UUID.randomUUID());
            account.setUsername(username);
            account.setUsernameNormalized(normalizedUsername);
            account.setTenantId(tenantId);
            account.setRole(role);
            account.setStatus(ACTIVE);
            account.setPasswordHash(passwordHashService.encode(password));
            accountMapper.insert(account);
            log.info("local_acceptance_account_created username={} role={} tenantId={}", username, role, tenantId);
            return;
        }
        if (!passwordHashService.matches(password, account.getPasswordHash())) {
            account.setPasswordHash(passwordHashService.encode(password));
            accountMapper.updateById(account);
            log.info("local_acceptance_account_password_rotated username={} role={} tenantId={}", username,
                    account.getRole(), account.getTenantId());
        }
    }
}
