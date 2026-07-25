package com.doob.mathagent.feishu;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
@Mapper public interface FeishuResourceBindingMapper extends BaseMapper<FeishuResourceBindingEntity> { @Select("SELECT * FROM feishu_resource_binding WHERE tenant_id=#{tenantId} AND document_id=#{documentId} LIMIT 1") FeishuResourceBindingEntity find(String tenantId,String documentId); }
