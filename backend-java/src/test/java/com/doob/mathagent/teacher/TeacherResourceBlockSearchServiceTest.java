package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.service.InMemoryTeacherDocumentBlockStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.RecentTeacherResourceBlockSearchAuditStore;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchAuditEvent;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import com.doob.mathagent.teacher.vo.TeacherDocumentBlockResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceBlockSearchResponse;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.vector.service.TestVectorIndexService;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeacherResourceBlockSearchServiceTest {

    @Test
    void teacherSearchesOwnPrivateBlocksAndDoesNotSeeAnotherTeacherPrivateBlocks() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-own", "teacher-1", "TEACHER_PRIVATE", "Own vector notes"));
        resourceStore.save(document("doc-other", "teacher-2", "TEACHER_PRIVATE", "Other private notes"));
        blockStore.replaceActiveBlocks("school-a", "doc-own", List.of(block(
                "b-own",
                "doc-own",
                1,
                "Space vector dot product method belongs to the teacher private handout.")));
        blockStore.replaceActiveBlocks("school-a", "doc-other", List.of(block(
                "b-other",
                "doc-other",
                1,
                "Space vector dot product method from another teacher private handout.")));
        TeacherResourceBlockSearchService service = com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "dot product",
                10);

        assertThat(response.hitCount()).isEqualTo(1);
        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-own");
        assertThat(response.hits().getFirst().permissionScope()).isEqualTo("TEACHER_PRIVATE");
    }

    @Test
    void teacherCanSearchTenantSharedBlocksWithoutSeeingOtherPrivateBlocks() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-shared", "admin-1", "MATH_VIP", "Shared vector bank"));
        resourceStore.save(document("doc-other-private", "teacher-2", "TEACHER_PRIVATE", "Other private bank"));
        blockStore.replaceActiveBlocks("school-a", "doc-shared", List.of(block(
                "b-shared",
                "doc-shared",
                1,
                "A shared Math VIP vector method can be reused by teachers.")));
        blockStore.replaceActiveBlocks("school-a", "doc-other-private", List.of(block(
                "b-other-private",
                "doc-other-private",
                1,
                "A private vector method must not leak to another teacher.")));
        TeacherResourceBlockSearchService service = com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "vector method",
                10);

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-shared");
        assertThat(response.hits().getFirst().permissionScope()).isEqualTo("MATH_VIP");
    }

    @Test
    void adminSearchesTenantBlocksAcrossOwners() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        resourceStore.save(document("doc-teacher", "teacher-1", "TEACHER_PRIVATE", "Teacher private bank"));
        resourceStore.save(document("doc-admin", "admin-1", "PUBLIC_TEXTBOOK", "Admin public bank"));
        blockStore.replaceActiveBlocks("school-a", "doc-teacher", List.of(block(
                "b-teacher",
                "doc-teacher",
                1,
                "Teacher vector examples for inspection.")));
        blockStore.replaceActiveBlocks("school-a", "doc-admin", List.of(block(
                "b-admin",
                "doc-admin",
                2,
                "Public vector examples for inspection.")));
        TeacherResourceBlockSearchService service = com.doob.mathagent.teacher.TeacherResourceBlockSearchServiceFixture.service(resourceStore, blockStore);

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "admin",
                "admin-1",
                "vector examples",
                10);

        assertThat(response.hits()).extracting(TeacherResourceBlockSearchResponse.Hit::blockId)
                .containsExactly("b-admin", "b-teacher");
    }

    @Test
    void recordsSearchAuditEventWithoutLocalPathsOrSecrets() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherDocumentBlockStore blockStore = new InMemoryTeacherDocumentBlockStore();
        RecentTeacherResourceBlockSearchAuditStore auditStore = new RecentTeacherResourceBlockSearchAuditStore(5);
        resourceStore.save(document("doc-own", "teacher-1", "TEACHER_PRIVATE", "Own vector notes"));
        blockStore.replaceActiveBlocks("school-a", "doc-own", List.of(block(
                "b-own",
                "doc-own",
                1,
                "Space vector dot product method belongs to the teacher private handout.")));
        TeacherResourceBlockSearchService service = new TeacherResourceBlockSearchService(
                resourceStore,
                blockStore,
                auditStore,
                TestVectorIndexService.successful(resourceStore, blockStore));

        TeacherResourceBlockSearchResponse response = service.search(
                "school-a",
                "teacher",
                "teacher-1",
                "dot product",
                10,
                "/api/mcp/tools/search_teacher_resource_evidence/call");

        TeacherResourceBlockSearchAuditEvent event = auditStore.findByQueryId(response.queryId()).orElseThrow();
        assertThat(event.tenantId()).isEqualTo("school-a");
        assertThat(event.subjectType()).isEqualTo("teacher");
        assertThat(event.subjectId()).isEqualTo("teacher-1");
        assertThat(event.endpoint()).isEqualTo("/api/mcp/tools/search_teacher_resource_evidence/call");
        assertThat(event.hits()).extracting(TeacherResourceBlockSearchAuditEvent.Hit::blockId)
                .containsExactly("b-own");
        assertThat(event.toString()).doesNotContain("C:/math");
        assertThat(event.toString()).doesNotContain("mcp_secret");
    }

    @Test
    void studentCannotSearchTeacherResourceBlocks() {
        TeacherResourceBlockSearchService service = TeacherResourceBlockSearchServiceFixture.service(
                new InMemoryTeacherResourceStore(),
                new InMemoryTeacherDocumentBlockStore());

        assertThatThrownBy(() -> service.search("school-a", "student", "student-1", "vector", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }

    private static TeacherResourceDocumentResponse document(
            String documentId,
            String ownerSubjectId,
            String permissionScope,
            String title) {
        return new TeacherResourceDocumentResponse(
                documentId,
                "school-a",
                ownerSubjectId,
                "local_path",
                title,
                null,
                "C:/math/" + documentId,
                permissionScope,
                "synced",
                "parsed",
                "pending",
                "waiting_rebuild",
                List.of());
    }

    private static TeacherDocumentBlockResponse block(
            String blockId,
            String documentId,
            int blockOrder,
            String text) {
        return new TeacherDocumentBlockResponse(
                blockId,
                documentId,
                documentId + ":" + blockOrder,
                "text",
                blockOrder,
                "Vectors",
                "Dot product",
                null,
                null,
                text,
                text.toLowerCase(),
                "[]",
                "[]",
                blockId + "-checksum",
                1.0,
                "active");
    }
}
