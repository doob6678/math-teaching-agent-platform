package com.doob.mathagent.teaching.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import java.time.Instant;
import org.apache.ibatis.annotations.Param;

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
    int tryAcquireLectureTask(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseExpireAt") Instant leaseExpireAt,
            @Param("startedAt") Instant startedAt);

    /** Atomically identifies a bounded set of expired top-level workflow leases. */
    java.util.List<com.doob.mathagent.teaching.entity.TeachingTaskEntity> findExpiredLectureLeases(
            @Param("now") Instant now,
            @Param("limit") int limit);

    /** Clears one expired lease only if no concurrent heartbeat or completion changed it. */
    int reclaimExpiredLectureTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("now") Instant now);

    /** Starts a fresh retry budget after an explicit user resume and replaces the public workflow snapshot. */
    int prepareLectureTaskForResume(
            @Param("taskId") String taskId,
            @Param("ownerKey") String ownerKey,
            @Param("responseJson") String responseJson,
            @Param("updatedAt") Instant updatedAt);

    /** 检查令牌仍对应 RUNNING 行，供外部 Writer 调用前进行围栏验证。 */
    int countOwnedRunningLectureTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken);

    /** 在不改变执行列的情况下保存当前拥有者的进度快照。 */
    int saveOwnedRunningLectureTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("responseJson") String responseJson,
            @Param("currentStage") String currentStage,
            @Param("updatedAt") Instant updatedAt);

    /** 最终快照、COMPLETED 状态和清租约必须在同一条围栏更新中完成。 */
    int completeOwnedLectureTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("responseJson") String responseJson,
            @Param("currentStage") String currentStage,
            @Param("finishedAt") Instant finishedAt);

    /** 当前拥有者将可见失败快照与 RETRYING/FAILED 状态原子落盘。 */
    int failOwnedLectureTask(
            @Param("taskId") String taskId,
            @Param("leaseToken") String leaseToken,
            @Param("responseJson") String responseJson,
            @Param("status") String status,
            @Param("error") String error,
            @Param("finishedAt") Instant finishedAt,
            @Param("updatedAt") Instant updatedAt);
}
