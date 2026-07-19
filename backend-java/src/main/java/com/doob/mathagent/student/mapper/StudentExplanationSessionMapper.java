package com.doob.mathagent.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentExplanationSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for explanation sessions.
 */
@Mapper
public interface StudentExplanationSessionMapper extends BaseMapper<StudentExplanationSessionEntity> {
}
