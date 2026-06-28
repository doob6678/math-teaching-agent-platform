package com.doob.mathagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Backend entry point.
 *
 * <p>Database and Redis integrations are conditionally configured so local no-database development can still start.</p>
 */
@SpringBootApplication(
        exclude = DataSourceAutoConfiguration.class,
        excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
public class MathAgentApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments passed by the runtime
     */
    public static void main(String[] args) {
        SpringApplication.run(MathAgentApplication.class, args);
    }
}
