package com.doob.mathagent.teacher.config;

import com.doob.mathagent.teacher.service.CompositeTeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.CompositeTeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.service.MyBatisTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.service.RecentTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditSink;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Teacher resource search audit wiring.
 */
@Configuration
public class TeacherResourceSearchAuditConfiguration {

    /**
     * Provides the bounded recent audit store used for fast queryId lookup and fallback.
     *
     * @return recent in-memory audit store
     */
    @Bean
    public RecentTeacherResourceBlockSearchAuditStore recentTeacherResourceBlockSearchAuditStore() {
        return new RecentTeacherResourceBlockSearchAuditStore(200);
    }

    /**
     * Provides the primary audit sink. When MySQL is enabled it writes both recent and persistent logs.
     *
     * @param recentAuditStore in-memory recent store
     * @param myBatisStore optional MyBatis-backed store
     * @return primary audit sink
     */
    @Bean
    @Primary
    public TeacherResourceBlockSearchAuditSink teacherResourceBlockSearchAuditSink(
            RecentTeacherResourceBlockSearchAuditStore recentAuditStore,
            ObjectProvider<MyBatisTeacherResourceBlockSearchAuditStore> myBatisStore) {
        MyBatisTeacherResourceBlockSearchAuditStore persistentStore = myBatisStore.getIfAvailable();
        if (persistentStore == null) {
            return new CompositeTeacherResourceBlockSearchAuditSink(List.of(recentAuditStore));
        }
        return new CompositeTeacherResourceBlockSearchAuditSink(List.of(recentAuditStore, persistentStore));
    }

    /**
     * Provides the primary audit lookup. Persistent logs are preferred, recent logs are fallback.
     *
     * @param recentAuditStore in-memory recent store
     * @param myBatisStore optional MyBatis-backed store
     * @return primary audit lookup
     */
    @Bean
    @Primary
    public TeacherResourceBlockSearchAuditLookup teacherResourceBlockSearchAuditLookup(
            RecentTeacherResourceBlockSearchAuditStore recentAuditStore,
            ObjectProvider<MyBatisTeacherResourceBlockSearchAuditStore> myBatisStore) {
        MyBatisTeacherResourceBlockSearchAuditStore persistentStore = myBatisStore.getIfAvailable();
        if (persistentStore == null) {
            return new CompositeTeacherResourceBlockSearchAuditLookup(List.of(recentAuditStore));
        }
        return new CompositeTeacherResourceBlockSearchAuditLookup(List.of(persistentStore, recentAuditStore));
    }
}
