package com.doob.mathagent.retrieval;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires deployment-owned textbook retrieval budgets into the real search service. */
@Configuration
public class TextbookRetrievalConfiguration {

    @Bean
    TextbookRetrievalProperties textbookRetrievalProperties(Environment environment) {
        return TextbookRetrievalProperties.fromSpringEnvironment(environment);
    }
}
