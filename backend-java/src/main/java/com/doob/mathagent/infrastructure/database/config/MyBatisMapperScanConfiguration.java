package com.doob.mathagent.infrastructure.database.config;

import com.doob.mathagent.feishu.FeishuDriveClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * MyBatis mapper scanning for the required MySQL-backed stores.
 */
@Configuration
// The feishu mappers live directly in com.doob.mathagent.feishu (no .mapper sub-package), so that whole package
// is scanned. ClassPathMapperScanner registers EVERY interface it finds, which turned the non-mapper
// FeishuDriveClient SPI into a phantom "feishuDriveClient" mapper bean; FeishuTenantLibraryService then failed
// startup with a two-candidate injection error against the real HttpFeishuDriveClient. Excluding the SPI by type
// keeps the package layout stable; any future non-mapper interface added under feishu must be excluded here too
// or moved to a dedicated package.
@MapperScan(
        basePackages = {"com.doob.mathagent.**.mapper", "com.doob.mathagent.feishu"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = FeishuDriveClient.class))
public class MyBatisMapperScanConfiguration {
}
