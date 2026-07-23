package com.doob.mathagent.teacher;

import com.doob.mathagent.knowledge.service.InMemoryKnowledgeQuestionBankStore;
import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.search.audit.TeacherResourceBlockSearchAuditSink;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.vector.service.TestVectorIndexService;

public final class TeacherResourceBlockSearchServiceFixture {

    private TeacherResourceBlockSearchServiceFixture() {
    }

    public static TeacherResourceBlockSearchService service(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore) {
        return service(resourceStore, blockStore, new InMemoryKnowledgeQuestionBankStore());
    }

    public static TeacherResourceBlockSearchService service(
            TeacherResourceStore resourceStore,
            TeacherDocumentBlockStore blockStore,
            InMemoryKnowledgeQuestionBankStore knowledgeStore) {
        return new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                new DisabledAuditSink(),
                TestVectorIndexService.successful(resourceStore, blockStore),
                new TeacherResourceGraphAlignmentService(knowledgeStore));
    }

    private static final class DisabledAuditSink implements TeacherResourceBlockSearchAuditSink {
        @Override
        public void record(TeacherResourceBlockSearchAuditEvent event) {
            // Audit is explicitly disabled by this test fixture.
        }
    }
}

