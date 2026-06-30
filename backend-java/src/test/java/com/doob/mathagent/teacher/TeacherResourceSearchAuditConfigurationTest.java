package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.config.TeacherResourceSearchAuditConfiguration;
import com.doob.mathagent.teacher.service.CompositeTeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.CompositeTeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditSink;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TeacherResourceSearchAuditConfigurationTest {

    @Test
    void exposesPrimaryRecentSinkAndLookupWhenDatabaseStoreIsUnavailable() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TeacherResourceSearchAuditConfiguration.class);
            context.refresh();

            TeacherResourceBlockSearchAuditSink sink = context.getBean(TeacherResourceBlockSearchAuditSink.class);
            TeacherResourceBlockSearchAuditLookup lookup = context.getBean(TeacherResourceBlockSearchAuditLookup.class);

            assertThat(sink).isInstanceOf(CompositeTeacherResourceBlockSearchAuditSink.class);
            assertThat(lookup).isInstanceOf(CompositeTeacherResourceBlockSearchAuditLookup.class);
        }
    }
}
