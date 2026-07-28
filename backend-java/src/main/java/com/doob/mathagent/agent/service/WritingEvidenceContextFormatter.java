package com.doob.mathagent.agent.service;

import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Formats permission-filtered teacher-resource hits for the writing workflow.
 *
 * <p>This boundary keeps the model context readable and auditable: writers receive a human-readable source label,
 * the exact selected block, a bounded text excerpt, and only backend-authorized image routes. Raw source paths,
 * credentials, and local files never cross this boundary.</p>
 */
final class WritingEvidenceContextFormatter {

    private WritingEvidenceContextFormatter() {
    }

    /**
     * Produces compact evidence paragraphs in the same order as retrieval ranking.
     *
     * @param hits already permission-filtered retrieval hits
     * @param maxTextChars maximum evidence characters retained for one hit
     * @param maxAssets maximum authorized image URIs retained for one hit
     * @return writer-safe evidence context
     */
    static String format(List<TeacherResourceBlockSearchResponse.Hit> hits, int maxTextChars, int maxAssets) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        return hits.stream()
                .map(hit -> formatHit(hit, maxTextChars, maxAssets))
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private static String formatHit(TeacherResourceBlockSearchResponse.Hit hit, int maxTextChars, int maxAssets) {
        String text = bounded(hit.evidenceText(), maxTextChars);
        if (text.isBlank()) {
            return "";
        }
        String assets = hit.assetRefs().stream()
                .map(TeacherResourceBlockSearchResponse.AssetRef::assetUri)
                .filter(uri -> uri != null && !uri.isBlank())
                .limit(maxAssets)
                .collect(Collectors.joining(", "));
        return "[资料来源：" + bounded(hit.documentTitle(), 120)
                + "; 文档=" + bounded(hit.documentId(), 120)
                + "; 块=" + bounded(hit.blockId(), 120) + "]\n"
                + text + (assets.isBlank() ? "" : "\nTEACHER_IMAGE: " + assets);
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank() || maximum <= 0) {
            return "";
        }
        String normalized = value.strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }
}
