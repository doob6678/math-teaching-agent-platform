package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.config.TeacherResourceSearchAuditConfiguration;
import com.doob.mathagent.teacher.search.audit.CompositeTeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.search.audit.CompositeTeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.search.audit.MyBatisTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditSink;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TeacherResourceSearchAuditConfigurationTest {

    @Test
    void refusesToExposeRecentOnlyAuditWhenDatabaseStoreIsUnavailable() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TeacherResourceSearchAuditConfiguration.class);

            assertThatThrownBy(context::refresh)
                    .hasMessageContaining("MyBatisTeacherResourceBlockSearchAuditStore");
        }
    }

    @Test
    void exposesPrimaryPersistentSinkAndLookupWhenDatabaseStoreIsAvailable() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getSystemProperties().put("math-agent.database.enabled", "true");
            context.register(TeacherResourceSearchAuditConfiguration.class);
            context.registerBean(MyBatisTeacherResourceBlockSearchAuditStore.class,
                    () -> new MyBatisTeacherResourceBlockSearchAuditStore(null, null));
            context.refresh();

            TeacherResourceBlockSearchAuditSink sink = context.getBean(TeacherResourceBlockSearchAuditSink.class);
            TeacherResourceBlockSearchAuditLookup lookup = context.getBean(TeacherResourceBlockSearchAuditLookup.class);

            assertThat(sink).isInstanceOf(CompositeTeacherResourceBlockSearchAuditSink.class);
            assertThat(lookup).isInstanceOf(CompositeTeacherResourceBlockSearchAuditLookup.class);
        }
    }
}

