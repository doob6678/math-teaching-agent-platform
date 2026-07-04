package com.doob.mathagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.auth.service.CompositeLocalAccountStore;
import com.doob.mathagent.auth.service.LocalAccount;
import com.doob.mathagent.auth.service.MyBatisLocalAccountStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompositeLocalAccountStoreTest {

    @Test
    void delegatesLookupToPersistentStoreOnly() {
        CompositeLocalAccountStore store = new CompositeLocalAccountStore(emptyPersistentStore());

        Optional<LocalAccount> account = store.findByUsername("student");

        assertThat(account).isEmpty();
    }

    @Test
    void delegatesStudentCreationToPersistentStoreOnly() {
        CompositeLocalAccountStore store = new CompositeLocalAccountStore(emptyPersistentStore());

        LocalAccount account = store.createStudent("student", "hash", "school-a");

        assertThat(account.userId()).isEqualTo("student-db");
        assertThat(account.password()).isEqualTo("hash");
    }

    private static MyBatisLocalAccountStore emptyPersistentStore() {
        return new MyBatisLocalAccountStore(null) {
            @Override
            public Optional<LocalAccount> findByUsername(String username) {
                return Optional.empty();
            }

            @Override
            public LocalAccount createStudent(String username, String encodedPassword, String tenantId) {
                return new LocalAccount("student-db", username, encodedPassword, "student", tenantId);
            }
        };
    }
}
