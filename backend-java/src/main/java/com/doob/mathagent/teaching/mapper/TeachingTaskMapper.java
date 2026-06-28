package com.doob.mathagent.teaching.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teaching.entity.TeachingTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for teaching tasks.
 *
 * <p>The mapper is intentionally thin: BaseMapper provides CRUD operations, while service-layer code owns tenant
 * isolation, idempotency, and task recovery rules.</p>
 */
@Mapper
public interface TeachingTaskMapper extends BaseMapper<TeachingTaskEntity> {
}
