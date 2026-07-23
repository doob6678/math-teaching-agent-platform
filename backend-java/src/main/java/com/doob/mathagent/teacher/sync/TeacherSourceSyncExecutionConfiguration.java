package com.doob.mathagent.teacher.sync;

import com.doob.mathagent.teacher.block.TeacherDocumentBlockStore;
import com.doob.mathagent.teacher.document.TeacherResourceStore;
import com.doob.mathagent.teacher.feishu.TeacherFeishuDownloadClient;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionClient;
import com.doob.mathagent.teacher.formula.TeacherFormulaRecognitionProperties;
import com.doob.mathagent.teacher.search.TeacherResourceGraphAlignmentService;
import com.doob.mathagent.teacher.service.TeacherResourceAssetService;
import com.doob.mathagent.teacher.service.TeacherPageTranscriptionClient;
import com.doob.mathagent.teacher.service.TeacherSourceSyncExecutionService;
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
            TeacherResourceAssetService assetService,
            TeacherFormulaRecognitionClient formulaRecognitionClient,
            TeacherFormulaRecognitionProperties formulaRecognitionProperties,
            TeacherPageTranscriptionClient pageTranscriptionClient) {
        return new TeacherSourceSyncExecutionService(
                resourceStore,
                jobStore,
                blockStore,
                feishuDownloadClient,
                syncProperties,
                checkpointStore,
                vectorIndexService,
                graphAlignmentService,
                assetService,
                formulaRecognitionClient,
                formulaRecognitionProperties,
                pageTranscriptionClient);
    }
}
