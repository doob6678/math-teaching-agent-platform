package com.doob.mathagent.teacher.formula;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational limits for paid formula vision requests.
 *
 * <p>These limits are intentionally separate from retrieval tuning. They bound explicit AI parsing work per source
 * document without changing any query score or silently choosing formulas by filename/keyword.</p>
 */
@ConfigurationProperties(prefix = "math-agent.teacher.formula-recognition")
public record TeacherFormulaRecognitionProperties(boolean enabled, int maxImagesPerDocument, int pagesPerRequest) {

    public int normalizedMaxImagesPerDocument() {
        return Math.max(0, maxImagesPerDocument);
    }

    public int normalizedPagesPerRequest() {
        return pagesPerRequest == 4 ? 4 : 2;
    }
}
