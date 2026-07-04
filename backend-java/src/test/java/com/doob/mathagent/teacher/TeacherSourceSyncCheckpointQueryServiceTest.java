package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncCheckpointStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncCheckpointQueryService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncCheckpointResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import org.junit.jupiter.api.Test;

class TeacherSourceSyncCheckpointQueryServiceTest {

    @Test
    void returnsCheckpointForVisibleTeacherResourceWithoutLeakingOtherOwners() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu vector root",
                "https://my.feishu.cn/drive/folder/rootToken",
                null,
                "TEACHER_PRIVATE",
                "md"));
        TeacherSourceSyncJobResponse job = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        checkpointStore.save(new TeacherSourceSyncCheckpointResponse(
                job.jobId(),
                "school-a",
                resource.documentId(),
                "rootToken",
                "folderToken-2",
                "楂樹腑鏁板/绌洪棿鍚戦噺",
                "pageToken-3",
                "[\"rootToken\",\"folderToken-2\"]",
                "[{\"token\":\"docx-1\"},{\"token\":\"docx-2\"}]",
                "[{\"message\":\"ProxyError\",\"retryable\":true}]",
                2,
                "2026-06-30T06:00:00Z"));
        TeacherSourceSyncCheckpointQueryService service =
                new TeacherSourceSyncCheckpointQueryService(resourceStore, jobStore, checkpointStore);

        TeacherSourceSyncCheckpointResponse checkpoint = service.findCheckpoint(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId(),
                job.jobId()).orElseThrow();

        assertThat(checkpoint.currentFolderToken()).isEqualTo("folderToken-2");
        assertThat(checkpoint.currentPath()).isEqualTo("楂樹腑鏁板/绌洪棿鍚戦噺");
        assertThat(checkpoint.pageToken()).isEqualTo("pageToken-3");
        assertThat(checkpoint.downloadedItemsJson()).contains("docx-1", "docx-2");
        assertThat(checkpoint.failedItemsJson()).contains("ProxyError");
        assertThatThrownBy(() -> service.findCheckpoint(
                "school-a",
                "teacher",
                "teacher-2",
                resource.documentId(),
                job.jobId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own resources");
    }

    @Test
    void adminCanReadCheckpointForAnyTeacherResource() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        InMemoryTeacherSourceSyncJobStore jobStore = new InMemoryTeacherSourceSyncJobStore();
        InMemoryTeacherSourceSyncCheckpointStore checkpointStore = new InMemoryTeacherSourceSyncCheckpointStore();
        TeacherResourceService resourceService = TeacherResourceServiceFixture.service(resourceStore);
        TeacherSourceSyncJobService jobService = new TeacherSourceSyncJobService(resourceStore, jobStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu vector root",
                "https://my.feishu.cn/drive/folder/rootToken",
                null,
                "TEACHER_PRIVATE",
                "md"));
        TeacherSourceSyncJobResponse job = jobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());
        checkpointStore.save(new TeacherSourceSyncCheckpointResponse(
                job.jobId(),
                "school-a",
                resource.documentId(),
                "rootToken",
                "rootToken",
                "Feishu vector root",
                null,
                "[\"rootToken\"]",
                "[]",
                "[]",
                1,
                "2026-06-30T06:00:00Z"));
        TeacherSourceSyncCheckpointQueryService service =
                new TeacherSourceSyncCheckpointQueryService(resourceStore, jobStore, checkpointStore);

        assertThat(service.findCheckpoint(
                "school-a",
                "admin",
                "admin-1",
                resource.documentId(),
                job.jobId())).isPresent();
    }
}
