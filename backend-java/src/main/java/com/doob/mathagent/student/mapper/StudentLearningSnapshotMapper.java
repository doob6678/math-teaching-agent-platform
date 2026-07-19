package com.doob.mathagent.student.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.student.entity.StudentLearningSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for student learning snapshots.
 */
@Mapper
public interface StudentLearningSnapshotMapper extends BaseMapper<StudentLearningSnapshotEntity> {
}
