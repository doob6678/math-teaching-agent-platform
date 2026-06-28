package com.doob.mathagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.auth.dto.LoginRequest;
import com.doob.mathagent.auth.service.AuthService;
import com.doob.mathagent.auth.service.LocalAccount;
import com.doob.mathagent.auth.service.LocalAccountStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    @Test
    void rejectsWrongPasswordBeforeCreatingSession() {
        AuthService service = new AuthService(store());

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

    private static LocalAccountStore store() {
        return username -> "student".equals(username)
                ? Optional.of(new LocalAccount("student-001", "student", "student-123456", "student", "school-a"))
                : Optional.empty();
    }
}
