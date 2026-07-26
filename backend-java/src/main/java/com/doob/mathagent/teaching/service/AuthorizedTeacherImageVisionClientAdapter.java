package com.doob.mathagent.teaching.service;

import com.doob.mathagent.teacher.service.AuthorizedImageTranscriptionService;
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
public class AuthorizedTeacherImageVisionClientAdapter implements AuthorizedTeacherImageVisionClient {

    private final AuthorizedImageTranscriptionService visionService;

    public AuthorizedTeacherImageVisionClientAdapter(AuthorizedImageTranscriptionService visionService) {
        this.visionService = visionService;
    }

    @Override
    public Optional<String> describe(Path authorizedImage, String mimeType) {
        AuthorizedImageTranscriptionService.VisionAnalysis analysis =
                visionService.analyzeAuthorizedLocalImage(authorizedImage, mimeType);
        if (!analysis.succeeded() || analysis.problemText() == null || analysis.problemText().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(analysis.problemText().strip());
    }
}
