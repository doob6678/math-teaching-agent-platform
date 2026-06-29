package com.doob.mathagent.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doob.mathagent.teacher.service.InMemoryTeacherResourceStore;
import com.doob.mathagent.teacher.service.InMemoryTeacherSourceSyncJobStore;
import com.doob.mathagent.teacher.service.TeacherResourceRegistrationCommand;
import com.doob.mathagent.teacher.service.TeacherResourceService;
import com.doob.mathagent.teacher.service.TeacherSourceSyncJobService;
import com.doob.mathagent.teacher.vo.TeacherResourceDocumentResponse;
import com.doob.mathagent.teacher.vo.TeacherSourceSyncJobResponse;
import org.junit.jupiter.api.Test;

class TeacherSourceSyncJobServiceTest {

    @Test
    void teacherCreatesQueuedFeishuSyncJobForOwnResource() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-1",
                "feishu",
                "Feishu question bank",
                "https://example.feishu.cn/docx/doc-token",
                null,
                "TEACHER_PRIVATE"));
        TeacherSourceSyncJobService syncJobService =
                new TeacherSourceSyncJobService(resourceStore, new InMemoryTeacherSourceSyncJobStore());

        TeacherSourceSyncJobResponse job = syncJobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-1",
                resource.documentId());

        assertThat(job.documentId()).isEqualTo(resource.documentId());
        assertThat(job.sourceType()).isEqualTo("feishu");
        assertThat(job.operation()).isEqualTo("feishu_download");
        assertThat(job.status()).isEqualTo("queued");
        assertThat(job.phase()).isEqualTo("download_pending");
        assertThat(job.createdBy()).isEqualTo("teacher-1");
        assertThat(job.message()).contains("Feishu");
    }

    @Test
    void teacherCannotCreateSyncJobForAnotherTeacherResource() {
        InMemoryTeacherResourceStore resourceStore = new InMemoryTeacherResourceStore();
        TeacherResourceService resourceService = new TeacherResourceService(resourceStore);
        TeacherResourceDocumentResponse resource = resourceService.register(new TeacherResourceRegistrationCommand(
                "school-a",
                "teacher",
                "teacher-owner",
                "local_path",
                "Local question bank",
                null,
                "C:/math/question-bank",
                "TEACHER_PRIVATE"));
        TeacherSourceSyncJobService syncJobService =
                new TeacherSourceSyncJobService(resourceStore, new InMemoryTeacherSourceSyncJobStore());

        assertThatThrownBy(() -> syncJobService.createSyncJob(
                "school-a",
                "teacher",
                "teacher-other",
                resource.documentId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own resources");
    }

    @Test
    void studentCannotCreateTeacherResourceSyncJob() {
        TeacherSourceSyncJobService syncJobService =
                new TeacherSourceSyncJobService(new InMemoryTeacherResourceStore(), new InMemoryTeacherSourceSyncJobStore());

        assertThatThrownBy(() -> syncJobService.createSyncJob(
                "school-a",
                "student",
                "student-1",
                "doc-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("teacher or admin");
    }
}
