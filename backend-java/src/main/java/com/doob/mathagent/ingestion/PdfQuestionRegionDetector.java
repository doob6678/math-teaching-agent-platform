package com.doob.mathagent.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Extracts question-number anchors with their actual PDF coordinates. The result is a conservative review candidate,
 * never a publication decision: text-layer order and coordinates cannot by themselves prove a scanned diagram match.
 */
public final class PdfQuestionRegionDetector {
    /** Detects all top-level numbered regions from a readable PDF source. */
    public List<DetectedQuestionRegion> detect(Path source) throws IOException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            List<DetectedQuestionRegion> regions = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDRectangle box = document.getPage(index).getMediaBox();
                int width = Math.round(box.getWidth());
                int height = Math.round(box.getHeight());
                PositionedLineStripper stripper = new PositionedLineStripper(width, height);
                stripper.setSortByPosition(true);
                stripper.setStartPage(index + 1);
                stripper.setEndPage(index + 1);
                stripper.getText(document);
                regions.addAll(QuestionRegionLayoutResolver.resolve(index + 1, width, height, uniqueTopLevelNumbers(stripper.anchors())));
            }
            return List.copyOf(regions);
        }
    }

    /** A top-level exam number occurs once per page; repeated text-layer copies are option labels or extraction echoes. */
    private static List<QuestionAnchor> uniqueTopLevelNumbers(List<QuestionAnchor> anchors) {
        LinkedHashMap<String, QuestionAnchor> firstByNumber = new LinkedHashMap<>();
        for (QuestionAnchor anchor : anchors) {
            firstByNumber.putIfAbsent(anchor.number(), anchor);
        }
        return List.copyOf(firstByNumber.values());
    }

    /** Captures a leading-number line at the first glyph coordinate instead of estimating it from extracted text. */
    private static final class PositionedLineStripper extends PDFTextStripper {
        private final int pageWidth;
        private final int pageHeight;
        private final List<QuestionAnchor> anchors = new ArrayList<>();

        private PositionedLineStripper(int pageWidth, int pageHeight) throws IOException {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            var number = QuestionNumberDetector.topLevelNumber(text);
            if (number.isEmpty() || textPositions.isEmpty()) {
                return;
            }
            TextPosition first = textPositions.getFirst();
            int x = clamp(Math.round(first.getXDirAdj()), 0, pageWidth - 1);
            int y = clamp(Math.round(first.getYDirAdj()), 0, pageHeight - 1);
            anchors.add(new QuestionAnchor(number.get(), text, x, y));
        }

        private List<QuestionAnchor> anchors() {
            return List.copyOf(anchors);
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
