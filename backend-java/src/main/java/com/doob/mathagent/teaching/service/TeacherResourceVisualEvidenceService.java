package com.doob.mathagent.teaching.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Materializes a teacher asset through its permission boundary for direct multimodal use and rendering.
 *
 * <p>The only way into this class is an opaque asset id plus the authenticated subject. It delegates that pair to
 * {@link TeacherResourceBlockSearchService#materializeVisibleAsset(String, RequestSubject)}, which opens the source
 * only after tenant/owner/scope checks. A vision client never receives a remote URL, database storage key, or an
 * arbitrary path. The original image is intentionally not pre-transcribed here: the handout model receives the
 * authorized pixels together with the matched text, avoiding a second slow or lossy vision request.</p>
 */
@Service
public class TeacherResourceVisualEvidenceService {

    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;

    public TeacherResourceVisualEvidenceService(TeacherResourceBlockSearchService teacherResourceBlockSearchService) {
        this.teacherResourceBlockSearchService = teacherResourceBlockSearchService;
    }

    /**
     * Returns the local renderer path. The drafting gateway reads the same file as direct multimodal context.
     *
     * @param assetId opaque asset id from the permission-filtered teacher search hit
     * @param mimeType persisted asset MIME type retained by the evidence contract
     * @param subject authenticated task owner/viewer
     * @return no value when the asset is not visible or cannot be materialized
     */
    public Optional<MaterializedImageEvidence> materialize(String assetId, String mimeType, RequestSubject subject) {
        return materialize(assetId, mimeType, subject, "");
    }

    /**
     * Materializes an image together with text extracted from the same authorized source block.
     * This is source text, not a model-invented visual caption; the handout model can inspect the pixels itself.
     */
    public Optional<MaterializedImageEvidence> materialize(
            String assetId,
            String mimeType,
            RequestSubject subject,
            String verifiedAdjacentText) {
        if (teacherResourceBlockSearchService == null || assetId == null || assetId.isBlank() || subject == null) {
            return Optional.empty();
        }
        Optional<Path> path = teacherResourceBlockSearchService.materializeVisibleAsset(assetId, subject);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MaterializedImageEvidence(path.get(), normalizeAdjacentText(verifiedAdjacentText)));
    }

    private static String normalizeAdjacentText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800).strip();
    }

    /** A renderer-local path and prompt-only verified visible facts for the same already-authorized asset. */
    public record MaterializedImageEvidence(Path imagePath, String imageDescription) {
    }
}
