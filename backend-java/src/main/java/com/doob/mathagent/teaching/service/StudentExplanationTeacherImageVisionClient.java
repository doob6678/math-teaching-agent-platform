package com.doob.mathagent.teaching.service;

import com.doob.mathagent.student.service.StudentExplanationVisionService;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reuses the existing real multimodal reader for teacher assets only after the teacher asset boundary authorized them.
 *
 * <p>Student uploads and teacher assets have different ownership models. This adapter therefore contains no upload
 * lookup and no browser identity handling: {@link TeacherResourceVisualEvidenceService} is responsible for obtaining
 * the path through {@code TeacherResourceBlockSearchService.materializeVisibleAsset}. The vision service receives
 * only that short-lived, backend-controlled materialization.</p>
 */
@Service
public class StudentExplanationTeacherImageVisionClient implements AuthorizedTeacherImageVisionClient {

    private final StudentExplanationVisionService visionService;

    public StudentExplanationTeacherImageVisionClient(StudentExplanationVisionService visionService) {
        this.visionService = visionService;
    }

    @Override
    public Optional<String> describe(Path authorizedImage, String mimeType) {
        StudentExplanationVisionService.VisionAnalysis analysis =
                visionService.analyzeAuthorizedLocalImage(authorizedImage, mimeType);
        if (!analysis.succeeded() || analysis.problemText() == null || analysis.problemText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(analysis.problemText().strip());
    }
}
