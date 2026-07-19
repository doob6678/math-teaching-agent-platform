package com.doob.mathagent.teaching;

import java.util.List;

/**
 * Deterministic merge output produced after structured review and before render/export.
 *
 * @param status merge status, such as READY, MERGED, or NEEDS_ATTENTION
 * @param mergedSections merged sections the renderer should consume
 * @param appliedPatches review patches handled deterministically by the backend
 * @param rejectedPatches review patches left for later recovery or human review
 * @param remainingFindings findings that still remain after deterministic merge
 */
public record TeachingDraftMergeResult(
        String status,
        TeachingDraftSections mergedSections,
        List<AppliedPatch> appliedPatches,
        List<TeachingDraftReview.ReviewPatch> rejectedPatches,
        List<TeachingDraftReview.ReviewFinding> remainingFindings) {

    public TeachingDraftMergeResult {
        status = status == null || status.isBlank() ? "READY" : status;
        mergedSections = mergedSections == null
                ? new TeachingDraftSections("", "", List.of(), List.of(), List.of(), List.of())
                : mergedSections;
        appliedPatches = appliedPatches == null ? List.of() : List.copyOf(appliedPatches);
        rejectedPatches = rejectedPatches == null ? List.of() : List.copyOf(rejectedPatches);
        remainingFindings = remainingFindings == null ? List.of() : List.copyOf(remainingFindings);
    }

    /**
     * Audit record for one patch accepted by the deterministic merge stage.
     */
    public record AppliedPatch(
            String reviewerCode,
            String targetSectionCode,
            String instruction) {

        public AppliedPatch {
            reviewerCode = reviewerCode == null ? "" : reviewerCode;
            targetSectionCode = targetSectionCode == null ? "" : targetSectionCode;
            instruction = instruction == null ? "" : instruction;
        }
    }
}
