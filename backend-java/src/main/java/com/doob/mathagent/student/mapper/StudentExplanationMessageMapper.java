package com.doob.mathagent.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentExplanationMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis mapper for explanation messages.
 */
@Mapper
public interface StudentExplanationMessageMapper extends BaseMapper<StudentExplanationMessageEntity> {
}
