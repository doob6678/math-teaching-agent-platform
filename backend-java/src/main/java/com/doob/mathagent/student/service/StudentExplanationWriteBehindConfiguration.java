package com.doob.mathagent.student.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the write-behind buffer settings; follows the conversation-context cache configuration pattern. */
@Configuration
@EnableConfigurationProperties(StudentExplanationWriteBehindProperties.class)
public class StudentExplanationWriteBehindConfiguration {
}
