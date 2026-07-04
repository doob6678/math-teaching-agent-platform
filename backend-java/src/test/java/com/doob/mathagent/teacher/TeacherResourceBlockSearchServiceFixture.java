package com.doob.mathagent.teacher;

import com.doob.mathagent.teacher.service.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.service.TeacherResourceStore;
import com.doob.mathagent.vector.service.TestVectorIndexService;

public final class TeacherResourceBlockSearchServiceFixture {

    private TeacherResourceBlockSearchServiceFixture() {
    }

    public static TeacherResourceBlockSearchService service(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        return new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                new DisabledAuditSink(),
                TestVectorIndexService.successful(resourceStore, blockStore));
    }

    private static final class DisabledAuditSink implements TeacherResourceBlockSearchAuditSink {
        @Override
        public void record(TeacherResourceBlockSearchAuditEvent event) {
            // Audit is explicitly disabled by this test fixture.
        }
    }
}
