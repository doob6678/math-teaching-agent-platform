package com.doob.mathagent.feishu;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeishuHandoutUploadMapper extends BaseMapper<FeishuHandoutUploadEntity> {
    @Select("SELECT * FROM feishu_handout_upload WHERE tenant_id=#{tenantId} AND task_id=#{taskId} AND version=#{version} LIMIT 1")
    FeishuHandoutUploadEntity find(String tenantId, String taskId, String version);
}
