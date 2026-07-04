package com.doob.mathagent.auth.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus entity for deployable authentication accounts.
 */
@TableName("auth_account")
public class AuthAccountEntity {

    /** Database account id. */
    @TableId
    private String accountId;

    /** Stable backend user id stored in Sa-Token login state. */
    private String userId;

    /** Tenant id used for data isolation. */
    private String tenantId;

    /** Display/login username as entered after trimming. */
    private String username;

    /** Lowercase normalized username used for unique login lookup. */
    private String usernameNormalized;

    /** Encoded password hash; plaintext passwords are never stored. */
    private String passwordHash;

    /** Backend role, for public registration this is always student. */
    private String role;

    /** Account lifecycle status. */
    private String status;

    /** Database creation time. */
    private LocalDateTime createdAt;

    /** Database update time. */
    private LocalDateTime updatedAt;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsernameNormalized() {
        return usernameNormalized;
    }

    public void setUsernameNormalized(String usernameNormalized) {
        this.usernameNormalized = usernameNormalized;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
