package com.doob.mathagent.teacher.config;

import com.doob.mathagent.teacher.search.audit.CompositeTeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.search.audit.CompositeTeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.search.audit.MyBatisTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.search.audit.RecentTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditLookup;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditSink;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Teacher resource search audit wiring.
 */
@Configuration
public class TeacherResourceSearchAuditConfiguration {

    /**
     * Provides the bounded recent audit cache used for fast queryId lookup after the event is persisted.
     *
     * @return recent in-memory audit cache
     */
    @Bean
    public RecentTeacherResourceBlockSearchAuditStore recentTeacherResourceBlockSearchAuditStore() {
        return new RecentTeacherResourceBlockSearchAuditStore(200);
    }

    /**
     * Provides the primary audit sink. Persistent MySQL audit is required; recent memory is only a cache.
     *
     * @param recentAuditStore in-memory recent cache
     * @param persistentStore MyBatis-backed audit store
     * @return primary audit sink
     */
    @Bean
    @Primary
    public TeacherResourceBlockSearchAuditSink teacherResourceBlockSearchAuditSink(
            RecentTeacherResourceBlockSearchAuditStore recentAuditStore,
            MyBatisTeacherResourceBlockSearchAuditStore persistentStore) {
        return new CompositeTeacherResourceBlockSearchAuditSink(List.of(persistentStore, recentAuditStore));
    }

    /**
     * Provides the primary audit lookup. Persistent logs are authoritative; recent memory is a short-lived cache.
     *
     * @param recentAuditStore in-memory recent cache
     * @param persistentStore MyBatis-backed audit store
     * @return primary audit lookup
     */
    @Bean
    @Primary
    public TeacherResourceBlockSearchAuditLookup teacherResourceBlockSearchAuditLookup(
            RecentTeacherResourceBlockSearchAuditStore recentAuditStore,
            MyBatisTeacherResourceBlockSearchAuditStore persistentStore) {
        return new CompositeTeacherResourceBlockSearchAuditLookup(List.of(persistentStore, recentAuditStore));
    }
}

