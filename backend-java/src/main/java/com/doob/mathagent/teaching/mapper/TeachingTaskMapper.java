package com.doob.mathagent.teaching.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for teaching_task.
 */
public interface TeachingTaskMapper extends BaseMapper<TeachingTaskEntity> {

    /**
     * Atomically acquires a new or retryable lecture task.
     *
     * <p>This SQL is intentionally explicit: the lease boundary depends on the exact grouping of the status OR
     * clauses, which is too important to delegate to a fluent-wrapper operator state machine.</p>
     */
    @Update("""
            UPDATE teaching_task
               SET status = 'RUNNING',
                   lease_owner = #{workerId},
                   lease_token = #{leaseToken},
                   lease_expire_at = #{leaseExpireAt},
                   started_at = #{startedAt},
                   finished_at = NULL,
                   last_error = NULL,
                   retry_count = retry_count + 1
             WHERE task_id = #{taskId}
               AND (
                    status IN ('CREATED', 'RETRYING')
                    OR (status = 'RUNNING' AND lease_expire_at < #{startedAt})
               )
            """)
    int tryAcquireLectureTask(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseExpireAt") Instant leaseExpireAt,
            @Param("startedAt") Instant startedAt);

    /** Starts a fresh retry budget after an explicit user resume and replaces the public workflow snapshot. */
    @Update("""
            UPDATE teaching_task
               SET status = 'RETRYING',
                   response_json = #{responseJson},
                   retry_count = 0,
                   lease_owner = NULL,
                   lease_token = NULL,
                   lease_expire_at = NULL,
                   current_stage = NULL,
                   last_error = NULL,
                   started_at = NULL,
                   finished_at = NULL,
                   updated_at = #{updatedAt}
             WHERE task_id = #{taskId}
               AND owner_key = #{ownerKey}
               AND status IN ('FAILED', 'RUNNING', 'COMPLETED')
            """)
    int prepareLectureTaskForResume(
            @Param("taskId") String taskId,
            @Param("ownerKey") String ownerKey,
            @Param("responseJson") String responseJson,
            @Param("updatedAt") Instant updatedAt);
}
