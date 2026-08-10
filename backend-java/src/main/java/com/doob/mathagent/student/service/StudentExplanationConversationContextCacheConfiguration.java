package com.doob.mathagent.student.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StudentExplanationConversationContextCacheProperties.class)
public class StudentExplanationConversationContextCacheConfiguration {
}
