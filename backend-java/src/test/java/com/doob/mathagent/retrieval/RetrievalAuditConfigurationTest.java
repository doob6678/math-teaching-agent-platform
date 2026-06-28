package com.doob.mathagent.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RetrievalAuditConfigurationTest {

    @Test
    void exposesSinglePrimaryRetrievalAuditSinkForServiceInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RetrievalAuditConfiguration.class);
            context.refresh();

            RetrievalAuditSink sink = context.getBean(RetrievalAuditSink.class);

            assertThat(sink).isInstanceOf(RecentRetrievalAuditStore.class);
        }
    }
}
