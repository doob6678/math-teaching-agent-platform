package com.doob.mathagent.teacher.config;

import com.doob.mathagent.teacher.service.TeacherFeishuDiscoveryClient;
import com.doob.mathagent.teacher.service.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.service.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.service.UnconfiguredTeacherFeishuDiscoveryClient;
import com.doob.mathagent.teacher.service.UnconfiguredTeacherFeishuDownloadClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Teacher source synchronization configuration.
 */
@Configuration
public class TeacherSourceSyncConfiguration {

    /**
     * Creates teacher source sync properties from Spring configuration.
     *
     * @param environment Spring environment
     * @return teacher source sync properties
     */
    @Bean
    TeacherSourceSyncProperties teacherSourceSyncProperties(Environment environment) {
        return TeacherSourceSyncProperties.fromSpringEnvironment(environment);
    }

    /**
     * Creates a fail-fast Feishu downloader when the process-backed downloader is disabled.
     *
     * @return unconfigured downloader that never reports fake success
     */
    @Bean
    @ConditionalOnMissingBean(TeacherFeishuDownloadClient.class)
    TeacherFeishuDownloadClient unconfiguredTeacherFeishuDownloadClient() {
        return new UnconfiguredTeacherFeishuDownloadClient();
    }

    /**
     * Creates a fail-fast Feishu discovery client when the process-backed client is disabled.
     *
     * @return unconfigured discovery client that never reports fake candidates
     */
    @Bean
    @ConditionalOnMissingBean(TeacherFeishuDiscoveryClient.class)
    TeacherFeishuDiscoveryClient unconfiguredTeacherFeishuDiscoveryClient() {
        return new UnconfiguredTeacherFeishuDiscoveryClient();
    }
}
