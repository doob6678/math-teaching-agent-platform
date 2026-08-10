package com.doob.mathagent.auth.service;

import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * Primary account store that requires persistent MySQL accounts.
 */
@Repository
@Primary
public class CompositeLocalAccountStore implements LocalAccountStore {

    private final MyBatisLocalAccountStore persistentStore;

    public CompositeLocalAccountStore(MyBatisLocalAccountStore persistentStore) {
        this.persistentStore = persistentStore;
    }

    @Override
    public Optional<LocalAccount> findByUsername(String username) {
        return persistentStore.findByUsername(username);
    }

    @Override
    public Optional<LocalAccount> findByUserId(String userId) {
        return persistentStore.findByUserId(userId);
    }

    @Override
    public LocalAccount createStudent(String username, String encodedPassword, String tenantId) {
        return persistentStore.createStudent(username, encodedPassword, tenantId);
    }

    @Override
    public LocalAccount createTeacher(String username, String encodedPassword, String tenantId) {
        return persistentStore.createTeacher(username, encodedPassword, tenantId);
    }
}
