package com.doob.mathagent.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.learning.entity.StudentLearningAttemptEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for answer facts. */
@Mapper
public interface StudentLearningAttemptMapper extends BaseMapper<StudentLearningAttemptEntity> { }
