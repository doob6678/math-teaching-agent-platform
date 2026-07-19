package com.doob.mathagent.teaching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingDraftMergerTest {

    @Test
    void mergesStudentLeakageAndLectureCardPatchesDeterministically() {
        TeachingDraftSections sections = new TeachingDraftSections(
                "【知识定位】用定义定位方法。",
                """
                【知识速记】先写定义。
                答案：D(0)=1
                【作答提醒】先判断再代入。
                """,
                List.of("第1屏：先圈出已知条件；再定位方法；最后安排课堂追问。"),
                List.of("练习 1"),
                List.of("PUBLIC_TEXTBOOK:函数:chunk-1"),
                List.of(
                        "student_answer_leakage_review_required",
                        "lecture_cards_derived_from_teacher_outline"));
        TeachingDraftReview review = TeachingDraftReviewCollector.collect(sections);

        TeachingDraftMergeResult result = TeachingDraftMerger.merge(sections, review);

        assertThat(result.status()).isEqualTo("MERGED");
        assertThat(result.appliedPatches())
                .extracting(TeachingDraftMergeResult.AppliedPatch::targetSectionCode)
                .containsExactly("studentWorksheet", "lectureCards");
        assertThat(result.remainingFindings()).isEmpty();
        assertThat(result.mergedSections().studentWorksheet())
                .contains("【知识速记】先写定义。", "【作答提醒】先判断再代入。")
                .doesNotContain("答案：", "D(0)=1");
        assertThat(result.mergedSections().lectureCards())
                .containsExactly("先圈出已知条件；再定位方法；最后安排课堂追问");
    }

    @Test
    void keepsUnsupportedSourceGroundingPatchPendingForLaterRecovery() {
        TeachingDraftSections sections = new TeachingDraftSections(
                "【知识定位】暂无来源。",
                "【知识速记】先写定义。",
                List.of(),
                List.of("练习 1"),
                List.of(),
                List.of("source_grounding_missing"));
        TeachingDraftReview review = TeachingDraftReviewCollector.collect(sections);

        TeachingDraftMergeResult result = TeachingDraftMerger.merge(sections, review);

        assertThat(result.status()).isEqualTo("NEEDS_ATTENTION");
        assertThat(result.appliedPatches()).isEmpty();
        assertThat(result.rejectedPatches())
                .extracting(TeachingDraftReview.ReviewPatch::targetSectionCode)
                .containsExactly("teacherExplanation");
        assertThat(result.remainingFindings())
                .extracting(TeachingDraftReview.ReviewFinding::reviewerCode)
                .containsExactly("SourceGroundingReviewer");
    }
}
