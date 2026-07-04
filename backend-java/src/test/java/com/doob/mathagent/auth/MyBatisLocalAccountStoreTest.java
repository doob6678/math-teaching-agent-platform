package com.doob.mathagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.doob.mathagent.auth.entity.AuthAccountEntity;
import com.doob.mathagent.auth.mapper.AuthAccountMapper;
import com.doob.mathagent.auth.service.LocalAccount;
import com.doob.mathagent.auth.service.MyBatisLocalAccountStore;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class MyBatisLocalAccountStoreTest {

    @Test
    void findsActiveAccountByNormalizedUsername() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.rows.add(entity("teacher-1", "Teacher", "teacher", "hash-1", "teacher", "active"));
        MyBatisLocalAccountStore store = new MyBatisLocalAccountStore(mapper.proxy());

        LocalAccount account = store.findByUsername(" TEACHER ").orElseThrow();

        assertThat(account.userId()).isEqualTo("teacher-1");
        assertThat(account.username()).isEqualTo("Teacher");
        assertThat(account.password()).isEqualTo("hash-1");
        assertThat(account.role()).isEqualTo("teacher");
    }

    @Test
    void createsStudentAccountWithHashAndNormalizedUsername() {
        CapturingMapper mapper = new CapturingMapper();
        MyBatisLocalAccountStore store = new MyBatisLocalAccountStore(mapper.proxy());

        LocalAccount account = store.createStudent(" NewStudent ", "pbkdf2_hash", "school-a");

        assertThat(account.role()).isEqualTo("student");
        assertThat(account.tenantId()).isEqualTo("school-a");
        assertThat(account.password()).isEqualTo("pbkdf2_hash");
        assertThat(mapper.inserted).hasSize(1);
        AuthAccountEntity inserted = mapper.inserted.getFirst();
        assertThat(inserted.getUsername()).isEqualTo("NewStudent");
        assertThat(inserted.getUsernameNormalized()).isEqualTo("newstudent");
        assertThat(inserted.getPasswordHash()).isEqualTo("pbkdf2_hash");
        assertThat(inserted.getRole()).isEqualTo("student");
        assertThat(inserted.getStatus()).isEqualTo("active");
    }

    @Test
    void convertsDuplicateUsernameToBusinessError() {
        CapturingMapper mapper = new CapturingMapper();
        mapper.duplicateOnInsert = true;
        MyBatisLocalAccountStore store = new MyBatisLocalAccountStore(mapper.proxy());

        assertThatThrownBy(() -> store.createStudent("student", "hash", "default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");
    }

    private static AuthAccountEntity entity(
            String userId,
            String username,
            String usernameNormalized,
            String passwordHash,
            String role,
            String status) {
        AuthAccountEntity entity = new AuthAccountEntity();
        entity.setAccountId("account-" + userId);
        entity.setUserId(userId);
        entity.setTenantId("default");
        entity.setUsername(username);
        entity.setUsernameNormalized(usernameNormalized);
        entity.setPasswordHash(passwordHash);
        entity.setRole(role);
        entity.setStatus(status);
        return entity;
    }

    private static class CapturingMapper {
        private final List<AuthAccountEntity> rows = new ArrayList<>();
        private final List<AuthAccountEntity> inserted = new ArrayList<>();
        private boolean duplicateOnInsert;

        AuthAccountMapper proxy() {
            return (AuthAccountMapper) Proxy.newProxyInstance(
                    AuthAccountMapper.class.getClassLoader(),
                    new Class<?>[] {AuthAccountMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "selectList" -> selectList((Wrapper<AuthAccountEntity>) args[0]);
                        case "insert" -> {
                            if (duplicateOnInsert) {
                                throw new DuplicateKeyException("duplicate");
                            }
                            inserted.add((AuthAccountEntity) args[0]);
                            yield 1;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private List<AuthAccountEntity> selectList(Wrapper<AuthAccountEntity> ignored) {
            return rows.stream()
                    .filter(row -> "teacher".equals(row.getUsernameNormalized()))
                    .filter(row -> "active".equals(row.getStatus()))
                    .toList();
        }
    }
}
