package com.doob.mathagent.teacher.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.teacher.sync.MyBatisTeacherSourceSyncManifestStore.TeacherSourceSyncManifestEntity;
import org.apache.ibatis.annotations.Mapper;

/** MyBatis mapper for durable per-file Feishu sync state. */
@Mapper
public interface TeacherSourceSyncManifestMapper extends BaseMapper<TeacherSourceSyncManifestEntity> {
}
