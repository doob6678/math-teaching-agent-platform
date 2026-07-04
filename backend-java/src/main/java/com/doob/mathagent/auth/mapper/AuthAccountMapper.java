package com.doob.mathagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.doob.mathagent.auth.entity.AuthAccountEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for persisted authentication accounts.
 */
@Mapper
public interface AuthAccountMapper extends BaseMapper<AuthAccountEntity> {
}
