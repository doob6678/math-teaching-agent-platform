package com.doob.mathagent.teaching;

/**
 * 教学任务状态，用于前端离开页面后继续查询任务进度和结果。
 */
public enum TeachingTaskStatus {
    /** 任务已创建但尚未执行。 */
    CREATED,
    /** 任务正在执行 DAG 节点。 */
    RUNNING,
    /** 当前租约已释放并等待下一次有界重试，不能视为终态失败。 */
    RETRYING,
    /** Automatic checks passed and a teacher or administrator must decide whether to publish. */
    WAITING_REVIEW,
    /** A durable non-publishable draft was requested and remains available for later editing. */
    DRAFT_ONLY,
    /** 任务已完成，结果可被重复读取。 */
    COMPLETED,
    /** 任务执行失败，前端可展示错误并按 taskId 追踪。 */
    FAILED
}
