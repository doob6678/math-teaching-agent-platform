package com.doob.mathagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.dto.RegisterRequest;
import com.doob.mathagent.auth.dto.TeacherAccountProvisionRequest;
import com.doob.mathagent.auth.service.AuthService;
import com.doob.mathagent.auth.service.LocalAccount;
import com.doob.mathagent.auth.service.LocalAccountStore;
import com.doob.mathagent.auth.service.PasswordHashService;
import com.doob.mathagent.auth.vo.LoginResponse;
import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void loginResponseContainsOnlyNonSensitiveSessionMetadata() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                new LoginResponse("student-001", "student", "student", "school-a"));

        assertThat(json).contains("student-001", "student", "school-a");
        assertThat(json).doesNotContain("tokenName", "tokenValue", "satoken");
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

        assertThatThrownBy(() -> service.register(new RegisterRequest("student", "student-123456")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username already exists");

        try {
            service.register(new RegisterRequest("new-student", "student-123456"));
        } catch (RuntimeException ignored) {
            // Unit tests do not bootstrap Sa-Token context; account creation is verified below.
        }

        LocalAccount created = store.accounts.get("new-student");
        assertThat(created).isNotNull();
        assertThat(created.role()).isEqualTo("student");
        assertThat(created.tenantId()).isEqualTo("default");
        assertThat(created.password()).isNotEqualTo("student-123456");
        assertThat(passwordHashService.matches("student-123456", created.password())).isTrue();
    }

    @Test
    void adminProvisionsTeacherIntoItsOwnTenantWithoutAcceptingRequestedTenant() {
        PasswordHashService passwordHashService = new PasswordHashService();
        CapturingStore store = new CapturingStore();
        AuthService service = new AuthService(store, passwordHashService);

        LocalAccount teacher = service.provisionTeacher(
                new TeacherAccountProvisionRequest("math-teacher", "teacher-123456"),
                new RequestSubject("school-a", "admin", "admin-001", "device-1"));

        assertThat(teacher.role()).isEqualTo("teacher");
        assertThat(teacher.tenantId()).isEqualTo("school-a");
        assertThat(passwordHashService.matches("teacher-123456", teacher.password())).isTrue();
    }

    @Test
    void rejectsTeacherProvisionByNonAdminSubject() {
        AuthService service = new AuthService(new CapturingStore(), new PasswordHashService());

        assertThatThrownBy(() -> service.provisionTeacher(
                new TeacherAccountProvisionRequest("math-teacher", "teacher-123456"),
                new RequestSubject("school-a", "teacher", "teacher-001", "device-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("administrator");
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
        public Optional<LocalAccount> findByUserId(String userId) {
            return accounts.values().stream()
                    .filter(account -> account.userId().equals(userId))
                    .findFirst();
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

        @Override
        public LocalAccount createTeacher(String username, String encodedPassword, String tenantId) {
            LocalAccount account = new LocalAccount("teacher-002", username, encodedPassword, "teacher", tenantId);
            LocalAccount previous = accounts.putIfAbsent(username, account);
            if (previous != null) {
                throw new IllegalArgumentException("Username already exists");
            }
            return account;
        }
    }
}
