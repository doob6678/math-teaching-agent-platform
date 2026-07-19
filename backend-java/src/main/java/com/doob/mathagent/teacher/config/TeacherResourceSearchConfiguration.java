package com.doob.mathagent.teacher.config;

import com.doob.mathagent.teacher.search.TeacherResourceSearchProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Teacher resource retrieval configuration.
 */
@Configuration
public class TeacherResourceSearchConfiguration {

    /**
     * Creates teacher resource search properties from Spring environment so rerank budgets and query-focus limits are
     * configured centrally instead of living as class-local constants inside the retriever.
     */
    @Bean
    TeacherResourceSearchProperties teacherResourceSearchProperties(Environment environment) {
        return TeacherResourceSearchProperties.fromSpringEnvironment(environment);
    }
}
