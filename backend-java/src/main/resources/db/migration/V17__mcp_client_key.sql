CREATE TABLE mcp_client_key (
    key_id CHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    owner_user_id VARCHAR(128) NOT NULL,
    owner_role VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    secret_hash VARCHAR(128) NOT NULL,
    secret_preview VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    last_used_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (key_id),
    UNIQUE KEY uk_mcp_client_key_secret_hash (secret_hash),
    KEY idx_mcp_client_key_owner (tenant_id, owner_user_id, status, created_at),
    KEY idx_mcp_client_key_role (tenant_id, owner_role, status),
    KEY idx_mcp_client_key_last_used (tenant_id, owner_user_id, last_used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
