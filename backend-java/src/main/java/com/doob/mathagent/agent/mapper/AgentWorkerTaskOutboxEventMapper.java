package com.doob.mathagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.agent.entity.AgentWorkerTaskOutboxEventEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for durable Agent Worker dispatch events. */
public interface AgentWorkerTaskOutboxEventMapper extends BaseMapper<AgentWorkerTaskOutboxEventEntity> {
    /**
     * Claims up to {@code limit} ready PENDING rows in one statement by stamping them PUBLISHING with the
     * claimant id. The {@code status = 'PENDING'} predicate keeps CAS semantics: a row already claimed by a
     * concurrent publisher does not match, so two claimants can never both count it.
     */
    int claimBatchPending(@Param("publisherId") String publisherId, @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil, @Param("limit") int limit);

    /** Reads back the rows whose current claim belongs to this exact publisher id. */
    List<AgentWorkerTaskOutboxEventEntity> selectClaimed(@Param("publisherId") String publisherId);

    AgentWorkerTaskOutboxEventEntity selectOldestUnpublished();
}