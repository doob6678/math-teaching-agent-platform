package com.doob.mathagent.teaching;

import java.util.List;

/**
 * Structured review result collected after draft sections are assembled and before merge/render decisions.
 *
 * @param status overall review status, such as READY or NEEDS_ATTENTION
 * @param findings reviewer findings grouped by reviewer role and target section
 * @param patches patch suggestions that later merge agents or humans can apply deterministically
 */
public record TeachingDraftReview(
        String status,
        List<ReviewFinding> findings,
        List<ReviewPatch> patches) {

    public TeachingDraftReview {
        status = status == null || status.isBlank() ? "READY" : status;
        findings = findings == null ? List.of() : List.copyOf(findings);
        patches = patches == null ? List.of() : List.copyOf(patches);
    }

    /**
     * One reviewer finding attached to a specific draft section.
     */
    public record ReviewFinding(
            String reviewerCode,
            String severity,
            String sectionCode,
            String summary,
            List<String> artifactRefs) {

        public ReviewFinding {
            reviewerCode = reviewerCode == null ? "" : reviewerCode;
            severity = severity == null || severity.isBlank() ? "info" : severity;
            sectionCode = sectionCode == null ? "" : sectionCode;
            summary = summary == null ? "" : summary;
            artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        }
    }

    /**
     * One deterministic patch suggestion that a merge stage can accept or reject later.
     */
    public record ReviewPatch(
            String reviewerCode,
            String targetSectionCode,
            String instruction) {

        public ReviewPatch {
            reviewerCode = reviewerCode == null ? "" : reviewerCode;
            targetSectionCode = targetSectionCode == null ? "" : targetSectionCode;
            instruction = instruction == null ? "" : instruction;
        }
    }
}
