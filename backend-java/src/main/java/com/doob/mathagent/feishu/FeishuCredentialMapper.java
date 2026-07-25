package com.doob.mathagent.feishu;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeishuCredentialMapper extends BaseMapper<FeishuCredentialEntity> {
    @Select("SELECT * FROM feishu_user_credential WHERE tenant_id=#{tenantId} AND subject_id=#{subjectId} AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 1")
    FeishuCredentialEntity findActive(String tenantId, String subjectId);
    @Select("SELECT * FROM feishu_user_credential WHERE credential_id=#{credentialId} AND tenant_id=#{tenantId} AND status='ACTIVE' LIMIT 1")
    FeishuCredentialEntity findActiveById(String tenantId, String credentialId);
}
