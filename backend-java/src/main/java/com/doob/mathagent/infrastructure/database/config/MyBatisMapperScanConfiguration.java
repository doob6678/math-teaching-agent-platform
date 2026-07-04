package com.doob.mathagent.infrastructure.database.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis mapper scanning for the required MySQL-backed stores.
 */
@Configuration
@MapperScan("com.doob.mathagent.**.mapper")
public class MyBatisMapperScanConfiguration {
}
