package com.doob.mathagent.teacher.config;

import com.doob.mathagent.teacher.sync.TeacherSourceSyncProperties;
import com.doob.mathagent.teacher.sync.TeacherSourceSyncSchedulerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Teacher source synchronization configuration.
 */
@Configuration
@EnableScheduling
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

    /** Scheduler settings are separate from downloader paths so enabling automation requires an explicit authority. */
    @Bean
    TeacherSourceSyncSchedulerProperties teacherSourceSyncSchedulerProperties(Environment environment) {
        return TeacherSourceSyncSchedulerProperties.fromSpringEnvironment(environment);
    }

}
