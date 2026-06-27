package com.doob.mathagent.resources;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TextbookResourceConfiguration {

    @Bean
    TextbookResourceProperties textbookResourceProperties() {
        return TextbookResourceProperties.fromEnvironment();
    }
}
