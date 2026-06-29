package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import org.junit.jupiter.api.Test;

class TeacherSourceSyncCheckpointStoreTest {

    @Test
    void inMemoryStoreSavesAndOverwritesCheckpointByTenantAndJob() {
        InMemoryTeacherSourceSyncCheckpointStore store = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherSourceSyncCheckpointResponse initial = checkpoint(
                "school-a",
                "job-1",
                "doc-1",
                "root-token",
                "folder-a",
                "A",
                "page-1",
                "[\"folder-a\"]",
                "[]",
                "[]",
                1);

        store.save(initial);
        store.save(checkpoint(
                "school-a",
                "job-1",
                "doc-1",
                "root-token",
                "folder-b",
                "A/B",
                "page-2",
                "[\"folder-a\",\"folder-b\"]",
                "[{\"token\":\"file-1\"}]",
                "[]",
                2));

        TeacherSourceSyncCheckpointResponse saved = store.findByJobId("school-a", "job-1").orElseThrow();
        assertThat(saved.currentFolderToken()).isEqualTo("folder-b");
        assertThat(saved.currentPath()).isEqualTo("A/B");
        assertThat(saved.pageToken()).isEqualTo("page-2");
        assertThat(saved.downloadedItemsJson()).contains("file-1");
        assertThat(saved.cursorVersion()).isEqualTo(2);
        assertThat(store.findByJobId("school-b", "job-1")).isEmpty();
    }

    /**
     * Builds a checkpoint response used by store contract tests.
     */
    private static TeacherSourceSyncCheckpointResponse checkpoint(
            String tenantId,
            String jobId,
            String documentId,
            String rootToken,
            String currentFolderToken,
            String currentPath,
            String pageToken,
            String visitedFolderTokensJson,
            String downloadedItemsJson,
            String failedItemsJson,
            int cursorVersion) {
        return new TeacherSourceSyncCheckpointResponse(
                jobId,
                tenantId,
                documentId,
                rootToken,
                currentFolderToken,
                currentPath,
                pageToken,
                visitedFolderTokensJson,
                downloadedItemsJson,
                failedItemsJson,
                cursorVersion,
                "2026-06-29T08:00:00Z");
    }
}
