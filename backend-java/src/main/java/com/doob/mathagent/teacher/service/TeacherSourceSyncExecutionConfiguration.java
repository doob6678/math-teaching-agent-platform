package com.doob.mathagent.teacher.service;

import com.doob.mathagent.vector.service.VectorIndexService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins production sync wiring to the constructor that includes persisted asset support.
 *
 * TeacherSourceSyncExecutionService keeps shorter constructors for focused unit tests, and those constructors
 * deliberately use a disabled asset service. Component scanning must not choose one of those compatibility
 * constructors in production, otherwise parsed DOCX/PDF/Feishu image references are kept as blocks but never
 * persisted as teacher_resource_asset rows.
 */
@Configuration
public class TeacherSourceSyncExecutionConfiguration {

    @Bean
    public TeacherSourceSyncExecutionService teacherSourceSyncExecutionService(
            TeacherResourceStore resourceStore,
            TeacherSourceSyncJobStore jobStore,
            TeacherDocumentBlockStore blockStore,
            TeacherFeishuDownloadClient feishuDownloadClient,
            TeacherSourceSyncProperties syncProperties,
            TeacherSourceSyncCheckpointStore checkpointStore,
            VectorIndexService vectorIndexService,
            TeacherResourceGraphAlignmentService graphAlignmentService,
            TeacherResourceAssetService assetService) {
        return new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                feishuDownloadClient,
                syncProperties,
                checkpointStore,
                vectorIndexService,
                graphAlignmentService,
                assetService);
    }
}
