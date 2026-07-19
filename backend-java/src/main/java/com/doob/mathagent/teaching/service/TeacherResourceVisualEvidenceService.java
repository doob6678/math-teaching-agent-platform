package com.doob.mathagent.teaching.service;

import com.doob.mathagent.infrastructure.security.RequestSubject;
import com.doob.mathagent.teacher.service.TeacherResourceBlockSearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Materializes a teacher asset through its permission boundary and optionally attaches real visual evidence.
 *
 * <p>The only way into this class is an opaque asset id plus the authenticated subject. It delegates that pair to
 * {@link TeacherResourceBlockSearchService#materializeVisibleAsset(String, RequestSubject)}, which opens the source
 * only after tenant/owner/scope checks. A vision client never receives a remote URL, database storage key, or an
 * arbitrary path. Failure is deliberately non-fatal: a valid image still reaches the LaTeX renderer even when no
 * provider is configured to describe it.</p>
 */
@Service
public class TeacherResourceVisualEvidenceService {

    private static final Set<String> SUPPORTED_IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");
    private static final int DEFAULT_MAX_DESCRIPTION_CHARACTERS = 420;

    private final TeacherResourceBlockSearchService teacherResourceBlockSearchService;
    private final AuthorizedTeacherImageVisionClient visionClient;
    private final int maxDescriptionCharacters;

    public TeacherResourceVisualEvidenceService(
            TeacherResourceBlockSearchService teacherResourceBlockSearchService,
            Optional<AuthorizedTeacherImageVisionClient> visionClient,
            @Value("${math-agent.teaching.image-understanding.max-description-characters:420}") int maxDescriptionCharacters) {
        this.teacherResourceBlockSearchService = teacherResourceBlockSearchService;
        this.visionClient = visionClient == null ? null : visionClient.orElse(null);
        this.maxDescriptionCharacters = maxDescriptionCharacters > 0
                ? maxDescriptionCharacters
                : DEFAULT_MAX_DESCRIPTION_CHARACTERS;
    }

    /**
     * Returns the local renderer path plus a concise, model-readable description when a real vision call succeeds.
     *
     * @param assetId opaque asset id from the permission-filtered teacher search hit
     * @param mimeType persisted asset MIME type, used to reject non-image attachments before vision is invoked
     * @param subject authenticated task owner/viewer
     * @return no value when the asset is not visible or cannot be materialized
     */
    public Optional<MaterializedImageEvidence> materialize(String assetId, String mimeType, RequestSubject subject) {
        if (teacherResourceBlockSearchService == null || assetId == null || assetId.isBlank() || subject == null) {
            return Optional.empty();
        }
        Optional<Path> path = teacherResourceBlockSearchService.materializeVisibleAsset(assetId, subject);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        String descriptor = "";
        if (isSupportedImage(mimeType) && visionClient != null && Files.isRegularFile(path.get())) {
            descriptor = visionClient.describe(path.get(), mimeType)
                    .map(value -> sanitizeDescription(value, maxDescriptionCharacters))
                    .orElse("");
        }
        return Optional.of(new MaterializedImageEvidence(path.get(), descriptor));
    }

    /**
     * Keeps provider output factual and prompt-safe: remove transport paths/URLs and cap the visible-fact payload.
     * This is intentionally not a graph parser; any missing adjacency or answer remains unknown.
     */
    static String sanitizeDescription(String description, int maxCharacters) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String cleaned = description
                .replaceAll("(?i)https?://[^\\s，。；;]+", " ")
                .replaceAll("(?i)[A-Z]:[\\\\/][^\\s，。；;]+", " ")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        int safeLimit = maxCharacters > 0 ? maxCharacters : DEFAULT_MAX_DESCRIPTION_CHARACTERS;
        if (cleaned.length() > safeLimit) {
            cleaned = cleaned.substring(0, safeLimit).strip();
            int sentenceEnd = Math.max(cleaned.lastIndexOf('。'), cleaned.lastIndexOf('；'));
            if (sentenceEnd >= safeLimit / 3) {
                cleaned = cleaned.substring(0, sentenceEnd + 1);
            }
        }
        return cleaned;
    }

    private static boolean isSupportedImage(String mimeType) {
        return mimeType != null && SUPPORTED_IMAGE_MIME_TYPES.contains(mimeType.strip().toLowerCase(Locale.ROOT));
    }

    /** A renderer-local path and prompt-only verified visible facts for the same already-authorized asset. */
    public record MaterializedImageEvidence(Path imagePath, String imageDescription) {
    }
}
