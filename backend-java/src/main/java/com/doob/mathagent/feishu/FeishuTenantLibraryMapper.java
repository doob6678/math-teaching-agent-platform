package com.doob.mathagent.feishu;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FeishuTenantLibraryMapper extends BaseMapper<FeishuTenantLibraryEntity> {
    @Select("SELECT * FROM feishu_tenant_library WHERE tenant_id=#{tenantId} LIMIT 1")
    FeishuTenantLibraryEntity findByTenant(String tenantId);
}
