package com.doob.mathagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.agent.entity.AgentWorkerTaskEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** MyBatis gateway for durable distributed worker tasks. */
public interface AgentWorkerTaskMapper extends BaseMapper<AgentWorkerTaskEntity> {
    List<AgentWorkerTaskEntity> selectQueuedWithoutCurrentOutbox(
            @Param("olderThan") Instant olderThan, @Param("limit") int limit);
}
