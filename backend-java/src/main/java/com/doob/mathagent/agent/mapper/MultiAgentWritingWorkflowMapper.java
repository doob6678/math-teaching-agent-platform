package com.doob.mathagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.agent.entity.MultiAgentWritingWorkflowEntity;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for multi_agent_writing_workflow rows.
 */
public interface MultiAgentWritingWorkflowMapper extends BaseMapper<MultiAgentWritingWorkflowEntity> {

    /** Atomically replaces a snapshot only when the caller read the current revision. */
    int updateIfRevisionMatches(
            @Param("entity") MultiAgentWritingWorkflowEntity entity,
            @Param("expectedRevision") long expectedRevision);
}
