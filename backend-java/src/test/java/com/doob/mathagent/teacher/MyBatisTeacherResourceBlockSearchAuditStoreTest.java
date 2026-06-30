package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.entity.TeacherResourceSearchAuditHitEntity;
import com.doob.mathagent.teacher.entity.TeacherResourceSearchAuditLogEntity;
import com.doob.mathagent.teacher.mapper.TeacherResourceSearchAuditHitMapper;
import com.doob.mathagent.teacher.mapper.TeacherResourceSearchAuditLogMapper;
import com.doob.mathagent.teacher.service.MyBatisTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditEvent;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisTeacherResourceBlockSearchAuditStoreTest {

    @Test
    void recordsQueryAndRankedHitsWithoutRawPathsOrSecrets() {
        CapturingQueryMapper queryMapper = new CapturingQueryMapper();
        CapturingHitMapper hitMapper = new CapturingHitMapper();
        MyBatisTeacherResourceBlockSearchAuditStore store =
                new MyBatisTeacherResourceBlockSearchAuditStore(queryMapper.proxy(), hitMapper.proxy());

        store.record(event());

        assertThat(queryMapper.inserted.getQueryId()).isEqualTo("query-1");
        assertThat(queryMapper.inserted.getTenantId()).isEqualTo("school-a");
        assertThat(queryMapper.inserted.getSubjectType()).isEqualTo("teacher");
        assertThat(queryMapper.inserted.getSubjectId()).isEqualTo("teacher-8");
        assertThat(queryMapper.inserted.getEndpoint()).isEqualTo("/api/mcp/tools/search_teacher_resource_evidence/call");
        assertThat(queryMapper.inserted.getQueryText()).isEqualTo("space vector");
        assertThat(queryMapper.inserted.getOccurredAt()).isNotNull();
        assertThat(hitMapper.inserted).hasSize(2);
        assertThat(hitMapper.inserted).extracting(TeacherResourceSearchAuditHitEntity::getRankNo)
                .containsExactly(1, 2);
        assertThat(hitMapper.inserted.get(0).getBlockId()).isEqualTo("block-1");
        assertThat(hitMapper.inserted.get(0).getScore()).isEqualByComparingTo(BigDecimal.valueOf(11.5));
        assertThat(queryMapper.inserted.toString()).doesNotContain("C:/").doesNotContain("secret");
    }

    @Test
    void findsPersistedAuditByQueryIdAndRestoresHitsInRankOrder() {
        CapturingQueryMapper queryMapper = new CapturingQueryMapper();
        CapturingHitMapper hitMapper = new CapturingHitMapper();
        MyBatisTeacherResourceBlockSearchAuditStore store =
                new MyBatisTeacherResourceBlockSearchAuditStore(queryMapper.proxy(), hitMapper.proxy());
        store.record(event());

        TeacherResourceBlockSearchAuditEvent restored = store.findByQueryId("query-1").orElseThrow();

        assertThat(restored.queryId()).isEqualTo("query-1");
        assertThat(restored.tenantId()).isEqualTo("school-a");
        assertThat(restored.subjectId()).isEqualTo("teacher-8");
        assertThat(restored.hits()).extracting(TeacherResourceBlockSearchAuditEvent.Hit::blockId)
                .containsExactly("block-1", "block-2");
    }

    /**
     * Builds one representative audit event.
     */
    private static TeacherResourceBlockSearchAuditEvent event() {
        return new TeacherResourceBlockSearchAuditEvent(
                "query-1",
                "school-a",
                "teacher",
                "teacher-8",
                "space vector",
                8,
                "teacher_block_lexical",
                2,
                12,
                "/api/mcp/tools/search_teacher_resource_evidence/call",
                List.of(
                        new TeacherResourceBlockSearchAuditEvent.Hit(
                                "doc-1",
                                "Vector source",
                                "TEACHER_PRIVATE",
                                "block-1",
                                "text",
                                3,
                                12,
                                11.5),
                        new TeacherResourceBlockSearchAuditEvent.Hit(
                                "doc-1",
                                "Vector source",
                                "TEACHER_PRIVATE",
                                "block-2",
                                "text",
                                4,
                                null,
                                2.0)));
    }

    private static final class CapturingQueryMapper {

        private TeacherResourceSearchAuditLogEntity inserted;

        /**
         * Creates a mapper proxy for query audit tests.
         */
        TeacherResourceSearchAuditLogMapper proxy() {
            return (TeacherResourceSearchAuditLogMapper) Proxy.newProxyInstance(
                    TeacherResourceSearchAuditLogMapper.class.getClassLoader(),
                    new Class<?>[] {TeacherResourceSearchAuditLogMapper.class},
                    (proxy, method, args) -> {
                        if ("insert".equals(method.getName())) {
                            inserted = (TeacherResourceSearchAuditLogEntity) args[0];
                            return 1;
                        }
                        if ("selectList".equals(method.getName())) {
                            return inserted == null ? List.of() : List.of(inserted);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class CapturingHitMapper {

        private final List<TeacherResourceSearchAuditHitEntity> inserted = new ArrayList<>();

        /**
         * Creates a mapper proxy for hit audit tests.
         */
        TeacherResourceSearchAuditHitMapper proxy() {
            return (TeacherResourceSearchAuditHitMapper) Proxy.newProxyInstance(
                    TeacherResourceSearchAuditHitMapper.class.getClassLoader(),
                    new Class<?>[] {TeacherResourceSearchAuditHitMapper.class},
                    (proxy, method, args) -> {
                        if ("insert".equals(method.getName())) {
                            inserted.add((TeacherResourceSearchAuditHitEntity) args[0]);
                            return 1;
                        }
                        if ("selectList".equals(method.getName())) {
                            return List.copyOf(inserted);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
