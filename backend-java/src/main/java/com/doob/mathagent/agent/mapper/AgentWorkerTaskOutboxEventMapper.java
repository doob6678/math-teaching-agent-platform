package com.doob.mathagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.agent.entity.AgentWorkerTaskOutboxEventEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for durable Agent Worker dispatch events. */
public interface AgentWorkerTaskOutboxEventMapper extends BaseMapper<AgentWorkerTaskOutboxEventEntity> {
    List<AgentWorkerTaskOutboxEventEntity> selectReadyPending(@Param("now") Instant now, @Param("limit") int limit);
    AgentWorkerTaskOutboxEventEntity selectOldestUnpublished();
}