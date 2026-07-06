package com.doob.mathagent.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.doob.mathagent.auth.entity.AuthAccountEntity;
import com.doob.mathagent.auth.mapper.AuthAccountMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * MySQL-backed account store used for deployable login and registration.
 */
@Repository
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
public class MyBatisLocalAccountStore implements LocalAccountStore {

    private static final String ACTIVE = "active";
    private final AuthAccountMapper mapper;

    public MyBatisLocalAccountStore(AuthAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<LocalAccount> findByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername.isBlank()) {
            return Optional.empty();
        }
        return mapper.selectList(new LambdaQueryWrapper<AuthAccountEntity>()
                .eq(AuthAccountEntity::getUsernameNormalized, normalizedUsername)
                .eq(AuthAccountEntity::getStatus, ACTIVE))
                .stream()
                .findFirst()
                .map(MyBatisLocalAccountStore::toAccount);
    }

    @Override
    public Optional<LocalAccount> findByUserId(String userId) {
        String normalizedUserId = textOrEmpty(userId);
        if (normalizedUserId.isBlank()) {
            return Optional.empty();
        }
        return mapper.selectList(new LambdaQueryWrapper<AuthAccountEntity>()
                .eq(AuthAccountEntity::getUserId, normalizedUserId)
                .eq(AuthAccountEntity::getStatus, ACTIVE))
                .stream()
                .findFirst()
                .map(MyBatisLocalAccountStore::toAccount);
    }

    @Override
    public LocalAccount createStudent(String username, String encodedPassword, String tenantId) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        AuthAccountEntity entity = new AuthAccountEntity();
        entity.setAccountId(UUID.randomUUID().toString());
        entity.setUserId("student-" + UUID.randomUUID());
        entity.setTenantId(textOrDefault(tenantId, "default"));
        entity.setUsername(username.strip());
        entity.setUsernameNormalized(normalizedUsername);
        entity.setPasswordHash(encodedPassword);
        entity.setRole("student");
        entity.setStatus(ACTIVE);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("Username already exists", exception);
        }
        return toAccount(entity);
    }

    private static LocalAccount toAccount(AuthAccountEntity entity) {
        return new LocalAccount(
                entity.getUserId(),
                entity.getUsername(),
                entity.getPasswordHash(),
                entity.getRole(),
                entity.getTenantId());
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase();
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.strip();
    }
}
