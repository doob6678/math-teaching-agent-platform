package com.doob.mathagent.teacher.service;

import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reads an already-authorized teacher source page with the configured real multimodal model.
 *
 * <p>The client deliberately accepts only a backend materialized local path. It has no remote URL, no user supplied
 * path and no direct asset-id lookup, so teacher-resource permission checks remain outside this model boundary.</p>
 */
@Service
public class TeacherPageTranscriptionClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(TeacherPageTranscriptionClient.class);
    /** The minimum self-reported confidence accepted as a replacement for a broken PDF text layer. */
    private static final double MINIMUM_CONFIDENCE = 0.95d;

    private final AuthorizedImageTranscriptionService visionService;
    private final boolean enabled;

    @Autowired
    public TeacherPageTranscriptionClient(AuthorizedImageTranscriptionService visionService) {
        this.visionService = visionService;
        this.enabled = true;
    }

    private TeacherPageTranscriptionClient() {
        this.visionService = null;
        this.enabled = false;
    }

    /** Disabled dependency for focused synchronization tests; production is wired with the real vision service. */
    public static TeacherPageTranscriptionClient disabled() {
        return new TeacherPageTranscriptionClient();
    }

    /** Exposes only whether the real vision dependency was wired; no provider configuration is revealed. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Transcribes visible question text from a materialized, permission-checked page image.
     *
     * @return empty when the model did not return high-confidence visible text; callers retain the original source
     *         rather than inventing a replacement
     */
    public Optional<PageTranscription> transcribe(Path authorizedPageImage, String mimeType) {
        if (!enabled) {
            return Optional.empty();
        }
        AuthorizedImageTranscriptionService.VisionAnalysis analysis =
                // Teacher-source pages are audited evidence.  Pin their visible-text transcription to the configured
                // gpt-5.6-luna provider instead of silently falling through to a different vendor after a relay wait.
                visionService.analyzeAuthorizedLocalImageWithPrimaryProvider(authorizedPageImage, mimeType);
        String text = analysis.problemText() == null ? "" : analysis.problemText().strip();
        if (!analysis.succeeded() || text.isBlank() || analysis.confidence() < MINIMUM_CONFIDENCE) {
            // Do not log private page text. Provider/model/status is enough to locate a broken authorization, timeout,
            // or relay boundary when a page correctly remains on its original extraction.
            LOGGER.warn("teacher_page_transcription_rejected provider={} model={} confidence={} reason={}",
                    analysis.providerName(), analysis.modelCode(), analysis.confidence(), analysis.message());
            return Optional.empty();
        }
        LOGGER.info("teacher_page_transcription_accepted provider={} model={} confidence={}",
                analysis.providerName(), analysis.modelCode(), analysis.confidence());
        return Optional.of(new PageTranscription(text, analysis.confidence(), analysis.providerName(), analysis.modelCode()));
    }

    /** High-confidence, visible-only page transcription retained in the source block audit trail. */
    public record PageTranscription(String text, double confidence, String provider, String modelCode) {
    }
}
