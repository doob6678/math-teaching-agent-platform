package com.doob.mathagent.teacher.formula;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables explicit AI formula-recognition limits without creating a separate persistence system. */
@Configuration
@EnableConfigurationProperties(TeacherFormulaRecognitionProperties.class)
public class TeacherFormulaRecognitionConfiguration {
}
