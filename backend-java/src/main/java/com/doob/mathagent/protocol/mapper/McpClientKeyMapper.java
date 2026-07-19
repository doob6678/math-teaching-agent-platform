package com.doob.mathagent.protocol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.protocol.entity.McpClientKeyEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for persisted MCP client keys.
 */
@Mapper
public interface McpClientKeyMapper extends BaseMapper<McpClientKeyEntity> {
}
