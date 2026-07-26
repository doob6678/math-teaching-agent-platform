CREATE TABLE student_learning_snapshot (
    snapshot_id CHAR(36) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    student_id VARCHAR(128) NOT NULL,
    grade_name VARCHAR(64) NULL,
    knowledge_progress_json JSON NOT NULL,
    knowledge_graph_json JSON NOT NULL,
    weak_points_json JSON NOT NULL,
    recent_questions_json JSON NOT NULL,
    score_trend_json JSON NOT NULL,
    resource_scopes_json JSON NOT NULL,
    source_summary VARCHAR(255) NOT NULL DEFAULT 'dashboard_progress',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_student_snapshot_tenant_student (tenant_id, student_id, updated_at),
    KEY idx_student_snapshot_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
