package com.doob.mathagent.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doob.mathagent.teacher.search.TeacherResourceBlockSearchResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingEvidenceContextFormatterTest {

    @Test
    void excludesPageBackedVisualUploadsFromWritingPrompt() {
        TeacherResourceBlockSearchResponse.Hit hit = new TeacherResourceBlockSearchResponse.Hit(
                "doc-1", "函数专题", "feishu", "TEACHER_PRIVATE", "block-1", "question", 1,
                "函数", "定义域", 8, "source.pdf", "question", List.of(), List.of("block-1"),
                "题目：求函数定义域。", "求函数定义域", 0.95,
                List.of("page-asset", "figure-asset"),
                List.of(
                        new TeacherResourceBlockSearchResponse.AssetRef(
                                "page-asset", "/api/teacher/resources/assets/page-asset", "image/png",
                                "page-8.png", "source.pdf", 8),
                        new TeacherResourceBlockSearchResponse.AssetRef(
                                "figure-asset", "/api/teacher/resources/assets/figure-asset", "image/png",
                                "figure.png", "question/figure.png", null)));

        String promptEvidence = WritingEvidenceContextFormatter.format(List.of(hit), 400, 4);

        assertThat(promptEvidence)
                .doesNotContain("page-asset")
                .contains("figure-asset");
    }
}
