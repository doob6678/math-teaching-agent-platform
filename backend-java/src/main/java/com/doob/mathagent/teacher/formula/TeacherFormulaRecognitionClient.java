package com.doob.mathagent.teacher.formula;

import java.util.List;

/**
 * Backend-to-worker boundary for explicit AI formula transcription.
 *
 * <p>The browser never sees the provider key. Callers receive a structured non-recognized result when the worker or
 * model cannot verify an image, so the synchronization transaction can retain the original private asset safely.</p>
 */
public interface TeacherFormulaRecognitionClient {

    FormulaRecognitionResult recognize(byte[] image, String mimeType);

    /**
     * Sends a bounded ordered page batch to the worker. The page index in each result is relative to this list, which
     * lets ingestion attach formula evidence back to the original PDF/DOCX page without filename-based matching.
     */
    default List<PageFormulaRecognitionResult> recognizePages(List<PageImage> pages) {
        return List.of();
    }

    static TeacherFormulaRecognitionClient disabled() {
        return (image, mimeType) -> FormulaRecognitionResult.notRecognized("disabled", "formula vision is not configured");
    }

    record FormulaRecognitionResult(
            String status,
            String latex,
            String plainText,
            double confidence,
            String model,
            String message) {

        public boolean recognized() {
            return "recognized".equals(status)
                    && latex != null && !latex.isBlank()
                    && plainText != null && !plainText.isBlank()
                    && confidence > 0.0d;
        }

        public static FormulaRecognitionResult notRecognized(String status, String message) {
            return new FormulaRecognitionResult(status, "", "", 0.0d, "", message == null ? "" : message);
        }
    }

    record PageImage(int pageNo, byte[] image, String mimeType) {
    }

    record PageFormulaRecognitionResult(int pageIndex, List<FormulaRecognitionResult> formulas, String model) {
    }
}
