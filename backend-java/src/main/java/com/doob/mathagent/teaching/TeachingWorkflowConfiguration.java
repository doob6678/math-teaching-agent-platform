package com.doob.mathagent.teaching;

import com.doob.mathagent.resources.TextbookResourceProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 教学编排配置，把教材根目录显式注入为 Path，避免服务层直接读取环境变量。
 */
@Configuration
public class TeachingWorkflowConfiguration {

    /**
     * 教学编排使用的教材 processed_books 根目录。
     */
    @Bean
    Path teachingProcessedBooksRoot(TextbookResourceProperties properties) {
        return properties.processedBooksRoot();
    }
}
