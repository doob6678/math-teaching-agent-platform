package com.doob.mathagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.dto.RegisterRequest;
import com.doob.mathagent.auth.service.AuthService;
import com.doob.mathagent.auth.service.LocalAccount;
import com.doob.mathagent.auth.service.LocalAccountStore;
import com.doob.mathagent.auth.service.PasswordHashService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    @Test
    void rejectsWrongPasswordBeforeCreatingSession() {
        AuthService service = new AuthService(store(), new PasswordHashService());

        assertThatThrownBy(() -> service.login(new LoginRequest("student", "wrong")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    void localAccountStoreFindsConfiguredUser() {
        Optional<LocalAccount> account = store().findByUsername("student");

        assertThat(account).isPresent();
        assertThat(account.orElseThrow().userId()).isEqualTo("student-001");
        assertThat(account.orElseThrow().role()).isEqualTo("student");
    }

    @Test
    void registersStudentWithHashedPasswordAndRejectsDuplicateUsername() {
        PasswordHashService passwordHashService = new PasswordHashService();
        CapturingStore store = new CapturingStore();
        AuthService service = new AuthService(store, passwordHashService);

        assertThatThrownBy(() -> service.register(new RegisterRequest("student", "student-123456", "school-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");

        try {
            service.register(new RegisterRequest("new-student", "student-123456", "school-a"));
        } catch (RuntimeException ignored) {
            // Unit tests do not bootstrap Sa-Token context; account creation is verified below.
        }

        LocalAccount created = store.accounts.get("new-student");
        assertThat(created).isNotNull();
        assertThat(created.role()).isEqualTo("student");
        assertThat(created.password()).isNotEqualTo("student-123456");
        assertThat(passwordHashService.matches("student-123456", created.password())).isTrue();
    }

    private static LocalAccountStore store() {
        return new CapturingStore();
    }

    /**
     * Test account store with duplicate checks.
     */
    private static final class CapturingStore implements LocalAccountStore {

        private final Map<String, LocalAccount> accounts = new ConcurrentHashMap<>();

        /**
         * Creates the test store with one existing student account.
         */
        private CapturingStore() {
            accounts.put("student", new LocalAccount("student-001", "student", "student-123456", "student", "school-a"));
        }

        @Override
        public Optional<LocalAccount> findByUsername(String username) {
            return Optional.ofNullable(accounts.get(username));
        }

        @Override
        public LocalAccount createStudent(String username, String encodedPassword, String tenantId) {
            LocalAccount account = new LocalAccount("student-002", username, encodedPassword, "student", tenantId);
            LocalAccount previous = accounts.putIfAbsent(username, account);
            if (previous != null) {
                throw new IllegalArgumentException("Username already exists");
            }
            return account;
        }
    }
}
