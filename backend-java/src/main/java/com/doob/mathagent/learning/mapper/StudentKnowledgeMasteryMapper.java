package com.doob.mathagent.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.learning.entity.StudentKnowledgeMasteryEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for the mastery projection. */
@Mapper
public interface StudentKnowledgeMasteryMapper extends BaseMapper<StudentKnowledgeMasteryEntity> { }
