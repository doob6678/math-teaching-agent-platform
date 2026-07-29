package com.doob.mathagent.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies column-aware regions so same-height anchors do not create cross-column or inverted boxes. */
class QuestionRegionLayoutResolverTest {
    @Test
    void resolvesSingleColumnUsingSuccessiveQuestionAnchors() {
        var regions = QuestionRegionLayoutResolver.resolve(1, 600, 800, List.of(
                new QuestionAnchor("1", "1. 集合", 42, 120), new QuestionAnchor("2", "2. 函数", 42, 340)));

        assertThat(regions).extracting(DetectedQuestionRegion::questionNumber).containsExactly("1", "2");
        assertThat(regions.getFirst().region()).isEqualTo(new QuestionRegion(0, 104, 600, 324));
        assertThat(regions.get(1).region()).isEqualTo(new QuestionRegion(0, 324, 600, 800));
        assertThat(regions).allMatch(region -> "SINGLE_COLUMN".equals(region.layout()));
    }

    @Test
    void isolatesTwoColumnsRatherThanConnectingSameHeightQuestions() {
        var regions = QuestionRegionLayoutResolver.resolve(2, 600, 800, List.of(
                new QuestionAnchor("1", "1. 左", 40, 100), new QuestionAnchor("2", "2. 左", 40, 300),
                new QuestionAnchor("3", "3. 右", 340, 100), new QuestionAnchor("4", "4. 右", 340, 300)));

        assertThat(regions).hasSize(4);
        assertThat(regions).allMatch(region -> "TWO_COLUMN".equals(region.layout()));
        assertThat(regions).anyMatch(region -> region.questionNumber().equals("3") && region.region().equals(new QuestionRegion(300, 84, 600, 284)));
    }

    @Test
    void ignoresSameLineDuplicateInsteadOfCreatingAnEmptyQuestionRegion() {
        var regions = QuestionRegionLayoutResolver.resolve(1, 600, 800, List.of(
                new QuestionAnchor("1", "1. 第一题", 40, 100), new QuestionAnchor("1", "1. 提取回声", 40, 100),
                new QuestionAnchor("2", "2. 第二题", 40, 300)));

        assertThat(regions).extracting(DetectedQuestionRegion::questionNumber).containsExactly("1", "2");
    }
}
