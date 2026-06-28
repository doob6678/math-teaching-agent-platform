package com.doob.mathagent.infrastructure.database.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Conditional MyBatis mapper scanning.
 *
 * <p>Mapper interfaces require a SqlSessionFactory, so scanning is enabled only when the application database switch is
 * enabled and a DataSource can be created.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "math-agent.database", name = "enabled", havingValue = "true")
@MapperScan("com.doob.mathagent.**.mapper")
public class MyBatisMapperScanConfiguration {
}
