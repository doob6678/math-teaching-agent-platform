package com.doob.mathagent.teaching;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic first-pass review collector for teaching draft sections.
 *
 * <p>This collector is intentionally simple: it turns known draft risks into explicit findings and patch
 * suggestions so later reviewer agents can replace the implementation without changing the response schema.</p>
 */
public final class TeachingDraftReviewCollector {

    private TeachingDraftReviewCollector() {
    }

    /**
     * Converts known draft risks into structured findings and merge-ready patch suggestions.
     */
    public static TeachingDraftReview collect(TeachingDraftSections sections) {
        TeachingDraftSections safeSections = sections == null
                ? new TeachingDraftSections("", "", List.of(), List.of(), List.of(), List.of())
                : sections;
        List<TeachingDraftReview.ReviewFinding> findings = new ArrayList<>();
        List<TeachingDraftReview.ReviewPatch> patches = new ArrayList<>();
        for (String risk : safeSections.risks()) {
            switch (risk) {
                case "student_answer_leakage_review_required" -> {
                    findings.add(new TeachingDraftReview.ReviewFinding(
                            "StudentLeakageReviewer",
                            "warning",
                            "studentWorksheet",
                            "Student worksheet still needs an answer-leakage check before merge.",
                            safeSections.sourceRefs()));
                    patches.add(new TeachingDraftReview.ReviewPatch(
                            "StudentLeakageReviewer",
                            "studentWorksheet",
                            "Remove answer, scoring, and solution-reveal wording from the student worksheet while preserving prompts."));
                }
                case "lecture_cards_derived_from_teacher_outline" -> {
                    findings.add(new TeachingDraftReview.ReviewFinding(
                            "LectureCardReviewer",
                            "warning",
                            "lectureCards",
                            "Lecture cards were derived from the teacher outline and still need independent lecture review.",
                            safeSections.sourceRefs()));
                    patches.add(new TeachingDraftReview.ReviewPatch(
                            "LectureCardReviewer",
                            "lectureCards",
                            "Review each lecture card for projector readability and split dense bullets into screen-sized steps."));
                }
                case "source_grounding_missing" -> {
                    findings.add(new TeachingDraftReview.ReviewFinding(
                            "SourceGroundingReviewer",
                            "warning",
                            "teacherExplanation",
                            "Draft has no evidence grounding and should not be merged without source recovery.",
                            List.of()));
                    patches.add(new TeachingDraftReview.ReviewPatch(
                            "SourceGroundingReviewer",
                            "teacherExplanation",
                            "Recover evidence references or rerun evidence retrieval before trusting the explanation draft."));
                }
                case "teacher_explanation_missing" -> {
                    findings.add(new TeachingDraftReview.ReviewFinding(
                            "MathCorrectnessReviewer",
                            "error",
                            "teacherExplanation",
                            "Teacher explanation draft is empty.",
                            safeSections.sourceRefs()));
                    patches.add(new TeachingDraftReview.ReviewPatch(
                            "MathCorrectnessReviewer",
                            "teacherExplanation",
                            "Regenerate or rewrite the teacher explanation before review can proceed."));
                }
                case "student_worksheet_missing" -> {
                    findings.add(new TeachingDraftReview.ReviewFinding(
                            "StudentLeakageReviewer",
                            "error",
                            "studentWorksheet",
                            "Student worksheet draft is empty.",
                            safeSections.sourceRefs()));
                    patches.add(new TeachingDraftReview.ReviewPatch(
                            "StudentLeakageReviewer",
                            "studentWorksheet",
                            "Generate a student-safe worksheet before merge and review."));
                }
                case "ai_draft_unstructured" -> findings.add(new TeachingDraftReview.ReviewFinding(
                        "StructureReviewer",
                        "info",
                        "teacherExplanation",
                        "Draft came from fallback or unstructured generation; keep human review before publishing.",
                        safeSections.sourceRefs()));
                default -> {
                    // Preserve forward compatibility for new risk codes without blocking current review collection.
                }
            }
        }
        String status = findings.stream().anyMatch(finding -> "error".equalsIgnoreCase(finding.severity()))
                ? "NEEDS_ATTENTION"
                : findings.isEmpty() ? "READY" : "NEEDS_ATTENTION";
        return new TeachingDraftReview(status, findings, patches);
    }
}
