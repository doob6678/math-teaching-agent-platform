package com.doob.mathagent.ingestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts number-line anchors to review regions without inventing OCR boxes. Two populated x-coordinate groups
 * indicate a two-column page; otherwise every candidate keeps the full page width and only its vertical boundary.
 */
final class QuestionRegionLayoutResolver {
    private static final int MINIMUM_ANCHORS_PER_COLUMN = 2;
    // PDFTextStripper reports a glyph baseline, not the top of the printed line. Keeping this named headroom before
    // both boundaries retains the full question-number line while excluding the following question's first glyphs.
    private static final int QUESTION_ANCHOR_HEADROOM_PDF_POINTS = 16;
    private static final String SINGLE_COLUMN = "SINGLE_COLUMN";
    private static final String TWO_COLUMN = "TWO_COLUMN";

    private QuestionRegionLayoutResolver() { }

    static List<DetectedQuestionRegion> resolve(int pageNumber, int pageWidth, int pageHeight, List<QuestionAnchor> anchors) {
        if (pageNumber < 1 || pageWidth < 1 || pageHeight < 1) {
            throw new IllegalArgumentException("page geometry must be positive");
        }
        int split = pageWidth / 2;
        List<QuestionAnchor> left = anchors.stream().filter(anchor -> anchor.x() < split).toList();
        List<QuestionAnchor> right = anchors.stream().filter(anchor -> anchor.x() >= split).toList();
        boolean twoColumn = left.size() >= MINIMUM_ANCHORS_PER_COLUMN && right.size() >= MINIMUM_ANCHORS_PER_COLUMN;
        if (!twoColumn) {
            return resolveColumn(pageNumber, pageWidth, pageHeight, anchors, 0, pageWidth, SINGLE_COLUMN);
        }
        List<DetectedQuestionRegion> regions = new ArrayList<>();
        regions.addAll(resolveColumn(pageNumber, pageWidth, pageHeight, left, 0, split, TWO_COLUMN));
        regions.addAll(resolveColumn(pageNumber, pageWidth, pageHeight, right, split, pageWidth, TWO_COLUMN));
        return regions.stream().sorted(Comparator.comparingInt(DetectedQuestionRegion::pageNumber)
                .thenComparingInt(region -> region.region().y1()).thenComparingInt(region -> region.region().x1())).toList();
    }

    private static List<DetectedQuestionRegion> resolveColumn(int pageNumber, int pageWidth, int pageHeight,
                                                               List<QuestionAnchor> anchors, int x1, int x2, String layout) {
        List<QuestionAnchor> ordered = anchors.stream().sorted(Comparator.comparingInt(QuestionAnchor::y).thenComparingInt(QuestionAnchor::x)).toList();
        List<DetectedQuestionRegion> result = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            QuestionAnchor current = ordered.get(index);
            int top = Math.max(0, current.y() - QUESTION_ANCHOR_HEADROOM_PDF_POINTS);
            int bottom = index + 1 < ordered.size()
                    ? Math.max(0, ordered.get(index + 1).y() - QUESTION_ANCHOR_HEADROOM_PDF_POINTS)
                    : pageHeight;
            // A same-line duplicate is not a second region. It stays in the audit trail only once visual review
            // supplies independent boxes, rather than creating an empty/inverted durable occurrence.
            if (bottom <= top) {
                continue;
            }
            result.add(new DetectedQuestionRegion(pageNumber, current.number(), new QuestionRegion(x1, top, x2, bottom), current.line(), layout));
        }
        return result;
    }
}
