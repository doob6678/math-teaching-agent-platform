package com.doob.mathagent.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentExplanationWorkflowEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for append-only public student explanation workflow events. */
@Mapper
public interface StudentExplanationWorkflowEventMapper extends BaseMapper<StudentExplanationWorkflowEventEntity> {
}
