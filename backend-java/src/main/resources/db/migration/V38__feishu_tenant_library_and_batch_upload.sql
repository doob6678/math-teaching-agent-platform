-- 2026-08-31 飞书按租户自动建库 + 讲义批量上传（此前只有只读 OAuth 发现/下载链路）。
-- 租户 → 机器人云空间根文件夹的持久映射：建库必须幂等，进程重启后不得重复建同名文件夹。
CREATE TABLE IF NOT EXISTS feishu_tenant_library (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL COMMENT '后端租户标识（RequestSubject.tenantId），不是飞书 open tenant_key',
    folder_name VARCHAR(256) NOT NULL COMMENT '实际创建的租户文件夹名，展示用并支持人工核对',
    root_folder_token VARCHAR(128) NOT NULL COMMENT '飞书 drive 文件夹 token（file_token of type=folder）',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uq_feishu_tenant_library (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 批量上传幂等台账：同租户同任务同版本且内容哈希一致时跳过重复上传。
CREATE TABLE IF NOT EXISTS feishu_handout_upload (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    subject_id VARCHAR(64) NOT NULL COMMENT '触发上传的教师/管理员主体，审计归属',
    task_id VARCHAR(128) NOT NULL,
    version VARCHAR(16) NOT NULL COMMENT 'teacher | student | lecture',
    content_hash CHAR(64) NOT NULL COMMENT 'PDF 字节 SHA-256，重编译改内容才会重新上传',
    file_name VARCHAR(512) NOT NULL,
    file_token VARCHAR(128) NULL COMMENT '飞书文件 token；FAILED 行为 NULL',
    status VARCHAR(16) NOT NULL COMMENT 'UPLOADED | SKIPPED | FAILED',
    message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uq_feishu_handout_upload (tenant_id, task_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
